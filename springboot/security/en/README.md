# Spring Security — from zero to securing a real application

A self-study guide to Spring Security in Spring Boot: how it intercepts requests, how login and authorization
work, why CSRF and CORS exist, how to build a REST API with JWT, and how to test and debug 401/403 responses.
It comes with a [runnable demo](../demo/) covered by 18 tests; the code and output in the lessons are taken from it.

Verified versions: **Spring Boot 4.1.2, Spring Security 7.1.2, JDK 21**. Most of the content also holds for
Spring Boot 3.x (Security 6.x); differences are called out where they matter.

Tiếng Việt: [../vn/README.md](../vn/README.md)

## Reading path

| # | Lesson | Content | Time |
|---|--------|---------|------|
| 1 | [01-foundations-and-core-concepts.md](01-foundations-and-core-concepts.md) | Authentication vs authorization, core vocabulary, what happens when you add the starter | 30 min |
| 2 | [02-filter-chain-architecture.md](02-filter-chain-architecture.md) | Which filters a request passes through, `SecurityContext`, where 401 and 403 come from | 40 min |
| 3 | [03-authentication.md](03-authentication.md) | `AuthenticationManager`, `UserDetailsService`, password storage, form login, session, logout | 45 min |
| 4 | [04-authorization.md](04-authorization.md) | URL rules, roles and authorities, `@PreAuthorize`, data-level authorization | 40 min |
| 5 | [05-csrf-cors-session-and-headers.md](05-csrf-cors-session-and-headers.md) | CSRF, CORS, session fixation, security headers: what they are and when they can be turned off | 40 min |
| 6 | [06-jwt-and-stateless-rest-api.md](06-jwt-and-stateless-rest-api.md) | What a JWT is, building a stateless API properly, refresh tokens, pitfalls | 45 min |
| 7 | [07-oauth2-and-openid-connect.md](07-oauth2-and-openid-connect.md) | OAuth2, OIDC, Google login, resource server with Keycloak | 40 min |
| 8 | [08-testing-debugging-and-troubleshooting.md](08-testing-debugging-and-troubleshooting.md) | Security tests, logging, common-error lookup table, production checklist | 30 min |
| 9 | [09-hands-on-lab-with-demo.md](09-hands-on-lab-with-demo.md) | Step-by-step `curl` lab: form login, CSRF, JWT, 401/403, method security | 60–90 min |

Suggestion: read 1 → 2, then do parts A and B of lesson 9 right away to see the theory run, then come back to 3–8.

Stuck on a real problem (401, 403, CORS, redirect loop)? Go straight to the
[lookup table in lesson 8](08-testing-debugging-and-troubleshooting.md#3-common-errors-lookup-table).

## Knowledge map

```
                        ┌─────────────────────────────┐
   HTTP request ───────▶│  Security filter chain (02) │
                        └──────────────┬──────────────┘
                 ┌─────────────────────┼─────────────────────┐
                 ▼                     ▼                     ▼
        Who are you? (03)      What may you do? (04)   Attack defences (05)
        form login, session    URL rules, roles,       CSRF, CORS, headers,
        JWT (06), OAuth2 (07)  @PreAuthorize           session fixation
                 └─────────────────────┼─────────────────────┘
                                       ▼
                          Test, debug, checklist (08)
```

## Demo

One Spring Boot application with two security "worlds" running side by side:

| Part | URL | Authentication | State | CSRF |
|------|-----|----------------|-------|------|
| REST API | `/api/**` | JWT in the `Authorization: Bearer` header | Stateless | Off |
| Web | everything else | Form login | `JSESSIONID` session cookie | On |

Built-in users: `user` / `password` (role USER) and `admin` / `admin123` (roles USER, ADMIN).

| File | Demonstrates |
|------|--------------|
| [ApiSecurityConfig.java](../demo/src/main/java/demo/security/config/ApiSecurityConfig.java) | Stateless filter chain, URL rules, JWT resource server |
| [WebSecurityConfig.java](../demo/src/main/java/demo/security/config/WebSecurityConfig.java) | Form login and session filter chain |
| [UserAndPasswordConfig.java](../demo/src/main/java/demo/security/config/UserAndPasswordConfig.java) | `UserDetailsService`, `PasswordEncoder`, `AuthenticationManager` |
| [JwtConfig.java](../demo/src/main/java/demo/security/config/JwtConfig.java) | Signing and verifying JWTs, mapping claims to authorities |
| [AuthController.java](../demo/src/main/java/demo/security/auth/AuthController.java), [TokenService.java](../demo/src/main/java/demo/security/auth/TokenService.java) | Exchanging username/password for a JWT |
| [NoteService.java](../demo/src/main/java/demo/security/api/NoteService.java) | `@PreAuthorize`, `@PostAuthorize` (ownership checks) |
| [JsonSecurityErrorHandlers.java](../demo/src/main/java/demo/security/error/JsonSecurityErrorHandlers.java) | JSON 401/403 responses |
| [ApiJwtSecurityTests.java](../demo/src/test/java/demo/security/ApiJwtSecurityTests.java), [WebFormLoginSecurityTests.java](../demo/src/test/java/demo/security/WebFormLoginSecurityTests.java) | Security tests with MockMvc |

### The demo's messages are in Vietnamese

The demo is shared with the Vietnamese version, so its source comments and response messages are written in
unaccented Vietnamese. Code snippets in these lessons have their comments translated; string literals and
captured output are shown exactly as the demo produces them. The strings you will see:

| Demo output | Meaning |
|-------------|---------|
| `Chua xac thuc hoac token khong hop le` | Not authenticated, or the token is invalid (401) |
| `Khong du quyen truy cap tai nguyen nay` | Not allowed to access this resource (403) |
| `Xin chao, endpoint nay ai cung goi duoc` | Hello, anyone can call this endpoint |
| `Xin chao user, ban dang dung session cookie.` | Hello user, you are using a session cookie. |
| `Trang chu cong khai. Thu vao /web/home de bi chuyen sang /login.` | Public home page. Try /web/home to be redirected to /login. |
| `Da doi ten hien thi cua user thanh: An` | Changed the display name of user to: An |
| `Ghi chu rieng cua user` / `Chi ADMIN thay duoc` | Private note of user / Only ADMIN can see this |

## Quick start

Requirements: JDK 21 and Maven 3.9 or later.

```bash
cd springboot/security/demo
mvn test                 # 18 tests, must end with BUILD SUCCESS
mvn spring-boot:run      # runs at http://localhost:8088
```

```bash
# In another terminal
curl -i http://localhost:8088/api/me            # 401: no token yet
curl -s -X POST http://localhost:8088/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"user","password":"password"}'   # returns an accessToken
```

The demo listens on port 8088. To change it: `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9000`.

The JWT signing secret in [application.yml](../demo/src/main/resources/application.yml) is a demo value only.
A real project always loads the secret from an environment variable or a secret manager and never commits it.
