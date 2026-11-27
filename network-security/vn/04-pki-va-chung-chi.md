# 04 — PKI và chứng chỉ

Mục tiêu: hiểu chữ ký trong bắt tay TLS được gắn với một danh tính như thế nào: khoá công khai/riêng, chứng chỉ X.509,
CA, chuỗi chứng chỉ, CSR, thu hồi (CRL/OCSP), và cách Java tổ chức tất cả qua keystore/truststore. Cuối bài bạn tự dựng
được một PKI nội bộ nhỏ và đọc được lỗi `PKIX path building failed`.

## 1. Vấn đề PKI giải quyết

Bài 2 kết thúc ở câu hỏi: chữ ký `CertificateVerify` chứng minh "bên kia giữ khoá riêng của khoá công khai K", nhưng
làm sao biết **K đúng là của `api.bank.com`**? Không có câu trả lời, kẻ đứng giữa chỉ cần tự tạo cặp khoá của mình
(demo 4, bản `broken`).

PKI (Public Key Infrastructure) trả lời bằng một bên thứ ba được tin trước: CA ký vào tuyên bố "K thuộc về
`api.bank.com`". Tuyên bố đã ký đó là **chứng chỉ**.

## 2. Khoá công khai, khoá riêng

| | Khoá riêng (private key) | Khoá công khai (public key) |
|---|---|---|
| Ai có | Chỉ chủ sở hữu (server, service) | Bất kỳ ai, nằm trong chứng chỉ |
| Dùng để | Ký (`CertificateVerify`, JWT RS256), giải mã | Kiểm tra chữ ký, mã hoá |
| Lộ thì | Kẻ khác mạo danh được bạn tới khi chứng chỉ hết hạn hoặc bị thu hồi | Không sao: vốn công khai |
| Lưu ở | Keystore có mật khẩu, HSM, KMS, secret của Kubernetes | Chứng chỉ, truststore, JWKS |

Quy tắc không thương lượng: khoá riêng **không rời khỏi** nơi nó được sinh ra nếu không cần, không nằm trong git, không
nằm trong image Docker, không gửi qua chat. Chính vì vậy quy trình chuẩn là CSR (mục 6): CA không bao giờ cần thấy khoá
riêng của bạn.

## 3. Chứng chỉ X.509

Các trường của một chứng chỉ thật (`example.com`):

```bash
openssl s_client -connect example.com:443 -servername example.com -showcerts </dev/null   # lấy chứng chỉ
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

| Trường | Ý nghĩa | Ai kiểm tra / dùng |
|--------|---------|--------------------|
| Subject | Chủ thể (`CN=...`) | Thông tin; với mTLS thường dùng CN làm danh tính service |
| **Subject Alternative Name (SAN)** | Tên miền/IP chứng chỉ hợp lệ cho | Client so với tên đang kết nối. **Trình duyệt và JDK kiểm tra SAN, không dựa vào CN** |
| Issuer | CA đã ký | Xây chuỗi |
| Not Before / Not After | Thời hạn | Hết hạn = bị từ chối. Chứng chỉ công khai hiện tối đa 398 ngày và đang giảm dần; ví dụ trên là 90 ngày |
| Public Key | Khoá công khai của chủ thể | Kiểm tra `CertificateVerify` |
| Basic Constraints `CA:FALSE/TRUE` | Có được ký chứng chỉ khác không | Ngăn một chứng chỉ server tự làm CA |
| Key Usage / Extended Key Usage | Dùng để làm gì: `serverAuth`, `clientAuth` | Chứng chỉ chỉ có `serverAuth` không dùng làm chứng chỉ client mTLS được |
| CRL Distribution Points, OCSP URI | Nơi kiểm tra thu hồi | Mục 7 |
| Signature | Chữ ký của Issuer trên toàn bộ phần trên | Bằng chứng CA đã bảo chứng |

Định dạng file: **PEM** (Base64 giữa `-----BEGIN CERTIFICATE-----`), **DER** (nhị phân), **PKCS#12** (`.p12`/`.pfx`:
gói khoá riêng + chuỗi chứng chỉ, có mật khẩu), **JKS** (định dạng cũ riêng của Java).

## 4. Certificate Authority

| Loại CA | Được tin vì | Dùng cho |
|---------|-------------|----------|
| CA công khai (Let's Encrypt, DigiCert, Sectigo...) | Chứng chỉ gốc có sẵn trong hệ điều hành, trình duyệt, JDK | Endpoint công khai trên Internet |
| CA nội bộ (tự dựng, Vault PKI, AWS Private CA, cert-manager, mesh CA) | Bạn chủ động đưa chứng chỉ gốc vào truststore | Service-to-service, mTLS, môi trường nội bộ |
| Tự ký (self-signed, không có CA) | Bạn tin chính chứng chỉ đó | Dev, test; khó xoay vòng nên tránh ở production |

CA gốc (root) gần như không bao giờ ký trực tiếp chứng chỉ server. Khoá riêng của nó được cất offline; việc ký hằng
ngày do **CA trung gian** (intermediate) đảm nhận. Lộ khoá trung gian thì thu hồi trung gian, không phải thay root
trong hàng tỉ thiết bị.

JDK mang sẵn một truststore các CA công khai:

```bash
keytool -list -cacerts -storepass changeit | grep -c trustedCertEntry
```

```
112
```

## 5. Chuỗi chứng chỉ

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
Root CA                 ← trong truststore của client (không cần server gửi)
   │ ký
Intermediate CA         ← server PHẢI gửi kèm
   │ ký
Leaf: CN=example.com    ← server gửi
```

Client xây đường đi (certification path) từ leaf lên tới một chứng chỉ nó đã tin, và kiểm tra **từng mắt xích**: chữ ký,
thời hạn, `CA:TRUE` ở các CA, key usage, thu hồi, rồi SAN của leaf. Java gọi quá trình này là PKIX; khi không tìm được
đường đi, bạn nhận:

```
PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException:
unable to find valid certification path to requested target
```

Ba nguyên nhân thường gặp, theo thứ tự hay gặp:

1. Truststore của client không chứa CA đã ký chứng chỉ server (CA nội bộ, proxy công ty giải mã TLS).
2. Server không gửi CA trung gian (trình duyệt đôi khi tự tải bù nên "trình duyệt chạy được mà Java thì không").
3. Có kẻ đứng giữa. Đây là lý do không được "sửa" lỗi này bằng cách tắt kiểm tra (mục 9).

## 6. CSR: xin chứng chỉ mà không giao khoá riêng

```
 Service                                       CA
   │ 1. sinh cặp khoá (khoá riêng ở lại đây)     │
   │ 2. tạo CSR = {subject, khoá công khai}      │
   │    ký CSR bằng khoá riêng (chứng minh giữ khoá)
   │ ── CSR ─────────────────────────────────▶ │ 3. kiểm tra quyền sở hữu tên miền/danh tính
   │ ◀────────────────────────── chứng chỉ ── │ 4. ký chứng chỉ bằng khoá riêng của CA
   │ 5. ghép chứng chỉ (+ chuỗi) với khoá riêng vào keystore
```

[create-demo-pki.sh](../demos/create-demo-pki.sh) làm đúng quy trình đó chỉ bằng `keytool` của JDK:

```bash
keytool -genkeypair -alias server -keyalg EC -groupname secp256r1 -dname "CN=localhost,O=Demo Server" -keystore server.p12 ...
keytool -certreq   -alias server -file server.csr -keystore server.p12 ...                       # tạo CSR
keytool -gencert   -alias ca -infile server.csr -outfile server.crt \
        -ext eku=serverAuth -ext san=dns:localhost,ip:127.0.0.1 -keystore ca.p12 ...             # CA ký
keytool -importcert -alias ca     -file ca.crt     -keystore server.p12 ...                      # issuer trước
keytool -importcert -alias server -file server.crt -keystore server.p12 ...                      # rồi chứng chỉ đã ký
```

```bash
openssl req -in server.csr -noout -verify -subject
```

```
Certificate request self-signature verify OK
subject=O=Demo Server, CN=localhost
```

CSR chứa khoá công khai và tự ký bằng khoá riêng; CA kiểm tra chữ ký đó để chắc người gửi giữ khoá riêng. Với CA công
khai, bước 3 là ACME (Let's Encrypt): chứng minh quyền kiểm soát tên miền bằng một file HTTP hoặc bản ghi DNS, hoàn
toàn tự động; trong Kubernetes thường do cert-manager làm.

PKI demo sau khi chạy script:

```
Demo Root CA ──ký──▶ server  (CN=localhost, EKU serverAuth, SAN localhost/127.0.0.1)
             └─ký──▶ client  (CN=order-service, EKU clientAuth)
attacker, rogue-client: tự ký, CA không ký
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

## 7. Thu hồi: CRL và OCSP

Chứng chỉ có thể phải vô hiệu trước hạn: khoá riêng bị lộ, service ngừng hoạt động, cấp nhầm.

| Cơ chế | Cách hoạt động | Nhược điểm |
|--------|----------------|------------|
| **CRL** (Certificate Revocation List) | CA phát hành định kỳ danh sách serial đã thu hồi, có chữ ký; client tải từ `CRL Distribution Points` | File lớn dần; cập nhật chậm (giờ tới ngày) |
| **OCSP** | Client hỏi OCSP responder "serial X còn hợp lệ không", nhận câu trả lời có chữ ký | Thêm một lượt mạng; responder biết bạn truy cập trang nào; responder sập thì client thường "fail open" |
| **OCSP stapling** | **Server** tự lấy câu trả lời OCSP và gửi kèm trong bắt tay | Không còn hai nhược điểm trên; cần server hỗ trợ |
| **Chứng chỉ ngắn hạn** | Hạn vài ngày đến vài giờ; lộ khoá thì chỉ việc ngừng gia hạn | Bắt buộc phải tự động hoá cấp phát |

`example.com` có stapling:

```bash
openssl s_client -connect example.com:443 -servername example.com -status </dev/null
```

```
    OCSP Response Status: successful (0x0)
    Cert Status: good
```

Thực tế cần biết cho Java: TrustManager mặc định của JDK **không kiểm tra thu hồi** cho TLS trừ khi bật
(`-Dcom.sun.net.ssl.checkRevocation=true` cùng cấu hình OCSP/CRL, hoặc tự cấu hình `PKIXRevocationChecker`). Với PKI
nội bộ, cách làm phổ biến và chắc chắn hơn là chứng chỉ ngắn hạn tự xoay vòng. Phần cấu hình thu hồi này chưa được
chạy trong quá trình viết tài liệu.

## 8. Keystore và truststore

Hai file, hai câu hỏi khác nhau. Nhầm hai thứ này là nguồn gốc của phần lớn lỗi cấu hình TLS trong Java.

| | Keystore | Truststore |
|---|---|---|
| Trả lời | "Tôi là ai?" | "Tôi tin ai?" |
| Chứa | Khoá riêng + chuỗi chứng chỉ của **mình** (`PrivateKeyEntry`) | Chứng chỉ CA của **bên kia** (`trustedCertEntry`) |
| Bí mật? | **Có**: chứa khoá riêng | Không chứa bí mật, nhưng phải chống sửa (thêm CA lạ = tin kẻ lạ) |
| Java API | `KeyManagerFactory` → `KeyManager` | `TrustManagerFactory` → `TrustManager` |
| System property | `javax.net.ssl.keyStore`, `...keyStorePassword` | `javax.net.ssl.trustStore`, `...trustStorePassword` |
| Mặc định của JDK | Không có | `$JAVA_HOME/lib/security/cacerts` (112 CA công khai) |

Ai cần gì:

| Vai trò | One-way TLS | mTLS |
|---------|-------------|------|
| Server | Keystore | Keystore + truststore (CA của client) |
| Client | Truststore (thường là `cacerts`) | Truststore + keystore (chứng chỉ client) |

Xem nội dung keystore của server demo:

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

Và truststore:

```bash
keytool -list -keystore out/pki/truststore.p12 -storepass changeit
```

```
Your keystore contains 1 entry

ca, 28 thg 11, 2026, trustedCertEntry,
Certificate fingerprint (SHA-256): 48:23:01:45:FD:7F:54:61:61:7A:63:02:B4:C9:6B:CC:67:B9:C3:33:94:86:EB:5E:0D:E2:34:5D:5E:28:E9:CD
```

Lưu ý thực tế:

- Dùng **PKCS#12** (mặc định từ JDK 9). JKS là định dạng cũ chỉ Java đọc được.
- Đặt `-Djavax.net.ssl.trustStore=...` **thay thế** `cacerts`, không cộng thêm. Ứng dụng vừa gọi CA nội bộ vừa gọi API
  công khai thì phải gộp cả hai vào một truststore, hoặc dùng `SSLContext` riêng cho từng client (bài 7). Cách thứ hai
  rõ ràng và ít bất ngờ hơn.
- Truststore nên chứa **CA**, không chứa từng chứng chỉ leaf: thay chứng chỉ server không phải phát lại truststore cho
  mọi client.
- Mật khẩu keystore và chính file keystore là bí mật: lấy từ secret store/biến môi trường, không commit (thư mục
  `out/` của demo đã nằm trong `.gitignore`).

## 9. Tắt kiểm tra chứng chỉ: lỗi phổ biến và nguy hiểm nhất

Gặp `PKIX path building failed`, đoạn "sửa" lan truyền nhiều nhất là một `X509TrustManager` thân rỗng (tương đương:
`curl -k`, `verify=False`, `rejectUnauthorized: false`, `InsecureSkipVerify: true`).

[TlsCertificateValidationDemo.java](../demos/TlsCertificateValidationDemo.java): kết nối của client rơi vào server
của kẻ mạo danh (DNS hoặc ARP bị giả). Kẻ đó cũng nói TLS, cũng có chứng chỉ `CN=localhost` do hắn tự tạo.

```
mode=broken
client -> impostor: handshake OK, peer certificate [CN=localhost,O=Attacker], request sent, reply: 200 OK
impostor captured: GET /account Authorization: Bearer demo-api-token-12345
RESULT: WRONG (encrypted connection, but to the attacker)
```

Fix: giữ kiểm tra, và chỉ định đúng thứ được tin, là truststore chứa CA nội bộ:

```
mode=fixed
client -> impostor: handshake REFUSED: PKIX path building failed: sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
client -> real server: handshake OK, peer certificate [CN=localhost,O=Demo Server], request sent, reply: 200 OK
impostor captured: nothing
RESULT: CORRECT (handshake with the impostor was refused, the token never left the client)
```

Bắt tay thất bại **trước** khi token rời khỏi client.

Một chi tiết riêng của Java: `SSLSocket` thô **không kiểm tra hostname** (SAN) trừ khi bật
`SSLParameters.setEndpointIdentificationAlgorithm("HTTPS")` (xem [DemoTlsSupport.java](../demos/DemoTlsSupport.java)).
`HttpClient`, `HttpsURLConnection` và các HTTP client của Spring thì tự kiểm tra. Kiểm tra chuỗi mà không kiểm tra tên
nghĩa là bất kỳ chứng chỉ hợp lệ nào do cùng CA cấp (cho một service khác) cũng mạo danh được server của bạn.

## 10. Tự kiểm tra

1. Chứng chỉ server có `CN=api.internal` nhưng SAN chỉ có `api.internal.svc`. Client gọi `https://api.internal` có
   kết nối được không?
2. Vì sao CA không cần, và không nên, thấy khoá riêng của bạn?
3. Truststore có chứa bí mật không? Vì sao vẫn phải bảo vệ nó?
4. Đặt `-Djavax.net.ssl.trustStore=internal-ca.p12` xong, lời gọi tới `https://api.stripe.com` bắt đầu lỗi. Vì sao?
5. Server Java bật mTLS báo client gửi chứng chỉ nhưng bị từ chối với `certificate_unknown`. Kiểm tra gì trước?

Đáp án ngắn: (1) Không; client kiểm tra SAN, CN bị bỏ qua khi có SAN. (2) CSR chỉ chứa khoá công khai và chữ ký chứng
minh bạn giữ khoá riêng; khoá riêng rời khỏi nơi sinh ra là thêm một chỗ có thể lộ. (3) Không; nhưng ai thêm được một
CA vào đó thì khiến ứng dụng tin mọi chứng chỉ CA đó cấp. (4) Truststore mới thay thế `cacerts`, nên CA công khai không
còn được tin. (5) Truststore của server có chứa CA đã ký chứng chỉ client không; chứng chỉ client có EKU `clientAuth`
không; còn hạn không.

Bài tiếp theo: [05 — Authentication và authorization](05-authentication-va-authorization.md).
