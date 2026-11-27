# 05 — Authentication and authorization

Goal: know the ways a system establishes the identity of users and of other systems (Basic, sessions, OAuth 2.0,
OpenID Connect, JWT), which network layer from the previous lessons each one relies on, and how authorisation
decisions are made (RBAC, ABAC).

This lesson focuses on **mechanisms and data flowing over the network**. Spring Security configuration details
(filters, form login, CSRF, `@PreAuthorize`) are in this repository's [Spring Security](../../springboot/security/en/README.md)
guide; lesson 7 connects the two.

## 1. Authentication and authorization

| | Authentication | Authorization |
|---|---|---|
| Question | Who are you? | What may you do? |
| Input | Proof: password, certificate, token, OTP | An authenticated identity + resource + action (+ context) |
| Failure | **401 Unauthorized** (a misleading name: it really means "unauthenticated") | **403 Forbidden** |
| Example | mTLS proves the caller is `order-service` | `order-service` may `GET /internal/inventory` but not `DELETE` it |

Always in this order: authenticate first, authorise second, and authorise **on every request**, not only at login.

There are two kinds of identity, proven differently:

| Subject | Common ways to prove it |
|---------|-------------------------|
| A user (through a browser, an app) | Password + MFA → a session cookie or an OAuth/OIDC token |
| A system (service, job, partner) | Client certificate (mTLS), OAuth 2.0 client credentials, API key, HMAC signature (lesson 6) |

## 2. Basic Authentication

```
GET /api/orders HTTP/1.1
Authorization: Basic YWxpY2U6UzNjcmV0LVBhNTU=          ← Base64("alice:S3cret-Pa55")
```

- Base64 is **character encoding, not encryption**: whoever reads the header has the password. Acceptable only over
  TLS (demo 1 shows that without TLS this header ends up with the relay).
- The password travels in **every** request: the more often it is sent, the more places it can leak (logs, proxies,
  heap dumps).
- No logout, no expiry, no scope.

Acceptable for: simple internal tools, actuator endpoints behind an admin network, temporary machine-to-machine calls.
Not for end users of a real application.

## 3. Session-based authentication

```
1. POST /login  (username, password)          ──▶ the server checks, creates a session, keeps it server-side
2.              ◀── Set-Cookie: JSESSIONID=8f3a...; HttpOnly; Secure; SameSite=Lax
3. GET /orders   Cookie: JSESSIONID=8f3a...    ──▶ the server looks up the session → it is alice
```

| Pros | Cons |
|------|------|
| Full server control: logout, instant revocation | Stateful: several instances need a shared session store (Redis, Spring Session) or sticky sessions |
| The session ID is a random string carrying no data | The browser **sends the cookie automatically** → CSRF protection is required (lesson 6) |
| A natural fit for server-rendered web apps | Not suited to service-to-service |

Mandatory cookie attributes: `HttpOnly` (JavaScript cannot read it, limiting XSS damage), `Secure` (HTTPS only),
`SameSite` (limits CSRF). Change the session ID after login (against session fixation): Spring Security does it already.

## 4. OAuth 2.0

OAuth 2.0 is a **delegation framework**: it lets an application obtain limited access to resources without holding the
user's password. It is **not** a user authentication protocol (that is OIDC, section 5).

| Role | Example |
|------|---------|
| Resource owner | The user alice |
| Client | A web/mobile application, or a service |
| Authorization server | Keycloak, Auth0, Okta, Entra ID, Spring Authorization Server |
| Resource server | Your API: verifies the access token and returns data |

### Authorization Code + PKCE (a user signs in through an app)

```
Browser          Client (app)                 Authorization server          Resource server
    │ 1. clicks "Sign in"                             │                            │
    │ ◀── 302 to /authorize?client_id&redirect_uri&scope&state&code_challenge      │
    │ ── 2. alice signs in + MFA on the AS's page ───▶│                            │
    │ ◀── 3. 302 back to redirect_uri?code=abc&state=...                           │
    │ ──────▶ 4. the client exchanges the code:       │                            │
    │         POST /token code=abc, code_verifier ───▶│  (back channel, over TLS)  │
    │         ◀── access_token (+ refresh_token, id_token)                         │
    │                    5. GET /api/orders  Authorization: Bearer <access_token> ─▶│
    │                                                  checks signature, iss, aud, exp, scope
```

- alice's password only goes to the authorization server; the client never sees it.
- The `code` passes through the browser (it can leak in logs and history), so it is short-lived, single-use, and
  exchanged for tokens over a direct TLS channel. **PKCE** (`code_challenge` = the hash of a `code_verifier` secret
  only the client holds) ensures that whoever steals the `code` cannot redeem it. Mandatory for mobile apps/SPAs,
  recommended for every client.
- `state` protects the login flow against CSRF; `redirect_uri` must exactly match the registered list.
- The Implicit and Password grants are dropped from current guidance (the OAuth 2.0 Security BCP, OAuth 2.1).

### Client Credentials (a service calls a service)

```
order-service ── POST /token  grant_type=client_credentials, client auth (secret, private_key_jwt or mTLS) ──▶ AS
              ◀── access_token (scope=inventory:read, aud=inventory-api)
order-service ── GET /inventory  Authorization: Bearer ... ──▶ inventory-api
```

No user involved. The service authenticates itself to the AS and receives a narrowly scoped token. Compared with direct mTLS:

| | mTLS | Client credentials |
|---|---|---|
| Identity lives at | The connection layer | The application layer (the token) |
| Authorisation by | The certificate's CN/SAN (an allow-list you maintain) | `scope`, `aud`, issued centrally by the AS |
| Revocation | Short-lived certificates / CRL | Short-lived tokens, disabling the client at the AS |
| Crossing a TLS-terminating proxy | The identity is lost (lesson 3) | Unaffected |

The two complement each other: many systems use mTLS for the channel **and** tokens for permissions. RFC 8705 even
binds tokens to the client certificate (certificate-bound tokens): a stolen token is useless without the private key.

### Token types

| Token | For | Characteristics |
|-------|-----|-----------------|
| Access token | The resource server | Short-lived (minutes); sent in `Authorization: Bearer` |
| Refresh token | The authorization server | Longer-lived; exchanged for new access tokens; must be stored carefully and rotated on every use |
| ID token (OIDC) | The client | Tells the client who the user is; **never** sent to APIs |

"Bearer" means whoever holds the token can use it, like cash. So tokens travel only over TLS, live briefly, never go
into logs, and are never placed in URLs.

## 5. OpenID Connect

OIDC is an **authentication** layer on top of OAuth 2.0: it adds the `openid` scope, the **ID token** (always a JWT),
the `userinfo` endpoint and configuration discovery:

```
https://issuer.example/.well-known/openid-configuration   → issuer, authorization_endpoint, token_endpoint, jwks_uri...
https://issuer.example/.well-known/jwks.json              → the public keys for verifying token signatures
```

An ID token answers "who just signed in, where, when, for which client":

```json
{ "iss": "https://issuer.example", "sub": "248289761001", "aud": "my-web-app",
  "exp": 1795803339, "iat": 1795802439, "nonce": "n-0S6_WzA2Mj", "email": "alice@example.com" }
```

The client must check the signature, `iss`, `aud` = its own client_id, `exp`, and that `nonce` matches the value it
sent (against replay).

The boundary to keep: **ID tokens are for clients, access tokens are for APIs.** An API accepting ID tokens instead of
access tokens is wrong: an ID token's `aud` is the client, not the API.

## 6. JWT

A JWT (JSON Web Token) is a self-contained token format: `header.payload.signature`, each part Base64URL.

```
eyJraWQiOiJkZW1vLWtleSIsImFsZyI6IlJTMjU2... . eyJpc3MiOiJodHRwczovL2F1dGguZGVtby5sb2Nh... . <signature>
{"kid":"demo-key","alg":"RS256"}               {"iss":"https://auth.demo.local","sub":"alice","aud":"orders-api",
                                                 "exp":1795803339,"iat":1795802439,"scope":"orders:read"}
```

(Taken from the Spring Boot demo in lesson 7.)

- The payload is **signed, not encrypted**: anyone can read it. Do not put secrets or sensitive personal data in it
  (encryption needs JWE).
- The big advantage: the resource server verifies **locally** with a public key, without calling the AS per request.
- The matching drawback: it cannot be revoked before `exp`. Compensate with short-lived access tokens (5–15 minutes) +
  refresh tokens, or opaque tokens + introspection when instant revocation is required.

| Algorithm | Key | Who can create tokens | Use when |
|-----------|-----|-----------------------|----------|
| HS256 | One shared secret (HMAC) | **Every** holder of the secret, including every API that verifies | One service issues and verifies its own tokens |
| RS256 / PS256 | RSA: the AS holds the private key, APIs hold the public key | The AS only | Many APIs, an independent AS (the default of most ASs) |
| ES256 | ECDSA P-256 | The AS only | As RS256, with shorter tokens |

### What verifying a JWT consists of

[JwtValidationDemo.java](../demos/JwtValidationDemo.java) writes each step by hand with the JDK so it is visible. The
`broken` mode does what a lot of home-made code does: Base64-decode the payload and believe it.

```
mode=broken
valid token (alice, USER)       -> ACCEPTED as sub=alice role=USER
payload edited to role=ADMIN    -> ACCEPTED as sub=alice role=ADMIN
alg=none, no signature          -> ACCEPTED as sub=mallory role=ADMIN
expired one hour ago            -> ACCEPTED as sub=alice role=USER
issued for audience billing-api -> ACCEPTED as sub=alice role=USER
RESULT: WRONG (4 forged or unusable tokens were accepted)
```

The `fixed` mode:

```java
if (!"HS256".equals(claim(header, "alg"))) return "REJECTED: ...";            // 1. the server picks the algorithm, not the token
byte[] expected = hmac(parts[0] + "." + parts[1]);                           // 2. signature over the exact header.payload bytes
if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) return "REJECTED: ...";
// only now is the payload worth reading
if (exp <= now) ...                                                          // 3. validity
if (!ISSUER.equals(iss)) ...                                                 // 4. the right issuer
if (!AUDIENCE.equals(aud)) ...                                               // 5. issued for THIS API
```

```
mode=fixed
valid token (alice, USER)       -> ACCEPTED as sub=alice role=USER
payload edited to role=ADMIN    -> REJECTED: signature does not match
alg=none, no signature          -> REJECTED: algorithm 'none' is not the configured HS256
expired one hour ago            -> REJECTED: expired
issued for audience billing-api -> REJECTED: audience 'billing-api' is not orders-api
RESULT: CORRECT (only the genuine, unexpired token for this API is accepted)
```

The last line deserves the most attention: a **genuine** token, valid signature, same issuer, only a different `aud`.
Without an audience check, every token issued for any of your company's APIs works on yours. Spring Security does
**not** check `aud` by default; lesson 7 configures it.

Other classic traps:

- **Algorithm confusion**: a server that accepts both HS256 and RS256 and uses the RSA public key as the HMAC secret;
  the attacker signs HS256 with that public key (which everyone has). Defence: fix the algorithm per key.
- Fetching keys from `jku`/`x5u` in the token header: the attacker points to his own key. Only take keys from the
  configured `jwks_uri`.
- A short, guessable HS256 secret: brute-forced offline from any single token. At least 256 random bits.

Do not hand-write this as the demo does in real code: use Spring Security's `NimbusJwtDecoder`, which performs these
steps and rotates keys from the JWKS.

## 7. RBAC and ABAC

| | RBAC (Role-Based) | ABAC (Attribute-Based) |
|---|---|---|
| Decides based on | The subject's roles: `ADMIN`, `USER` | Attributes of the subject, resource, action, environment |
| Example rule | `ADMIN` may delete orders | May view an order if `order.ownerId == user.id`, or `user.department == order.department` during working hours |
| Pros | Simple, easy to audit | Expresses fine-grained, per-record rules |
| Cons | Role explosion as rules grow complex | Hard to test, hard to answer "who can do what" |
| In Spring | `hasRole('ADMIN')`, `hasAuthority('SCOPE_orders:read')` | `@PreAuthorize("#order.ownerId == authentication.name")`, a custom `AuthorizationManager` |

In practice they are combined: RBAC/scopes for coarse URL-level checks, ABAC for ownership checks at the data level.
Missing the second layer is the IDOR/BOLA bug, OWASP's number-one API vulnerability: an authenticated user changes
`/orders/1001` to `/orders/1002` and reads someone else's order.

With OAuth tokens: `scope` is what **the client has been delegated**, not the user's permissions. The API still has to
check whether the user in the token has rights over **that specific record**.

## 8. Choosing a mechanism

| Situation | Choose |
|-----------|--------|
| Server-rendered web app, one domain | Session cookie (`HttpOnly`, `Secure`, `SameSite`) + CSRF token |
| SPA / mobile, user sign-in | OIDC Authorization Code + PKCE; the API accepts access tokens |
| A service calling a service in your own system | mTLS and/or OAuth client credentials |
| An external partner calling your API | OAuth client credentials, or API key + HMAC signature (lesson 6) |
| You receive webhooks | HMAC signature + timestamp (lesson 6) |
| Internal tools, scripts | Basic auth over TLS, or personal tokens with an expiry |

## 9. Self-check

1. An API returns 401 for a request with a valid token that lacks a scope. Right or wrong?
2. Why does the Base64 in Basic auth not protect the password?
3. Why is PKCE needed even when there is a `client_secret`? And when the client is a mobile app?
4. Your API accepts ID tokens instead of access tokens. What is wrong?
5. Why is HS256 unsuitable when 10 microservices verify tokens issued by one AS?

Short answers: (1) Wrong; missing permissions is 403 (`insufficient_scope`), 401 is for missing/invalid/expired
tokens. (2) Anyone can reverse Base64; only TLS protects it on the wire. (3) PKCE ensures only the session that started
the flow can redeem the `code`; a mobile app cannot keep a secret (anyone can unpack the app), so PKCE is its only
protection. (4) An ID token's `aud` is the client; it tells the client who the user is. The API must check an access
token whose `aud` is the API itself. (5) All 10 services must hold the secret, so any compromised service can **mint**
valid tokens for the whole system; with RS256 the services hold only the public key.

Next: [06 — API security](06-api-security.md).
