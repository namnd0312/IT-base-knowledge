# 06 — Bảo mật API

Mục tiêu: nắm các cơ chế bảo vệ một API khi nó bị gọi bởi hệ thống khác qua mạng: API key, ký request bằng HMAC,
kiểm tra chữ ký webhook, chống phát lại, chống kẻ đứng giữa, giới hạn tần suất, và CORS/CSRF với API được gọi từ
trình duyệt. Mỗi cơ chế trả lời một câu hỏi khác nhau; hiểu sai câu hỏi là chọn sai cơ chế.

| Cơ chế | Trả lời câu hỏi |
|--------|-----------------|
| TLS | Có ai đọc/sửa trên đường truyền không, server có đúng không |
| API key | Request này của **khách hàng/ứng dụng** nào |
| Chữ ký HMAC | Request này có đúng do người giữ secret tạo ra, và **không bị sửa** không |
| Timestamp + nonce/ID | Request này có **mới** không, hay là bản phát lại |
| Rate limit | Bên này có đang gọi **quá nhiều** không |
| CORS | Trình duyệt có được cho JavaScript của **origin khác** đọc response không |
| CSRF token | Request có đúng do trang của **chính bạn** gửi, không phải trang khác lợi dụng cookie |

## 1. API key

Một chuỗi bí mật định danh client, gửi trong header:

```
GET /v1/orders HTTP/1.1
X-API-Key: sk_live_7Hq2...
```

| Làm đúng | Vì sao |
|----------|--------|
| Sinh bằng `SecureRandom`, ≥ 128 bit, có tiền tố dễ nhận (`sk_live_`) | Không đoán được; công cụ quét secret phát hiện được khi lỡ commit |
| Server lưu **hash** của key (SHA-256 là đủ vì key đã ngẫu nhiên dài), không lưu bản rõ | Lộ database không lộ key |
| So sánh trong thời gian hằng | Không lộ qua thời gian phản hồi |
| Gửi trong header, không trong URL | URL nằm trong log server, log proxy, lịch sử trình duyệt |
| Mỗi key gắn phạm vi (quyền, môi trường) và rate limit riêng | Lộ một key không lộ tất cả |
| Xoay vòng được: cho phép hai key song song trong thời gian chuyển | Thay key không làm gián đoạn |

Giới hạn bản chất: API key là **bearer secret**. Nó định danh ứng dụng, không định danh người dùng; ai cầm nó đều dùng
được; và nó không bảo vệ nội dung request. Với giao dịch giá trị cao, kết hợp với chữ ký HMAC (mục 2) hoặc thay bằng
OAuth client credentials / mTLS (bài 5).

## 2. Ký request bằng HMAC

Thay vì gửi secret, client dùng secret để **ký** request; server tính lại chữ ký với cùng secret rồi so sánh. Secret
không bao giờ đi trên mạng.

```
string_to_sign = METHOD + "\n" + PATH + "\n" + QUERY_ĐÃ_SẮP_XẾP + "\n" + TIMESTAMP + "\n" + SHA256(BODY)
signature      = HMAC-SHA256(secret, string_to_sign)

POST /v1/payments HTTP/1.1
X-Key-Id: client-42
X-Timestamp: 1795802439
X-Signature: 6f1c0e...
```

Điều quyết định độ an toàn là **cái gì nằm trong `string_to_sign`**:

- Phần không được ký thì kẻ đứng giữa (hoặc một proxy lỗi) sửa được mà chữ ký vẫn đúng. Ký method, path, query, body,
  và mọi header có ý nghĩa nghiệp vụ.
- Hai bên phải tạo chuỗi **giống hệt từng byte** (canonicalization): thứ tự query, encoding, khoảng trắng trong JSON.
  Ký trên **byte thô của body**, đừng ký trên object đã parse rồi serialize lại.
- Có timestamp trong chuỗi ký để chống phát lại (mục 4).

AWS Signature V4 là ví dụ trưởng thành của mô hình này. Khác với chữ ký số (bài 2), HMAC dùng secret chung: server cũng
tạo được chữ ký, nên nó chứng minh "một trong hai bên" chứ không chống chối bỏ.

## 3. Kiểm tra chữ ký webhook

Webhook đảo chiều quan hệ: **nhà cung cấp** (Stripe, GitHub, cổng thanh toán) gọi vào endpoint công khai của **bạn**.
Ai trên Internet cũng gửi được request tới URL đó, nên endpoint webhook không có chữ ký là endpoint cho phép bất kỳ ai
"báo thanh toán thành công".

Nhà cung cấp cấp cho bạn một secret và ký mỗi request. Hình dạng phổ biến (Stripe dùng `t=...,v1=...`):

```
X-Timestamp: 1795802439
X-Signature: HMAC-SHA256(secret, timestamp + "." + raw_body)
```

Phía nhận, theo thứ tự:

1. Đọc **body thô** (byte), trước khi bất kỳ framework nào parse JSON.
2. Tính HMAC trên `timestamp + "." + raw_body`, so sánh thời gian hằng với chữ ký. Sai → 401.
3. Kiểm tra timestamp trong cửa sổ cho phép (thường 5 phút). Ngoài cửa sổ → từ chối.
4. Kiểm tra **event ID chưa xử lý** (lưu trong DB với unique constraint). Đã có → trả 2xx nhưng không xử lý lại.
5. Xử lý, trả 2xx nhanh; việc nặng đưa vào hàng đợi. (Nhà cung cấp sẽ gửi lại khi bạn timeout, đó là thêm một lý do cần bước 4.)

## 4. Replay attack

Chữ ký hợp lệ chứng minh **ai đã viết** request, không chứng minh request **mới**. Kẻ nào bắt được một request hợp lệ
(log, proxy, bản dump, một máy client bị chiếm) gửi lại nguyên văn thì chữ ký vẫn đúng.

[WebhookSignatureReplayDemo.java](../demos/WebhookSignatureReplayDemo.java): một nhà cung cấp thanh toán gọi
`POST /webhooks/payment` có chữ ký. Kẻ tấn công có một request thật, và gửi ba thứ: body đã sửa, nguyên request đó, và
một request bắt được hôm qua.

Bản `broken` ký chỉ body và không nhớ gì:

```
mode=broken signedContent=body
genuine event               -> 200 processed
tampered body, same sig     -> 401 bad signature
replay of the same request  -> 200 processed
captured request, 1 day old -> 200 processed
account credited: 15000 cents (expected 5000)
RESULT: WRONG (replayed requests carry a valid signature and were processed again)
```

HMAC làm đúng việc của nó (body bị sửa bị chặn), và tài khoản vẫn được cộng tiền ba lần.

Bản `fixed`: timestamp nằm **trong** nội dung được ký (nên không sửa được), có cửa sổ thời gian, và mỗi event ID chỉ
xử lý một lần.

```java
if (timestamp == null || !matches(sign(timestamp + "." + body), signature)) return 401;      // xác thực
if (Math.abs(now - Long.parseLong(timestamp)) > TOLERANCE.toSeconds())    return 408;      // còn mới
return processedEventIds.add(field(body, "eventId")) ? 200 : 409;                           // chưa xử lý
```

```
mode=fixed signedContent=timestamp.body
genuine event               -> 200 processed
tampered body, same sig     -> 401 bad signature
replay of the same request  -> 409 event id already processed
captured request, 1 day old -> 408 timestamp outside the 5-minute window
account credited: 5000 cents (expected 5000)
RESULT: CORRECT (one genuine event, credited once)
```

Hai lớp bổ sung cho nhau: cửa sổ thời gian giới hạn **bao lâu** một bản bắt được còn dùng được; ID/nonce chặn phát lại
**trong** cửa sổ đó (và chỉ cần nhớ ID trong khoảng thời gian bằng cửa sổ). Trong demo các mã 408/409 được chọn để dễ
đọc; với webhook thật nên trả 2xx cho bản trùng để nhà cung cấp ngừng gửi lại.

Phát lại không chỉ là chuyện webhook: TLS 1.3 0-RTT (bài 3), JWT bị đánh cắp (dùng lại tới `exp`), ID token (`nonce`),
lệnh chuyển tiền. Quy tắc chung: thao tác không idempotent cần **idempotency key** do client sinh.

## 5. Man-in-the-middle

MITM là kẻ chủ động đứng trên đường truyền: đọc, sửa, chặn, chèn. Các bài trước đã cho thấy từng mảnh:

| Kẻ đứng giữa vào được bằng | Bị chặn bởi | Demo |
|---------------------------|-------------|------|
| Đọc lưu lượng không mã hoá | TLS | 1 |
| Sửa nội dung có kèm hash | MAC/HMAC, AEAD | 2, 3 |
| Thay khoá công khai trong trao đổi khoá | Chữ ký trên khoá (chứng chỉ) | 4 |
| Trình chứng chỉ tự tạo | Client kiểm tra chuỗi tới CA tin cậy + hostname | 5 |
| Giả làm một service nội bộ | mTLS | 6 |
| Sửa token / tự tạo token | Chữ ký JWT + `alg` cố định | 7 |
| Phát lại request đã ký | Timestamp + nonce/ID | 8 |

Các điểm còn lại cần nhớ khi thiết kế API:

- **Mọi lỗ hổng MITM thực tế đều bắt đầu ở phía client**: `TrustManager` chấp nhận tất cả, tắt kiểm tra hostname,
  truststore bị thêm CA lạ. Review code client gọi API kỹ như code server.
- **Proxy giải mã TLS của doanh nghiệp** là một MITM hợp pháp: nó có CA riêng được cài vào máy nhân viên. Service của
  bạn chạy trong mạng đó sẽ thấy chứng chỉ do CA của proxy ký.
- **Certificate pinning** (client chỉ chấp nhận một khoá công khai cụ thể) chặt hơn kiểm tra CA, đổi lại rủi ro tự khoá
  mình khi xoay chứng chỉ. Hợp với app mobile gọi API của chính mình; hiếm khi đáng cho service-to-service (dùng CA
  nội bộ + mTLS thay thế).
- **SSL stripping** với trình duyệt: giữ người dùng ở HTTP. Chặn bằng HSTS.

## 6. Rate limiting

API trả lời nhanh và không giới hạn là API bị dò được (đoán mật khẩu, liệt kê ID, cào dữ liệu) và bị làm sập được.

[LoginBruteForceRateLimitDemo.java](../demos/LoginBruteForceRateLimitDemo.java): endpoint đăng nhập bảo vệ bằng mã PIN
4 số.

```
mode=broken pinSpace=10,000 limit=none
attempts=7,352 elapsed=1840 ms responses={200=1, 401=7351}
pin found: 7351
RESULT: WRONG (the whole PIN space can be tried in seconds)
```

Sau 5 lần sai thì khoá 60 giây, và khi đang khoá **không so sánh** PIN (đoán trúng cũng không biết):

```
mode=fixed pinSpace=10,000 limit=5 failures then locked 60 s
attempts=10,000 elapsed=2186 ms responses={401=5, 429=9995}
pin found: no
RESULT: CORRECT (only 5 guesses were evaluated, the rest got 429)
```

| Thuật toán | Cách hoạt động | Đặc điểm |
|------------|----------------|----------|
| Fixed window | Đếm trong mỗi phút | Đơn giản; dồn được 2× giới hạn ở ranh giới hai cửa sổ |
| Sliding window | Đếm trong 60 giây gần nhất | Chính xác hơn, tốn bộ nhớ hơn |
| **Token bucket** | Xô N token, nạp đều r token/giây; mỗi request tốn 1 | Cho phép bùng nổ ngắn, giữ tốc độ trung bình. Phổ biến nhất |

Khoá giới hạn theo gì quyết định ai bị ảnh hưởng: theo API key/client ID (công bằng giữa khách hàng), theo người dùng,
theo IP (bị botnet vượt qua; nhiều người thật chung một IP sau NAT), theo tài khoản bị nhắm (chặn đoán mật khẩu, nhưng
cho kẻ khác **cố tình khoá** tài khoản nạn nhân). Thường cần kết hợp, với thời gian chờ tăng dần thay vì khoá cứng.

Trả `429 Too Many Requests` kèm `Retry-After`. Với nhiều instance, bộ đếm phải dùng chung (Redis, hoặc đặt ở API
gateway/ingress). Trong Java thường dùng Bucket4j hoặc Resilience4j `RateLimiter`, hoặc để gateway lo.

## 7. CORS và CSRF

Hai cơ chế này chỉ liên quan khi **trình duyệt** gọi API. Service-to-service không có CORS hay CSRF.

### CORS

Same-origin policy: JavaScript của `https://shop.example` **gửi** được request tới `https://api.example`, nhưng
trình duyệt **không cho đọc** response, trừ khi API trả header cho phép:

```
Access-Control-Allow-Origin: https://shop.example
Access-Control-Allow-Credentials: true
```

Request "không đơn giản" (method `PUT`/`DELETE`, header `Authorization`, `Content-Type: application/json`) được trình
duyệt hỏi trước bằng **preflight** `OPTIONS`.

| Sai lầm | Hậu quả |
|---------|---------|
| Phản chiếu mọi `Origin` gửi lên kèm `Allow-Credentials: true` | Bất kỳ trang nào cũng đọc được dữ liệu của người dùng đang đăng nhập |
| Coi CORS là cơ chế xác thực/chặn request | CORS chỉ quyết định trình duyệt có **cho đọc** response; curl, server, script không quan tâm CORS |

Cấu hình: danh sách origin tường minh, không dùng `*` khi có credentials.

### CSRF

Trình duyệt **tự gửi cookie** theo domain đích, kể cả khi request xuất phát từ trang khác:

```html
<!-- trên https://evil.example -->
<form action="https://bank.example/transfer" method="POST">
  <input name="to" value="mallory"><input name="amount" value="5000">
</form>
<script>document.forms[0].submit()</script>
```

Nếu `bank.example` xác thực bằng session cookie, request này mang cookie của nạn nhân và được thực hiện.

| API xác thực bằng | Có nguy cơ CSRF | Biện pháp |
|-------------------|-----------------|-----------|
| Cookie (session, hoặc JWT đặt trong cookie) | **Có** | CSRF token (synchronizer token), `SameSite=Lax/Strict`, kiểm tra `Origin` |
| `Authorization: Bearer` do JavaScript gắn | Không: trình duyệt không tự gắn header này | Tắt CSRF ở chain đó là hợp lý |
| mTLS, API key trong header, HMAC | Không (với client không phải trình duyệt) | |

Đó là lý do demo Spring ở bài 7 tắt CSRF cho các chain dùng bearer token và chứng chỉ client, và lý do không được làm
vậy với chain dùng session. Chi tiết cấu hình CSRF/CORS trong Spring:
[Spring Security bài 5](../../springboot/security/vn/05-csrf-cors-session-va-header.md).

## 8. Checklist API

- [ ] Chỉ HTTPS; client của mọi lời gọi ra ngoài kiểm tra chứng chỉ và hostname.
- [ ] Xác thực mọi endpoint trừ những endpoint liệt kê tường minh là công khai; phân quyền ở cả mức bản ghi (chống IDOR).
- [ ] API key lưu dạng hash, gửi trong header, có phạm vi và xoay vòng được.
- [ ] Request giá trị cao và webhook có chữ ký trên body thô + timestamp; có cửa sổ thời gian và lưu ID đã xử lý.
- [ ] Thao tác không idempotent nhận idempotency key.
- [ ] Rate limit theo client/người dùng; 429 + `Retry-After`; bộ đếm dùng chung giữa các instance.
- [ ] CORS liệt kê origin tường minh; CSRF bật cho mọi chain dùng cookie.
- [ ] Không ghi token, API key, chữ ký, body nhạy cảm vào log.

## 9. Tự kiểm tra

1. Webhook đã có chữ ký HMAC đúng. Vì sao vẫn cộng tiền ba lần trong bản `broken`?
2. Vì sao timestamp phải nằm trong nội dung được ký, không chỉ là một header bên cạnh?
3. Server tính chữ ký trên `objectMapper.writeValueAsString(parsedBody)`. Vấn đề gì sẽ xảy ra?
4. API chỉ nhận `Authorization: Bearer`. Có cần CSRF token không? Nếu chuyển token vào cookie thì sao?
5. Bật CORS `Access-Control-Allow-Origin: *` có làm API "mở cho mọi người gọi" không?

Đáp án ngắn: (1) Chữ ký chứng minh ai viết, không chứng minh request mới; không có timestamp và không nhớ ID đã xử lý
thì mọi bản gửi lại đều hợp lệ. (2) Header không được ký thì kẻ phát lại sửa timestamp thành "bây giờ" là qua được kiểm
tra thời gian. (3) Serialize lại có thể đổi thứ tự field, khoảng trắng, định dạng số; chữ ký thật bị báo sai, hoặc tệ
hơn, hai cách hiểu khác nhau của cùng một body. Luôn ký trên byte thô. (4) Không, vì trình duyệt không tự gắn header đó;
đặt token vào cookie thì trình duyệt tự gửi nên lại cần chống CSRF. (5) Không; CORS chỉ quyết định trình duyệt có cho
JavaScript của origin khác đọc response; mọi client ngoài trình duyệt vốn đã gọi được. Phần nguy hiểm là kết hợp với
credentials.

Bài tiếp theo: [07 — Triển khai trong Java và Spring Boot](07-trien-khai-java-va-spring-boot.md).
