# 06 — JWT and stateless REST API

Goal: after this lesson you understand a JWT well enough to read each part of it, you can build a stateless API
with Spring Security's built-in support (no hand-written filter), and you know JWT's limits so you do not use it
in the wrong place.

## 1. What a JWT is

A JWT (JSON Web Token) is a string of three parts separated by dots. A real token issued by the demo:

```
eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzcHJpbmctc2VjdXJpdHktc3R1ZHktZGVtbyIsInN1YiI6InVzZXIiLCJleHAiOjE3OTU3MDk1NjksImlhdCI6MTc5NTcwODY2OSwicm9sZXMiOlsiUk9MRV9VU0VSIl19.H74MFxgWFQSLnOxnciobEolK-N7F6ideqJPW2ox0NfM
└────── header ─────┘ └──────────────────────────────── payload ───────────────────────────────────────────────────────────────────────┘ └──────────────── signature ───────────────┘
```

| Part | Content after base64url decoding | Meaning |
|------|----------------------------------|---------|
| Header | `{"alg":"HS256"}` | The signing algorithm |
| Payload | `{"iss":"spring-security-study-demo","sub":"user","exp":1795709569,"iat":1795708669,"roles":["ROLE_USER"]}` | The claims: information about the user and the token |
| Signature | HMAC-SHA256(header + "." + payload, secret) | Proves the token was issued by the server and not modified |

Commonly used standard claims:

| Claim | Meaning |
|-------|---------|
| `sub` | Subject: whose token this is (username or user id) |
| `iss` | Issuer: who issued it |
| `aud` | Audience: which service it was issued for |
| `exp` | When it expires (seconds since 1970) |
| `iat` | When it was issued |
| `jti` | A unique token id (used when revocation is needed) |

### Two facts to know by heart

1. **The payload is not encrypted.** Base64 is just another way of writing it; anyone holding the token can read it.
   Never put passwords, card numbers or sensitive data in a JWT. Try it:

   ```bash
   echo 'eyJpc3MiOiJzcHJpbmctc2VjdXJpdHktc3R1ZHktZGVtbyIsInN1YiI6InVzZXIiLCJleHAiOjE3OTU3MDk1NjksImlhdCI6MTc5NTcwODY2OSwicm9sZXMiOlsiUk9MRV9VU0VSIl19' | base64 -d
   ```

2. **The signature prevents modification, not reading.** Change one character in the payload (for example promoting
   yourself to `ROLE_ADMIN`) and the signature no longer matches, so the server rejects it. On the demo, appending
   one character to the token:

   ```
   HTTP/1.1 401
   WWW-Authenticate: Bearer
   ```
   ```
   ProviderManager : Authentication failed with provider JwtAuthenticationProvider since
                     An error occurred while attempting to decode the Jwt: Signed JWT rejected: Invalid signature
   ```

## 2. The flow

```
┌────────┐  1. POST /api/auth/login {username, password}   ┌────────┐
│        │ ──────────────────────────────────────────────▶ │        │ checks the password
│ Client │  2. {accessToken: "eyJ...", expiresIn: 900}     │ Server │ signs a JWT with the secret
│        │ ◀────────────────────────────────────────────── │        │
│        │                                                 │        │
│        │  3. GET /api/me                                 │        │ verifies signature + expiry
│        │     Authorization: Bearer eyJ...                │        │ NO database lookup
│        │ ──────────────────────────────────────────────▶ │        │ NO session
│        │  4. 200 {...}                                   │        │
└────────┘ ◀────────────────────────────────────────────── └────────┘
```

The server stores nothing about the login. Everything needed to recognise the user is in the token, and the
signature guarantees that information is trustworthy. That is what "stateless" means.

## 3. Implementation in Spring Security

Many tutorials hand-write a hundred-line `JwtAuthenticationFilter` with the `jjwt` library. There is no need:
Spring Security ships an **OAuth2 Resource Server** that does exactly this, is thoroughly tested, and handles the edge cases.

Dependency (Spring Boot 4; on Boot 3 it is `spring-boot-starter-oauth2-resource-server`):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>
```

### Step 1: signing key, encoder, decoder

[JwtConfig.java](../demo/src/main/java/demo/security/config/JwtConfig.java):

```java
@Bean
SecretKey jwtSecretKey(@Value("${app.jwt.secret}") String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) {
        throw new IllegalStateException("app.jwt.secret phai dai toi thieu 32 byte cho HS256");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
}

@Bean
JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
}

@Bean
JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
    return NimbusJwtDecoder.withSecretKey(jwtSecretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
}
```

The `JwtDecoder` checks the signature and the time claims (`exp`, `nbf`) itself. You write no validation code.
(The exception message says: `app.jwt.secret` must be at least 32 bytes for HS256.)

### Step 2: issuing the token

[TokenService.java](../demo/src/main/java/demo/security/auth/TokenService.java):

```java
JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer("spring-security-study-demo")
        .subject(authentication.getName())
        .issuedAt(now)
        .expiresAt(now.plus(ttl))                 // 15 minutes
        .claim(JwtConfig.ROLES_CLAIM, roles)
        .build();

JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
```

### Step 3: mapping claims to authorities

By default Spring reads the `scope` claim and adds the `SCOPE_` prefix. The demo puts permissions in the `roles`
claim, so it must be told:

```java
@Bean
JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName(ROLES_CLAIM);
    authorities.setAuthorityPrefix("");          // the claim values are already "ROLE_USER"

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
}
```

Forgetting this step is the number one cause of "the token is valid but `hasRole` always returns 403": the
authorities are read from the wrong claim or with the wrong prefix. Call `/api/me` to see the authorities Spring actually got:

```json
{"authorities":["FACTOR_BEARER","ROLE_USER"],"username":"user","tokenExpiresAt":"2026-11-26T16:12:49Z"}
```

### Step 4: the filter chain

[ApiSecurityConfig.java](../demo/src/main/java/demo/security/config/ApiSecurityConfig.java):

```java
http
        .securityMatcher("/api/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                .authenticationEntryPoint(errorHandlers)
                .accessDeniedHandler(errorHandlers))
        .exceptionHandling(ex -> ex
                .authenticationEntryPoint(errorHandlers)
                .accessDeniedHandler(errorHandlers));
```

The `oauth2ResourceServer(...jwt...)` line installs `BearerTokenAuthenticationFilter` in the chain. That filter
reads the `Authorization: Bearer ...` header, calls the `JwtDecoder`, and puts a `JwtAuthenticationToken` in the `SecurityContext`.

Reading the token in a controller:

```java
@GetMapping("/me")
public Map<String, Object> me(Authentication authentication, @AuthenticationPrincipal Jwt jwt) {
    jwt.getSubject();            // "user"
    jwt.getExpiresAt();
    jwt.getClaimAsStringList("roles");
    ...
}
```

## 4. Symmetric and asymmetric keys

| | HS256 (symmetric) | RS256 / ES256 (asymmetric) |
|---|---|---|
| Keys | One secret for both signing and verifying | A private key to sign, a public key to verify |
| Who can verify | Whoever has the secret, and they can also **sign** | Whoever has the public key; they cannot sign |
| Fits | One service that both issues and checks (like the demo) | Many services checking tokens issued in one place |
| Key distribution | Must stay secret everywhere it is used | The public key is published at a JWKS endpoint |

In a microservice system always use asymmetric keys: share an HS256 secret with ten services and a leak in any one
lets an attacker forge tokens for the whole system. When tokens are issued by Keycloak, Auth0 or Spring
Authorization Server, the resource server needs one line of configuration and Spring downloads the public keys itself (lesson 7):

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/demo
```

## 5. JWT's limits and how to handle them

### It cannot be revoked

An issued token is valid until it expires. The user logs out, is locked, has permissions changed: the old token
still works. That is the price of not looking anything up in a database.

| Mitigation | Trade-off |
|------------|-----------|
| Short-lived access tokens (5–15 minutes) | Needs a refresh token to avoid constant re-login |
| A deny list keyed by `jti` (kept in Redis until the token expires) | A Redis lookup per request: part of the statelessness is lost |
| A per-user "token version", bumped on password change or lock | A user lookup per request |

### Refresh tokens

```
Access token:  short-lived (15 minutes), sent with every API request
Refresh token: long-lived (days, weeks), sent ONLY to the token-refresh endpoint,
               stored on the server (database) → revocable
```

When the access token expires, the client exchanges the refresh token for a new access token. Good practice:

- A refresh token is a random string, stored **hashed** on the server; it need not be a JWT.
- **Rotation**: every use issues a new refresh token and invalidates the old one. Seeing an old one reused means it
  leaked: revoke the whole chain.
- Logging out means deleting the refresh token on the server.

The demo leaves refresh tokens out to stay focused. If a project needs login, registration, refresh, revocation
and social login, consider an existing authorization server (Keycloak, Spring Authorization Server) instead of building one.

### Where to keep the token in a browser

| Storage | XSS can read the token | CSRF | Notes |
|---------|------------------------|------|-------|
| `localStorage` / `sessionStorage` | Yes | Not affected | One XSS bug loses the token |
| A JavaScript in-memory variable | Harder | Not affected | Lost on page reload; combine with a refresh token in a cookie |
| `HttpOnly` + `Secure` + `SameSite` cookie | No | **Yes**: CSRF protection must be on | Effectively back to the cookie model |

No option is immune. The currently recommended model for SPAs is the **BFF (Backend For Frontend)**: the browser
holds only an `HttpOnly` session cookie with its own backend; the backend holds the tokens and calls APIs on its behalf.

### Other pitfalls

| Pitfall | Consequence | How to avoid |
|---------|-------------|--------------|
| A short, guessable secret, or one committed to git | Anyone can sign an admin token | At least 32 random bytes, loaded from an environment variable |
| Accepting `alg: none` or letting the token pick the algorithm | Signature checking is bypassed | Pin the algorithm in the decoder, as the demo does with `.macAlgorithm(MacAlgorithm.HS256)` |
| Not checking `iss`, `aud` | A token issued for another service works on yours | Add issuer and audience validators |
| No `exp`, or a very long lifetime | A leaked token works forever | Always set a short lifetime |
| Too much data in claims | Bloated headers, stale data (permissions changed but the token has not) | Only identity and minimal permissions |
| Logging tokens, putting them in URLs | Tokens leak through logs and browser history | Send them in headers only, mask them in logs |
| Plain HTTP | Tokens are sniffed | Always HTTPS |

## 6. When not to use JWT

- A server-rendered web app on one domain: session cookies are simpler, instantly revocable, with fewer things to get wrong.
- Instant revocation is a hard requirement (banking, admin consoles): sessions, or opaque tokens with lookup.

JWT is worth it when several services must verify identity without calling a central place on every request, or
when the client is not a browser.

## 7. Self-check

1. Someone captures your access token. What can they read and what can they do?
2. A user is locked at 10:00; their token expires at 10:12. What happens during those 12 minutes?
3. A valid token contains `"roles":["ADMIN"]`, yet `hasRole("ADMIN")` returns 403. What do you check first?
4. Why should a ten-service system not share one HS256 secret?

Short answers: (1) They can read the whole payload and use the token as you until it expires. (2) The token is
still accepted, unless there is a deny list or a user-status check. (3) The `JwtAuthenticationConverter`: is it
reading the right claim, and with which prefix; here the authority `ROLE_ADMIN` is needed but the claim only gives
`ADMIN`. (4) Every service can sign tokens; a leak in one place brings down the whole system.

Next: [07 — OAuth2 and OpenID Connect](07-oauth2-and-openid-connect.md).
