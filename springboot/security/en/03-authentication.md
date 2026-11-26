# 03 — Authentication

Goal: after this lesson you understand the processing chain from the moment a user types a password to the
moment an `Authentication` sits in the `SecurityContext`, you can wire a user source from a database, and you
store passwords correctly.

## 1. The authentication machinery

```
Authentication filter (form / basic / bearer)
     │  builds an UNAUTHENTICATED Authentication from the request
     ▼
AuthenticationManager                    ← interface with one method: authenticate(Authentication)
     │  default implementation: ProviderManager
     ▼
ProviderManager                          ← asks each provider in turn "can you handle this type?"
     ├── DaoAuthenticationProvider       ← username + password
     ├── JwtAuthenticationProvider       ← JWT bearer token
     └── ...
     │
     ▼ (for DaoAuthenticationProvider)
UserDetailsService.loadUserByUsername()  ← you provide: where to find the user
PasswordEncoder.matches()                ← you provide: how to compare the password
     │
     ▼
AUTHENTICATED Authentication → the filter puts it in the SecurityContext
```

| Component | Responsibility | Do you write it |
|-----------|----------------|-----------------|
| `AuthenticationManager` | The single entry point for authentication | No |
| `AuthenticationProvider` | Authenticates one specific kind of credential | Rarely (only for custom mechanisms: OTP, customised LDAP...) |
| `UserDetailsService` | Finds a user by username | **Yes**, when users live in your database |
| `UserDetails` | What Spring needs to know about a user: username, hashed password, authorities, lock state | The built-in `User.builder()` is enough |
| `PasswordEncoder` | Hashes and matches passwords | Declare a bean; never write the algorithm yourself |

Declare just the two beans `UserDetailsService` and `PasswordEncoder` and Spring builds the
`DaoAuthenticationProvider` and `AuthenticationManager` for you. The demo's startup log confirms it:

```
Global AuthenticationManager configured with UserDetailsService bean with name userDetailsService
```

## 2. User sources

### In memory (for learning and tests)

[UserAndPasswordConfig.java](../demo/src/main/java/demo/security/config/UserAndPasswordConfig.java):

```java
@Bean
UserDetailsService userDetailsService(PasswordEncoder encoder) {
    return new InMemoryUserDetailsManager(
            User.withUsername("user")
                    .password(encoder.encode("password"))
                    .roles("USER")            // becomes the authority "ROLE_USER"
                    .build(),
            User.withUsername("admin")
                    .password(encoder.encode("admin123"))
                    .roles("USER", "ADMIN")
                    .build());
}
```

### From a database (real projects)

Write a class implementing `UserDetailsService` that converts your entity into `UserDetails`:

```java
record Account(String username, String passwordHash, boolean locked, List<String> roles) {}

interface AccountRepository {
    Optional<Account> findByUsername(String username);
}

@Service
class DatabaseUserDetailsService implements UserDetailsService {

    private final AccountRepository accounts;

    DatabaseUserDetailsService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Account account = accounts.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        return User.withUsername(account.username())
                .password(account.passwordHash())          // the hashed string from the DB
                .roles(account.roles().toArray(String[]::new))
                .accountLocked(account.locked())
                .build();
    }
}
```

Remember:

- This method **only finds the user**; it does not compare passwords. That is `DaoAuthenticationProvider`'s job.
- Throw `UsernameNotFoundException` when nothing is found. Spring converts it into `BadCredentialsException`
  before it leaves, so an attacker cannot probe which usernames exist. Your own login error message should be
  equally generic: "Wrong username or password".
- "Username" is just the identifying key: it can be an email or a phone number.
- The flags `accountLocked`, `disabled`, `accountExpired`, `credentialsExpired` are checked by Spring automatically.

## 3. Storing passwords

### Principles

| Storage | Verdict |
|---------|---------|
| Plain text | A leaked database leaks every password. Never |
| Reversible encryption (AES...) | Whoever has the key can decrypt. Not for passwords |
| Fast hashes (MD5, SHA-256), even salted | GPUs try billions per second. Not enough |
| **Slow adaptive hashes**: bcrypt, argon2, scrypt, PBKDF2 | Correct. Deliberately slow, a unique salt per password, difficulty can be raised over time |

Passwords **never need to be reversed**. At login, hash what was typed and compare it with the stored value.

### `PasswordEncoder` in Spring

```java
@Bean
PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
}
```

The result of hashing the string `password` (actually run on Spring Security 7.1.2):

```
{bcrypt}$2a$10$S65sxXUV6GKlkZZgDWsrwu1ep6FsQcBR.x6wHzNcI4SHAFW5cx8Uu
└──┬───┘└┬┘└┬┘└────────────────────────┬───────────────────────────┘
   │     │  │                          └ salt (22 chars) + hash (31 chars)
   │     │  └ cost = 10 → 2^10 rounds. Each +1 doubles the time
   │     └ bcrypt version
   └ DelegatingPasswordEncoder prefix: says which algorithm was used
```

Hashing the same password twice gives two different strings because the salt is random. So matching must use
`encoder.matches(raw, encoded)`, never string `equals`.

Why prefer `DelegatingPasswordEncoder` over a direct `new BCryptPasswordEncoder()`: the `{bcrypt}` prefix lets
each password declare its own algorithm. When you move to argon2 years later, old passwords still verify, and
Spring re-hashes them with the new algorithm when the user logs in successfully (if your `UserDetailsService`
also implements `UserDetailsPasswordService`).

A common error: `There is no PasswordEncoder mapped for the id "null"`. Cause: the password in the database has
no `{...}` prefix while a `DelegatingPasswordEncoder` is in use. Fix it by adding the prefix to the data, or use
`BCryptPasswordEncoder` directly if all the data is bcrypt.

When registering a user, always hash before saving:

```java
account.setPasswordHash(passwordEncoder.encode(rawPassword));
```

## 4. Form login: the full flow

```java
http.formLogin(Customizer.withDefaults());
```

```
1. GET /web/home (not logged in)
     AuthorizationFilter denies → ExceptionTranslationFilter
     → saves the original URL in the RequestCache → 302 Location: /login

2. GET /login
     DefaultLoginPageGeneratingFilter returns an HTML form (with a hidden _csrf input)

3. POST /login  (username, password, _csrf)
     CsrfFilter checks the token
     UsernamePasswordAuthenticationFilter → AuthenticationManager
        ├─ wrong → 302 /login?error
        └─ right → change the session id (session fixation protection)
                   save the SecurityContext in the HttpSession
                   302 to the original URL saved in step 1 (or "/")

4. GET /web/home  with the JSESSIONID cookie
     SecurityContextHolderFilter loads the context from the session → logged in → 200
```

Common customisations:

```java
http.formLogin(form -> form
        .loginPage("/login").permitAll()      // your own login page; permitAll or you get an endless redirect
        .defaultSuccessUrl("/web/home")
        .failureUrl("/login?error"));
```

With a custom `loginPage`, the form must `POST` to `/login` with the parameters `username`, `password` and the
CSRF token. With Thymeleaf, `th:action` inserts the token for you.

## 5. HTTP Basic

```java
http.httpBasic(Customizer.withDefaults());
```

The client sends `Authorization: Basic base64(username:password)` with **every** request. Base64 is not
encryption, so use it over HTTPS only. It suits internal tools, scripts and simple service-to-service calls; it
does not suit end-user applications because the password travels with every request.

## 6. Authenticating in a controller (login for a REST API)

An API has no form. The controller receives JSON and calls the `AuthenticationManager` directly
([AuthController.java](../demo/src/main/java/demo/security/auth/AuthController.java)):

```java
@PostMapping("/login")
public LoginResponse login(@RequestBody LoginRequest request) {
    Authentication unauthenticated =
            UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password());

    // Wrong password → throws BadCredentialsException → ExceptionTranslationFilter → 401
    Authentication authenticated = authenticationManager.authenticate(unauthenticated);

    IssuedToken token = tokenService.issue(authenticated);
    return new LoginResponse(token.value(), "Bearer", token.expiresInSeconds());
}
```

To inject the `AuthenticationManager` you must expose it as a bean:

```java
@Bean
AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
    return configuration.getAuthenticationManager();
}
```

The controller **never compares passwords itself**. Hand it to the `AuthenticationManager` to benefit from all
the built-in checks (locked or expired accounts, protection against timing-based probing).

The real DEBUG log for a wrong password followed by a correct one:

```
DaoAuthenticationProvider : Failed to authenticate since password does not match stored value
ProviderManager           : Authentication failed with provider DaoAuthenticationProvider since Bad credentials
...
DaoAuthenticationProvider : Authenticated user
```

## 7. Sessions

| Setting | Meaning |
|---------|---------|
| `SessionCreationPolicy.IF_REQUIRED` | The default. Create a session when needed (after form login) |
| `SessionCreationPolicy.STATELESS` | Never create or use a session. For token-based APIs |
| `sessionFixation(f -> f.changeSessionId())` | The default. Change the session id after login |
| `maximumSessions(1)` | One session per user; logging in elsewhere invalidates the old one |

```java
http.sessionManagement(session -> session
        .sessionFixation(fixation -> fixation.changeSessionId())
        .maximumSessions(1)
        .maxSessionsPreventsLogin(false));   // false: the new session kicks the old one; true: block the new login
```

The session cookie needs these flags in production (`application.yml`):

```yaml
server:
  servlet:
    session:
      timeout: 30m
      cookie:
        http-only: true     # JavaScript cannot read it → limits XSS damage
        secure: true        # sent over HTTPS only
        same-site: lax      # limits cross-site sending → an extra CSRF defence
```

With several instances, in-memory sessions on each machine are not shared. Use Spring Session with Redis, or go stateless.

## 8. Remember-me and logout

```java
http
    .rememberMe(remember -> remember
            .key("change-to-a-secret-value")
            .tokenValiditySeconds(14 * 24 * 3600))
    .logout(logout -> logout
            .logoutSuccessUrl("/login?logout")
            .deleteCookies("JSESSIONID"));
```

- **Remember-me**: issues an extra long-lived cookie to log the user back in when the session expires. Convenient,
  but it raises the risk if the cookie is stolen. For sensitive actions (changing the password, payments), require
  `fullyAuthenticated()` to force the password to be re-entered (lesson 4).
- **Logout**: the default is `POST /logout` with a CSRF token (not `GET`, so another page cannot log you out with
  an `<img>` tag). It invalidates the session, clears the `SecurityContext`, and removes the remember-me cookie.

## 9. Self-check

1. Does `UserDetailsService.loadUserByUsername` compare the password? Who does?
2. Why does bcrypt give two different strings for the same password, and login still works?
3. Why does Spring hide `UsernameNotFoundException` behind `BadCredentialsException`?
4. After `loginPage("/sign-in")` the application reports "too many redirects". What is missing?

Short answers: (1) No; `DaoAuthenticationProvider` calls `PasswordEncoder.matches`. (2) The random salt is part of
the resulting string; `matches` reads that salt back to re-hash and compare. (3) To prevent probing for existing
usernames. (4) `permitAll()` on the login page: the login page itself requires login, so it redirects to itself.

Next: [04 — Authorization](04-authorization.md).
