# 04 — Authorization

Goal: after this lesson you can write authorization rules per URL and per method, choose the right layer for
each kind of rule, and avoid the mistakes that leave permissions open.

## 1. Two layers of authorization

| | Per request (URL) | Per method |
|---|---|---|
| Declared with | `authorizeHttpRequests` in the `SecurityFilterChain` | `@PreAuthorize`, `@PostAuthorize` on methods |
| Runs in | `AuthorizationFilter`, before the controller | An AOP proxy wrapped around the bean |
| Knows | URL, HTTP method, user | Method arguments, return value, user |
| Fits | Coarse rules: "the `/admin` area is ADMIN only" | Fine rules: "only the order's owner may view this order" |

Use both (defence in depth): URLs block coarsely at the gate, methods protect the business logic no matter which
controller calls it.

## 2. Authorization per URL

[ApiSecurityConfig.java](../demo/src/main/java/demo/security/config/ApiSecurityConfig.java):

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
        .requestMatchers("/api/public/**").permitAll()
        .requestMatchers("/api/admin/**").hasRole("ADMIN")
        .anyRequest().authenticated())
```

### Rule number one: the first matching rule wins

Rules are evaluated **top to bottom**. Specific ones first, general ones last.

```java
// WRONG: the first line matches everything; the ADMIN rule below is never evaluated
.requestMatchers("/api/**").authenticated()
.requestMatchers("/api/admin/**").hasRole("ADMIN")

// RIGHT
.requestMatchers("/api/admin/**").hasRole("ADMIN")
.requestMatchers("/api/**").authenticated()
```

### Rule number two: always have a closing line

End with `anyRequest().authenticated()` or `anyRequest().denyAll()`. Endpoints added later are then locked by
default instead of open by default. This is the **deny by default** principle.

### Built-in rules

| Rule | Lets the request through when |
|------|-------------------------------|
| `permitAll()` | Anyone, logged in or not |
| `denyAll()` | Nobody |
| `authenticated()` | Logged in (remember-me counts) |
| `fullyAuthenticated()` | Logged in with real credentials in this session; remember-me does not count |
| `anonymous()` | Not logged in (for example a sign-up page) |
| `hasRole("ADMIN")` | Has the authority `ROLE_ADMIN` |
| `hasAnyRole("ADMIN", "STAFF")` | Has one of the roles |
| `hasAuthority("product:write")` | Has exactly that authority |
| `hasAnyAuthority(...)` | Has one of the authorities |
| `access(authorizationManager)` | Custom logic |

A combined example (compiled against Spring Security 7.1.2):

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/css/**", "/js/**").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
        .requestMatchers(HttpMethod.POST, "/api/products").hasAuthority("product:write")
        .requestMatchers("/api/reports/**").hasAnyRole("ADMIN", "STAFF")
        .requestMatchers("/api/internal/**").access(officeHoursOnly())
        .requestMatchers("/actuator/health").permitAll()
        .requestMatchers("/actuator/**").hasRole("ADMIN")
        .requestMatchers("/account/delete").fullyAuthenticated()
        .anyRequest().denyAll());
```

### Path syntax

| Pattern | Matches |
|---------|---------|
| `/api/users` | Exactly that path |
| `/api/users/*` | One segment: `/api/users/5`, but not `/api/users/5/orders` |
| `/api/users/**` | Every level below, and `/api/users` itself |
| `/api/users/{id}` | One segment, with a named variable |

### Custom rules with `AuthorizationManager`

```java
static AuthorizationManager<RequestAuthorizationContext> officeHoursOnly() {
    return (Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) -> {
        int hour = java.time.LocalTime.now().getHour();
        boolean granted = authentication.get().isAuthenticated() && hour >= 8 && hour < 18;
        return new AuthorizationDecision(granted);
    };
}
```

`AuthorizationManager` is the interface behind every authorization decision in modern Spring Security: it takes
an `Authentication` and a context, and returns allow or deny.

## 3. Roles and authorities

Technically there is only one thing: `GrantedAuthority`, a string. A "role" is an authority that starts with `ROLE_`.

| You write | It looks for the authority |
|-----------|----------------------------|
| `hasRole("ADMIN")` | `ROLE_ADMIN` |
| `hasAuthority("ROLE_ADMIN")` | `ROLE_ADMIN` |
| `hasAuthority("ADMIN")` | `ADMIN` (does not match `ROLE_ADMIN`) |
| `User.roles("ADMIN")` | Creates `ROLE_ADMIN` |
| `User.authorities("ADMIN")` | Creates `ADMIN` |

Most "but I am clearly an admin" 403s come from a prefix mismatch: the database stores `ADMIN`, the code checks
`hasRole("ADMIN")`. Do not write `.hasRole("ROLE_ADMIN")` in `authorizeHttpRequests`: Spring fails at startup
because the prefix is duplicated.

Ways to organise permissions:

| Model | Example | When |
|-------|---------|------|
| Roles only | `ROLE_ADMIN`, `ROLE_USER` | Small applications with few user types |
| Roles + permissions | Role `EDITOR` consists of `article:read`, `article:write`; code checks permissions | Larger systems. A new role is just a new combination of permissions, no code change |

### Role hierarchy

To make ADMIN automatically hold everything STAFF and USER hold, without assigning all three:

```java
@Bean
static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.withDefaultRolePrefix()
            .role("ADMIN").implies("STAFF")
            .role("STAFF").implies("USER")
            .build();
}
```

## 4. Authorization per method

Enable it once ([ApiSecurityConfig.java](../demo/src/main/java/demo/security/config/ApiSecurityConfig.java)):

```java
@Configuration
@EnableMethodSecurity
public class ApiSecurityConfig { ... }
```

| Annotation | Runs | Used to |
|------------|------|---------|
| `@PreAuthorize` | Before the method | Prevent the call. The most used |
| `@PostAuthorize` | After the method | Decide based on the return value |
| `@PreFilter` / `@PostFilter` | Before / after | Filter the elements of a collection argument / result |

Expressions are written in SpEL:

| Expression | Meaning |
|------------|---------|
| `hasRole('ADMIN')` | Same as the URL rule |
| `authentication.name` | The current username |
| `#id`, `#request.ownerId` | Method arguments |
| `returnObject` | The return value (only in `@PostAuthorize`) |
| `@orderSecurity.isOwner(#id, authentication)` | Call a method on a bean |

### Example: ownership-based permission

[NoteService.java](../demo/src/main/java/demo/security/api/NoteService.java):

```java
@PostAuthorize("returnObject.owner == authentication.name or hasRole('ADMIN')")
public Note findById(long id) { ... }

@PreAuthorize("hasRole('ADMIN')")
public void delete(long id) { ... }
```

The URL `/api/notes/**` only requires "logged in". The rule "only the note's owner or an ADMIN" cannot be written
at the URL layer because that layer does not know who note 2 belongs to.

The real result when user `user` reads the note of `admin`:

```
HTTP/1.1 403
{"status":403,"error":"Forbidden","message":"Khong du quyen truy cap tai nguyen nay"}
```

The matching DEBUG log shows exactly which expression denied it:

```
AuthorizationManagerAfterMethodInterceptor : Failed to authorize ... NoteService.findById(long) ...
   ExpressionAuthorizationDecision [granted=false,
   expressionAttribute=returnObject.owner == authentication.name or hasRole('ADMIN')]
```

Missing this check is the **IDOR / Broken Object Level Authorization** vulnerability: a user changes the id in the
URL and reads someone else's data. It tops the OWASP API Security list, and no URL configuration can stop it.

`@PostAuthorize` is for **read** methods only. The method has already run when the check happens, so if the method
writes data, the data is already written. For writes, use `@PreAuthorize` with a checking bean:

```java
@PreAuthorize("@noteSecurity.isOwner(#id, authentication.name) or hasRole('ADMIN')")
public void update(long id, String content) { ... }
```

### Method security pitfalls

Method security works through a **proxy**. The annotation only takes effect when the call goes through the proxy.

| Situation | Does the annotation run |
|-----------|-------------------------|
| Another bean calls in (`controller` calls `service.delete()`) | Yes |
| An internal call in the same class (`this.delete()`) | **No**: it bypasses the proxy |
| A `private` method | **No** |
| An object created with `new`, not a Spring bean | **No** |
| `@EnableMethodSecurity` is missing | **No**, and there is no warning |

The last case is the most dangerous: the annotation is there, looks safe, and does nothing. Always have a test
proving that a user without the permission gets 403 (lesson 8).

## 5. Where to put a rule

| Kind of rule | Layer |
|--------------|-------|
| Public or login required | URL |
| A whole area by role (`/admin/**`) | URL |
| Data-based: owner, same department, same tenant | Method (service) |
| Business logic shared by several controllers, jobs, listeners | Method (service) |
| Filtering returned data by user | Best done in the query itself (`WHERE owner = :user`) |

Hiding a button in the UI is not authorization. Every check must live on the server.

## 6. Self-check

1. What is wrong with the order `anyRequest().authenticated()` followed by `requestMatchers("/admin/**").hasRole("ADMIN")`?
2. The database stores the permission as `ADMIN`. What does `hasRole("ADMIN")` return?
3. Why can't the rule "only the order's owner may view the order" go in `authorizeHttpRequests`?
4. Method `a()` in a service calls `this.b()`; `b()` has `@PreAuthorize("hasRole('ADMIN')")`. What happens when a regular user calls `a()`?

Short answers: (1) Spring refuses to start: no rule may be declared after `anyRequest()`. Even if it were allowed,
the ADMIN rule would never be evaluated. (2) Denied, because it looks for `ROLE_ADMIN`. (3) The URL layer does not
know who the order belongs to. (4) `b()` runs normally, unchecked, because the internal call bypasses the proxy.

Next: [05 — CSRF, CORS, session and headers](05-csrf-cors-session-and-headers.md).
