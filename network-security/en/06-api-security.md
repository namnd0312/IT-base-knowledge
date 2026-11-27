# 06 — API security

Goal: know the mechanisms that protect an API called by other systems over the network: API keys, HMAC request
signing, webhook signature verification, replay protection, man-in-the-middle defences, rate limiting, and CORS/CSRF
for APIs called from browsers. Each mechanism answers a different question; misreading the question means picking the
wrong mechanism.

| Mechanism | Answers |
|-----------|---------|
| TLS | Is anyone reading/modifying on the path, is the server the right one |
| API key | Which **customer/application** this request belongs to |
| HMAC signature | Was this request really created by the secret holder, and **not modified** |
| Timestamp + nonce/ID | Is this request **fresh**, or a replay |
| Rate limit | Is this party calling **too much** |
| CORS | May the browser let JavaScript from **another origin** read the response |
| CSRF token | Was the request sent by **your own** page, not another page riding on the cookie |

## 1. API keys

A secret string identifying the client, sent in a header:

```
GET /v1/orders HTTP/1.1
X-API-Key: sk_live_7Hq2...
```

| Do it right | Why |
|-------------|-----|
| Generate with `SecureRandom`, ≥ 128 bits, with a recognisable prefix (`sk_live_`) | Unguessable; secret scanners spot it when committed by mistake |
| Store a **hash** of the key server-side (SHA-256 suffices since the key is long and random), never the plain key | A database leak does not leak keys |
| Compare in constant time | No leak through response timing |
| Send it in a header, not in the URL | URLs end up in server logs, proxy logs, browser history |
| Give each key a scope (permissions, environment) and its own rate limit | One leaked key does not expose everything |
| Make it rotatable: allow two keys side by side during a switch-over | Changing keys causes no outage |

The inherent limit: an API key is a **bearer secret**. It identifies an application, not a user; whoever holds it can
use it; and it does not protect the request content. For high-value operations, combine it with an HMAC signature
(section 2) or replace it with OAuth client credentials / mTLS (lesson 5).

## 2. HMAC request signing

Instead of sending the secret, the client uses it to **sign** the request; the server recomputes the signature with the
same secret and compares. The secret never travels on the network.

```
string_to_sign = METHOD + "\n" + PATH + "\n" + SORTED_QUERY + "\n" + TIMESTAMP + "\n" + SHA256(BODY)
signature      = HMAC-SHA256(secret, string_to_sign)

POST /v1/payments HTTP/1.1
X-Key-Id: client-42
X-Timestamp: 1795802439
X-Signature: 6f1c0e...
```

What decides the security is **what goes into `string_to_sign`**:

- Whatever is not signed can be changed by someone in the middle (or a buggy proxy) with the signature still valid.
  Sign the method, path, query, body and every business-relevant header.
- Both sides must build **byte-identical** strings (canonicalisation): query order, encoding, JSON whitespace. Sign the
  **raw body bytes**, never an object parsed and serialised again.
- Include a timestamp in the signed string to prevent replay (section 4).

AWS Signature V4 is a mature example of this model. Unlike digital signatures (lesson 2), HMAC uses a shared secret:
the server can create signatures too, so it proves "one of the two parties" and gives no non-repudiation.

## 3. Verifying webhook signatures

Webhooks reverse the relationship: the **provider** (Stripe, GitHub, a payment gateway) calls a public endpoint of
**yours**. Anyone on the Internet can send requests to that URL, so a webhook endpoint without a signature is an
endpoint that lets anyone "report a successful payment".

The provider gives you a secret and signs each request. A common shape (Stripe uses `t=...,v1=...`):

```
X-Timestamp: 1795802439
X-Signature: HMAC-SHA256(secret, timestamp + "." + raw_body)
```

On the receiving side, in this order:

1. Read the **raw body** (bytes), before any framework parses the JSON.
2. Compute the HMAC over `timestamp + "." + raw_body` and compare in constant time with the signature. Wrong → 401.
3. Check that the timestamp is inside the allowed window (typically 5 minutes). Outside → reject.
4. Check that the **event ID has not been processed** (store it in the DB under a unique constraint). Seen before →
   return 2xx but do not process again.
5. Process, return 2xx quickly; move heavy work to a queue. (Providers resend when you time out, one more reason for step 4.)

## 4. Replay attacks

A valid signature proves **who wrote** a request, not that the request is **fresh**. Whoever captures one valid request
(a log, a proxy, a dump, a compromised client machine) and resends it verbatim still has a valid signature.

[WebhookSignatureReplayDemo.java](../demos/WebhookSignatureReplayDemo.java): a payment provider calls
`POST /webhooks/payment` with a signature. The attacker has one genuine request and sends three things: a modified
body, the same request again, and a request captured yesterday.

The `broken` mode signs only the body and remembers nothing:

```
mode=broken signedContent=body
genuine event               -> 200 processed
tampered body, same sig     -> 401 bad signature
replay of the same request  -> 200 processed
captured request, 1 day old -> 200 processed
account credited: 15000 cents (expected 5000)
RESULT: WRONG (replayed requests carry a valid signature and were processed again)
```

HMAC does its job (the modified body is blocked), and the account is still credited three times.

The `fixed` mode: the timestamp is **inside** the signed content (so it cannot be altered), there is a time window, and
each event ID is processed only once.

```java
if (timestamp == null || !matches(sign(timestamp + "." + body), signature)) return 401;      // authentic
if (Math.abs(now - Long.parseLong(timestamp)) > TOLERANCE.toSeconds())    return 408;      // fresh
return processedEventIds.add(field(body, "eventId")) ? 200 : 409;                           // not seen before
```

```
mode=fixed signedContent=timestamp.body
genuine event               -> 200 processed
tampered body, same sig     -> 401 bad signature
replay of the same request  -> 409 event id already processed
captured request, 1 day old -> 408 timestamp outside the 5-minute window
account credited: 5000 cents (expected 5000)
RESULT: CORRECT (one genuine event, credited once)
```

The two layers complement each other: the time window limits **how long** a capture stays usable; the ID/nonce blocks
replays **within** that window (and IDs only need to be remembered for the length of the window). The demo uses
408/409 for readability; a real webhook should answer 2xx for duplicates so the provider stops resending.

Replay is not only a webhook problem: TLS 1.3 0-RTT (lesson 3), stolen JWTs (reusable until `exp`), ID tokens
(`nonce`), money transfers. The general rule: non-idempotent operations need a client-generated **idempotency key**.

## 5. Man-in-the-middle

A MITM is an active attacker on the path: reading, modifying, dropping, injecting. The previous lessons showed each piece:

| The man in the middle gets in by | Stopped by | Demo |
|----------------------------------|------------|------|
| Reading unencrypted traffic | TLS | 1 |
| Modifying content that carries a hash | MAC/HMAC, AEAD | 2, 3 |
| Replacing public keys during key exchange | Signatures on the key (certificates) | 4 |
| Presenting a home-made certificate | The client checks the chain to a trusted CA + the host name | 5 |
| Posing as an internal service | mTLS | 6 |
| Modifying / minting tokens | JWT signatures + a fixed `alg` | 7 |
| Replaying signed requests | Timestamp + nonce/ID | 8 |

The remaining points to keep in mind when designing APIs:

- **Practically every real MITM vulnerability starts on the client side**: an accept-all `TrustManager`, disabled
  host-name checks, a truststore with a foreign CA added. Review the code calling APIs as carefully as server code.
- **Corporate TLS-inspecting proxies** are a legitimate MITM: they have their own CA installed on employee machines.
  Your service running in such a network will see certificates signed by the proxy's CA.
- **Certificate pinning** (the client accepts only one specific public key) is stricter than CA validation, at the risk
  of locking yourself out when certificates rotate. A fit for mobile apps calling their own API; rarely worth it for
  service-to-service (use an internal CA + mTLS instead).
- **SSL stripping** against browsers: keeping the user on HTTP. Stopped by HSTS.

## 6. Rate limiting

An API that answers quickly without limit can be probed (password guessing, ID enumeration, scraping) and brought down.

[LoginBruteForceRateLimitDemo.java](../demos/LoginBruteForceRateLimitDemo.java): a login endpoint protected by a
4-digit PIN.

```
mode=broken pinSpace=10,000 limit=none
attempts=7,352 elapsed=1840 ms responses={200=1, 401=7351}
pin found: 7351
RESULT: WRONG (the whole PIN space can be tried in seconds)
```

After 5 failures the account is locked for 60 seconds, and while locked the PIN is **not compared** (a correct guess
learns nothing):

```
mode=fixed pinSpace=10,000 limit=5 failures then locked 60 s
attempts=10,000 elapsed=2186 ms responses={401=5, 429=9995}
pin found: no
RESULT: CORRECT (only 5 guesses were evaluated, the rest got 429)
```

| Algorithm | How it works | Characteristics |
|-----------|--------------|-----------------|
| Fixed window | Count per minute | Simple; allows a 2× burst at the boundary between windows |
| Sliding window | Count over the last 60 seconds | More accurate, more memory |
| **Token bucket** | A bucket of N tokens refilled at r tokens/second; each request spends 1 | Allows short bursts, holds the average rate. The most common |

What you key the limit on decides who is affected: per API key/client ID (fairness between customers), per user, per
IP (bypassed by botnets; many real users share one IP behind NAT), per targeted account (stops password guessing, but
lets others **deliberately lock** a victim's account). Usually a combination is needed, with increasing delays rather
than a hard lock.

Return `429 Too Many Requests` with `Retry-After`. With several instances the counters must be shared (Redis, or the
API gateway/ingress). In Java this is typically Bucket4j or Resilience4j `RateLimiter`, or left to the gateway.

## 7. CORS and CSRF

These two only matter when a **browser** calls the API. Service-to-service has no CORS or CSRF.

### CORS

The same-origin policy: JavaScript on `https://shop.example` can **send** requests to `https://api.example`, but the
browser **will not let it read** the response unless the API returns headers allowing it:

```
Access-Control-Allow-Origin: https://shop.example
Access-Control-Allow-Credentials: true
```

"Non-simple" requests (methods `PUT`/`DELETE`, an `Authorization` header, `Content-Type: application/json`) are
checked first by the browser with an `OPTIONS` **preflight**.

| Mistake | Consequence |
|---------|-------------|
| Reflecting any incoming `Origin` together with `Allow-Credentials: true` | Any site can read the data of a signed-in user |
| Treating CORS as authentication/request blocking | CORS only decides whether the browser **lets the response be read**; curl, servers and scripts ignore CORS |

Configuration: an explicit list of origins, never `*` with credentials.

### CSRF

Browsers **send cookies automatically** for the target domain, even when the request originates from another page:

```html
<!-- on https://evil.example -->
<form action="https://bank.example/transfer" method="POST">
  <input name="to" value="mallory"><input name="amount" value="5000">
</form>
<script>document.forms[0].submit()</script>
```

If `bank.example` authenticates with a session cookie, this request carries the victim's cookie and is executed.

| The API authenticates with | CSRF risk | Measures |
|----------------------------|-----------|----------|
| Cookies (a session, or a JWT stored in a cookie) | **Yes** | CSRF token (synchronizer token), `SameSite=Lax/Strict`, checking `Origin` |
| `Authorization: Bearer` attached by JavaScript | No: browsers do not attach this header automatically | Disabling CSRF on that chain is reasonable |
| mTLS, API key in a header, HMAC | No (for non-browser clients) | |

That is why the Spring demo in lesson 7 disables CSRF on chains using bearer tokens and client certificates, and why
it must not do so on a chain using sessions. CSRF/CORS configuration in Spring:
[Spring Security lesson 5](../../springboot/security/en/05-csrf-cors-session-and-headers.md).

## 8. API checklist

- [ ] HTTPS only; every outbound client checks the certificate and the host name.
- [ ] Every endpoint is authenticated except those explicitly listed as public; authorisation also at record level (against IDOR).
- [ ] API keys stored as hashes, sent in headers, scoped and rotatable.
- [ ] High-value requests and webhooks carry a signature over the raw body + timestamp; there is a time window and a store of processed IDs.
- [ ] Non-idempotent operations accept an idempotency key.
- [ ] Rate limits per client/user; 429 + `Retry-After`; counters shared across instances.
- [ ] CORS lists origins explicitly; CSRF is on for every cookie-based chain.
- [ ] Tokens, API keys, signatures and sensitive bodies are never logged.

## 9. Self-check

1. The webhook already has a correct HMAC signature. Why is money still credited three times in `broken` mode?
2. Why must the timestamp be inside the signed content, not just a header next to it?
3. The server computes the signature over `objectMapper.writeValueAsString(parsedBody)`. What will go wrong?
4. An API only accepts `Authorization: Bearer`. Does it need CSRF tokens? What if the token moves into a cookie?
5. Does enabling CORS `Access-Control-Allow-Origin: *` make the API "open for everyone to call"?

Short answers: (1) A signature proves who wrote it, not that it is fresh; without a timestamp and a memory of processed
IDs, every resend is valid. (2) If the header is not signed, the replayer changes the timestamp to "now" and passes the
time check. (3) Re-serialising can change field order, whitespace and number formats; genuine signatures fail, or
worse, two different readings of the same body arise. Always sign raw bytes. (4) No, since browsers do not attach that
header automatically; put the token in a cookie and the browser sends it by itself, so CSRF protection is needed again.
(5) No; CORS only decides whether the browser lets JavaScript from another origin read the response; every non-browser
client could call it anyway. The danger lies in combining it with credentials.

Next: [07 — Implementation in Java and Spring Boot](07-implementation-in-java-and-spring-boot.md).
