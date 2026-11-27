# 01 — Nền tảng mạng: dữ liệu đi qua những gì

Mục tiêu: nắm đủ mạng để trả lời chính xác câu hỏi "khi service A gọi service B, ở mỗi chặng ai thấy được gì, ai sửa
được gì, và lớp nào chịu trách nhiệm bảo vệ". Bài này không dạy lại mạng từ đầu; nó chọn đúng những phần mà các bài
TLS, PKI, xác thực ở sau dựa vào.

## 1. Mô hình OSI và TCP/IP: TLS nằm ở đâu

| OSI | TCP/IP | Giao thức | Đơn vị / địa chỉ | Ghi chú bảo mật |
|-----|--------|-----------|------------------|-----------------|
| 7 Application | Application | HTTP, gRPC, DNS, SMTP | URL, header | Xác thực người dùng, phân quyền (bài 5, 6) |
| 6 Presentation | ↑ | **TLS** (thường xếp ở đây hoặc "giữa 4 và 7") | | Mã hoá, toàn vẹn, xác thực server/client (bài 3) |
| 5 Session | ↑ | | | |
| 4 Transport | Transport | TCP, UDP, QUIC (trên UDP) | Port | Không mã hoá, không xác thực |
| 3 Network | Internet | IP, ICMP | Địa chỉ IP | IP nguồn giả được; IPsec nếu cần mã hoá ở tầng này |
| 2 Data link | Link | Ethernet, Wi-Fi, ARP | MAC | ARP không xác thực: dễ chen vào giữa trong LAN |
| 1 Physical | ↑ | Cáp, sóng | | |

```
┌──────────┬──────────┬──────────┬────────────────────────────────────────────┐
│ Ethernet │ IP       │ TCP      │ TLS record                                  │
│ MAC      │ IP nguồn │ port     │ ┌────────────────────────────────────────┐ │
│          │ IP đích  │          │ │ HTTP: GET /orders  Authorization: ...   │ │ ← chỉ phần này được mã hoá
│          │          │          │ └────────────────────────────────────────┘ │
└──────────┴──────────┴──────────┴────────────────────────────────────────────┘
  đổi mỗi chặng   lộ suốt đường   lộ                 bí mật với mọi thiết bị ở giữa
```

Ba hệ quả cần nhớ:

1. Các giao thức tầng 2–4 **không có mã hoá hay xác thực**. Mọi bảo đảm về bí mật và danh tính đến từ TLS (hoặc
   SSH, IPsec) và từ tầng ứng dụng.
2. TLS che nội dung HTTP (URL, header, body, cookie, token), **không** che IP, port, kích thước và thời điểm gói, và
   tên miền (trường SNI gửi rõ trong bản tin đầu tiên).
3. Ở mỗi chặng (Wi-Fi, switch, router, load balancer, proxy, service mesh sidecar) đều có thiết bị đọc được những gì
   chưa mã hoá. Giả định an toàn: **mọi thứ trên đường truyền đều đọc và sửa được gói tin** của bạn.

## 2. TCP và UDP

| | TCP | UDP |
|---|---|---|
| Kết nối | Có (bắt tay 3 bước) | Không |
| Bảo đảm | Đủ, đúng thứ tự, gửi lại khi mất | Không |
| Dùng cho | HTTP/1.1, HTTP/2, JDBC, gRPC | DNS, QUIC (HTTP/3), WireGuard, streaming |
| Bảo mật đi kèm | TLS | DTLS, hoặc QUIC (TLS 1.3 tích hợp sẵn) |
| Giả IP nguồn | Khó: phải nhận được SYN-ACK | Dễ: một gói đơn là đủ (nền của tấn công khuếch đại) |

## 3. Bắt tay 3 bước, và cái giá thật của một kết nối HTTPS

```
Client                                   Server
  │ ── SYN (seq=x) ────────────────────────▶ │
  │ ◀── SYN-ACK (seq=y, ack=x+1) ─────────── │      1 RTT: TCP
  │ ── ACK (ack=y+1) ──────────────────────▶ │
  │ ── TLS ClientHello ────────────────────▶ │
  │ ◀── ServerHello ... Finished ─────────── │      +1 RTT: TLS 1.3 (TLS 1.2: +2 RTT)
  │ ── Finished + HTTP request ────────────▶ │
  │ ◀── HTTP response ────────────────────── │      +1 RTT: request thật
```

- Bắt tay TCP chỉ đồng bộ số thứ tự. "Kết nối đã mở" **không** có nghĩa ai đã xác thực ai.
- Port trả SYN-ACK là *open*, trả RST là *closed*, im lặng là *filtered* (firewall bỏ gói): đó là cách quét port.
- Server giữ trạng thái cho mỗi SYN chờ ACK; gửi hàng loạt SYN rồi bỏ đi là SYN flood (Linux chống bằng SYN cookie).

Với server Java, ý nghĩa thực tế: một kết nối HTTPS mới tốn **2 RTT + phép toán bất đối xứng** trước khi gửi được
byte dữ liệu đầu tiên. Vì vậy:

- Dùng connection pool và keep-alive (`HttpClient`, `RestClient`, pool JDBC) thay vì mở kết nối mới cho mỗi request.
- TLS session resumption giảm chi phí bắt tay lại (bài 3).
- Đặt **connect timeout và read timeout** cho mọi lời gọi ra ngoài. Một kết nối không có timeout là một thread có thể
  bị giữ vô hạn.

## 4. DNS

DNS đổi tên miền thành IP. Gần như mọi kết nối bắt đầu bằng một truy vấn DNS.

```bash
dig +noall +answer example.com A
```

```
example.com.		205	IN	A	104.20.23.154
example.com.		205	IN	A	172.66.147.243
```

DNS truyền thống chạy trên UDP 53, **không mã hoá, không xác thực**: người trên đường truyền thấy bạn hỏi tên gì, và
kẻ trả lời giả được thì dẫn bạn tới IP của hắn. Có DNSSEC (ký bản ghi) và DoT/DoH (mã hoá chặng tới resolver), nhưng
không thể giả định chúng luôn có mặt.

Đây là lý do thiết kế quan trọng nhất của TLS: **client không tin DNS, nó kiểm tra danh tính server bằng chứng chỉ**.
Bị dẫn sai IP thì bắt tay thất bại, miễn là client kiểm tra chứng chỉ và tên miền (bài 4, demo 5). Tắt kiểm tra chứng
chỉ là biến mọi lỗi DNS thành một cuộc MITM thành công.

Lưu ý cho Java: JVM cache kết quả DNS (`networkaddress.cache.ttl`); trong môi trường IP thay đổi nhanh (Kubernetes,
cloud) giá trị này ảnh hưởng tới việc client có theo kịp IP mới hay không.

## 5. HTTP và HTTPS

```bash
curl -v http://127.0.0.1:39100/          # python3 -m http.server 39100 --bind 127.0.0.1
```

```
> GET / HTTP/1.1
> Host: 127.0.0.1:39100
> User-Agent: curl/8.7.1
> Accept: */*
>
< HTTP/1.0 200 OK
< Server: SimpleHTTP/0.6 Python/3.13.3
< Content-type: text/html; charset=utf-8
```

HTTP là văn bản: những dòng trên đi trên dây đúng như vậy, kể cả `Authorization`, `Cookie`, body chứa mật khẩu.

| | HTTP | HTTPS (HTTP trên TLS) |
|---|---|---|
| Nội dung (URL path, header, body) | Lộ, sửa được | Mã hoá, phát hiện được sửa đổi |
| Server là ai | Không biết | Xác thực bằng chứng chỉ |
| Lộ ra | Tất cả | IP, port, tên miền (SNI), kích thước, thời điểm |
| Port mặc định | 80 | 443 |

HTTP/2 trên thực tế luôn đi kèm TLS; HTTP/3 chạy trên QUIC, nơi TLS 1.3 nằm sẵn bên trong giao thức.

### Tận mắt: một thiết bị đứng giữa đọc được gì

[PlaintextVsTlsEavesdropDemo.java](../demos/PlaintextVsTlsEavesdropDemo.java) dựng client, server và một **relay** ở
giữa. Relay đóng vai bất kỳ thiết bị nào trên đường truyền: chuyển tiếp trung thực và giữ một bản sao.

```bash
cd network-security
./demos/create-demo-pki.sh            # một lần: tạo CA, chứng chỉ server/client cho các demo TLS
javac -d out demos/*.java
java -cp out PlaintextVsTlsEavesdropDemo broken
```

```
mode=broken transport=plain TCP
client sent:      LOGIN user=alice password=S3cret-Pa55
server replied:   OK welcome alice
eavesdropper saw: 38 bytes: LOGIN user=alice password=S3cret-Pa55.
password visible on the wire: true
RESULT: WRONG (anyone on the path can read the password)
```

Client và server không có cách nào biết mình bị đọc. Chế độ `fixed` (TLS) ở bài 3.

## 6. Mỗi lớp cung cấp gì: bảng nối sang các bài sau

| Câu hỏi | Ai trả lời | Bài |
|---------|-----------|-----|
| Dữ liệu có bị đọc/sửa trên đường truyền không | TLS (AES-GCM, ECDHE) | 2, 3 |
| Server có đúng là `api.bank.com` không | Chứng chỉ X.509, CA, truststore | 4 |
| Client (service) có đúng là `order-service` không | mTLS, hoặc token client credentials | 3, 4, 5 |
| Người dùng là ai, được làm gì | Session, OAuth 2.0, OIDC, JWT, RBAC/ABAC | 5 |
| Request có bị giả, bị phát lại, bị gửi dồn không | HMAC signature, timestamp, rate limit | 6 |
| Cấu hình tất cả những thứ trên trong Java/Spring | `SSLContext`, SSL bundle, filter chain, resource server | 7 |

## 7. Lệnh dùng xuyên suốt

| Lệnh | Dùng để |
|------|---------|
| `dig <tên>` | Hỏi DNS |
| `nc -vz <host> <port>` | Port TCP có mở không |
| `lsof -nP -iTCP -sTCP:LISTEN` / `ss -tlnp` | Máy đang nghe ở port nào, địa chỉ nào (`0.0.0.0` = mọi card mạng) |
| `curl -v <url>` | Xem request/response và bắt tay TLS |
| `openssl s_client -connect host:443 -servername host` | Xem chuỗi chứng chỉ, phiên bản và cipher suite TLS |
| `keytool -list -v -keystore x.p12` | Xem nội dung keystore/truststore |
| `tcpdump` / Wireshark | Bắt gói (cần root) |

## 8. Tự kiểm tra

1. Dùng HTTPS, người quản lý mạng còn biết được gì về request của bạn?
2. Vì sao TLS không cần tin DNS?
3. Vì sao giả IP nguồn với UDP dễ hơn với TCP?
4. Một service Java gọi API ngoài mất trung bình 300 ms dù API chỉ xử lý 20 ms. Nghi ngờ đầu tiên là gì?

Đáp án ngắn: (1) IP đích, port, tên miền (SNI, DNS), kích thước và thời điểm; không thấy path, header, body. (2) Client
kiểm tra chứng chỉ được CA tin cậy ký cho đúng tên miền; kẻ giả DNS không có private key của chứng chỉ đó. (3) TCP đòi
bên gửi nhận SYN-ACK (gửi về IP thật) mới hoàn tất bắt tay; UDP không bắt tay. (4) Mở kết nối TCP + TLS mới cho mỗi
lời gọi (vài RTT + phép toán bất đối xứng); dùng connection pool/keep-alive.

Bài tiếp theo: [02 — Mật mã học](02-mat-ma-hoc.md).
