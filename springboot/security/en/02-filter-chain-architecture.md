# 02 — Filter chain architecture

Goal: after this lesson you can draw the path of a request through Spring Security, and when you see a 401/403
you know which filter produced it. This is the most important lesson: once you understand it, every
configuration in later lessons is just "turn a filter on, off, or replace it".

## 1. Spring Security is a chain of servlet filters

Spring Security sits **in front of** the `DispatcherServlet`. Whether a request is blocked or let through is
decided before your controller is called.

```
Client
  │
  ▼
┌──────────────────────── Servlet container (Tomcat) ────────────────────────┐
│ Other filters (encoding...)                                                │
│      │                                                                     │
│      ▼                                                                     │
│ DelegatingFilterProxy        ← a servlet filter, the bridge into Spring    │
│      │                                                                     │
│      ▼                                                                     │
│ FilterChainProxy             ← the "springSecurityFilterChain" bean        │
│      │   picks the FIRST SecurityFilterChain that matches the request      │
│      ├── SecurityFilterChain #1  (/api/**)        → 13 filters             │
│      └── SecurityFilterChain #2  (everything else) → 15 filters            │
│      │                                                                     │
│      ▼                                                                     │
│ DispatcherServlet → Controller                                             │
└────────────────────────────────────────────────────────────────────────────┘
```

| Component | Role |
|-----------|------|
| `DelegatingFilterProxy` | Tomcat knows nothing about Spring beans. This filter is registered with Tomcat and hands the work to a bean inside Spring |
| `FilterChainProxy` | The single entry point of Spring Security. It selects the chain, and it is the best place for a breakpoint when debugging |
| `SecurityFilterChain` | A list of filters + a condition saying which requests it applies to. Each `SecurityFilterChain` bean you declare is one chain |

## 2. Inside a chain: the real filter list

Enable `org.springframework.security=DEBUG` logging and start the demo: Spring prints the exact filter list of
each chain. The demo's web chain (form login):

```
Will secure any request with filters:
  DisableEncodeUrlFilter, WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter,
  HeaderWriterFilter, CsrfFilter, LogoutFilter, UsernamePasswordAuthenticationFilter,
  DefaultResourcesFilter, DefaultLoginPageGeneratingFilter, DefaultLogoutPageGeneratingFilter,
  RequestCacheAwareFilter, SecurityContextHolderAwareRequestFilter,
  AnonymousAuthenticationFilter, ExceptionTranslationFilter, AuthorizationFilter
```

The demo's API chain (JWT):

```
Will secure Or [PathPattern [/api/**]] with filters:
  DisableEncodeUrlFilter, WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter,
  HeaderWriterFilter, LogoutFilter, OAuth2ProtectedResourceMetadataFilter,
  BearerTokenAuthenticationFilter, RequestCacheAwareFilter,
  SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter,
  SessionManagementFilter, ExceptionTranslationFilter, AuthorizationFilter
```

Comparing the two lists shows directly how configuration changes things: the API chain has no `CsrfFilter`
(because of `csrf.disable()`), and `UsernamePasswordAuthenticationFilter` is replaced by `BearerTokenAuthenticationFilter`.

You do not need to memorise them all. Know these, in execution order:

| Order | Filter | What it does |
|-------|--------|--------------|
| 1 | `SecurityContextHolderFilter` | Loads the saved `SecurityContext` (from the session) into `SecurityContextHolder`; clears it when the request ends |
| 2 | `HeaderWriterFilter` | Writes the security headers to the response |
| 3 | `CsrfFilter` | Checks the CSRF token on state-changing requests. Wrong token: 403 |
| 4 | `LogoutFilter` | Catches `POST /logout`, invalidates the session |
| 5 | Authentication filters: `UsernamePasswordAuthenticationFilter` (form), `BasicAuthenticationFilter` (Basic), `BearerTokenAuthenticationFilter` (JWT) | Read credentials from the request, call the `AuthenticationManager`, put the result in the `SecurityContext` |
| 6 | `AnonymousAuthenticationFilter` | If nobody is authenticated yet: sets an "anonymous" `Authentication` |
| 7 | `ExceptionTranslationFilter` | Catches security exceptions from later steps and turns them into 401 or 403 |
| 8 | `AuthorizationFilter` | Applies the `authorizeHttpRequests` rules. Always the last filter |

The ordering rule: **load context → attack defences → authenticate → authorize**. Authorization comes last
because it needs the result of authentication.

## 3. How a request travels through the chain

Example: `GET /api/admin/stats` with the JWT of a regular (non-admin) user.

```
1. FilterChainProxy          matches /api/** → use the API chain
2. SecurityContextHolderFilter   empty context (stateless, no session)
3. BearerTokenAuthenticationFilter
       reads the Authorization header → JwtDecoder verifies signature and expiry → OK
       puts JwtAuthenticationToken[user, ROLE_USER] in the SecurityContext
4. AnonymousAuthenticationFilter  skipped, an Authentication already exists
5. ExceptionTranslationFilter     calls on, waiting to catch exceptions
6. AuthorizationFilter
       rule: /api/admin/** needs hasRole("ADMIN") → the user lacks it
       throws AccessDeniedException
7. ExceptionTranslationFilter     catches it, the user IS authenticated → AccessDeniedHandler → 403
```

The real DEBUG log of exactly this case (shortened):

```
FilterChainProxy                   : Securing GET /api/admin/stats
JwtAuthenticationProvider          : Authenticated token
BearerTokenAuthenticationFilter    : Set SecurityContextHolder to JwtAuthenticationToken [...
                                     Granted Authorities=[ROLE_USER, FactorGrantedAuthority [authority=FACTOR_BEARER, ...]]]
```

No `Secured GET /api/admin/stats` line means the request was stopped inside the chain and never reached the
controller. A successful request always shows the pair `Securing ...` then `Secured ...`.

`FACTOR_BEARER` is an authority Spring Security 7 adds to record which factor was used to authenticate
(supporting multi-factor authentication). A password login gets `FACTOR_PASSWORD`. It does not affect role rules.

## 4. Where 401 and 403 come from

All of it happens in `ExceptionTranslationFilter`:

```
                     Exception from a later filter or from a controller/service
                                          │
                 ┌────────────────────────┴────────────────────────┐
                 ▼                                                 ▼
      AuthenticationException                           AccessDeniedException
                 │                                                 │
                 │                              ┌──────────────────┴─────────────────┐
                 │                              ▼                                    ▼
                 │                    the user is ANONYMOUS              the user IS authenticated
                 │                              │                                    │
                 ▼                              ▼                                    ▼
        AuthenticationEntryPoint ◀──────────────┘                          AccessDeniedHandler
        "please log in"                                                    "you lack permission"
        - web: 302 to /login                                               - 403
        - API: 401
```

A subtle point to remember: **an anonymous user who is denied access gets 401 (or a redirect to login), not 403**.
Spring treats it as "not logged in" rather than "lacking permission".

The demo replaces both handlers with a JSON version ([JsonSecurityErrorHandlers.java](../demo/src/main/java/demo/security/error/JsonSecurityErrorHandlers.java)):

```java
@Component
public class JsonSecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, "Chua xac thuc hoac token khong hop le");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "Khong du quyen truy cap tai nguyen nay");
    }
}
```

Because these filters run before the `DispatcherServlet`, **`@ControllerAdvice` cannot catch errors thrown by a filter**.
To change the 401/403 format you must configure the entry point and handler as above.

Conversely, security exceptions thrown **inside a controller/service** (for example from `@PreAuthorize`) do pass
through `@ControllerAdvice`. If you have a catch-all `@ExceptionHandler(Exception.class)`, it swallows
`AccessDeniedException` and returns 500 instead of 403. The fix: add a dedicated handler that rethrows it.

## 5. SecurityContextHolder: where "who is logged in" lives

```
SecurityContextHolder          (stored in a ThreadLocal by default: one context per thread)
   └── SecurityContext
          └── Authentication
                 ├── principal
                 ├── credentials
                 └── authorities
```

- **Within one request**: an authentication filter puts the `Authentication` in; controllers and services read it.
  At the end of the request the context is removed from the thread (the thread returns to the pool and must not
  carry leftover data).
- **Between requests**: the `SecurityContextRepository` saves and reloads it. With form login it is stored in the
  `HttpSession`. With a stateless API nothing is stored: every request re-authenticates with its token.

A consequence of `ThreadLocal`: code running on another thread (`@Async`, `CompletableFuture`, hand-made threads)
**does not see** the current user. Use `DelegatingSecurityContextExecutor`, or pass the user information as a parameter.

## 6. Multiple filter chains in one application

The demo has two chains. `@Order` decides the evaluation order; the first chain that matches is used and the
request **never falls through to a later chain**:

```java
@Bean
@Order(1)
SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ...) throws Exception {
    http.securityMatcher("/api/**")      // this chain only takes /api/**
        ...
}

@Bean
@Order(2)
SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
    http                                  // no securityMatcher = takes every remaining request
        ...
}
```

Two concepts that are easy to mix up:

| | `securityMatcher(...)` | `requestMatchers(...)` |
|---|---|---|
| Answers | Does this chain handle the request? | Within the chain, what does the request need? |
| Used at | The `http` level | Inside `authorizeHttpRequests` |

The classic mistake: putting the catch-all chain at `@Order(1)`. It swallows every request and the later chain
never runs. Always put chains with a specific `securityMatcher` first and the general chain last.

## 7. Adding your own filter

```java
static class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Request-Id", java.util.UUID.randomUUID().toString());
        chain.doFilter(request, response);   // forget this line and the request stops here
    }
}

http.addFilterBefore(new RequestIdFilter(), UsernamePasswordAuthenticationFilter.class);
```

Notes:

- The insertion point is relative to a standard filter: `addFilterBefore`, `addFilterAfter`, `addFilterAt`.
- Do not annotate that filter with `@Component`. Spring Boot registers every `Filter` bean with Tomcat, so the
  filter would apply to every request of the application, outside the chain and in the wrong order.
  Create it with `new` in the configuration, as above.
- Before writing an authentication filter yourself (for example one that reads JWTs), check whether Spring
  already has one. For JWT it does: `oauth2ResourceServer` (lesson 6).

## 8. How `permitAll()` differs from "bypassing security"

| | `permitAll()` | `web.ignoring()` (`WebSecurityCustomizer`) |
|---|---|---|
| Does the request go through the filter chain | Yes | No |
| Security headers, CSRF, context | Yes | No |
| Use for | Public endpoints | Almost never; only for static resources if truly needed |

Prefer `permitAll()`. A public endpoint should still get security headers and still be able to read the user if
they happen to be logged in.

## 9. Self-check

1. Why must `AuthorizationFilter` come after `AnonymousAuthenticationFilter`?
2. An application has a `@ControllerAdvice` returning neat JSON for every error, yet 401 responses still use the default format. Why?
3. Calling `SecurityContextHolder.getContext().getAuthentication()` inside an `@Async` method returns `null`. Why?
4. Two chains: `@Order(1)` has no `securityMatcher`, `@Order(2)` has `securityMatcher("/api/**")`. What happens to `/api/me`?

Short answers: (1) So that there is always an `Authentication` to evaluate, even for someone not logged in.
(2) The 401 is produced in a filter, before the `DispatcherServlet`; `@ControllerAdvice` only sees errors raised
in controllers. (3) The context is stored per `ThreadLocal`; another thread has none. (4) Chain 1 takes everything;
chain 2 never runs.

Next: [03 — Authentication](03-authentication.md).
