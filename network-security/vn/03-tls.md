# 03 — TLS

Mục tiêu: hiểu TLS ghép các khối mật mã ở bài 2 thành một kênh an toàn như thế nào: bắt tay diễn ra ra sao, session
key sinh ra từ đâu, cipher suite nghĩa là gì, TLS 1.2 khác 1.3 ở điểm nào, và one-way TLS khác mTLS ra sao. Đủ để đọc
được log `javax.net.debug`, output `openssl s_client`, và quyết định cấu hình cho server Java.

## 1. SSL và TLS

| Phiên bản | Năm | Tình trạng |
|-----------|-----|------------|
| SSL 2.0, SSL 3.0 | 1995, 1996 | Đã bị phá (POODLE với SSL 3.0). Cấm dùng |
| TLS 1.0, TLS 1.1 | 1999, 2006 | Đã khai tử (RFC 8996, 2021). JDK tắt mặc định |
| **TLS 1.2** | 2008 | Còn dùng rộng rãi; an toàn **nếu** chỉ bật cipher suite tốt |
| **TLS 1.3** | 2018 | Mặc định hiện nay; bỏ hẳn các lựa chọn yếu |

TLS là tên mới của SSL sau khi IETF tiếp quản. "Chứng chỉ SSL", `SSLContext`, `SSLSocket`, `server.ssl.*` chỉ là tên
gọi cũ còn sót lại; bên dưới đều là TLS. JDK 21 mặc định bật đúng hai phiên bản:

```
enabled protocols: [TLSv1.3, TLSv1.2]
```

## 2. TLS bảo đảm gì

| Bảo đảm | Nhờ | Không có thì |
|---------|-----|--------------|
| Bí mật | Mã hoá AEAD (AES-GCM, ChaCha20-Poly1305) bằng session key | Thiết bị đứng giữa đọc được token, mật khẩu |
| Toàn vẹn | Tag của AEAD + số thứ tự record | Sửa, chèn, phát lại, đảo thứ tự record |
| Xác thực server | Chứng chỉ X.509 + chữ ký `CertificateVerify` | Nói chuyện bí mật với kẻ mạo danh |
| Xác thực client (tuỳ chọn) | mTLS: client cũng trình chứng chỉ | Server không biết service nào đang gọi |

Demo 1 ở chế độ `fixed`: cùng relay đứng giữa, giờ chỉ thấy bản mã.

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

Code gửi/nhận dữ liệu không đổi một dòng; chỉ đổi cách tạo socket. TLS trong suốt với ứng dụng, đó là lý do nó dễ bị
cấu hình sai mà không ai nhận ra.

## 3. Bắt tay TLS 1.3

```
Client                                                         Server
  │ ── ClientHello ─────────────────────────────────────────────▶ │
  │    • phiên bản, cipher suite hỗ trợ                            │
  │    • key_share: khoá công khai ECDHE dùng một lần (X25519...) │
  │    • SNI: tên miền muốn tới (gửi rõ)                           │
  │                                                                │
  │ ◀───────────────────────────────────────────── ServerHello ── │
  │                       cipher suite đã chọn, key_share của server
  │      ── cả hai tính được shared secret → suy ra handshake keys ──
  │ ═══════════════ từ đây mọi thứ đã mã hoá ═══════════════       │
  │ ◀── EncryptedExtensions                                        │
  │ ◀── CertificateRequest            (chỉ khi mTLS)               │
  │ ◀── Certificate                   chuỗi chứng chỉ server       │
  │ ◀── CertificateVerify             chữ ký trên toàn bộ bắt tay  │
  │ ◀── Finished                      MAC trên toàn bộ bắt tay     │
  │                                                                │
  │ ── Certificate + CertificateVerify (chỉ khi mTLS) ───────────▶ │
  │ ── Finished ────────────────────────────────────────────────▶ │
  │ ◀════════════ dữ liệu ứng dụng (application traffic keys) ═══▶ │
  │ ◀── NewSessionTicket (để lần sau resume)                       │
```

Đối chiếu với bài 2:

1. **Trao đổi khoá**: hai `key_share` là Diffie–Hellman dùng một lần (ECDHE). Kẻ nghe lén thấy cả hai mà không tính
   được bí mật chung. Khoá dùng một lần rồi xoá, nên có **forward secrecy**.
2. **Xác thực**: server gửi chứng chỉ (chứa khoá công khai dài hạn) và `CertificateVerify` là chữ ký bằng khoá riêng
   tương ứng trên toàn bộ bản tin bắt tay đã trao đổi. Kẻ đứng giữa thay `key_share` thì chữ ký không còn khớp. Đây
   chính là bản `fixed` của [KeyExchangeMitmDemo](../demos/KeyExchangeMitmDemo.java).
3. **Finished**: MAC trên toàn bộ bắt tay, chống hạ cấp và sửa bản tin.

Bật log của JDK để thấy đúng trình tự đó (client và server cùng chạy trong một JVM nên dòng đan xen):

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

`-Djavax.net.debug=ssl:handshake` (hoặc `all`) là công cụ số một khi debug lỗi TLS trong Java: nó cho thấy phiên bản,
cipher suite, chứng chỉ mỗi bên gửi, và bản tin nào làm bắt tay thất bại.

## 4. Session key

"Session key" là các khoá đối xứng thật sự mã hoá dữ liệu. Không bên nào gửi nó qua mạng: cả hai **tự suy ra**.

```
ECDHE(khoá riêng của mình, key_share của bên kia)  ──▶  shared secret
                                     │
                       HKDF (key schedule TLS 1.3), trộn thêm hash của bản tin bắt tay
                                     │
          ┌──────────────────────────┼──────────────────────────────┐
          ▼                          ▼                              ▼
 handshake traffic keys     application traffic keys        resumption secret
 (mã hoá Certificate...)    client→server, server→client    (cho NewSessionTicket)
                            mỗi chiều một khoá + IV riêng
```

- Hai chiều dùng hai khoá khác nhau; mỗi record có nonce suy từ số thứ tự, nên không bao giờ lặp nonce (yêu cầu sống
  còn của AES-GCM, bài 2).
- Khoá được làm mới được (`KeyUpdate`) trên kết nối sống lâu.
- **Session resumption**: server gửi `NewSessionTicket`; lần kết nối sau client trình lại ticket (PSK) để bỏ qua phần
  xác thực bằng chứng chỉ. Tiết kiệm phép toán bất đối xứng, vẫn kết hợp ECDHE mới nên giữ forward secrecy.
- **0-RTT** (early data) của TLS 1.3 cho gửi dữ liệu ngay trong bản tin đầu khi resume, đổi lại dữ liệu đó **có thể bị
  phát lại**. Chỉ hợp với request idempotent; JDK không bật 0-RTT.

Hệ quả vận hành: forward secrecy nghĩa là dù sau này lộ khoá riêng của chứng chỉ, lưu lượng đã ghi lại vẫn không giải
mã được. Ngược lại, trong TLS 1.2 với "RSA key exchange" (client mã hoá bí mật bằng khoá công khai RSA của server), lộ
khoá riêng là giải mã được **mọi** phiên cũ đã ghi.

## 5. Cipher suite

Cipher suite là tổ hợp thuật toán hai bên thống nhất. Tên của nó đọc được từng phần.

TLS 1.2:

```
TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384
    │     │          │           │
    │     │          │           └ hàm hash cho PRF / HMAC bắt tay
    │     │          └ mã hoá dữ liệu: AES-256 chế độ GCM (AEAD)
    │     └ xác thực server: chứng chỉ ECDSA
    └ trao đổi khoá: ECDHE (có forward secrecy)
```

TLS 1.3 tách việc trao đổi khoá (nhóm `key_share`) và chữ ký (thuật toán chữ ký) ra khỏi tên suite, chỉ còn AEAD + hash:

```
TLS_AES_256_GCM_SHA384      TLS_AES_128_GCM_SHA256      TLS_CHACHA20_POLY1305_SHA256
```

Danh sách JDK 21 bật mặc định (9 trong 37 suite đầu tiên, theo thứ tự ưu tiên):

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

Quy tắc khi phải tự chọn suite cho TLS 1.2: chỉ giữ **ECDHE** (forward secrecy) + **AEAD** (GCM hoặc ChaCha20-Poly1305).
Loại bỏ mọi suite có `TLS_RSA_WITH_` (không forward secrecy), `CBC`, `3DES`, `RC4`, `NULL`, `EXPORT`, `anon`. Phần
`ECDSA`/`RSA` trong tên phải khớp loại khoá của chứng chỉ server: chứng chỉ EC chỉ dùng được suite `ECDHE_ECDSA`.

So với thực tế: cùng server `example.com` khi ép từng phiên bản:

```
## TLS 1.2
Peer signature type: ecdsa_secp256r1_sha256
New, TLSv1.2, Cipher is ECDHE-ECDSA-CHACHA20-POLY1305
## TLS 1.3
Peer signature type: ecdsa_secp256r1_sha256
Negotiated TLS1.3 group: X25519MLKEM768
New, TLSv1.3, Cipher is TLS_AES_256_GCM_SHA384
```

(OpenSSL dùng tên ngắn cho suite TLS 1.2. `X25519MLKEM768` là trao đổi khoá lai cổ điển + hậu lượng tử, bài 2.)

## 6. TLS 1.2 và TLS 1.3

| | TLS 1.2 | TLS 1.3 |
|---|---|---|
| Số RTT trước khi gửi dữ liệu | 2 | 1 (0 khi resume với 0-RTT) |
| Trao đổi khoá | RSA hoặc (EC)DHE | Chỉ (EC)DHE: forward secrecy luôn có |
| Mã hoá | Còn cho CBC, RC4, 3DES... | Chỉ AEAD |
| Chứng chỉ trong bắt tay | Gửi rõ | Mã hoá |
| Số cipher suite | Hàng trăm tổ hợp | 5 |
| Renegotiation | Có (nguồn của nhiều lỗi) | Bỏ; thay bằng `KeyUpdate` và post-handshake auth |
| Chống hạ cấp | Phụ thuộc cấu hình | Có sẵn trong giao thức |

Khuyến nghị: bật cả 1.3 và 1.2 (vì client cũ), ưu tiên 1.3, và với 1.2 chỉ giữ danh sách suite ở mục 5. Tắt hẳn 1.0/1.1.

## 7. One-way TLS và mTLS

| | One-way TLS (HTTPS thông thường) | Mutual TLS (mTLS) |
|---|---|---|
| Ai trình chứng chỉ | Chỉ server | Server **và** client |
| Server biết gì về client | Chỉ IP; danh tính phải lấy ở tầng ứng dụng (mật khẩu, token) | Danh tính đã được mật mã chứng minh ngay khi bắt tay |
| Client cần | Truststore (để tin server) | Truststore + keystore (khoá riêng + chứng chỉ của mình) |
| Server cần | Keystore | Keystore + truststore (CA của client) |
| Dùng cho | Trình duyệt, app di động gọi API công khai | Service-to-service, API đối tác, thiết bị IoT, service mesh |

Cơ chế: server gửi thêm `CertificateRequest` kèm **danh sách CA nó chấp nhận**; client trả `Certificate` +
`CertificateVerify` (chữ ký bằng khoá riêng của client), chứng minh nó giữ khoá riêng chứ không chỉ chép được chứng chỉ.

### Tận mắt: header tự khai so với chứng chỉ

[MutualTlsDemo.java](../demos/MutualTlsDemo.java): một endpoint nội bộ chỉ được phục vụ `order-service`. Bản `broken`
dùng one-way TLS và lấy danh tính caller từ header `X-Service-Name`, một cách làm phổ biến hơn người ta nghĩ.

```
mode=broken server=one-way TLS, identity = X-Service-Name header
real order-service                   -> 200 orders for caller 'order-service'
rogue, no certificate                -> 200 orders for caller 'order-service'
RESULT: WRONG (anyone who can reach the port can claim to be order-service)
```

Kênh được mã hoá, server đã được xác thực, và server vẫn không biết ai đang gọi. Header là dữ liệu do client tự viết.

Bản `fixed`: `setNeedClientAuth(true)`, truststore chứa CA nội bộ, danh tính lấy từ CN của chứng chỉ **đã được kiểm tra**.

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

Dòng thứ ba quan trọng: chứng chỉ tự ký với **đúng** `CN=order-service` vẫn bị từ chối. Thứ được kiểm tra không phải
cái tên, mà là việc chứng chỉ được một CA trong truststore ký. Cấu hình tương đương trong Spring Boot ở bài 7.

Ba điểm vận hành của mTLS:

- **Ai cấp chứng chỉ client**: cần một CA nội bộ và quy trình cấp, xoay vòng (chứng chỉ ngắn hạn), thu hồi (bài 4).
  Service mesh (Istio, Linkerd) tự động hoá việc này.
- **Chứng chỉ hợp lệ chưa phải là được phép**: mọi service có chứng chỉ của CA đều qua được bắt tay. Vẫn phải phân
  quyền theo danh tính (allow-list CN/SAN), như demo làm.
- **TLS kết thúc ở đâu**: nếu load balancer giải mã TLS rồi chuyển HTTP vào trong, ứng dụng không còn thấy chứng chỉ
  client. Hoặc load balancer làm mTLS và chuyển danh tính qua header (khi đó phải bảo đảm chỉ load balancer mới gửi
  được header đó), hoặc dùng TLS passthrough.

## 8. Checklist TLS

- [ ] Chỉ TLS 1.3 và 1.2; với 1.2 chỉ ECDHE + AEAD.
- [ ] Chứng chỉ đúng tên miền (SAN), gửi đủ CA trung gian, tự động gia hạn (bài 4).
- [ ] Client luôn kiểm tra chứng chỉ **và** tên miền; không có `TrustManager` chấp nhận tất cả (demo 5).
- [ ] Lưu lượng nội bộ cũng dùng TLS; service-to-service cân nhắc mTLS.
- [ ] Biết TLS kết thúc ở đâu (load balancer, ingress, sidecar) và đoạn phía sau được bảo vệ thế nào.
- [ ] HTTP chuyển hướng sang HTTPS, bật HSTS cho web.

## 9. Tự kiểm tra

1. Hai bên không gửi session key qua mạng. Vậy chúng có nó bằng cách nào?
2. Vì sao `TLS_RSA_WITH_AES_256_GCM_SHA384` bị loại dù dùng AES-GCM?
3. Kẻ tấn công thay `key_share` của server trong ServerHello. Bước nào phát hiện?
4. Với mTLS, server nhận một chứng chỉ client hợp lệ do CA nội bộ ký cho `CN=reporting-service`. Request có nên được
   phục vụ ở endpoint của `order-service` không?

Đáp án ngắn: (1) Mỗi bên kết hợp khoá riêng ECDHE của mình với `key_share` của bên kia ra cùng một shared secret, rồi
dùng HKDF suy ra các khoá. (2) Trao đổi khoá bằng RSA: không có forward secrecy; lộ khoá riêng server là giải mã được
mọi phiên đã ghi. (3) `CertificateVerify`: chữ ký của server trên bản tin bắt tay không còn khớp với nội dung client đã
thấy (và `Finished` cũng sai). (4) Không; bắt tay chỉ chứng minh danh tính, còn phân quyền vẫn phải kiểm tra
`reporting-service` có trong allow-list của endpoint đó hay không.

Bài tiếp theo: [04 — PKI và chứng chỉ](04-pki-va-chung-chi.md).
