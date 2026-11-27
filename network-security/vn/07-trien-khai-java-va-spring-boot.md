# 07 — Triển khai trong Java và Spring Boot

Mục tiêu: nối mọi thứ ở bài 1–6 với code: `SSLContext`/`SSLSocket` và keystore/truststore trong JDK, HTTPS và mTLS
trong Spring Boot (SSL bundle), filter chain cho hai kiểu danh tính (chứng chỉ client và JWT), OAuth2 resource server
và kiểm tra JWT, và phía client gọi một service mTLS từ Java.

Mọi thứ trong bài có trong [spring-boot-demo/](../spring-boot-demo/) và các demo Java thuần; output là từ lần chạy
thật (Spring Boot 4.1.2, Spring Security 7, JDK 21.0.5). Các đoạn đánh dấu *minh hoạ* thì chưa được chạy.

## 1. Bản đồ: lớp nào làm gì

```
                     ┌─────────────────────────── JVM của server ───────────────────────────┐
 client ──TCP──▶ Tomcat/JSSE ──────────▶ Spring Security FilterChainProxy ─────▶ Controller
                 │ SSLContext                │ chain 1 /internal/**: X509AuthenticationFilter
                 │  KeyManager  ← keystore   │   CN chứng chỉ → UserDetailsService → ROLE_SERVICE
                 │  TrustManager← truststore │ chain 2 /api/**: BearerTokenAuthenticationFilter
                 │ bắt tay, kiểm tra chứng   │   JwtDecoder (chữ ký, exp, iss, aud) → SCOPE_*
                 │ chỉ client (mTLS)         │ AuthorizationFilter: hasRole / hasAuthority
                 └───────────────────────────┴──────────────────────────────────────────────┘
```

- **JSSE** (Java Secure Socket Extension, tầng TLS của JDK) làm bắt tay, kiểm tra chuỗi chứng chỉ và hostname. Nếu
  chứng chỉ client không được CA trong truststore ký, request **không bao giờ** tới Spring.
- **Spring Security** chỉ nhận kết quả: chứng chỉ đã được kiểm tra (ở attribute `jakarta.servlet.request.X509Certificate`)
  hoặc header `Authorization`; nó biến chúng thành `Authentication` và phân quyền.

## 2. JDK: SSLContext, KeyManager, TrustManager

Toàn bộ cấu hình TLS trong Java quy về một câu hỏi: dựng `SSLContext` từ keystore nào và truststore nào.

```java
// "Tôi là ai": khoá riêng + chuỗi chứng chỉ của mình
KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
kmf.init(keyStore, password);

// "Tôi tin ai": CA của bên kia
TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
tmf.init(trustStore);

SSLContext context = SSLContext.getInstance("TLS");
context.init(kmf.getKeyManagers(),      // null = không trình chứng chỉ (client one-way TLS)
             tmf.getTrustManagers(),    // null = dùng cacerts mặc định của JDK
             null);                     // null = SecureRandom mặc định
```

([DemoTlsSupport.java](../demos/DemoTlsSupport.java) chính là đoạn này, gói lại thành `context(keyStore, trustStore)`.)

| Lớp | Vai trò |
|-----|---------|
| `KeyStore` | Nạp file PKCS#12/JKS vào bộ nhớ |
| `KeyManager` (`X509KeyManager`) | Chọn chứng chỉ để trình bên kia. Phía client, nó chỉ chọn chứng chỉ do một CA trong `CertificateRequest` của server cấp |
| `TrustManager` (`X509TrustManager`) | Kiểm tra chuỗi chứng chỉ bên kia trình (PKIX) |
| `SSLContext` | Gói cả hai; sinh `SSLSocketFactory`, `SSLServerSocketFactory`, `SSLEngine` |
| `SSLParameters` | Phiên bản, cipher suite, `needClientAuth`, **`endpointIdentificationAlgorithm`** |

### SSLSocket: phía client và phía server

```java
// client: kiểm tra hostname phải tự bật với SSLSocket thô
SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(host, port);
SSLParameters params = socket.getSSLParameters();
params.setEndpointIdentificationAlgorithm("HTTPS");     // thiếu dòng này: chứng chỉ hợp lệ của BẤT KỲ tên nào cũng qua
params.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
socket.setSSLParameters(params);
socket.startHandshake();                                // lỗi chứng chỉ ném SSLHandshakeException tại đây
SSLSession session = socket.getSession();               // getProtocol(), getCipherSuite(), getPeerCertificates()

// server mTLS
SSLServerSocket server = (SSLServerSocket) context.getServerSocketFactory().createServerSocket(port);
server.setNeedClientAuth(true);                         // setWantClientAuth(true): yêu cầu nhưng không bắt buộc
```

Trong code ứng dụng hiếm khi dùng `SSLSocket` trực tiếp; nhưng mọi HTTP client và server Java đều dựng trên đúng các
đối tượng này, nên hiểu nó là hiểu được mọi thông báo lỗi TLS.

### Cấu hình toàn JVM và vì sao nên tránh

```
-Djavax.net.ssl.keyStore=client.p12  -Djavax.net.ssl.keyStorePassword=...  -Djavax.net.ssl.keyStoreType=PKCS12
-Djavax.net.ssl.trustStore=truststore.p12  -Djavax.net.ssl.trustStorePassword=...
-Djavax.net.debug=ssl:handshake                    # debug: in từng bản tin bắt tay (bài 3)
```

Các property này đổi `SSLContext.getDefault()` cho **cả JVM**: mọi thư viện, mọi lời gọi ra ngoài. Đặt `trustStore`
là thay thế `cacerts` (bài 4), nên lời gọi tới API công khai có thể đột ngột lỗi. Ưu tiên một `SSLContext` riêng cho
từng client cần cấu hình đặc biệt (mục 7), hoặc SSL bundle của Spring Boot.

## 3. Spring Security filter chain

Demo có hai `SecurityFilterChain`, mỗi chain cho một kiểu danh tính ([SecurityConfig.java](../spring-boot-demo/src/main/java/demo/netsec/SecurityConfig.java)):

```java
@Bean @Order(1)
SecurityFilterChain internalChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/internal/**")
        .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("SERVICE"))
        .x509(x509 -> x509
                .subjectPrincipalRegex("CN=(.*?)(?:,|$)")      // lấy CN làm tên principal
                .userDetailsService(trustedServices()))          // allow-list: CN nào được làm SERVICE
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable());                            // không dùng cookie
    return http.build();
}

@Bean @Order(2)
SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
    http.securityMatcher("/api/**", "/dev/**")
        .authorizeHttpRequests(auth -> auth
                .requestMatchers("/dev/token").permitAll()              // chỉ để demo tự phát token
                .requestMatchers("/api/admin/**").hasAuthority("SCOPE_orders:admin")
                .anyRequest().hasAuthority("SCOPE_orders:read"))
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(csrf -> csrf.disable());                            // bearer token không tự gửi bởi trình duyệt
    return http.build();
}
```

- `securityMatcher` quyết định request thuộc chain nào; chain đầu tiên khớp được dùng, các chain sau bị bỏ qua.
- Chứng chỉ **hợp lệ** chưa đủ: `userDetailsService` là allow-list. Một service khác có chứng chỉ do cùng CA cấp vẫn
  không có `ROLE_SERVICE` (bài 3, mục 7).
- Tắt CSRF chỉ đúng vì hai chain này không xác thực bằng cookie (bài 6). Cách hoạt động chi tiết của `FilterChainProxy`
  và các filter: [Spring Security bài 2](../../springboot/security/vn/02-kien-truc-filter-chain.md).

## 4. HTTPS trong Spring Boot: SSL bundle

Từ Spring Boot 3.1, cấu hình TLS gom vào **SSL bundle**: khai báo một lần, dùng cho server, cho client, cho kết nối
Redis/Kafka... ([application.yml](../spring-boot-demo/src/main/resources/application.yml)):

```yaml
spring:
  ssl:
    bundle:
      jks:
        demo-server:
          key:
            alias: server
          keystore:            # server là ai
            location: file:../out/pki/server.p12
            password: ${KEYSTORE_PASSWORD:changeit}
            type: PKCS12
          truststore:          # server tin client nào (chỉ cần cho mTLS)
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

Có cả `spring.ssl.bundle.pem.*` để dùng trực tiếp file PEM (`certificate`, `private-key`), tiện với chứng chỉ do
cert-manager hoặc Let's Encrypt cấp. Bundle hỗ trợ `reload-on-update: true` để nạp lại chứng chỉ khi file đổi mà không
khởi động lại (*minh hoạ*, chưa chạy trong tài liệu này).

Kiểm tra từ ngoài:

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

Dòng `Acceptable client certificate CA names` là nội dung `CertificateRequest`: server đang xin chứng chỉ client và
liệt kê CA nó chấp nhận, lấy từ truststore. Client không tin CA của server thì dừng ngay ở bắt tay:

```bash
curl https://localhost:8443/internal/inventory
```

```
curl: (60) SSL certificate problem: self signed certificate in certificate chain
```

## 5. mTLS trong Spring Boot

| `server.ssl.client-auth` | Hành vi |
|--------------------------|---------|
| `none` (mặc định) | One-way TLS |
| `want` | Xin chứng chỉ client; không có vẫn bắt tay được; **có mà không hợp lệ thì bắt tay thất bại** |
| `need` | Bắt buộc có chứng chỉ hợp lệ, không có là thất bại ở bắt tay |

Demo dùng `want` vì một cổng phục vụ cả hai loại caller: `/internal/**` bắt buộc chứng chỉ (qua filter chain), `/api/**`
dùng JWT. Bốn trường hợp:

```bash
cd out/pki
openssl pkcs12 -in client.p12 -passin pass:changeit -nokeys -out client.crt.pem            # curl cần PEM
openssl pkcs12 -in client.p12 -passin pass:changeit -nocerts -nodes -out client.key.pem
```

```
== tin CA, không gửi chứng chỉ client
{"timestamp":"...","status":403,"error":"Forbidden","path":"/internal/inventory"} -> 403

== chứng chỉ client do CA ký (curl --cacert ca.crt --cert client.crt.pem --key client.key.pem)
{"certificateIssuer":"CN=Demo Root CA,O=Demo","authorities":"[ROLE_SERVICE, FACTOR_X509]","items":42,
 "certificateSubject":"CN=order-service,O=Demo Client","tlsProtocol":"TLSv1.3","caller":"order-service"} -> 200

== chứng chỉ tự ký, CN=order-service
curl: (56) LibreSSL SSL_read: ...:sslv3 alert certificate unknown
```

(`FACTOR_X509` là authority Spring Security 7 tự thêm để ghi nhận yếu tố xác thực đã dùng.)

Hai chi tiết chỉ thấy khi chạy thật:

1. **Client Java không gửi chứng chỉ "lạ"**. Với cùng chứng chỉ tự ký, curl gửi nó và bị từ chối ở bắt tay; còn
   `KeyManager` của JDK chỉ trình chứng chỉ do một CA trong danh sách `Acceptable client certificate CA names` cấp,
   nên nó **không gửi gì**, bắt tay thành công, và filter chain trả 403. Test
   `certificateNotSignedByTheTrustedCaIsNeverAccepted` khẳng định đúng hành vi này. Khi debug "client gửi chứng chỉ
   rồi mà server báo không có", hãy kiểm tra CA của chứng chỉ client có nằm trong truststore của server không.
2. **TLS 1.3 và `want`**. Khi khởi động, Tomcat cảnh báo:

   ```
   WARN ... SSLUtilBase : The JSSE TLS 1.3 implementation does not support post handshake authentication (PHA)
   and is therefore incompatible with optional certificate authentication
   ```

   TLS 1.3 bỏ renegotiation, nên server không thể "xin chứng chỉ sau" khi đã biết request tới path nào. Chứng chỉ phải
   được xin ngay trong bắt tay đầu, cho **mọi** kết nối. Demo vẫn chạy đúng vì Tomcat xin chứng chỉ ở mọi bắt tay
   (client không có thì bỏ qua). Nếu mọi endpoint đều là service-to-service, dùng `need`. Nếu cần tách hẳn, mở hai
   connector/hai cổng (một `need`, một `none`).

### Sau load balancer

Nhiều hệ thống kết thúc TLS ở load balancer/ingress. Khi đó ứng dụng **không** thấy chứng chỉ client; load balancer
phải làm mTLS và chuyển danh tính qua header (ví dụ `X-Forwarded-Client-Cert` của Envoy). Ứng dụng chỉ được tin header
đó khi chắc chắn mọi request đều đi qua load balancer (network policy, hoặc mTLS giữa load balancer và ứng dụng), nếu
không ai cũng tự gắn được header, đúng lỗi của [MutualTlsDemo](../demos/MutualTlsDemo.java) bản `broken`.

## 6. OAuth2 resource server và kiểm tra JWT

### Cấu hình với authorization server thật (*minh hoạ*)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://issuer.example           # tải /.well-known/openid-configuration → jwks_uri
          audiences: orders-api                         # kiểm tra claim aud
```

Với `issuer-uri`, Spring tự tải khoá công khai từ JWKS, cache, và tải lại khi gặp `kid` mới (AS xoay khoá), kiểm tra
chữ ký, `exp`/`nbf` và `iss`. Không khai `audiences` thì **không** kiểm tra `aud`.

### Cấu hình tường minh trong demo

Demo tự phát token ([DemoTokenIssuer.java](../spring-boot-demo/src/main/java/demo/netsec/DemoTokenIssuer.java), chỉ để
chạy độc lập) và dựng `JwtDecoder` bằng tay để thấy từng bước:

```java
@Bean
JwtDecoder jwtDecoder(DemoTokenIssuer issuer, @Value("${app.jwt.issuer}") String issuerUri,
                      @Value("${app.jwt.audience}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(issuer.publicKey())
            .signatureAlgorithm(SignatureAlgorithm.RS256)                      // cố định thuật toán
            .build();
    OAuth2TokenValidator<Jwt> audienceValidator =
            new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuerUri),                  // exp, nbf (lệch giờ 60 s), iss
            audienceValidator));
    return decoder;
}
```

Resource server chỉ giữ **khoá công khai**; nó kiểm tra được token nhưng không tạo được token. `scope` trong token
thành authority `SCOPE_orders:read`, khớp với `hasAuthority(...)` ở filter chain.

Kết quả với curl (token lấy từ `POST /dev/token`):

```
== không có token
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="https://localhost:8443/.well-known/oauth-protected-resource"

== token hợp lệ
{"audience":["orders-api"],"subject":"alice","orders":3,"scope":"orders:read"} -> 200

== thiếu scope orders:admin
HTTP/1.1 403
WWW-Authenticate: Bearer error="insufficient_scope", error_description="The request requires higher privileges than provided by the access token."

== token thật nhưng aud=billing-api
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: The aud claim is not valid"

== hết hạn (token 1 giây, gọi sau 63 giây: vượt quá 60 giây lệch giờ cho phép)
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Jwt expired at 2026-11-27T18:00:52Z"

== payload sửa thành scope "orders:read orders:admin", giữ chữ ký cũ
HTTP/1.1 401
WWW-Authenticate: Bearer error="invalid_token", error_description="An error occurred while attempting to decode the Jwt: Signed JWT rejected: Invalid signature"
```

Đúng 401 cho "token không dùng được" và 403 cho "token tốt nhưng không đủ quyền" (bài 5). Header `WWW-Authenticate`
theo RFC 6750 cho client biết vì sao. Cân nhắc: trong production, `error_description` chi tiết giúp debug nhưng cũng cho
kẻ dò token biết họ sai ở đâu.

Chi tiết về JWT trong Spring (tự phát token, `JwtAuthenticationConverter`, đọc claim `roles`):
[Spring Security bài 6](../../springboot/security/vn/06-jwt-va-rest-api-stateless.md).

## 7. Phía client: gọi service mTLS từ Java

JDK `HttpClient` với `SSLContext` riêng, lấy từ
[MutualTlsAndJwtIntegrationTests.java](../spring-boot-demo/src/test/java/demo/netsec/MutualTlsAndJwtIntegrationTests.java):

```java
TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
trust.init(load("truststore"));                              // tin server nhờ CA nội bộ
KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
keys.init(load("client"), PASSWORD);                         // trình chứng chỉ order-service
SSLContext context = SSLContext.getInstance("TLS");
context.init(keys.getKeyManagers(), trust.getTrustManagers(), null);

HttpClient client = HttpClient.newBuilder().sslContext(context).build();   // tự kiểm tra hostname
HttpResponse<String> response = client.send(
        HttpRequest.newBuilder(URI.create("https://localhost:8443/internal/inventory")).build(),
        HttpResponse.BodyHandlers.ofString());
```

Với Spring, dùng lại SSL bundle cho client (*minh hoạ*):

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

Mỗi client một bundle: lời gọi tới service nội bộ dùng CA nội bộ + chứng chỉ client, lời gọi tới API công khai vẫn dùng
`cacerts`. Không cần đụng tới `javax.net.ssl.*` toàn JVM.

## 8. Chạy demo

```bash
cd network-security
./demos/create-demo-pki.sh                    # CA, server.p12, client.p12, truststore.p12, attacker, rogue-client
cd spring-boot-demo
mvn test                                      # 8 integration test qua HTTPS thật, cổng ngẫu nhiên
mvn spring-boot:run                           # https://localhost:8443
```

```
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
```

| Test | Khẳng định |
|------|-----------|
| `trustedServiceCertificateIsAuthenticatedByItsCommonName` | Chứng chỉ do CA ký → 200, caller = CN |
| `connectionWithoutClientCertificateCannotReachInternalEndpoints` | Không chứng chỉ → 403 |
| `certificateNotSignedByTheTrustedCaIsNeverAccepted` | Chứng chỉ tự ký → không được gửi → 403 |
| `serverWhoseCertificateIsNotSignedByTheTrustedCaIsRefusedByTheClient` | Client dùng `cacerts` mặc định → `PKIX path building failed` |
| `validTokenForThisAudienceIsAccepted` | JWT đúng → 200 |
| `genuineTokenIssuedForAnotherApiIsRejected` | Sai `aud` → 401 |
| `tokenWithoutRequiredScopeIsForbidden` | Thiếu scope → 403 |
| `tamperedTokenIsRejected` | Sửa payload → 401 |

## 9. Checklist triển khai

- [ ] Keystore/truststore là PKCS#12, mật khẩu lấy từ secret store, file không nằm trong git hay image.
- [ ] Mỗi client ra ngoài có `SSLContext`/SSL bundle riêng; không dùng `javax.net.ssl.trustStore` toàn JVM trừ khi chủ đích.
- [ ] Không có `TrustManager` chấp nhận tất cả, `HostnameVerifier` trả `true`, hay `setEndpointIdentificationAlgorithm(null)`.
- [ ] `server.ssl` bật TLS 1.3 + 1.2; mTLS dùng `need` khi mọi endpoint là service-to-service.
- [ ] Chain mTLS có allow-list danh tính, không chỉ "có chứng chỉ hợp lệ".
- [ ] Resource server cố định thuật toán, kiểm tra `iss` **và** `aud`; access token ngắn hạn.
- [ ] Biết TLS kết thúc ở đâu; header danh tính từ load balancer chỉ được tin khi không ai khác gửi được.
- [ ] `-Djavax.net.debug=ssl:handshake` để debug, không để bật ở production (log chứa nhiều chi tiết bắt tay).

## 10. Tự kiểm tra

1. Request tới `/internal/inventory` với chứng chỉ không hợp lệ có đi tới `X509AuthenticationFilter` không?
2. Vì sao demo dùng `client-auth: want` mà không dùng `need`, và cái giá của lựa chọn đó với TLS 1.3?
3. Đổi `NimbusJwtDecoder.withPublicKey(...)` sang `issuer-uri`, cần thêm gì để giữ kiểm tra audience?
4. Client Java có chứng chỉ client nhưng server báo không nhận được chứng chỉ nào. Nghi ngờ đầu tiên?

Đáp án ngắn: (1) Không; JSSE từ chối ở bắt tay, request không tới Spring. (2) Một cổng phục vụ cả caller dùng chứng chỉ
lẫn caller dùng JWT; với TLS 1.3 không có post-handshake auth nên mọi kết nối đều bị xin chứng chỉ ngay từ đầu, không
thể xin theo path. (3) `spring.security.oauth2.resourceserver.jwt.audiences`, hoặc tự thêm `JwtClaimValidator` cho `aud`.
(4) CA đã ký chứng chỉ client không nằm trong truststore của server, nên `KeyManager` của client không chọn nó (xem
`Acceptable client certificate CA names`).

Bài tiếp theo: [08 — Thực hành với demo](08-thuc-hanh-voi-demo.md).
