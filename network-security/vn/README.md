# Network Security cho Java backend — dữ liệu được bảo vệ thế nào khi đi qua mạng

Bộ tài liệu cho lập trình viên Java/Spring đã đi làm: không dừng ở "cấu hình được Spring Security", mà hiểu **dữ liệu
được bảo vệ như thế nào khi di chuyển qua network**, **các hệ thống xác thực danh tính của nhau ra sao**, và **triển
khai các cơ chế đó trong Java/Spring Boot**.

Gồm 8 bài, 9 [demo Java thuần](../demos/) (mỗi demo có bản lỗi và bản fix) và một [demo Spring Boot](../spring-boot-demo/)
có HTTPS, mTLS và JWT resource server với 8 integration test qua HTTPS thật. Output trong tài liệu lấy từ các lần chạy đó.

Phiên bản đã kiểm chứng: **JDK 21.0.5**, Spring Boot 4.1.2 / Spring Security 7, OpenSSL 3.6.2, curl 8.7.1 (macOS).

English: [../en/README.md](../en/README.md)

## Lộ trình

| # | Bài | Nội dung | Thời gian |
|---|-----|----------|-----------|
| 1 | [01-nen-tang-mang.md](01-nen-tang-mang.md) | OSI và TCP/IP, TLS nằm ở đâu, TCP/UDP, bắt tay 3 bước, DNS, HTTP/HTTPS | 30 phút |
| 2 | [02-mat-ma-hoc.md](02-mat-ma-hoc.md) | AES-GCM, RSA và ECC, SHA-256, HMAC, chữ ký số, Diffie–Hellman | 50 phút |
| 3 | [03-tls.md](03-tls.md) | SSL và TLS, bắt tay TLS 1.3, session key, cipher suite, TLS 1.2 và 1.3, one-way TLS và mTLS | 50 phút |
| 4 | [04-pki-va-chung-chi.md](04-pki-va-chung-chi.md) | Khoá công khai/riêng, X.509, CA, chuỗi chứng chỉ, CSR, CRL/OCSP, keystore/truststore | 50 phút |
| 5 | [05-authentication-va-authorization.md](05-authentication-va-authorization.md) | Basic, session, OAuth 2.0, OpenID Connect, JWT, RBAC/ABAC | 50 phút |
| 6 | [06-bao-mat-api.md](06-bao-mat-api.md) | API key, ký HMAC, chữ ký webhook, replay, MITM, rate limiting, CORS/CSRF | 45 phút |
| 7 | [07-trien-khai-java-va-spring-boot.md](07-trien-khai-java-va-spring-boot.md) | `SSLContext`/`SSLSocket`, keystore/truststore, filter chain, HTTPS, mTLS, OAuth2 resource server, kiểm tra JWT | 60 phút |
| 8 | [08-thuc-hanh-voi-demo.md](08-thuc-hanh-voi-demo.md) | Lab 9 demo + Spring Boot: chạy lỗi → quan sát → fix → kiểm chứng | 90–120 phút |

Đọc theo thứ tự 1 → 7: mỗi bài dùng khối của bài trước. Đã vững mật mã thì có thể bắt đầu từ bài 3. Cần cấu hình gấp
thì đọc thẳng bài 7, nhưng quay lại bài 3–4 khi gặp lỗi TLS đầu tiên.

## Bản đồ

```
         Dữ liệu đi qua những gì, ai thấy gì (01)
                          │
                          ▼
   Khối mật mã: AES, RSA/ECC, SHA-256, HMAC, chữ ký, DH (02)
                          │
          ┌───────────────┴────────────────┐
          ▼                                ▼
  TLS: bắt tay, session key,        PKI: X.509, CA, chuỗi, CSR,
  cipher suite, mTLS (03)  ◀──────▶ CRL/OCSP, keystore/truststore (04)
          │                                │
          └───────────────┬────────────────┘
                          ▼
     Danh tính ở tầng ứng dụng: session, OAuth2, OIDC, JWT, RBAC/ABAC (05)
                          ▼
     Bảo vệ API: API key, HMAC, webhook, replay, MITM, rate limit, CORS/CSRF (06)
                          ▼
     Triển khai: SSLContext, SSL bundle, filter chain, mTLS, resource server (07)
```

## Demo

| # | Demo | Lỗi tái hiện | Bài |
|---|------|--------------|-----|
| 1 | [PlaintextVsTlsEavesdropDemo](../demos/PlaintextVsTlsEavesdropDemo.java) | Thiết bị đứng giữa đọc mật khẩu trên kết nối không mã hoá | 1, 3 |
| 2 | [IntegrityHashVsHmacDemo](../demos/IntegrityHashVsHmacDemo.java) | Gói bị sửa vẫn được nhận vì chỉ kèm SHA-256 | 2 |
| 3 | [EncryptionEcbVsGcmDemo](../demos/EncryptionEcbVsGcmDemo.java) | AES/ECB lộ mẫu và cho cắt dán bản mã | 2 |
| 4 | [KeyExchangeMitmDemo](../demos/KeyExchangeMitmDemo.java) | Diffie–Hellman không xác thực: kẻ đứng giữa đọc và sửa | 2 |
| 5 | [TlsCertificateValidationDemo](../demos/TlsCertificateValidationDemo.java) | Client tin mọi chứng chỉ gửi token cho server giả | 4 |
| 6 | [MutualTlsDemo](../demos/MutualTlsDemo.java) | One-way TLS + header tự khai: ai cũng giả được service nội bộ | 3 |
| 7 | [JwtValidationDemo](../demos/JwtValidationDemo.java) | Decode JWT rồi tin: sửa role, `alg=none`, hết hạn, sai audience | 5 |
| 8 | [WebhookSignatureReplayDemo](../demos/WebhookSignatureReplayDemo.java) | Webhook có HMAC đúng vẫn bị phát lại, cộng tiền 3 lần | 6 |
| 9 | [LoginBruteForceRateLimitDemo](../demos/LoginBruteForceRateLimitDemo.java) | PIN 4 số bị dò ra trong chưa tới 2 giây | 6 |
| S | [spring-boot-demo](../spring-boot-demo/) | HTTPS + mTLS (`x509()`) + OAuth2 resource server (RS256, kiểm tra `aud`) | 7 |

[create-demo-pki.sh](../demos/create-demo-pki.sh) dựng một PKI nhỏ chỉ bằng `keytool` (CA → chứng chỉ server và client
qua CSR, truststore, cộng hai danh tính tự ký cho kẻ tấn công). [DemoTlsSupport.java](../demos/DemoTlsSupport.java) dựng
`SSLContext` từ chúng. Comment và output của demo bằng tiếng Anh vì dùng chung cho hai bản tài liệu.

## Chạy nhanh

```bash
cd network-security
./demos/create-demo-pki.sh
javac -d out demos/*.java

java -cp out MutualTlsDemo broken      # sai: ai cũng tự xưng là order-service được
java -cp out MutualTlsDemo fixed       # đúng: chỉ chứng chỉ do CA nội bộ ký mới vào được

cd spring-boot-demo && mvn test        # HTTPS, mTLS, JWT qua kết nối thật
```

```
mode=fixed server=needClientAuth=true, identity = CN of the verified client certificate
real order-service                   -> 200 orders for caller 'order-service'
rogue, no certificate                -> TLS failure: Received fatal alert: bad_certificate
rogue, self-signed CN=order-service  -> TLS failure: Received fatal alert: bad_certificate
RESULT: CORRECT (only a holder of a CA-signed client key is accepted)
```

`out/` (gồm cả keystore và khoá demo) đã nằm trong [.gitignore](../.gitignore). Mật khẩu keystore demo `changeit` chỉ
bảo vệ khoá dùng trên loopback.

## Phần đã chạy thật và phần minh hoạ

| Đã chạy, output là thật | Minh hoạ, chưa chạy |
|-------------------------|---------------------|
| 9 demo Java, cả hai chế độ; `javax.net.debug` | Cấu hình `issuer-uri` với authorization server thật |
| Spring Boot demo: `mvn test` (8 test) và các lệnh curl/openssl ở bài 7 | `RestClientSsl` / SSL bundle phía client, `reload-on-update` |
| `create-demo-pki.sh`, `keytool`, `openssl s_client/x509/req/verify`, OCSP stapling của example.com | Bật kiểm tra thu hồi trong JDK (`checkRevocation`, `PKIXRevocationChecker`) |
| Số đo RSA/ECDSA/Ed25519, cipher suite mặc định của JDK 21 | Load balancer chuyển chứng chỉ client qua header |

## Tài liệu liên quan trong kho

- [Spring Security](../../springboot/security/vn/README.md): filter chain, form login, CSRF, CORS, JWT, OAuth2 ở mức
  cấu hình chi tiết. Bộ này giải thích lớp mạng và mật mã bên dưới.
- [Java Multithreading](../../Java/multithread/vn/README.md): thread pool, timeout, giới hạn đồng thời.
