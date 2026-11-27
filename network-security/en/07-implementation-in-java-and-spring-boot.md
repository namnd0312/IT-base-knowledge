# 07 — Implementation in Java and Spring Boot

Goal: connect everything from lessons 1–6 to code: `SSLContext`/`SSLSocket` and keystores/truststores in the JDK,
HTTPS and mTLS in Spring Boot (SSL bundles), filter chains for two kinds of identity (client certificates and JWTs),
the OAuth2 resource server and JWT validation, and the client side of calling an mTLS service from Java.

Everything in this lesson is in [spring-boot-demo/](../spring-boot-demo/) and the plain-Java demos; output is from real
runs (Spring Boot 4.1.2, Spring Security 7, JDK 21.0.5). Snippets marked *illustrative* were not run.

## 1. The map: which layer does what

```
                     ┌─────────────────────────── the server's JVM ─────────────────────────┐
 client ──TCP──▶ Tomcat/JSSE ──────────▶ Spring Security FilterChainProxy ─────▶ Controller
                 │ SSLContext                │ chain 1 /internal/**: X509AuthenticationFilter
                 │  KeyManager  ← keystore   │   certificate CN → UserDetailsService → ROLE_SERVICE
                 │  TrustManager← truststore │ chain 2 /api/**: BearerTokenAuthenticationFilter
                 │ handshake, verifies the   │   JwtDecoder (signature, exp, iss, aud) → SCOPE_*
                 │ client certificate (mTLS) │ AuthorizationFilter: hasRole / hasAuthority
                 └───────────────────────────┴──────────────────────────────────────────────┘
```

- **JSSE** (Java Secure Socket Extension, the JDK's TLS layer) performs the handshake and checks the certificate chain
  and host name. If a client certificate is not signed by a CA in the truststore, the request **never** reaches Spring.
- **Spring Security** only receives the results: the already-verified certificate (in the
  `jakarta.servlet.request.X509Certificate` attribute) or the `Authorization` header; it turns them into an
  `Authentication` and authorises.

## 2. The JDK: SSLContext, KeyManager, TrustManager

All TLS configuration in Java comes down to one question: which keystore and which truststore the `SSLContext` is built from.

```java
// "Who am I": my private key + certificate chain
KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
kmf.init(keyStore, password);

// "Whom do I trust": the other side's CA
TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
tmf.init(trustStore);

SSLContext context = SSLContext.getInstance("TLS");
context.init(kmf.getKeyManagers(),      // null = present no certificate (a one-way TLS client)
             tmf.getTrustManagers(),    // null = the JDK default cacerts
             null);                     // null = the default SecureRandom
```

([DemoTlsSupport.java](../demos/DemoTlsSupport.java) is exactly this, wrapped as `context(keyStore, trustStore)`.)

| Class | Role |
|-------|------|
| `KeyStore` | Loads a PKCS#12/JKS file into memory |
| `KeyManager` (`X509KeyManager`) | Picks the certificate to present. On the client side it only picks certificates issued by a CA listed in the server's `CertificateRequest` |
| `TrustManager` (`X509TrustManager`) | Verifies the chain the other side presents (PKIX) |
| `SSLContext` | Bundles both; produces `SSLSocketFactory`, `SSLServerSocketFactory`, `SSLEngine` |
| `SSLParameters` | Versions, cipher suites, `needClientAuth`, **`endpointIdentificationAlgorithm`** |

### SSLSocket: client side and server side

```java
// client: with a raw SSLSocket, host-name checking must be switched on yourself
SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port);
SSLParameters params = socket.getSSLParameters();
params.setEndpointIdentificationAlgorithm("HTTPS");     // without this line, a valid certificate for ANY name passes
params.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
socket.setSSLParameters(params);
socket.startHandshake();                                // certificate errors throw SSLHandshakeException here
SSLSession session = socket.getSession();               // getProtocol(), getCipherSuite(), getPeerCertificates()

// mTLS server
SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(port);
server.setNeedClientAuth(true);                         // setWantClientAuth(true): request it but do not require it
```

Application code rarely uses `SSLSocket` directly; but every Java HTTP client and server is built on exactly these
objects, so understanding them means understanding every TLS error message.

### JVM-wide configuration, and why to avoid it

```
-Djavax.net.ssl.keyStore=client.p12  -Djavax.net.ssl.keyStorePassword=...  -Djavax.net.ssl.keyStoreType=PKCS12
-Djavax.net.ssl.trustStore=truststore.p12  -Djavax.net.ssl.trustStorePassword=...
-Djavax.net.debug=ssl:handshake                    # debugging: prints each handshake message (lesson 3)
```

These properties change `SSLContext.getDefault()` for **the whole JVM**: every library, every outbound call. Setting
`trustStore` replaces `cacerts` (lesson 4), so calls to public APIs can suddenly fail. Prefer a dedicated `SSLContext`
per client that needs special configuration (section 7), or Spring Boot SSL bundles.

## 3. The Spring Security filter chain

The demo has two `SecurityFilterChain`s, one per kind of identity ([SecurityConfig.java](../spring-boot-demo/src/main/java/demo/netsec/SecurityConfig.java)):

```java
@Bean @Order(1)
SecurityFilterChain internalChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/internal/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("SERVICE"))
        .x509(x509 -> x509
                .subjectPrincipalRegex("CN=(.*?)(?:,|$)")      // the CN becomes the principal name
                .userDetailsService(trustedServices()))          // allow-list: which CNs get SERVICE
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable());                            // no cookies used
    return http.build();
}

@Bean @Order(2)
SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**", "/dev/**")
        .authorizeHttpRequests(auth -> auth
                .requestMatchers("/dev/token").permitAll()              // only so the demo can mint tokens
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_orders:admin")
                .anyRequest().hasAuthority("SCOPE_orders:read"))
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable());                            // browsers do not send bearer tokens automatically
    return http.build();
}
```

- `securityMatcher` decides which chain a request belongs to; the first matching chain is used, later ones are skipped.
- A **valid** certificate is not enough: `userDetailsService` is the allow-list. Another service with a certificate
  from the same CA still does not get `ROLE_SERVICE` (lesson 3, section 7).
- Disabling CSRF is only right because these two chains do not authenticate with cookies (lesson 6). How
  `FilterChainProxy` and the filters work in detail: [Spring Security lesson 2](../../springboot/security/en/02-filter-chain-architecture.md).

## 4. HTTPS in Spring Boot: SSL bundles

Since Spring Boot 3.1, TLS configuration is gathered into **SSL bundles**: declared once, used by the server, by
clients, by Redis/Kafka connections... ([application.yml](../spring-boot-demo/src/main/resources/application.yml)):

```yaml
spring:
  ssl:
    bundle:
      jks:
        demo-server:
          key:
            alias: server
          keystore:            # who the server is
            location: file:../out/pki/server.p12
            password: ${KEYSTORE_PASSWORD:changeit}
            type: PKCS12
          truststore:          # which clients the server trusts (only needed for mTLS)
            location: file:../out/pki/truststore.p12
            password: ${KEYSTORE_PASSWORD:changeit}
            type: PKCS12
          options:
            enabled-protocols: TLSv1.3,TLSv1.2

server:
  port: 8443
  ssl:
    bundle: demo-server
    client-auth: want
```

There is also `spring.ssl.bundle.pem.*` for using PEM files directly (`certificate`, `private-key`), handy for
certificates issued by cert-manager or Let's Encrypt. Bundles support `reload-on-update: true` to reload certificates
when the files change without a restart (*illustrative*, not run in this guide).

Checking from outside:

```bash
openssl s_client -connect localhost:8443 -CAfile out/pki/ca.crt
```

```
Acceptable client certificate CA names
O=Demo, CN=Demo Root CA
Verification: OK
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
```

```bash
openssl s_client -connect localhost:8443 -CAfile out/pki/ca.crt -tls1_2
```

```
New, TLSv1.2, Cipher is ECDHE-ECDSA-AES256-GCM-SHA384
```

The `Acceptable client certificate CA names` line is the content of `CertificateRequest`: the server is asking for a
client certificate and listing the CAs it accepts, taken from the truststore. A client that does not trust the
server's CA stops right at the handshake:

```bash
curl https://localhost:8443/internal/inventory
```

```
curl: (60) SSL certificate problem: self signed certificate in certificate chain
```

## 5. mTLS in Spring Boot

| `server.ssl.client-auth` | Behaviour |
|--------------------------|-----------|
| `none` (default) | One-way TLS |
| `want` | Requests a client certificate; the handshake succeeds without one; **with an invalid one the handshake fails** |
| `need` | A valid certificate is mandatory; without one the handshake fails |

The demo uses `want` because one port serves both kinds of caller: `/internal/**` requires a certificate (through the
filter chain), `/api/**` uses JWTs. Four cases:

```bash
cd out/pki
openssl pkcs12 -in client.p12 -passin pass:changeit -nokeys -out client.crt.pem            # curl needs PEM
openssl pkcs12 -in client.p12 -passin pass:changeit -nocerts -nodes -out client.key.pem
```

```
== trusts the CA, sends no client certificate
{"timestamp":"...","status":403,"error":"Forbidden","path":"/internal/inventory"} -> 403

== client certificate signed by the CA (curl --cacert ca.crt --cert client.crt.pem --key client.key.pem)
{"certificateIssuer":"CN=Demo Root CA,O=Demo","authorities":"[ROLE_SERVICE, FACTOR_X509]","items":42,
 "certificateSubject":"CN=order-service,O=Demo Client","tlsProtocol":"TLSv1.3","caller":"order-service"} -> 200

== self-signed certificate, CN=order-service
curl: (56) LibreSSL SSL_read: ...:sslv3 alert certificate unknown
```

(`FACTOR_X509` is an authority Spring Security 7 adds to record which authentication factor was used.)

Two details you only see when running it for real:

1. **A Java client does not send a "foreign" certificate**. With the same self-signed certificate, curl sends it and
   is rejected in the handshake; the JDK's `KeyManager`, however, only presents a certificate issued by a CA in the
   `Acceptable client certificate CA names` list, so it **sends nothing**, the handshake succeeds, and the filter chain
   returns 403. The test `certificateNotSignedByTheTrustedCaIsNeverAccepted` asserts exactly this behaviour. When
   debugging "the client sends a certificate but the server says it has none", check whether the client certificate's
   CA is in the server's truststore.
2. **TLS 1.3 and `want`**. At startup Tomcat warns:

   ```
   WARN ... SSLUtilBase : The JSSE TLS 1.3 implementation does not support post handshake authentication (PHA)
   and is therefore incompatible with optional certificate authentication
   ```

   TLS 1.3 removed renegotiation, so the server cannot "ask for a certificate later" once it knows which path a request
   targets. The certificate must be requested in the initial handshake, for **every** connection. The demo still works
   because Tomcat requests the certificate in every handshake (clients without one simply skip it). If every endpoint
   is service-to-service, use `need`. To separate them fully, open two connectors/ports (one `need`, one `none`).

### Behind a load balancer

Many systems terminate TLS at a load balancer/ingress. The application then does **not** see the client certificate;
the load balancer must do mTLS and pass the identity on in a header (for example Envoy's `X-Forwarded-Client-Cert`).
The application may trust that header only if it is certain every request passes through the load balancer (network
policy, or mTLS between the load balancer and the application); otherwise anyone can attach the header themselves,
which is precisely the bug in [MutualTlsDemo](../demos/MutualTlsDemo.java)'s `broken` mode.

## 6. The OAuth2 resource server and JWT validation

### Configuration with a real authorization server (*illustrative*)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://issuer.example           # fetches /.well-known/openid-configuration → jwks_uri
          audiences: orders-api                         # checks the aud claim
```

With `issuer-uri`, Spring fetches the public keys from the JWKS, caches them, and refetches on an unknown `kid` (when
the AS rotates keys); it checks the signature, `exp`/`nbf` and `iss`. Without `audiences`, `aud` is **not** checked.

### The explicit configuration in the demo

The demo mints its own tokens ([DemoTokenIssuer.java](../spring-boot-demo/src/main/java/demo/netsec/DemoTokenIssuer.java),
only so it runs standalone) and builds the `JwtDecoder` by hand to make each step visible:

```java
@Bean
JwtDecoder jwtDecoder(DemoTokenIssuer issuer, @Value("${app.jwt.issuer}") String issuerUri,
                      @Value("${app.jwt.audience}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(issuer.publicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)                      // a fixed algorithm
            .build();
    OAuth2TokenValidator<Jwt> audienceValidator =
            new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuerUri),                  // exp, nbf (60 s clock skew), iss
            audienceValidator));
    return decoder;
}
```

The resource server holds only the **public key**; it can verify tokens but cannot create them. The token's `scope`
becomes the authority `SCOPE_orders:read`, matching `hasAuthority(...)` in the filter chain.

Results with curl (tokens obtained from `POST /dev/token`):

```
== no token
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="https://localhost:8443/.well-known/oauth-protected-resource"

== valid token
{"audience":["orders-api"],"subject":"alice","orders":3,"scope":"orders:read"} -> 200

== missing scope orders:admin
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token."

== genuine token but aud=billing-api
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: The aud claim is not valid"

== expired (a 1-second token used 63 seconds later: beyond the allowed 60-second clock skew)
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Jwt expired at 2026-11-27T18:00:52Z"

== payload edited to scope "orders:read orders:admin", old signature kept
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Signed JWT rejected: Invalid signature"
```

Exactly 401 for "unusable token" and 403 for "good token, insufficient permission" (lesson 5). The `WWW-Authenticate`
header per RFC 6750 tells the client why. A trade-off: in production, detailed `error_description`s help debugging but
also tell someone probing with tokens where they went wrong.

More about JWTs in Spring (minting tokens, `JwtAuthenticationConverter`, reading a `roles` claim):
[Spring Security lesson 6](../../springboot/security/en/06-jwt-and-stateless-rest-api.md).

## 7. The client side: calling an mTLS service from Java

The JDK `HttpClient` with a dedicated `SSLContext`, taken from
[MutualTlsAndJwtIntegrationTests.java](../spring-boot-demo/src/test/java/demo/netsec/MutualTlsAndJwtIntegrationTests.java):

```java
TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
trust.init(load("truststore"));                              // trust the server through the internal CA
KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
keys.init(load("client"), PASSWORD);                         // present the order-service certificate
SSLContext context = SSLContext.getInstance("TLS");
context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);

HttpClient client = HttpClient.newBuilder().sslContext(context).build();   // checks the host name itself
HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("https://localhost:8443/internal/inventory")).build(),
        HttpResponse.BodyHandlers.ofString());
```

With Spring, reuse an SSL bundle for the client (*illustrative*):

```yaml
spring.ssl.bundle.jks.order-service-client:
  keystore:   { location: file:client.p12, password: ${KEYSTORE_PASSWORD}, type: PKCS12 }
  truststore: { location: file:truststore.p12, password: ${KEYSTORE_PASSWORD}, type: PKCS12 }
```

```java
@Bean
RestClient inventoryClient(RestClient.Builder builder, RestClientSsl ssl) {
    return builder.baseUrl("https://inventory.internal:8443").apply(ssl.fromBundle("order-service-client")).build();
}
```

One bundle per client: calls to internal services use the internal CA + a client certificate, calls to public APIs
keep using `cacerts`. No need to touch JVM-wide `javax.net.ssl.*`.

## 8. Running the demo

```bash
cd network-security
./demos/create-demo-pki.sh                    # CA, server.p12, client.p12, truststore.p12, attacker, rogue-client
cd spring-boot-demo
mvn test                                      # 8 integration tests over real HTTPS, random port
mvn spring-boot:run                           # https://localhost:8443
```

```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

| Test | Asserts |
|------|---------|
| `trustedServiceCertificateIsAuthenticatedByItsCommonName` | A CA-signed certificate → 200, caller = CN |
| `connectionWithoutClientCertificateCannotReachInternalEndpoints` | No certificate → 403 |
| `certificateNotSignedByTheTrustedCaIsNeverAccepted` | A self-signed certificate → not sent → 403 |
| `serverWhoseCertificateIsNotSignedByTheTrustedCaIsRefusedByTheClient` | A client using the default `cacerts` → `PKIX path building failed` |
| `validTokenForThisAudienceIsAccepted` | The right JWT → 200 |
| `genuineTokenIssuedForAnotherApiIsRejected` | Wrong `aud` → 401 |
| `tokenWithoutRequiredScopeIsForbidden` | Missing scope → 403 |
| `tamperedTokenIsRejected` | Edited payload → 401 |

## 9. Implementation checklist

- [ ] Keystores/truststores are PKCS#12, passwords come from a secret store, files are not in git or images.
- [ ] Each outbound client has its own `SSLContext`/SSL bundle; no JVM-wide `javax.net.ssl.trustStore` unless deliberate.
- [ ] No accept-all `TrustManager`, no `HostnameVerifier` returning `true`, no `setEndpointIdentificationAlgorithm(null)`.
- [ ] `server.ssl` enables TLS 1.3 + 1.2; mTLS uses `need` when every endpoint is service-to-service.
- [ ] The mTLS chain has an identity allow-list, not just "has a valid certificate".
- [ ] The resource server fixes the algorithm and checks `iss` **and** `aud`; access tokens are short-lived.
- [ ] You know where TLS terminates; identity headers from a load balancer are trusted only when nobody else can send them.
- [ ] `-Djavax.net.debug=ssl:handshake` for debugging, never left on in production (the log contains a lot of handshake detail).

## 10. Self-check

1. Does a request to `/internal/inventory` with an invalid certificate reach `X509AuthenticationFilter`?
2. Why does the demo use `client-auth: want` rather than `need`, and what does that choice cost with TLS 1.3?
3. Switching from `NimbusJwtDecoder.withPublicKey(...)` to `issuer-uri`, what must be added to keep the audience check?
4. A Java client has a client certificate but the server reports receiving none. First suspicion?

Short answers: (1) No; JSSE rejects it during the handshake and the request never reaches Spring. (2) One port serves
both certificate-based and JWT-based callers; with TLS 1.3 there is no post-handshake auth, so every connection is
asked for a certificate up front and it cannot be requested per path. (3)
`spring.security.oauth2.resourceserver.jwt.audiences`, or your own `JwtClaimValidator` for `aud`. (4) The CA that signed
the client certificate is not in the server's truststore, so the client's `KeyManager` does not pick it (see
`Acceptable client certificate CA names`).

Next: [08 — Hands-on lab with demos](08-hands-on-lab-with-demos.md).
