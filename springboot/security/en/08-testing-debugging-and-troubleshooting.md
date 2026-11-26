# 08 — Testing, debugging and troubleshooting

Goal: after this lesson you can write tests proving your security rules are right, turn on logging to find the
cause of a 401/403 yourself, and you have a lookup table for the most frequent errors.

## 1. Security tests

A wrong security rule does not make the application fail: it silently opens the door. So **every rule needs at
least one test for the denied case**, not only for the allowed case.

Dependencies (Spring Boot 4; on Boot 3 use `spring-boot-starter-test` + `spring-security-test`):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

The test skeleton:

```java
@SpringBootTest
@AutoConfigureMockMvc
class ApiJwtSecurityTests {

    @Autowired
    MockMvc mockMvc;
}
```

On Boot 4, `@AutoConfigureMockMvc` lives in `org.springframework.boot.webmvc.test.autoconfigure`;
on Boot 3 it is `org.springframework.boot.test.autoconfigure.web.servlet`.

### Ways to "log in" inside a test

| Tool | What it does | Use when |
|------|--------------|----------|
| `@WithMockUser(username = "user", roles = "ADMIN")` | Puts an `Authentication` into the `SecurityContext` up front | Testing authorization on a session chain; testing services with `@PreAuthorize` |
| `.with(user("an").roles("USER"))` | Same, but per request | Several different users in one test |
| `.with(jwt())` | Simulates an already-verified JWT | Testing authorization on a resource server |
| `.with(csrf())` | Adds a valid CSRF token | Every `POST`/`PUT`/`DELETE` into a chain with CSRF on |
| `formLogin()` | Performs a real `POST /login` | Testing the login flow itself |
| Call the real login endpoint and use the token | Goes through issuing and verifying the token | End-to-end integration tests |

### Examples from the demo

Not logged in must return 401 in the right format ([ApiJwtSecurityTests.java](../demo/src/test/java/demo/security/ApiJwtSecurityTests.java)):

```java
@Test
void protectedEndpointWithoutTokenReturns401Json() throws Exception {
    mockMvc.perform(get("/api/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.status").value(401));
}
```

The whole flow: real login, take the token, call the API:

```java
@Test
void userTokenIsForbiddenOnAdminUrl() throws Exception {
    String token = login("user", "password");

    mockMvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403));
}
```

Simulating a JWT to test the ownership rule quickly:

```java
@Test
void otherUserCannotReadSomeoneElsesNote() throws Exception {
    mockMvc.perform(get("/api/notes/2").with(jwt().jwt(j -> j.subject("user"))))
            .andExpect(status().isForbidden());
}

@Test
void adminCanReadAnyNote() throws Exception {
    mockMvc.perform(get("/api/notes/1")
                    .with(jwt().jwt(j -> j.subject("admin"))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk());
}
```

The form login flow and CSRF ([WebFormLoginSecurityTests.java](../demo/src/test/java/demo/security/WebFormLoginSecurityTests.java)):

```java
@Test
void formLoginWithCorrectPasswordAuthenticates() throws Exception {
    mockMvc.perform(formLogin().user("user").password("password"))
            .andExpect(authenticated().withUsername("user").withRoles("USER"));
}

@Test
@WithMockUser(username = "user")
void postWithoutCsrfTokenIsForbiddenEvenWhenLoggedIn() throws Exception {
    mockMvc.perform(post("/web/profile/display-name").param("value", "An"))
            .andExpect(status().isForbidden());
}

@Test
@WithMockUser(username = "user")
void postWithCsrfTokenSucceeds() throws Exception {
    mockMvc.perform(post("/web/profile/display-name").param("value", "An").with(csrf()))
            .andExpect(status().isOk());
}
```

### Minimum test matrix per endpoint

| Case | Expectation |
|------|-------------|
| No credentials | 401 (API) or 302 to login (web) |
| Logged in, lacking permission | 403 |
| Logged in, sufficient permission | 2xx |
| Sufficient permission but someone else's resource | 403 or 404 |
| (Chain with CSRF) a write request without a token | 403 |

Note: `@WithMockUser` and `jwt()` **skip authentication**. They prove the authorization rules are right; they do not
prove that login or token verification works. Always keep a few tests that go through the real flow.

## 2. Debugging

### Turn on logging

```yaml
logging:
  level:
    org.springframework.security: DEBUG
```

Or temporarily at startup:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--logging.level.org.springframework.security=DEBUG
```

The log tells you: which chain was selected, what the authentication filter concluded, which rule denied.
`TRACE` is even more detailed (every filter invocation). Enable it in development only.

### Reading the log by symptom

A successful request always shows the pair:

```
FilterChainProxy : Securing GET /api/me
FilterChainProxy : Secured GET /api/me
```

`Securing` without `Secured`: blocked inside the chain. Look at the lines in between:

| Log line | Conclusion |
|----------|------------|
| `Set SecurityContextHolder to anonymous SecurityContext` | No authentication filter recognised the user. Missing header, wrong `Bearer` prefix, cookie not sent |
| `Failed to authenticate since password does not match stored value` | Wrong password, or the stored password has the wrong format |
| `... Signed JWT rejected: Invalid signature` | The token was modified, or signed with a key other than the verification key |
| `Jwt expired at ...` | The token expired; can also be clock skew between machines |
| `Set SecurityContextHolder to JwtAuthenticationToken [... Granted Authorities=[...]]` | Authentication succeeded. **Read the authority list here** and compare it with the rule |
| `Failed to authorize ... expressionAttribute=...` | Method security denied; the expression is printed right in the log |
| `Invalid CSRF token found for ...` | Missing or wrong CSRF token |

### Three questions for any 401/403

```
1. Which chain did the request fall into?          → the "Will secure ..." lines at startup, securityMatcher, @Order
2. Who does Spring think the user is, with which authorities?  → the "Set SecurityContextHolder to ..." line
3. Which rule applies to this URL/method?          → the order of requestMatchers, @PreAuthorize
```

Most problems surface at question 2: the actual authorities differ from what you assumed (missing `ROLE_` prefix,
wrong claim).

### A self-inspection endpoint

An endpoint returning the current `Authentication` is very useful during development, like the demo's `/api/me`:

```json
{"authorities":["FACTOR_BEARER","ROLE_USER"],"username":"user","tokenExpiresAt":"2026-11-26T16:12:49Z"}
```

## 3. Common errors lookup table

| Symptom | Usual cause | Fix |
|---------|-------------|-----|
| Every request returns 401 right after adding the starter | Default behaviour: everything locked | Declare a `SecurityFilterChain` with your rules |
| `POST` returns 403 although logged in, `GET` works | Missing CSRF token | Send the token; or turn CSRF off **if** that chain does not use cookies (lesson 5) |
| 403 although the user "has the admin role" | Prefix mismatch: data says `ADMIN`, the rule looks for `ROLE_ADMIN` | Be consistent: `roles("ADMIN")` + `hasRole("ADMIN")`, or `hasAuthority` on both sides |
| Valid JWT but `hasRole` always returns 403 | Permissions read from the `scope` claim as `SCOPE_...` | Configure a `JwtAuthenticationConverter` (lesson 6) |
| Not logged in, opening an admin page: 401/redirect instead of 403 | By design: anonymous counts as unauthenticated | Nothing to fix |
| `There is no PasswordEncoder mapped for the id "null"` | Stored password lacks the `{bcrypt}` prefix | Add the prefix, or use `BCryptPasswordEncoder` directly |
| Login always fails with the right password | Raw password stored while the encoder is bcrypt; or hashed twice | Hash exactly once at registration, with the same encoder |
| "Too many redirects" on the login page | A custom login page without `permitAll()` | `.loginPage("/login").permitAll()` |
| CSS/JS do not load on the login page | Static resources require login too | `requestMatchers("/css/**", "/js/**").permitAll()` |
| CORS error in the browser, Postman is fine | Origin not allowed, or the preflight is blocked | `CorsConfigurationSource` + `http.cors(...)` (lesson 5) |
| The error page returns 401 instead of the real error content | `/error` requires login | `requestMatchers("/error").permitAll()` |
| `@PreAuthorize` has no effect | `@EnableMethodSecurity` missing; internal `this.x()` call; `private` method | Lesson 4, the pitfalls section |
| A permission error from `@PreAuthorize` returns 500 | `@ExceptionHandler(Exception.class)` swallows `AccessDeniedException` | Add a dedicated handler that rethrows it |
| `@ControllerAdvice` cannot change the 401/403 format | The error is produced in a filter, before the controller | Configure `AuthenticationEntryPoint`, `AccessDeniedHandler` (lesson 2) |
| The second chain never runs | The first chain has no `securityMatcher` and takes everything | Specific chains get a lower `@Order`, the general chain goes last |
| A custom filter also runs for requests outside the chain | The filter is a `@Component`, so Boot registers it with Tomcat | Create it with `new` in the configuration |
| `getAuthentication()` is `null` inside `@Async` | The `SecurityContext` is stored per `ThreadLocal` | `DelegatingSecurityContextExecutor`, or pass the user as a parameter |
| Logged in, still treated as logged out (several instances) | The session lives in another instance's memory | Spring Session + Redis, or go stateless |
| H2 console shows a blank page | `X-Frame-Options: DENY` blocks the iframe; CSRF blocks the form | Dev only: `frameOptions(sameOrigin)`, skip CSRF for `/h2-console/**` |
| Swagger UI returns 401 | Its paths are not opened | `permitAll()` for `/swagger-ui/**`, `/v3/api-docs/**` (consider disabling in production) |
| Code copied from the internet does not compile (`antMatchers`, `WebSecurityConfigurerAdapter`, `.and()`) | Written for Spring Security 5 | The rename table in lesson 1 |

## 4. Production checklist

**Authentication**

- [ ] Passwords hashed with bcrypt/argon2 through a `PasswordEncoder`; no raw password anywhere, logs included.
- [ ] The failed-login message does not reveal whether the username exists.
- [ ] Login attempts are limited (rate limit or temporary lock) against password guessing.
- [ ] No default user, demo password or in-memory user remains.

**Authorization**

- [ ] The rules end with `anyRequest().authenticated()` or `denyAll()`.
- [ ] Every access by id has an ownership check at the service layer.
- [ ] Every rule has a test for the denied case.
- [ ] `/actuator/**` only exposes `health`; the rest needs a role or runs on an internal port.

**Sessions and tokens**

- [ ] Session cookie: `HttpOnly`, `Secure`, `SameSite`.
- [ ] CSRF on for every chain that authenticates with cookies.
- [ ] JWT: short lifetime, pinned algorithm, `iss`/`aud` checked, secret or private key outside the source code.
- [ ] There is a way to revoke access when an account is locked or compromised.

**Infrastructure and configuration**

- [ ] Everything over HTTPS; HSTS on.
- [ ] CORS lists specific origins; no `*` with credentials.
- [ ] A Content-Security-Policy is set.
- [ ] No secrets in git: check `application.yml` and the commit history.
- [ ] Logs contain no passwords, tokens or cookies; security log level at `INFO` or higher.
- [ ] Error responses leak no stack traces or internal details.
- [ ] Dependencies are kept up to date with security patches.

Next: [09 — Hands-on lab with the demo](09-hands-on-lab-with-demo.md).
