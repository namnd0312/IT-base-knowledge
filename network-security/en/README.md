# Network Security for Java backends — how data is protected as it crosses the network

A guide for working Java/Spring developers: not stopping at "I can configure Spring Security", but understanding
**how data is protected as it moves across the network**, **how systems authenticate each other**, and **how to
implement those mechanisms in Java/Spring Boot**.

It has 8 lessons, 9 [plain-Java demos](../demos/) (each with a broken and a fixed mode) and a
[Spring Boot demo](../spring-boot-demo/) with HTTPS, mTLS and a JWT resource server, covered by 8 integration tests over
real HTTPS. The output in the guide comes from those runs.

Verified versions: **JDK 21.0.5**, Spring Boot 4.1.2 / Spring Security 7, OpenSSL 3.6.2, curl 8.7.1 (macOS).

Tiếng Việt: [../vn/README.md](../vn/README.md)

## Reading path

| # | Lesson | Content | Time |
|---|--------|---------|------|
| 1 | [01-networking-fundamentals.md](01-networking-fundamentals.md) | OSI and TCP/IP, where TLS sits, TCP/UDP, the 3-way handshake, DNS, HTTP/HTTPS | 30 min |
| 2 | [02-cryptography.md](02-cryptography.md) | AES-GCM, RSA and ECC, SHA-256, HMAC, digital signatures, Diffie–Hellman | 50 min |
| 3 | [03-tls.md](03-tls.md) | SSL vs TLS, the TLS 1.3 handshake, session keys, cipher suites, TLS 1.2 vs 1.3, one-way TLS vs mTLS | 50 min |
| 4 | [04-pki-and-certificates.md](04-pki-and-certificates.md) | Public/private keys, X.509, CAs, certificate chains, CSRs, CRL/OCSP, keystores/truststores | 50 min |
| 5 | [05-authentication-and-authorization.md](05-authentication-and-authorization.md) | Basic, sessions, OAuth 2.0, OpenID Connect, JWT, RBAC/ABAC | 50 min |
| 6 | [06-api-security.md](06-api-security.md) | API keys, HMAC signing, webhook signatures, replay, MITM, rate limiting, CORS/CSRF | 45 min |
| 7 | [07-implementation-in-java-and-spring-boot.md](07-implementation-in-java-and-spring-boot.md) | `SSLContext`/`SSLSocket`, keystores/truststores, filter chains, HTTPS, mTLS, OAuth2 resource server, JWT validation | 60 min |
| 8 | [08-hands-on-lab-with-demos.md](08-hands-on-lab-with-demos.md) | Lab with 9 demos + Spring Boot: run broken → observe → fix → verify | 90–120 min |

Read 1 → 7 in order: each lesson uses the blocks of the previous one. Comfortable with cryptography already? Start at
lesson 3. Need configuration urgently? Go straight to lesson 7, but come back to lessons 3–4 at your first TLS error.

## Map

```
          What your data passes through, who sees what (01)
                          │
                          ▼
   Cryptographic blocks: AES, RSA/ECC, SHA-256, HMAC, signatures, DH (02)
                          │
          ┌───────────────┴────────────────┐
          ▼                                ▼
  TLS: handshake, session keys,     PKI: X.509, CAs, chains, CSRs,
  cipher suites, mTLS (03) ◀──────▶ CRL/OCSP, keystores/truststores (04)
          │                                │
          └───────────────┬────────────────┘
                          ▼
     Application-level identity: sessions, OAuth2, OIDC, JWT, RBAC/ABAC (05)
                          ▼
     Protecting APIs: API keys, HMAC, webhooks, replay, MITM, rate limits, CORS/CSRF (06)
                          ▼
     Implementation: SSLContext, SSL bundles, filter chains, mTLS, resource server (07)
```

## Demos

| # | Demo | Failure reproduced | Lesson |
|---|------|--------------------|--------|
| 1 | [PlaintextVsTlsEavesdropDemo](../demos/PlaintextVsTlsEavesdropDemo.java) | A device in the middle reads the password on an unencrypted connection | 1, 3 |
| 2 | [IntegrityHashVsHmacDemo](../demos/IntegrityHashVsHmacDemo.java) | A modified packet is accepted because it only carries a SHA-256 | 2 |
| 3 | [EncryptionEcbVsGcmDemo](../demos/EncryptionEcbVsGcmDemo.java) | AES/ECB leaks patterns and allows ciphertext cut-and-paste | 2 |
| 4 | [KeyExchangeMitmDemo](../demos/KeyExchangeMitmDemo.java) | Unauthenticated Diffie–Hellman: the man in the middle reads and rewrites | 2 |
| 5 | [TlsCertificateValidationDemo](../demos/TlsCertificateValidationDemo.java) | A client trusting every certificate sends its token to a fake server | 4 |
| 6 | [MutualTlsDemo](../demos/MutualTlsDemo.java) | One-way TLS + a self-declared header: anyone can pose as an internal service | 3 |
| 7 | [JwtValidationDemo](../demos/JwtValidationDemo.java) | Decode a JWT and trust it: edited role, `alg=none`, expired, wrong audience | 5 |
| 8 | [WebhookSignatureReplayDemo](../demos/WebhookSignatureReplayDemo.java) | A webhook with a correct HMAC is replayed, credited 3 times | 6 |
| 9 | [LoginBruteForceRateLimitDemo](../demos/LoginBruteForceRateLimitDemo.java) | A 4-digit PIN guessed in under 2 seconds | 6 |
| S | [spring-boot-demo](../spring-boot-demo/) | HTTPS + mTLS (`x509()`) + OAuth2 resource server (RS256, `aud` check) | 7 |

[create-demo-pki.sh](../demos/create-demo-pki.sh) builds a small PKI using only `keytool` (a CA → server and client
certificates through CSRs, a truststore, plus two self-signed identities for the attacker).
[DemoTlsSupport.java](../demos/DemoTlsSupport.java) builds `SSLContext`s from them.

## Quick start

```bash
cd network-security
./demos/create-demo-pki.sh
javac -d out demos/*.java

java -cp out MutualTlsDemo broken      # wrong: anyone can claim to be order-service
java -cp out MutualTlsDemo fixed       # correct: only a certificate signed by the internal CA gets in

cd spring-boot-demo && mvn test        # HTTPS, mTLS, JWT over real connections
```

```
mode=fixed server=needClientAuth=true, identity = CN of the verified client certificate
real order-service                   -> 200 orders for caller 'order-service'
rogue, no certificate                -> TLS failure: Received fatal alert: bad_certificate
rogue, self-signed CN=order-service  -> TLS failure: Received fatal alert: bad_certificate
RESULT: CORRECT (only a holder of a CA-signed client key is accepted)
```

`out/` (including the demo keystores and keys) is in [.gitignore](../.gitignore). The demo keystore password
`changeit` only protects loopback-only keys.

## What was run and what is illustrative

| Run; the output is real | Illustrative, not run |
|-------------------------|-----------------------|
| The 9 Java demos, both modes; `javax.net.debug` | `issuer-uri` configuration against a real authorization server |
| The Spring Boot demo: `mvn test` (8 tests) and the curl/openssl commands in lesson 7 | Client-side `RestClientSsl` / SSL bundles, `reload-on-update` |
| `create-demo-pki.sh`, `keytool`, `openssl s_client/x509/req/verify`, example.com's OCSP stapling | Enabling revocation checking in the JDK (`checkRevocation`, `PKIXRevocationChecker`) |
| RSA/ECDSA/Ed25519 measurements, JDK 21's default cipher suites | A load balancer forwarding client certificates in a header |

## Related guides in this repository

- [Spring Security](../../springboot/security/en/README.md): filter chains, form login, CSRF, CORS, JWT and OAuth2 at
  the detailed configuration level. This guide explains the network and cryptography underneath.
- [Java Multithreading](../../Java/multithread/en/README.md): thread pools, timeouts, concurrency limits.
