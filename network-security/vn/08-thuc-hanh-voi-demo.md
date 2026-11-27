# 08 — Thực hành với demo

Mục tiêu: tự chạy 9 demo Java thuần và demo Spring Boot theo cùng một quy trình: **chạy bản lỗi → quan sát → giải
thích nguyên nhân → đọc fix → chạy bản fix để kiểm chứng**. Output đầy đủ của từng demo nằm ở bài tương ứng; bài này
là đường chạy, câu hỏi và bài tập mở rộng.

Thời gian: 90–120 phút.

## Chuẩn bị

Yêu cầu: JDK 21+, Maven (chỉ cho demo Spring Boot), `openssl` và `curl` (tuỳ chọn). Không cần root, không cần mạng.

```bash
cd network-security
./demos/create-demo-pki.sh          # tạo out/pki: CA, server, client, truststore, attacker, rogue-client
javac -d out demos/*.java
```

- Mọi demo chỉ dùng `127.0.0.1`. `out/` (gồm cả khoá demo) đã nằm trong [.gitignore](../.gitignore).
- Mỗi demo nhận `broken` hoặc `fixed` và in dòng `RESULT:` ở cuối. Giá trị hex, số byte, thời gian sẽ khác trên máy
  bạn; dòng `RESULT:` thì không.
- Chạy lại `create-demo-pki.sh` là tạo CA mới: mọi keystore cũ bị thay. Spring Boot demo và các demo TLS đọc chung
  thư mục này.

| # | Demo | Bài | Lỗi tái hiện |
|---|------|-----|--------------|
| 1 | [PlaintextVsTlsEavesdropDemo](../demos/PlaintextVsTlsEavesdropDemo.java) | 1, 3 | Thiết bị đứng giữa đọc mật khẩu trên TCP thường |
| 2 | [IntegrityHashVsHmacDemo](../demos/IntegrityHashVsHmacDemo.java) | 2 | Gói bị sửa vẫn được nhận vì chỉ kèm SHA-256 |
| 3 | [EncryptionEcbVsGcmDemo](../demos/EncryptionEcbVsGcmDemo.java) | 2 | AES/ECB lộ mẫu, cho cắt dán bản mã |
| 4 | [KeyExchangeMitmDemo](../demos/KeyExchangeMitmDemo.java) | 2 | Diffie–Hellman không xác thực: kẻ đứng giữa đọc và sửa |
| 5 | [TlsCertificateValidationDemo](../demos/TlsCertificateValidationDemo.java) | 4 | TrustManager chấp nhận tất cả gửi token cho server giả |
| 6 | [MutualTlsDemo](../demos/MutualTlsDemo.java) | 3 | Danh tính service lấy từ header tự khai |
| 7 | [JwtValidationDemo](../demos/JwtValidationDemo.java) | 5 | Decode JWT rồi tin: sửa role, `alg=none`, hết hạn, sai `aud` |
| 8 | [WebhookSignatureReplayDemo](../demos/WebhookSignatureReplayDemo.java) | 6 | Webhook có HMAC đúng vẫn bị phát lại, cộng tiền 3 lần |
| 9 | [LoginBruteForceRateLimitDemo](../demos/LoginBruteForceRateLimitDemo.java) | 6 | PIN 4 số bị dò ra trong chưa tới 2 giây |
| S | [spring-boot-demo](../spring-boot-demo/) | 7 | HTTPS, mTLS (`x509()`), JWT resource server, client mTLS |

---

## Lab 1 — Văn bản thuần và TLS

```bash
java -cp out PlaintextVsTlsEavesdropDemo broken
java -cp out PlaintextVsTlsEavesdropDemo fixed
java -Djavax.net.debug=ssl:handshake -cp out PlaintextVsTlsEavesdropDemo fixed 2>&1 | grep -E "Produced|Consuming"
```

**Quan sát.** Bản `broken`: 38 byte, mật khẩu đọc được. Bản `fixed`: 692 byte rác, `TLSv1.3 TLS_AES_256_GCM_SHA384`.
Lệnh thứ ba in trình tự bắt tay của bài 3.

**Đọc code.** Trong `main`, chỉ có dòng tạo `ServerSocket` và dòng tạo client socket khác nhau giữa hai chế độ.

**Câu hỏi.** Relay vẫn biết gì về kết nối TLS? (Hai đầu, kích thước, thời điểm. Và tên miền qua SNI nếu client gửi.)

## Lab 2 — Hash và HMAC

```bash
java -cp out IntegrityHashVsHmacDemo broken
java -cp out IntegrityHashVsHmacDemo fixed
```

**Nguyên nhân.** SHA-256 không có khoá: kẻ sửa nội dung tự tính lại. HMAC cần secret.

**Câu hỏi.** HMAC có chặn được việc gửi lại nguyên gói hợp lệ không? (Không. Đó là Lab 8.)

## Lab 3 — ECB và GCM

```bash
java -cp out EncryptionEcbVsGcmDemo broken
java -cp out EncryptionEcbVsGcmDemo fixed
```

**Quan sát.** ECB: hai khối bản mã giống nhau, và chép khối 3 đè khối 1 giải mã ra lệnh khác **không lỗi**. GCM: lật
một bit là `AEADBadTagException: Tag mismatch`.

**Bài tập.** Trong `encryptGcm`, thay nonce ngẫu nhiên bằng 12 byte 0. Dòng "same plaintext encrypted twice" đổi thế
nào, và vì sao đó là lỗi nghiêm trọng với GCM?

## Lab 4 — Trao đổi khoá và kẻ đứng giữa

```bash
java -cp out KeyExchangeMitmDemo broken
java -cp out KeyExchangeMitmDemo fixed
```

**Quan sát.** `alice and bob share a key: false`, nhưng cả hai đều mã hoá AES-GCM với tag hợp lệ, và Mallory đổi số tài
khoản. Bản `fixed`: chữ ký Ed25519 trên khoá dùng một lần làm lộ việc thay khoá.

**Câu hỏi.** Trong TLS, cái gì đóng vai "khoá định danh Alice đã có từ trước"? (Chứng chỉ server, tin được nhờ CA trong
truststore. Đó là Lab 5.)

## Lab 5 — Kiểm tra chứng chỉ

```bash
java -cp out TlsCertificateValidationDemo broken
java -cp out TlsCertificateValidationDemo fixed
```

**Quan sát.** Bản `broken`: TLS 1.3 thật, token tới tay `O=Attacker`. Bản `fixed`: `PKIX path building failed` với kẻ
mạo danh, server thật vẫn chạy.

**Bài tập.**

1. Trong `DemoTlsSupport.connect`, gọi `connect(..., false)` (tắt kiểm tra hostname), rồi tạo một chứng chỉ do demo CA
   ký cho `CN=other-service` (sửa `create-demo-pki.sh`, không có SAN `localhost`). Server dùng chứng chỉ đó có qua được
   bắt tay không? Bật lại kiểm tra hostname thì sao?
2. Tìm trong codebase của bạn: `X509TrustManager`, `HostnameVerifier`, `setEndpointIdentificationAlgorithm`,
   `InsecureSkipVerify`, `verify=False`, `curl -k`, `--insecure`. Mỗi kết quả cần một lời giải thích.

## Lab 6 — One-way TLS và mTLS

```bash
java -cp out MutualTlsDemo broken
java -cp out MutualTlsDemo fixed
```

**Quan sát.** Bản `broken`: kênh đã mã hoá, vẫn phục vụ rogue vì tin header. Bản `fixed`: rogue không chứng chỉ và rogue
tự ký **đúng CN** đều bị `bad_certificate`.

**Câu hỏi.** Vì sao chứng chỉ tự ký có `CN=order-service` vẫn bị từ chối? (Thứ được kiểm tra là chữ ký của CA trong
truststore, không phải cái tên.)

**Bài tập.** Thêm vào `create-demo-pki.sh` một chứng chỉ client do demo CA ký cho `CN=reporting-service`. Bắt tay có
thành công không, và request được trả gì? (Thành công; 403 vì allow-list chỉ có `order-service`.)

## Lab 7 — Kiểm tra JWT

```bash
java -cp out JwtValidationDemo broken
java -cp out JwtValidationDemo fixed
```

**Quan sát.** Bản `broken` chấp nhận 4 token xấu. Bản `fixed` từ chối từng cái với lý do riêng: chữ ký, thuật toán,
hết hạn, audience.

**Bài tập.** Dán token hợp lệ (in nó ra trong `main`) vào một công cụ decode JWT bất kỳ. Bạn đọc được những gì mà không
cần khoá? Rút ra điều gì về việc đặt dữ liệu vào payload?

## Lab 8 — Chữ ký webhook và phát lại

```bash
java -cp out WebhookSignatureReplayDemo broken
java -cp out WebhookSignatureReplayDemo fixed
```

**Quan sát.** Bản `broken`: body sửa bị chặn (401), nhưng bản phát lại và bản cũ một ngày đều được xử lý: 15000 thay vì
5000. Bản `fixed`: 409 cho bản trùng ID, 408 cho bản ngoài cửa sổ 5 phút.

**Bài tập.**

1. Bỏ kiểm tra event ID, chỉ giữ cửa sổ thời gian. Phát lại ngay lập tức có qua được không?
2. Giữ chữ ký chỉ trên body nhưng vẫn kiểm tra header `X-Timestamp`. Kẻ phát lại sửa header thành thời điểm hiện tại
   thì sao?

## Lab 9 — Giới hạn tần suất

```bash
java -cp out LoginBruteForceRateLimitDemo broken
java -cp out LoginBruteForceRateLimitDemo fixed
```

**Quan sát.** Dòng `responses={200=1, 401=7351}` chính là thứ log của bạn sẽ ghi lại.

**Câu hỏi.** Bản fix mở ra kiểu tấn công nào? (Cố tình đoán sai để khoá tài khoản người khác. Bài 6, mục 6.)

## Lab S — Spring Boot: HTTPS, mTLS, JWT

```bash
cd spring-boot-demo
mvn test
mvn spring-boot:run          # cửa sổ khác
```

```bash
cd ../out/pki
openssl pkcs12 -in client.p12 -passin pass:changeit -nokeys -out client.crt.pem
openssl pkcs12 -in client.p12 -passin pass:changeit -nocerts -nodes -out client.key.pem
U=https://localhost:8443

curl $U/internal/inventory                                                        # 1. không tin CA của server
curl --cacert ca.crt $U/internal/inventory                                        # 2. không chứng chỉ client → 403
curl --cacert ca.crt --cert client.crt.pem --key client.key.pem $U/internal/inventory   # 3. mTLS → 200

T=$(curl -s --cacert ca.crt -X POST "$U/dev/token?subject=alice" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl --cacert ca.crt -H "Authorization: Bearer $T" $U/api/orders                  # 4. JWT → 200
curl --cacert ca.crt -i -H "Authorization: Bearer $T" $U/api/admin/orders         # 5. thiếu scope → 403

TB=$(curl -s --cacert ca.crt -X POST "$U/dev/token?subject=alice&audience=billing-api" | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')
curl --cacert ca.crt -i -H "Authorization: Bearer $TB" $U/api/orders              # 6. sai aud → 401

openssl s_client -connect localhost:8443 -CAfile ca.crt </dev/null                # 7. xem CertificateRequest
```

Kết quả mong đợi và giải thích: bài 7, mục 4–6.

**Bài tập.**

1. Đổi `client-auth: want` thành `need`. Bước 2 và bước 4 thay đổi thế nào? Vì sao?
2. Xoá `audienceValidator` khỏi `jwtDecoder`. Bước 6 trả gì? Test nào trong `mvn test` bắt được thay đổi đó?
3. Thêm `-Djavax.net.debug=ssl:handshake` khi chạy app (`mvn spring-boot:run -Dspring-boot.run.jvmArguments=...`) rồi gọi
   bước 3. Tìm dòng server nhận `Certificate` và `CertificateVerify` của client.

## Tổng kết

| Lab | Một câu cần nhớ |
|-----|-----------------|
| 1 | Mọi thứ trên đường truyền đọc được thứ không mã hoá |
| 2 | Hash không có khoá không chống được kẻ sửa gói tin |
| 3 | "Đã mã hoá" chưa có nghĩa là "không sửa được"; dùng AEAD |
| 4 | Mã hoá mà không xác thực là nói chuyện bí mật với người lạ |
| 5 | Tắt kiểm tra chứng chỉ là tắt TLS |
| 6 | Danh tính phải được chứng minh bằng mật mã, không được tự khai |
| 7 | Decode không phải là validate; kiểm tra cả `alg`, chữ ký, `exp`, `iss`, `aud` |
| 8 | Chữ ký chứng minh ai viết, không chứng minh request mới |
| 9 | Endpoint trả lời nhanh không giới hạn là endpoint dò được |
| S | JSSE lo bắt tay và chứng chỉ; Spring Security lo biến kết quả thành danh tính và quyền |

Quay lại: [README](README.md)
