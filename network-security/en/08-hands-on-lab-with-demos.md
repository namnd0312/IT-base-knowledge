# 08 — Hands-on lab with demos

Goal: run the 9 plain-Java demos and the Spring Boot demo yourself with one routine: **run the broken mode → observe →
explain the cause → read the fix → run the fixed mode to verify**. The full output of each demo is in its lesson; this
lesson is the run order, the questions and the extension exercises.

Time: 90–120 minutes.

## Setup

Requirements: JDK 21+, Maven (only for the Spring Boot demo), `openssl` and `curl` (optional). No root, no network.

```bash
cd network-security
./demos/create-demo-pki.sh          # creates out/pki: CA, server, client, truststore, attacker, rogue-client
javac -d out demos/*.java
```

- Every demo uses `127.0.0.1` only. `out/` (including the demo keys) is in [.gitignore](../.gitignore).
- Every demo takes `broken` or `fixed` and prints a `RESULT:` line at the end. Hex values, byte counts and timings will
  differ on your machine; the `RESULT:` lines will not.
- Rerunning `create-demo-pki.sh` creates a new CA: every old keystore is replaced. The Spring Boot demo and the TLS
  demos read the same directory.

| # | Demo | Lesson | Failure reproduced |
|---|------|--------|--------------------|
| 1 | [PlaintextVsTlsEavesdropDemo](../demos/PlaintextVsTlsEavesdropDemo.java) | 1, 3 | A device in the middle reads the password over plain TCP |
| 2 | [IntegrityHashVsHmacDemo](../demos/IntegrityHashVsHmacDemo.java) | 2 | A modified packet is accepted because it only carries a SHA-256 |
| 3 | [EncryptionEcbVsGcmDemo](../demos/EncryptionEcbVsGcmDemo.java) | 2 | AES/ECB leaks patterns, allows ciphertext cut-and-paste |
| 4 | [KeyExchangeMitmDemo](../demos/KeyExchangeMitmDemo.java) | 2 | Unauthenticated Diffie–Hellman: the man in the middle reads and rewrites |
| 5 | [TlsCertificateValidationDemo](../demos/TlsCertificateValidationDemo.java) | 4 | An accept-all TrustManager sends the token to a fake server |
| 6 | [MutualTlsDemo](../demos/MutualTlsDemo.java) | 3 | Service identity taken from a self-declared header |
| 7 | [JwtValidationDemo](../demos/JwtValidationDemo.java) | 5 | Decode a JWT and trust it: edited role, `alg=none`, expired, wrong `aud` |
| 8 | [WebhookSignatureReplayDemo](../demos/WebhookSignatureReplayDemo.java) | 6 | A webhook with a correct HMAC is replayed, credited 3 times |
| 9 | [LoginBruteForceRateLimitDemo](../demos/LoginBruteForceRateLimitDemo.java) | 6 | A 4-digit PIN guessed in under 2 seconds |
| S | [spring-boot-demo](../spring-boot-demo/) | 7 | HTTPS, mTLS (`x509()`), JWT resource server, an mTLS client |

---

## Lab 1 — Plain text and TLS

```bash
java -cp out PlaintextVsTlsEavesdropDemo broken
java -cp out PlaintextVsTlsEavesdropDemo fixed
java -Djavax.net.debug=ssl:handshake -cp out PlaintextVsTlsEavesdropDemo fixed 2>&1 | grep -E "Produced|Consuming"
```

**Observe.** `broken`: 38 bytes, the password is readable. `fixed`: 692 bytes of noise, `TLSv1.3 TLS_AES_256_GCM_SHA384`.
The third command prints the handshake sequence from lesson 3.

**Read the code.** In `main`, only the line creating the `ServerSocket` and the line creating the client socket differ
between the modes.

**Question.** What does the relay still know about the TLS connection? (Both ends, sizes, timing. And the domain
through SNI if the client sends it.)

## Lab 2 — Hash and HMAC

```bash
java -cp out IntegrityHashVsHmacDemo broken
java -cp out IntegrityHashVsHmacDemo fixed
```

**Cause.** SHA-256 has no key: whoever modifies the content recomputes it. HMAC needs the secret.

**Question.** Does HMAC stop the same valid packet being resent? (No. That is Lab 8.)

## Lab 3 — ECB and GCM

```bash
java -cp out EncryptionEcbVsGcmDemo broken
java -cp out EncryptionEcbVsGcmDemo fixed
```

**Observe.** ECB: two identical ciphertext blocks, and copying block 3 over block 1 decrypts to a different order
**without an error**. GCM: flipping one bit gives `AEADBadTagException: Tag mismatch`.

**Exercise.** In `encryptGcm`, replace the random nonce with 12 zero bytes. How does the "same plaintext encrypted
twice" line change, and why is that a serious bug with GCM?

## Lab 4 — Key exchange and the man in the middle

```bash
java -cp out KeyExchangeMitmDemo broken
java -cp out KeyExchangeMitmDemo fixed
```

**Observe.** `alice and bob share a key: false`, yet both encrypt with AES-GCM and valid tags, and Mallory changes the
account number. `fixed`: an Ed25519 signature on the one-time key exposes the substitution.

**Question.** In TLS, what plays the part of "the identity key Alice already had"? (The server certificate, trusted
through a CA in the truststore. That is Lab 5.)

## Lab 5 — Certificate validation

```bash
java -cp out TlsCertificateValidationDemo broken
java -cp out TlsCertificateValidationDemo fixed
```

**Observe.** `broken`: genuine TLS 1.3, the token reaches `O=Attacker`. `fixed`: `PKIX path building failed` for the
impostor, the real server still works.

**Exercises.**

1. In `call`, pass `false` to `DemoTlsSupport.connect` (host-name checking off), then create a certificate signed by
   the demo CA for `CN=other-service` (edit `create-demo-pki.sh`, no `localhost` SAN). Does a server using it pass the
   handshake? And with host-name checking back on?
2. Search your codebase for: `X509TrustManager`, `HostnameVerifier`, `setEndpointIdentificationAlgorithm`,
   `InsecureSkipVerify`, `verify=False`, `curl -k`, `--insecure`. Every hit needs an explanation.

## Lab 6 — One-way TLS and mTLS

```bash
java -cp out MutualTlsDemo broken
java -cp out MutualTlsDemo fixed
```

**Observe.** `broken`: the channel is encrypted, and the rogue is still served because the header is trusted. `fixed`:
the rogue without a certificate and the self-signed rogue with **the right CN** both get `bad_certificate`.

**Question.** Why is a self-signed certificate with `CN=order-service` still rejected? (What is checked is the
signature of a CA in the truststore, not the name.)

**Exercise.** Add a client certificate signed by the demo CA for `CN=reporting-service` to `create-demo-pki.sh`. Does
the handshake succeed, and what does the request return? (It succeeds; 403 because the allow-list only has `order-service`.)

## Lab 7 — JWT validation

```bash
java -cp out JwtValidationDemo broken
java -cp out JwtValidationDemo fixed
```

**Observe.** `broken` accepts 4 bad tokens. `fixed` rejects each with its own reason: signature, algorithm, expiry, audience.

**Exercise.** Paste the valid token (print it in `main`) into any JWT decoder. What can you read without a key? What
does that tell you about putting data in the payload?

## Lab 8 — Webhook signatures and replay

```bash
java -cp out WebhookSignatureReplayDemo broken
java -cp out WebhookSignatureReplayDemo fixed
```

**Observe.** `broken`: the modified body is blocked (401), but the replay and the day-old capture are both processed:
15000 instead of 5000. `fixed`: 409 for the duplicate ID, 408 outside the 5-minute window.

**Exercises.**

1. Remove the event ID check, keep only the time window. Does an immediate replay get through?
2. Keep the signature over the body only, but still check the `X-Timestamp` header. What if the replayer sets the
   header to the current time?

## Lab 9 — Rate limiting

```bash
java -cp out LoginBruteForceRateLimitDemo broken
java -cp out LoginBruteForceRateLimitDemo fixed
```

**Observe.** The line `responses={200=1, 401=7351}` is exactly what your logs would record.

**Question.** Which attack does the fix open up? (Guessing wrong on purpose to lock other people's accounts. Lesson 6, section 6.)

## Lab S — Spring Boot: HTTPS, mTLS, JWT

```bash
cd spring-boot-demo
mvn test
mvn spring-boot:run          # in another terminal
```

```bash
cd ../out/pki
openssl pkcs12 -in client.p12 -passin pass:changeit -nokeys -out client.crt.pem
openssl pkcs12 -in client.p12 -passin pass:changeit -nocerts -nodes -out client.key.pem
U=https://localhost:8443

curl $U/internal/inventory                                                        # 1. does not trust the server's CA
curl --cacert ca.crt $U/internal/inventory                                        # 2. no client certificate → 403
curl --cacert ca.crt --cert client.crt.pem --key client.key.pem $U/internal/inventory   # 3. mTLS → 200

T=$(curl -s --cacert ca.crt -X POST "$U/dev/token?subject=alice" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl --cacert ca.crt -H "Authorization: Bearer $T" $U/api/orders                  # 4. JWT → 200
curl --cacert ca.crt -i -H "Authorization: Bearer $T" $U/api/admin/orders         # 5. missing scope → 403

TB=$(curl -s --cacert ca.crt -X POST "$U/dev/token?subject=alice&audience=billing-api" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl --cacert ca.crt -i -H "Authorization: Bearer $TB" $U/api/orders              # 6. wrong aud → 401

openssl s_client -connect localhost:8443 -CAfile ca.crt </dev/null                # 7. look at the CertificateRequest
```

Expected results and explanations: lesson 7, sections 4–6.

**Exercises.**

1. Change `client-auth: want` to `need`. How do steps 2 and 4 change? Why?
2. Remove `audienceValidator` from `jwtDecoder`. What does step 6 return? Which test in `mvn test` catches the change?
3. Add `-Djavax.net.debug=ssl:handshake` when running the app (`mvn spring-boot:run -Dspring-boot.run.jvmArguments=...`)
   and call step 3. Find the lines where the server receives the client's `Certificate` and `CertificateVerify`.

## Summary

| Lab | One sentence to remember |
|-----|--------------------------|
| 1 | Everything on the path can read what is not encrypted |
| 2 | An unkeyed hash does not stop someone who modifies packets |
| 3 | "Encrypted" does not mean "cannot be modified"; use AEAD |
| 4 | Encryption without authentication is a private talk with a stranger |
| 5 | Disabling certificate validation disables TLS |
| 6 | Identity must be proven cryptographically, never self-declared |
| 7 | Decoding is not validating; check `alg`, signature, `exp`, `iss`, `aud` |
| 8 | A signature proves who wrote a request, not that it is fresh |
| 9 | An endpoint that answers quickly without limit is a guessable endpoint |
| S | JSSE handles the handshake and certificates; Spring Security turns the result into identity and permissions |

Back to: [README](README.md)
