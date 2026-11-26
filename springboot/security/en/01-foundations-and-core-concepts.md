# 01 — Foundations and core concepts

Goal: after this lesson you can tell authentication from authorization, use the terms every Spring Security
document relies on, and know exactly what happens when you add the starter to a project.

## 1. Web application security answers two questions

| | Authentication | Authorization |
|---|---|---|
| Question | Who are you? | What are you allowed to do? |
| Input | Proof of identity: password, token, session cookie | The authenticated identity + the resource being accessed |
| Failure returns | **401 Unauthorized** | **403 Forbidden** |
| Everyday analogy | The guard checks your staff badge at the gate | Your badge does not open the server room |

Authentication always runs first. Without knowing who you are, nothing can decide what you may do.

The name of status 401 is misleading: "Unauthorized" really means **not authenticated**. Remember:
401 = "I don't know who you are", 403 = "I know who you are, and you are not allowed".

Beyond these two questions, Spring Security handles a third area: **defending against common attacks**
(CSRF, session fixation, clickjacking...). Most of it is on by default; see lesson 5.

## 2. Required vocabulary

| Concept | Meaning | In code |
|---------|---------|---------|
| Principal | The actor: usually a user, sometimes a service | `authentication.getPrincipal()` |
| Credentials | What proves the identity: password, token | `authentication.getCredentials()` |
| Authority | One granted permission, as a string: `ROLE_ADMIN`, `product:write` | `GrantedAuthority` |
| Role | An authority with the `ROLE_` prefix. A naming convention, not a separate type | `hasRole("ADMIN")` matches `ROLE_ADMIN` |
| Authentication | The object bundling the three above + an "is authenticated" flag | `Authentication` |
| SecurityContext | Where the `Authentication` of the current request is kept | `SecurityContextHolder.getContext()` |

`Authentication` plays two roles, which confuses most beginners:

```
Before authentication:  Authentication { principal="an", credentials="the-password", authenticated=false }
                        = what the user claims, not yet checked
                                         │
                                         ▼  AuthenticationManager verifies
After authentication:   Authentication { principal=UserDetails(an), credentials=null,
                                         authorities=[ROLE_USER], authenticated=true }
                        = a confirmed identity; the password is erased from memory
```

Getting the current user anywhere in the code:

```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
String username = authentication.getName();
```

In a controller, declare a parameter and Spring injects it (see [ApiController.java](../demo/src/main/java/demo/security/api/ApiController.java)):

```java
@GetMapping("/me")
public Map<String, Object> me(Authentication authentication, @AuthenticationPrincipal Jwt jwt) { ... }
```

## 3. Stateful and stateless: two ways to "remember" a logged-in user

HTTP is stateless: every request stands alone. After login, the server needs a way to recognise that the
next request comes from the same person.

| | Session (stateful) | Token (stateless) |
|---|---|---|
| Server stores | A session in RAM/Redis holding the `SecurityContext` | Nothing |
| Client holds | A `JSESSIONID` cookie (a lookup key, meaningless on its own) | A self-contained token (JWT) |
| Sent with a request | The browser sends the cookie automatically | The client attaches `Authorization: Bearer ...` itself |
| Revocation | Easy: delete the session | Hard: a token is usable until it expires |
| Typical risk | CSRF (because cookies are sent automatically) | Token leakage (XSS, logs) |
| Fits | Server-rendered web apps on one domain | REST APIs for mobile, SPAs, service-to-service |

Neither is better in absolute terms. The demo in this guide runs both so you can compare them.

## 4. What happens when you only add the starter

Add exactly one dependency, with no configuration at all:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

Spring Boot auto-configures according to the **secure by default** principle (everything locked):

| Default behaviour | Detail |
|-------------------|--------|
| Every endpoint requires login | Including the one you just wrote, including `/actuator/**` |
| One user is created | Username `user`, random password printed at startup: `Using generated security password: ...` |
| Form login is on | An auto-generated `/login` page; unauthenticated browsers are redirected there |
| HTTP Basic is on | A client sending `Authorization: Basic ...` can log in too |
| CSRF is on | Every `POST`/`PUT`/`DELETE` needs a CSRF token, otherwise 403 |
| Security headers are added | `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Cache-Control: no-store`... |
| Session fixation protection | The session id changes after login |

Practical consequences people hit on day one:

- Calling the API from Postman returns 401: by design, you are not logged in.
- Logged in, but `POST` still returns 403: the CSRF token is missing (lesson 5).
- The generated password changes on every restart: it is for experiments only.

As soon as you declare your own `SecurityFilterChain` or `UserDetailsService` bean, the corresponding default
backs off (the random user disappears, the default rules are replaced by yours).

## 5. Minimal modern configuration

Since Spring Security 6, configuration means **declaring a `SecurityFilterChain` bean**, written with lambdas:

```java
@Configuration
public class WebSecurityConfig {

    @Bean
    SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/error").permitAll()   // public
                        .anyRequest().authenticated())                // everything else needs login
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults());
        return http.build();
    }
}
```

Read it line by line: "`/` and `/error` are open to everyone; every other request needs login; log in with the
default form; logout is available". This is the demo's [WebSecurityConfig.java](../demo/src/main/java/demo/security/config/WebSecurityConfig.java).

### Old tutorials: the rename table

A great many tutorials were written for Spring Security 5 and no longer compile. Translate them with this table:

| Old way (Security 5, Boot 2) | Current way (Security 6/7, Boot 3/4) |
|------------------------------|--------------------------------------|
| `extends WebSecurityConfigurerAdapter` + `configure(HttpSecurity)` | A `SecurityFilterChain` bean |
| `configure(AuthenticationManagerBuilder)` | `UserDetailsService` + `PasswordEncoder` beans |
| `authorizeRequests()` | `authorizeHttpRequests()` |
| `antMatchers(...)`, `mvcMatchers(...)` | `requestMatchers(...)` |
| `http.csrf().disable().and()...` (chaining with `and()`) | Lambdas: `http.csrf(csrf -> csrf.disable())` |
| `@EnableGlobalMethodSecurity(prePostEnabled = true)` | `@EnableMethodSecurity` |
| `javax.servlet.*` | `jakarta.servlet.*` |

Spring Boot 4 additionally renames a few starters compared with Boot 3:

| Spring Boot 3 | Spring Boot 4 |
|---------------|---------------|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `spring-boot-starter-oauth2-resource-server` | `spring-boot-starter-security-oauth2-resource-server` |
| `spring-boot-starter-oauth2-client` | `spring-boot-starter-security-oauth2-client` |
| `spring-security-test` (declared separately) | `spring-boot-starter-security-test` |

## 6. Self-check

1. A request with no credentials hits a URL that needs the ADMIN role: 401 or 403? Why?
2. How do `hasRole("ADMIN")` and `hasAuthority("ADMIN")` differ?
3. Why does an app using session cookies need CSRF protection while an API using the `Authorization` header does not?
4. After authentication completes, what does `authentication.getCredentials()` return? Why is it designed that way?

Short answers: (1) 401, because the caller is unknown, so permissions are not even considered. (2) `hasRole` adds
the prefix and looks for `ROLE_ADMIN`; `hasAuthority` matches the exact string `ADMIN`. (3) The browser sends
cookies automatically with every request to that domain, including requests triggered by a malicious page; the
`Authorization` header is never sent automatically. (4) `null`: the password is erased so it does not linger in
memory or leak into logs.

Next: [02 — Filter chain architecture](02-filter-chain-architecture.md).
