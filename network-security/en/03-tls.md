# 03 — TLS

Goal: understand how TLS assembles the cryptographic blocks of lesson 2 into a secure channel: how the handshake goes,
where session keys come from, what a cipher suite means, how TLS 1.2 differs from 1.3, and how one-way TLS differs
from mTLS. Enough to read `javax.net.debug` logs and `openssl s_client` output, and to decide a Java server's configuration.

## 1. SSL and TLS

| Version | Year | Status |
|---------|------|--------|
| SSL 2.0, SSL 3.0 | 1995, 1996 | Broken (POODLE for SSL 3.0). Forbidden |
| TLS 1.0, TLS 1.1 | 1999, 2006 | Deprecated (RFC 8996, 2021). Disabled by default in the JDK |
| **TLS 1.2** | 2008 | Still widely used; secure **if** only good cipher suites are enabled |
| **TLS 1.3** | 2018 | Today's default; removes the weak options altogether |

TLS is SSL's new name after the IETF took it over. "SSL certificate", `SSLContext`, `SSLSocket`, `server.ssl.*` are
leftover names; underneath it is all TLS. JDK 21 enables exactly two versions by default:

```
enabled protocols: [TLSv1.3, TLSv1.2]
```

## 2. What TLS guarantees

| Guarantee | Provided by | Without it |
|-----------|-------------|------------|
| Confidentiality | AEAD encryption (AES-GCM, ChaCha20-Poly1305) with session keys | A device in the middle reads tokens and passwords |
| Integrity | The AEAD tag + record sequence numbers | Records modified, injected, replayed, reordered |
| Server authentication | X.509 certificate + `CertificateVerify` signature | A private conversation with an impostor |
| Client authentication (optional) | mTLS: the client presents a certificate too | The server does not know which service is calling |

Demo 1 in `fixed` mode: the same relay in the middle now sees only ciphertext.

```bash
java -cp out PlaintextVsTlsEavesdropDemo fixed
```

```
mode=fixed transport=TLS
negotiated:       TLSv1.3 TLS_AES_256_GCM_SHA384
client sent:      LOGIN user=alice password=S3cret-Pa55
server replied:   OK welcome alice
eavesdropper saw: 692 bytes: .............b..\.q 4.>.m............[\..;$ .UHP.T..m.2....(2..+0....Tp`V,...J.......,.+...0.../...
password visible on the wire: false
RESULT: CORRECT (the path only carries ciphertext)
```

The code that sends and receives data does not change by a single line; only socket creation does. TLS is
transparent to the application, which is precisely why it is easy to misconfigure without anyone noticing.

## 3. The TLS 1.3 handshake

```
Client                                                         Server
  │ ── ClientHello ─────────────────────────────────────────────▶ │
  │    • versions, supported cipher suites                         │
  │    • key_share: one-time ECDHE public key (X25519...)          │
  │    • SNI: the domain wanted (in clear)                         │
  │                                                                │
  │ ◀───────────────────────────────────────────── ServerHello ── │
  │                       chosen cipher suite, the server's key_share
  │      ── both compute the shared secret → derive handshake keys ──
  │ ═══════════════ everything from here on is encrypted ═══════   │
  │ ◀── EncryptedExtensions                                        │
  │ ◀── CertificateRequest            (mTLS only)                  │
  │ ◀── Certificate                   the server's chain           │
  │ ◀── CertificateVerify             signature over the handshake │
  │ ◀── Finished                      MAC over the handshake       │
  │                                                                │
  │ ── Certificate + CertificateVerify (mTLS only) ─────────────▶ │
  │ ── Finished ────────────────────────────────────────────────▶ │
  │ ◀════════════ application data (application traffic keys) ══▶ │
  │ ◀── NewSessionTicket (for resuming next time)                  │
```

Mapped to lesson 2:

1. **Key exchange**: the two `key_share`s are a one-time Diffie–Hellman (ECDHE). An eavesdropper sees both and cannot
   compute the shared secret. The keys are used once and deleted, giving **forward secrecy**.
2. **Authentication**: the server sends its certificate (containing its long-term public key) and `CertificateVerify`,
   a signature with the matching private key over every handshake message exchanged so far. If someone in the middle
   swaps a `key_share`, the signature no longer matches. This is exactly the `fixed` mode of
   [KeyExchangeMitmDemo](../demos/KeyExchangeMitmDemo.java).
3. **Finished**: a MAC over the whole handshake, preventing downgrades and modified messages.

Turn on the JDK's logging to see that very sequence (client and server run in one JVM, so lines interleave):

```bash
java -Djavax.net.debug=ssl:handshake -cp out PlaintextVsTlsEavesdropDemo fixed
```

```
Produced ClientHello handshake message
Consuming ClientHello handshake message
Produced ServerHello handshake message
Consuming ServerHello handshake message
Produced EncryptedExtensions message
Produced server Certificate message
Produced server CertificateVerify handshake message
Consuming server Certificate handshake message
Produced server Finished handshake message
Consuming CertificateVerify handshake message
Consuming server Finished handshake message
Produced client Finished handshake message
Consuming client Finished handshake message
Produced NewSessionTicket stateless post
Consuming NewSessionTicket message
```

`-Djavax.net.debug=ssl:handshake` (or `all`) is the number-one tool when debugging TLS failures in Java: it shows the
version, the cipher suite, the certificates each side sends, and which message made the handshake fail.

## 4. Session keys

"Session keys" are the symmetric keys that actually encrypt the data. Neither side sends them over the network: both
**derive** them.

```
ECDHE(my private key, the other side's key_share)  ──▶  shared secret
                                     │
                     HKDF (the TLS 1.3 key schedule), mixed with a hash of the handshake messages
                                     │
          ┌──────────────────────────┼──────────────────────────────┐
          ▼                          ▼                              ▼
 handshake traffic keys     application traffic keys        resumption secret
 (encrypt Certificate...)   client→server, server→client    (for NewSessionTicket)
                            one key + IV per direction
```

- The two directions use different keys; each record's nonce derives from its sequence number, so a nonce never
  repeats (a must for AES-GCM, lesson 2).
- Keys can be refreshed (`KeyUpdate`) on long-lived connections.
- **Session resumption**: the server sends a `NewSessionTicket`; on the next connection the client presents the ticket
  (a PSK) to skip certificate authentication. It saves asymmetric work and still mixes in a fresh ECDHE, preserving
  forward secrecy.
- TLS 1.3 **0-RTT** (early data) lets data be sent in the very first message when resuming, at the price that this
  data **can be replayed**. Only fit for idempotent requests; the JDK does not enable 0-RTT.

Operational consequence: forward secrecy means that even if the certificate's private key leaks later, recorded traffic
still cannot be decrypted. Conversely, in TLS 1.2 with "RSA key exchange" (the client encrypts the secret with the
server's RSA public key), a leaked private key decrypts **every** recorded past session.

## 5. Cipher suites

A cipher suite is the algorithm combination both sides agree on. Its name reads part by part.

TLS 1.2:

```
TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
    │     │          │           │
    │     │          │           └ hash function for the PRF / handshake HMAC
    │     │          └ data encryption: AES-256 in GCM mode (AEAD)
    │     └ server authentication: an ECDSA certificate
    └ key exchange: ECDHE (forward secrecy)
```

TLS 1.3 moves key exchange (the `key_share` group) and the signature (the signature algorithm) out of the suite name,
leaving only AEAD + hash:

```
TLS_AES_256_GCM_SHA384      TLS_AES_128_GCM_SHA256      TLS_CHACHA20_POLY1305_SHA256
```

What JDK 21 enables by default (the first 9 of 37 suites, in preference order):

```
enabled cipher suites: 37
  TLS_AES_256_GCM_SHA384
  TLS_AES_128_GCM_SHA256
  TLS_CHACHA20_POLY1305_SHA256
  TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
  TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256
  TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256
  TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384
  TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256
  TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256
named groups: [x25519, secp256r1, secp384r1, secp521r1, x448, ffdhe2048, ffdhe3072, ffdhe4096,...
```

The rule when you must choose TLS 1.2 suites yourself: keep only **ECDHE** (forward secrecy) + **AEAD** (GCM or
ChaCha20-Poly1305). Drop every suite with `TLS_RSA_WITH_` (no forward secrecy), `CBC`, `3DES`, `RC4`, `NULL`, `EXPORT`,
`anon`. The `ECDSA`/`RSA` part of the name must match the server certificate's key type: an EC certificate can only
use `ECDHE_ECDSA` suites.

In the real world, the same `example.com` server forced to each version:

```
## TLS 1.2
Peer signature type: ecdsa_secp256r1_sha256
New, TLSv1.2, Cipher is ECDHE-ECDSA-CHACHA20-POLY1305
## TLS 1.3
Peer signature type: ecdsa_secp256r1_sha256
Negotiated TLS1.3 group: X25519MLKEM768
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
```

(OpenSSL uses short names for TLS 1.2 suites. `X25519MLKEM768` is the hybrid classical + post-quantum key exchange, lesson 2.)

## 6. TLS 1.2 versus TLS 1.3

| | TLS 1.2 | TLS 1.3 |
|---|---|---|
| RTTs before data can be sent | 2 | 1 (0 when resuming with 0-RTT) |
| Key exchange | RSA or (EC)DHE | (EC)DHE only: forward secrecy always |
| Encryption | Still allows CBC, RC4, 3DES... | AEAD only |
| Certificate in the handshake | Sent in clear | Encrypted |
| Number of cipher suites | Hundreds of combinations | 5 |
| Renegotiation | Yes (the source of many bugs) | Removed; replaced by `KeyUpdate` and post-handshake auth |
| Downgrade protection | Depends on configuration | Built into the protocol |

Recommendation: enable both 1.3 and 1.2 (for older clients), prefer 1.3, and for 1.2 keep only the suites from
section 5. Turn 1.0/1.1 off entirely.

## 7. One-way TLS and mTLS

| | One-way TLS (ordinary HTTPS) | Mutual TLS (mTLS) |
|---|---|---|
| Who presents a certificate | The server only | The server **and** the client |
| What the server knows about the client | Only its IP; identity has to come from the application layer (password, token) | An identity proven cryptographically during the handshake |
| The client needs | A truststore (to trust the server) | Truststore + keystore (its own private key + certificate) |
| The server needs | A keystore | Keystore + truststore (the clients' CA) |
| Used for | Browsers, mobile apps calling public APIs | Service-to-service, partner APIs, IoT devices, service meshes |

Mechanism: the server additionally sends `CertificateRequest` with **the list of CAs it accepts**; the client answers
with `Certificate` + `CertificateVerify` (a signature with the client's private key), proving it holds the private key
and has not merely copied a certificate.

### See it: a self-declared header versus a certificate

[MutualTlsDemo.java](../demos/MutualTlsDemo.java): an internal endpoint may only serve `order-service`. The `broken`
mode uses one-way TLS and takes the caller's identity from an `X-Service-Name` header, a practice more common than
people think.

```
mode=broken server=one-way TLS, identity = X-Service-Name header
real order-service                   -> 200 orders for caller 'order-service'
rogue, no certificate                -> 200 orders for caller 'order-service'
RESULT: WRONG (anyone who can reach the port can claim to be order-service)
```

The channel is encrypted, the server was authenticated, and the server still does not know who is calling. A header
is data the client writes itself.

The `fixed` mode: `setNeedClientAuth(true)`, a truststore holding the internal CA, and the identity taken from the CN
of the **verified** certificate.

```java
SSLServerSocket server = (SSLServerSocket) DemoTlsSupport.context("server", "truststore")   // keystore + truststore
        .getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
server.setNeedClientAuth(true);
...
X509Certificate peer = (X509Certificate) socket.getSession().getPeerCertificates()[0];
String caller = commonName(peer);
```

```
mode=fixed server=needClientAuth=true, identity = CN of the verified client certificate
real order-service                   -> 200 orders for caller 'order-service'
rogue, no certificate                -> TLS failure: Received fatal alert: bad_certificate
rogue, self-signed CN=order-service  -> TLS failure: Received fatal alert: bad_certificate
RESULT: CORRECT (only a holder of a CA-signed client key is accepted)
```

The third line matters: a self-signed certificate with **exactly** `CN=order-service` is still rejected. What is
checked is not the name but that a CA in the truststore signed the certificate. The Spring Boot equivalent is in lesson 7.

Three operational points about mTLS:

- **Who issues client certificates**: you need an internal CA and a process to issue, rotate (short-lived
  certificates) and revoke them (lesson 4). Service meshes (Istio, Linkerd) automate this.
- **A valid certificate is not permission**: every service with a certificate from the CA passes the handshake. You
  still have to authorise by identity (an allow-list of CN/SAN values), as the demo does.
- **Where TLS terminates**: if a load balancer decrypts TLS and forwards plain HTTP inwards, the application no longer
  sees the client certificate. Either the load balancer does mTLS and forwards the identity in a header (then you must
  ensure only the load balancer can send that header), or you use TLS passthrough.

## 8. TLS checklist

- [ ] Only TLS 1.3 and 1.2; for 1.2 only ECDHE + AEAD.
- [ ] Certificates have the right names (SAN), are served with their intermediates, and renew automatically (lesson 4).
- [ ] Clients always check the certificate **and** the host name; no accept-all `TrustManager` (demo 5).
- [ ] Internal traffic uses TLS too; consider mTLS for service-to-service.
- [ ] You know where TLS terminates (load balancer, ingress, sidecar) and how the segment behind it is protected.
- [ ] HTTP redirects to HTTPS, HSTS is on for web front ends.

## 9. Self-check

1. Neither side sends the session key over the network. How do they get it?
2. Why is `TLS_RSA_WITH_AES_256_GCM_SHA384` dropped although it uses AES-GCM?
3. An attacker replaces the server's `key_share` in the ServerHello. Which step detects it?
4. With mTLS, the server receives a valid client certificate signed by the internal CA for `CN=reporting-service`.
   Should the request be served on `order-service`'s endpoint?

Short answers: (1) Each side combines its own ECDHE private key with the other's `key_share` into the same shared
secret, then derives the keys with HKDF. (2) RSA key exchange: no forward secrecy; a leaked server private key decrypts
every recorded session. (3) `CertificateVerify`: the server's signature over the handshake no longer matches what the
client saw (and `Finished` fails too). (4) No; the handshake only proves identity, authorisation must still check
whether `reporting-service` is on that endpoint's allow-list.

Next: [04 — PKI and certificates](04-pki-and-certificates.md).
