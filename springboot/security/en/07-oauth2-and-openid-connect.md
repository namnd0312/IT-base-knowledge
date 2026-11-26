# 07 — OAuth2 and OpenID Connect

Goal: after this lesson you understand which problem OAuth2 solves, can tell the roles and flows apart, and know
which role your Spring application plays and which starter it needs. This lesson is a concept map; running it for
real needs an identity provider (Google, Keycloak), so it is not part of the demo.

## 1. The problem

A photo-printing app wants your pictures from Google Photos. The bad way: you give that app your Google password.
It then has full control of your account, and you can only revoke it by changing the password.

OAuth2 is a **delegation** protocol: you let a third-party application access part of your resources, for a limited
time, **without handing over your password**. What the application receives is an access token with a narrow scope.

## 2. Four roles

| Role | Who | In the example |
|------|-----|----------------|
| Resource Owner | The person owning the data | You |
| Client | The application wanting access | The photo-printing app |
| Authorization Server | Where the user authenticates and tokens are issued | Google Accounts, Keycloak, Auth0 |
| Resource Server | The API holding the data, accepting tokens | The Google Photos API |

Your Spring Boot application can play one or more roles:

| Your application's role | Situation | Starter (Spring Boot 4) | DSL |
|-------------------------|-----------|-------------------------|-----|
| Client | "Log in with Google"; calling someone else's API on behalf of the user | `spring-boot-starter-security-oauth2-client` | `oauth2Login()`, `oauth2Client()` |
| Resource Server | Your API accepts access tokens issued elsewhere | `spring-boot-starter-security-oauth2-resource-server` | `oauth2ResourceServer()` |
| Authorization Server | You run the token issuer yourself | `spring-boot-starter-oauth2-authorization-server` | (its own configuration) |

The demo in this guide is a resource server that issues tokens for itself: a reduced form that is enough for a single service.

## 3. OAuth2 and OpenID Connect

| | OAuth2 | OpenID Connect (OIDC) |
|---|---|---|
| Purpose | Delegation: "what this application may do" | Authentication: "who this user is" |
| You receive | An access token | An access token + an **ID token** |
| ID token | None | A JWT with user information: `sub`, `email`, `name` |
| Relationship | The foundation | A thin layer built on OAuth2 |

"Log in with Google" is OIDC. The access token is for the API (resource server) to read; the ID token is for the
client to read, to learn who just logged in. Do not send an ID token as a credential to an API.

## 4. The flows (grant types)

| Flow | Used when | Status |
|------|-----------|--------|
| **Authorization Code + PKCE** | A user is present: web, SPA, mobile | Standard, for every case with a user |
| **Client Credentials** | Service calls service, no user | Standard |
| Refresh Token | Getting a new access token on expiry | Standard, accompanies the flows above |
| Device Code | Devices without a keyboard (TV, CLI) | Standard |
| Implicit | Old SPAs: the token returned straight in the URL | **Dropped**. Replaced by Authorization Code + PKCE |
| Resource Owner Password | The client collects username and password itself | **Dropped**. It defeats the very purpose of OAuth2 |

### Authorization Code + PKCE step by step

```
User         Client (your application)        Authorization Server          Resource Server
 │ clicks "Log in"      │                              │                           │
 │─────────────────────▶│                              │                           │
 │                      │ 1. generates code_verifier (random), keeps it            │
 │                      │    code_challenge = SHA256(code_verifier)                │
 │  2. 302 to /authorize?client_id=...&redirect_uri=...                            │
 │     &scope=openid profile&state=...&code_challenge=...                          │
 │◀─────────────────────│                              │                           │
 │ 3. logs in and consents (the password is typed only here)                       │
 │────────────────────────────────────────────────────▶│                           │
 │  4. 302 back to redirect_uri?code=...&state=...     │                           │
 │◀────────────────────────────────────────────────────│                           │
 │─────────────────────▶│                              │                           │
 │                      │ 5. POST /token: code + code_verifier (+ client_secret)   │
 │                      │─────────────────────────────▶│                           │
 │                      │ 6. access_token, id_token, refresh_token                 │
 │                      │◀─────────────────────────────│                           │
 │                      │ 7. GET /api/... Authorization: Bearer <access_token>     │
 │                      │─────────────────────────────────────────────────────────▶│
```

Why the detour through a `code` instead of returning the token in step 4: the URL in step 4 passes through the
browser and ends up in history and logs. The `code` is single-use, lives for tens of seconds, and is useless
without the `code_verifier`. The real tokens only travel server to server in steps 5 and 6.

| Parameter | Defends against |
|-----------|-----------------|
| `state` | CSRF on the login flow itself: the client checks that the returned `state` matches what it sent |
| `code_challenge` / `code_verifier` (PKCE) | Someone intercepting the `code` cannot exchange it without the `code_verifier` |
| Pre-registered `redirect_uri` | The authorization server only returns the `code` to a registered address, not the attacker's page |

Spring Security takes care of `state`, PKCE and the code-for-token exchange. You do not write these steps.

## 5. Your application as a Client: log in with Google

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: openid, profile, email
```

```java
http
    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
    .oauth2Login(Customizer.withDefaults());
```

- Google, GitHub, Facebook and Okta have their endpoints preconfigured; only `client-id` and `client-secret` are needed.
- The redirect URI to register in the Google Console follows Spring's pattern: `{baseUrl}/login/oauth2/code/google`.
- After login the principal is an `OidcUser`. Read it with `@AuthenticationPrincipal OidcUser user`, then `user.getEmail()`.
- The application still uses a session to remember the user after login. `oauth2Login` only replaces the "check the password" step.
- `client-secret` is a real secret: load it from an environment variable, do not commit it.

For other providers (Keycloak, an in-house one) add a `provider` with an `issuer-uri`; Spring reads the configuration
from `{issuer-uri}/.well-known/openid-configuration`.

What you must decide yourself: mapping the Google user to an account and **permissions** in your system. Google only
says "this is who it is"; roles in your application are your data.

## 6. Your application as a Resource Server: accepting tokens from Keycloak

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/demo
```

```java
http
    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
```

With `issuer-uri`, Spring does the following by itself:

1. Downloads the metadata and finds the JWKS address (the list of public keys).
2. Verifies each token's signature with the matching public key; re-downloads when the authorization server rotates keys.
3. Checks `exp`, and that `iss` matches the `issuer-uri`.

Compared with the demo (lesson 6): no more `JwtEncoder`, no secret, no `/login` endpoint of your own. Issuing tokens
belongs to the authorization server.

Two common snags:

- **Permissions live in another claim.** By default Spring reads `scope` as `SCOPE_xxx`. Keycloak puts roles in
  `realm_access.roles`. Write your own `JwtAuthenticationConverter` to read the right place (the same technique as in lesson 6).
- **Checking `aud`.** It is not checked by default. If one authorization server issues tokens for several APIs, add
  an audience validator so another API's token does not work on yours.

### Scopes and roles

| | Scope | Role |
|---|---|---|
| Limits | What the **client application** may do on behalf of the user | What the **user** may do |
| Example | `photos.read` | `ADMIN` |
| Granted by | The user, on the consent screen | A system administrator |

The effective permission is the intersection: an application with the `orders.write` scope is still denied if the
user is not allowed to edit orders.

## 7. Service calls service: Client Credentials

There is no user. Service A authenticates itself to the authorization server with `client_id` + `client_secret`,
receives an access token, and calls service B. Service B is a resource server as in section 6.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          inventory:
            provider: company-auth
            client-id: order-service
            client-secret: ${ORDER_SERVICE_SECRET}
            authorization-grant-type: client_credentials
            scope: inventory.read
        provider:
          company-auth:
            issuer-uri: https://auth.example.com/realms/demo
```

Spring manages fetching, caching and refreshing the token through the `OAuth2AuthorizedClientManager`; you attach it
to the HTTP client (`RestClient`, `WebClient`) so the token is added to every request.

## 8. Choosing an architecture

| Situation | Suggestion |
|-----------|------------|
| One web application, its own users, server-rendered | Form login + session (lesson 3). No OAuth2 needed |
| One backend API + one frontend/mobile app of your own | Issue JWTs yourself as in the demo, or session + SPA-style CSRF |
| "Log in with Google/GitHub" | `oauth2Login` |
| Many services and applications, single sign-on (SSO) | A dedicated authorization server (Keycloak...); the APIs are resource servers |
| Service calls service | Client Credentials |

Building your own authorization server (registration, password reset, MFA, refresh, revocation, account locking) is
a lot of work with many places to go wrong. Once the need grows beyond one service, an existing product is usually
cheaper and safer.

## 9. Self-check

1. How do the access token and the ID token differ, and who reads each?
2. Why does step 4 return a `code` rather than the access token directly?
3. Your API accepts tokens from Keycloak, but `hasRole("ADMIN")` always returns 403 although the user has the admin role. Why?
4. A nightly job service needs to call an internal API. Which flow?

Short answers: (1) The access token is for the resource server to read and decide on access; the ID token tells the
client who the user is. (2) The URL passes through the browser and leaks easily; the `code` is single-use,
short-lived, and needs the `code_verifier` to be exchanged. (3) Spring reads permissions from the `scope` claim by
default; Keycloak's roles are in `realm_access.roles` and need a custom converter. (4) Client Credentials.

Next: [08 — Testing, debugging and troubleshooting](08-testing-debugging-and-troubleshooting.md).
