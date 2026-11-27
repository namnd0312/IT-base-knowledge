# 04 — PKI and certificates

Goal: understand how the signature in the TLS handshake gets tied to an identity: public/private keys, X.509
certificates, CAs, certificate chains, CSRs, revocation (CRL/OCSP), and how Java organises all of it through keystores
and truststores. By the end you can build a small internal PKI yourself and read a `PKIX path building failed` error.

## 1. The problem PKI solves

Lesson 2 ended with a question: the `CertificateVerify` signature proves "the other side holds the private key for
public key K", but how do you know **K really belongs to `api.bank.com`**? Without an answer, someone in the middle
only needs to create a key pair of their own (demo 4, `broken` mode).

PKI (Public Key Infrastructure) answers with a third party trusted in advance: a CA signs the statement "K belongs to
`api.bank.com`". That signed statement is a **certificate**.

## 2. Public and private keys

| | Private key | Public key |
|---|---|---|
| Held by | The owner only (server, service) | Anyone; it sits in the certificate |
| Used to | Sign (`CertificateVerify`, RS256 JWTs), decrypt | Verify signatures, encrypt |
| If it leaks | Others impersonate you until the certificate expires or is revoked | Nothing: it is public anyway |
| Stored in | Password-protected keystores, HSMs, KMS, Kubernetes secrets | Certificates, truststores, JWKS |

The non-negotiable rule: a private key **does not leave** the place it was generated unless it must; it is not in
git, not in a Docker image, not sent over chat. That is precisely why the standard process is a CSR (section 6): the CA
never needs to see your private key.

## 3. X.509 certificates

The fields of a real certificate (`example.com`):

```bash
openssl s_client -connect example.com:443 -servername example.com -showcerts </dev/null   # fetch the certificate
openssl x509 -in leaf.pem -noout -text
```

```
        Version: 3 (0x2)
        Signature Algorithm: ecdsa-with-SHA256
        Issuer: C=US, O=SSL Corporation, CN=Cloudflare TLS Issuing ECC CA 3
            Not Before: Nov 24 23:48:34 2026 GMT
            Not After : Feb 22 23:53:08 2027 GMT
        Subject: CN=example.com
            Public Key Algorithm: id-ecPublicKey
                Public-Key: (256 bit)
            X509v3 Basic Constraints: critical
                CA:FALSE
                CA Issuers - URI:http://i.cf-i.ssl.com/Cloudflare-TLS-I-E3.cer
                OCSP - URI:http://o.cf-i.ssl.com
            X509v3 Subject Alternative Name:
                DNS:example.com, DNS:*.example.com
            X509v3 Extended Key Usage:
                TLS Web Server Authentication
            X509v3 CRL Distribution Points:
                  URI:http://c.cf-i.ssl.com/ae801ed1c55bb579d79208b0d772acfb8cc3a208.crl
            X509v3 Key Usage: critical
                Digital Signature
```

| Field | Meaning | Who checks / uses it |
|-------|---------|----------------------|
| Subject | The subject (`CN=...`) | Informational; with mTLS the CN often serves as the service identity |
| **Subject Alternative Name (SAN)** | The names/IPs the certificate is valid for | The client compares it with the name it connects to. **Browsers and the JDK check the SAN, not the CN** |
| Issuer | The CA that signed it | Chain building |
| Not Before / Not After | Validity | Expired = rejected. Public certificates are now at most 398 days and getting shorter; this one is 90 days |
| Public Key | The subject's public key | Verifying `CertificateVerify` |
| Basic Constraints `CA:FALSE/TRUE` | May it sign other certificates | Stops a server certificate from acting as a CA |
| Key Usage / Extended Key Usage | What it may be used for: `serverAuth`, `clientAuth` | A `serverAuth`-only certificate cannot serve as an mTLS client certificate |
| CRL Distribution Points, OCSP URI | Where to check revocation | Section 7 |
| Signature | The issuer's signature over everything above | Proof that the CA vouched for it |

File formats: **PEM** (Base64 between `-----BEGIN CERTIFICATE-----` lines), **DER** (binary), **PKCS#12**
(`.p12`/`.pfx`: private key + certificate chain, password-protected), **JKS** (Java's legacy format).

## 4. Certificate Authorities

| Kind of CA | Trusted because | Used for |
|------------|-----------------|----------|
| Public CA (Let's Encrypt, DigiCert, Sectigo...) | Its root certificate ships with the OS, browsers and the JDK | Public Internet endpoints |
| Internal CA (home-built, Vault PKI, AWS Private CA, cert-manager, a mesh CA) | You deliberately add its root to the truststore | Service-to-service, mTLS, internal environments |
| Self-signed (no CA) | You trust that very certificate | Dev, tests; hard to rotate, so avoid in production |

A root CA almost never signs server certificates directly. Its private key is kept offline; day-to-day signing is done
by **intermediate CAs**. If an intermediate key leaks, the intermediate is revoked instead of replacing the root on
billions of devices.

The JDK ships a truststore of public CAs:

```bash
keytool -list -cacerts -storepass changeit | grep -c trustedCertEntry
```

```
112
```

## 5. Certificate chains

```bash
openssl s_client -connect example.com:443 -servername example.com </dev/null
```

```
Certificate chain
 0 s:CN=example.com
   i:C=US, O=SSL Corporation, CN=Cloudflare TLS Issuing ECC CA 3
 1 s:C=US, O=SSL Corporation, CN=Cloudflare TLS Issuing ECC CA 3
   i:C=US, O=SSL Corporation, CN=SSL.com TLS Transit ECC CA R2
 2 s:C=US, O=SSL Corporation, CN=SSL.com TLS Transit ECC CA R2
   i:C=US, O=SSL Corporation, CN=SSL.com TLS ECC Root CA 2022
 ...
Verification: OK
```

```
Root CA                 ← in the client's truststore (the server need not send it)
   │ signs
Intermediate CA         ← the server MUST send it
   │ signs
Leaf: CN=example.com    ← the server sends it
```

The client builds a path (certification path) from the leaf up to a certificate it already trusts, and checks **every
link**: signature, validity, `CA:TRUE` on the CAs, key usage, revocation, then the leaf's SAN. Java calls this process
PKIX; when no path can be found you get:

```
PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
unable to find valid certification path to requested target
```

The three usual causes, most common first:

1. The client's truststore lacks the CA that signed the server certificate (an internal CA, a corporate proxy that
   decrypts TLS).
2. The server does not send the intermediate CA (browsers sometimes fetch it themselves, hence "it works in the
   browser but not in Java").
3. Someone is in the middle. Which is why this error must not be "fixed" by disabling validation (section 9).

## 6. CSR: requesting a certificate without handing over the private key

```
 Service                                       CA
   │ 1. generate a key pair (the private key stays here)
   │ 2. create a CSR = {subject, public key}     │
   │    sign the CSR with the private key (proof of possession)
   │ ── CSR ─────────────────────────────────▶ │ 3. check control of the domain/identity
   │ ◀────────────────────────── certificate ─ │ 4. sign the certificate with the CA's private key
   │ 5. combine the certificate (+ chain) with the private key in the keystore
```

[create-demo-pki.sh](../demos/create-demo-pki.sh) follows exactly that process using only the JDK's `keytool`:

```bash
keytool -genkeypair -alias server -keyalg EC -groupname secp256r1 -dname "CN=localhost,O=Demo Server" -keystore server.p12 ...
keytool -certreq   -alias server -file server.csr -keystore server.p12 ...                       # create the CSR
keytool -gencert   -alias ca -infile server.csr -outfile server.crt \
        -ext eku=serverAuth -ext san=dns:localhost,ip:127.0.0.1 -keystore ca.p12 ...             # the CA signs
keytool -importcert -alias ca     -file ca.crt     -keystore server.p12 ...                      # the issuer first
keytool -importcert -alias server -file server.crt -keystore server.p12 ...                      # then the signed reply
```

```bash
openssl req -in server.csr -noout -verify -subject
```

```
Certificate request self-signature verify OK
subject=O=Demo Server, CN=localhost
```

The CSR contains the public key and is self-signed with the private key; the CA verifies that signature to be sure
the sender holds the private key. With public CAs, step 3 is ACME (Let's Encrypt): proving control of the domain with
an HTTP file or a DNS record, fully automated; in Kubernetes usually done by cert-manager.

The demo PKI after running the script:

```
Demo Root CA ──signs──▶ server  (CN=localhost, EKU serverAuth, SAN localhost/127.0.0.1)
             └─signs──▶ client  (CN=order-service, EKU clientAuth)
attacker, rogue-client: self-signed, not signed by the CA
```

```bash
openssl verify -CAfile ca.crt server.crt client.crt attacker.crt
```

```
server.crt: OK
client.crt: OK
O=Attacker, CN=localhost
error 18 at 0 depth lookup: self-signed certificate
error attacker.crt: verification failed
```

## 7. Revocation: CRL and OCSP

A certificate may have to be invalidated before it expires: a leaked private key, a decommissioned service, a
mis-issuance.

| Mechanism | How it works | Drawback |
|-----------|--------------|----------|
| **CRL** (Certificate Revocation List) | The CA periodically publishes a signed list of revoked serials; clients fetch it from `CRL Distribution Points` | Grows over time; slow to update (hours to days) |
| **OCSP** | The client asks the OCSP responder "is serial X still valid" and gets a signed answer | An extra network round trip; the responder learns which sites you visit; when it is down clients usually "fail open" |
| **OCSP stapling** | The **server** fetches the OCSP answer itself and attaches it to the handshake | Removes both drawbacks; needs server support |
| **Short-lived certificates** | Valid for days or hours; on a key leak you simply stop renewing | Requires automated issuance |

`example.com` staples:

```bash
openssl s_client -connect example.com:443 -servername example.com -status </dev/null
```

```
    OCSP Response Status: successful (0x0)
    Cert Status: good
```

What a Java developer needs to know: the JDK's default TrustManager **does not check revocation** for TLS unless you
turn it on (`-Dcom.sun.net.ssl.checkRevocation=true` together with OCSP/CRL settings, or a configured
`PKIXRevocationChecker`). With an internal PKI, the more common and more reliable approach is short-lived certificates
rotated automatically. This revocation configuration was not run while writing the guide.

## 8. Keystores and truststores

Two files, two different questions. Confusing them is the source of most TLS configuration mistakes in Java.

| | Keystore | Truststore |
|---|---|---|
| Answers | "Who am I?" | "Whom do I trust?" |
| Contains | **My own** private key + certificate chain (`PrivateKeyEntry`) | The **other side's** CA certificates (`trustedCertEntry`) |
| Secret? | **Yes**: it holds the private key | Holds no secret, but must be tamper-proof (adding a foreign CA = trusting strangers) |
| Java API | `KeyManagerFactory` → `KeyManager` | `TrustManagerFactory` → `TrustManager` |
| System properties | `javax.net.ssl.keyStore`, `...keyStorePassword` | `javax.net.ssl.trustStore`, `...trustStorePassword` |
| JDK default | None | `$JAVA_HOME/lib/security/cacerts` (112 public CAs) |

Who needs what:

| Role | One-way TLS | mTLS |
|------|-------------|------|
| Server | Keystore | Keystore + truststore (the clients' CA) |
| Client | Truststore (usually `cacerts`) | Truststore + keystore (the client certificate) |

The demo server's keystore:

```bash
keytool -list -v -keystore out/pki/server.p12 -storepass changeit
```

```
Alias name: server
Entry type: PrivateKeyEntry
Certificate chain length: 2
Certificate[1]:
Owner: CN=localhost, O=Demo Server
Issuer: CN=Demo Root CA, O=Demo
  serverAuth
  DNSName: localhost
  IPAddress: 127.0.0.1
Certificate[2]:
Owner: CN=Demo Root CA, O=Demo
Issuer: CN=Demo Root CA, O=Demo
  CA:true
```

And the truststore:

```bash
keytool -list -keystore out/pki/truststore.p12 -storepass changeit
```

```
Your keystore contains 1 entry

ca, 28 thg 11, 2026, trustedCertEntry,
Certificate fingerprint (SHA-256): 48:23:01:45:FD:7F:54:61:61:7A:63:02:B4:C9:6B:CC:67:B9:C3:33:94:86:EB:5E:0D:E2:34:5D:5E:28:E9:CD
```

(The date is printed in the machine's locale.) Practical notes:

- Use **PKCS#12** (the default since JDK 9). JKS is a legacy format only Java reads.
- Setting `-Djavax.net.ssl.trustStore=...` **replaces** `cacerts`, it does not add to it. An application that calls
  both an internal CA and public APIs must merge both into one truststore, or use a separate `SSLContext` per client
  (lesson 7). The second option is clearer and less surprising.
- A truststore should hold **CAs**, not individual leaf certificates: replacing a server certificate then does not
  require redistributing truststores to every client.
- The keystore password and the keystore file itself are secrets: take them from a secret store/environment variable,
  never commit them (the demo's `out/` directory is in `.gitignore`).

## 9. Disabling certificate validation: the most common and most dangerous mistake

Faced with `PKIX path building failed`, the most widely copied "fix" is an `X509TrustManager` with empty methods
(equivalents: `curl -k`, `verify=False`, `rejectUnauthorized: false`, `InsecureSkipVerify: true`).

[TlsCertificateValidationDemo.java](../demos/TlsCertificateValidationDemo.java): the client's connection lands on an
impostor's server (forged DNS or ARP). The impostor also speaks TLS and also has a `CN=localhost` certificate it made itself.

```
mode=broken
client -> impostor: handshake OK, peer certificate [CN=localhost,O=Attacker], request sent, reply: 200 OK
impostor captured: GET /account Authorization: Bearer demo-api-token-12345
RESULT: WRONG (encrypted connection, but to the attacker)
```

The fix: keep validation, and specify exactly what is trusted, namely a truststore holding the internal CA:

```
mode=fixed
client -> impostor: handshake REFUSED: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
client -> real server: handshake OK, peer certificate [CN=localhost,O=Demo Server], request sent, reply: 200 OK
impostor captured: nothing
RESULT: CORRECT (handshake with the impostor was refused, the token never left the client)
```

The handshake fails **before** the token leaves the client.

A Java-specific detail: a raw `SSLSocket` does **not check the host name** (SAN) unless you enable
`SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` (see [DemoTlsSupport.java](../demos/DemoTlsSupport.java)).
`HttpClient`, `HttpsURLConnection` and Spring's HTTP clients check it themselves. Checking the chain without checking
the name means any valid certificate issued by the same CA (for another service) can impersonate your server.

## 10. Self-check

1. A server certificate has `CN=api.internal` but its SAN only contains `api.internal.svc`. Can a client calling
   `https://api.internal` connect?
2. Why does the CA not need, and should not get, your private key?
3. Does a truststore contain secrets? Why must it still be protected?
4. After setting `-Djavax.net.ssl.trustStore=internal-ca.p12`, calls to `https://api.stripe.com` start failing. Why?
5. A Java server with mTLS reports that a client sent a certificate but it was rejected with `certificate_unknown`.
   What do you check first?

Short answers: (1) No; the client checks the SAN, the CN is ignored when a SAN is present. (2) The CSR carries only
the public key and a signature proving you hold the private key; a private key that leaves its birthplace is one more
place it can leak. (3) No; but whoever can add a CA to it makes the application trust everything that CA issues.
(4) The new truststore replaces `cacerts`, so public CAs are no longer trusted. (5) Whether the server's truststore
contains the CA that signed the client certificate; whether the client certificate has the `clientAuth` EKU; whether
it is still valid.

Next: [05 — Authentication and authorization](05-authentication-and-authorization.md).
