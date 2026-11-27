# 05 — Authentication và authorization

Mục tiêu: nắm các cách một hệ thống xác lập danh tính của người dùng và của hệ thống khác (Basic, session, OAuth 2.0,
OpenID Connect, JWT), mỗi cách dựa vào lớp mạng nào ở các bài trước, và cách ra quyết định phân quyền (RBAC, ABAC).

Bài này tập trung vào **cơ chế và luồng dữ liệu trên mạng**. Chi tiết cấu hình Spring Security (filter, form login,
CSRF, `@PreAuthorize`) có ở bộ tài liệu [Spring Security](../../springboot/security/vn/README.md) của kho này; bài 7
nối hai phần lại.

## 1. Authentication và authorization

| | Authentication (xác thực) | Authorization (phân quyền) |
|---|---|---|
| Câu hỏi | Bạn là ai? | Bạn được làm gì? |
| Đầu vào | Thông tin chứng minh: mật khẩu, chứng chỉ, token, OTP | Danh tính đã xác thực + tài nguyên + hành động (+ ngữ cảnh) |
| Thất bại | **401 Unauthorized** (tên gây nhầm: thực chất là "unauthenticated") | **403 Forbidden** |
| Ví dụ | mTLS chứng minh caller là `order-service` | `order-service` được `GET /internal/inventory` nhưng không được `DELETE` |

Luôn theo thứ tự: xác thực trước, phân quyền sau, và phân quyền **ở mọi request**, không chỉ ở lúc đăng nhập.

Danh tính có hai loại, cách chứng minh khác nhau:

| Chủ thể | Cách chứng minh phổ biến |
|---------|--------------------------|
| Người dùng (qua trình duyệt, app) | Mật khẩu + MFA → session cookie hoặc token OAuth/OIDC |
| Hệ thống (service, job, đối tác) | Chứng chỉ client (mTLS), OAuth 2.0 client credentials, API key, chữ ký HMAC (bài 6) |

## 2. Basic Authentication

```
GET /api/orders HTTP/1.1
Authorization: Basic YWxpY2U6UzNjcmV0LVBhNTU=          ← Base64("alice:S3cret-Pa55")
```

- Base64 là **mã hoá ký tự, không phải mã hoá bảo mật**: ai đọc được header là có mật khẩu. Chỉ chấp nhận được trên
  TLS (demo 1 cho thấy thiếu TLS thì header này nằm trong tay relay).
- Mật khẩu đi trong **mọi** request: càng nhiều lần gửi, càng nhiều chỗ có thể lộ (log, proxy, heap dump).
- Không có logout, không có hết hạn, không có phạm vi quyền.

Chấp nhận được cho: công cụ nội bộ đơn giản, actuator sau mạng quản trị, lời gọi máy-máy tạm thời. Không dùng cho
người dùng cuối của ứng dụng thật.

## 3. Session-based authentication

```
1. POST /login  (username, password)          ──▶ server kiểm tra, tạo session, lưu ở server
2.              ◀── Set-Cookie: JSESSIONID=8f3a...; HttpOnly; Secure; SameSite=Lax
3. GET /orders   Cookie: JSESSIONID=8f3a...    ──▶ server tra session → biết là alice
```

| Ưu | Nhược |
|----|-------|
| Server kiểm soát hoàn toàn: logout, thu hồi tức thì | Có trạng thái: nhiều instance cần session dùng chung (Redis, Spring Session) hoặc sticky session |
| Session ID là chuỗi ngẫu nhiên, không chứa dữ liệu | Trình duyệt **tự gửi cookie** → phải chống CSRF (bài 6) |
| Phù hợp tự nhiên với web render ở server | Không hợp cho service-to-service |

Thuộc tính cookie bắt buộc: `HttpOnly` (JavaScript không đọc được, giảm hậu quả XSS), `Secure` (chỉ gửi qua HTTPS),
`SameSite` (giảm CSRF). Đổi session ID sau khi đăng nhập (chống session fixation): Spring Security làm sẵn.

## 4. OAuth 2.0

OAuth 2.0 là **khung uỷ quyền**: cho một ứng dụng lấy được quyền truy cập có giới hạn vào tài nguyên, mà không cầm mật
khẩu của người dùng. Nó **không** phải giao thức xác thực người dùng (đó là OIDC, mục 5).

| Vai trò | Ví dụ |
|---------|-------|
| Resource owner | Người dùng alice |
| Client | Ứng dụng web/mobile, hoặc một service |
| Authorization server | Keycloak, Auth0, Okta, Entra ID, Spring Authorization Server |
| Resource server | API của bạn: kiểm tra access token, trả dữ liệu |

### Authorization Code + PKCE (người dùng đăng nhập qua ứng dụng)

```
Trình duyệt      Client (app)                 Authorization server          Resource server
    │ 1. bấm "Đăng nhập"                              │                            │
    │ ◀── 302 tới /authorize?client_id&redirect_uri&scope&state&code_challenge     │
    │ ── 2. alice đăng nhập + MFA trên trang của AS ─▶│                            │
    │ ◀── 3. 302 về redirect_uri?code=abc&state=... ──│                            │
    │ ──────▶ 4. client đổi code:                     │                            │
    │         POST /token code=abc, code_verifier ───▶│  (kênh sau, qua TLS)       │
    │         ◀── access_token (+ refresh_token, id_token)                         │
    │                    5. GET /api/orders  Authorization: Bearer <access_token> ─▶│
    │                                                  kiểm tra chữ ký, iss, aud, exp, scope
```

- Mật khẩu của alice chỉ đi tới authorization server; client không bao giờ thấy.
- `code` đi qua trình duyệt (có thể lộ trong log, lịch sử), nên nó ngắn hạn, dùng một lần, và đổi thành token qua kênh
  TLS trực tiếp. **PKCE** (`code_challenge` = hash của một bí mật `code_verifier` mà chỉ client giữ) bảo đảm kẻ cướp
  được `code` không đổi được nó. Bắt buộc với app mobile/SPA, khuyến nghị cho mọi client.
- `state` chống CSRF trên luồng đăng nhập; `redirect_uri` phải khớp chính xác danh sách đã đăng ký.
- Luồng Implicit và Password grant đã bị loại trong khuyến nghị hiện hành (OAuth 2.0 Security BCP, OAuth 2.1).

### Client Credentials (service gọi service)

```
order-service ── POST /token  grant_type=client_credentials, xác thực client (secret, private_key_jwt hoặc mTLS) ──▶ AS
              ◀── access_token (scope=inventory:read, aud=inventory-api)
order-service ── GET /inventory  Authorization: Bearer ... ──▶ inventory-api
```

Không có người dùng. Service xác thực chính nó với AS và nhận token có phạm vi hẹp. So với mTLS trực tiếp:

| | mTLS | Client credentials |
|---|---|---|
| Danh tính ở | Tầng kết nối | Tầng ứng dụng (token) |
| Phân quyền theo | CN/SAN của chứng chỉ (tự quản lý allow-list) | `scope`, `aud` do AS cấp tập trung |
| Thu hồi | Chứng chỉ ngắn hạn / CRL | Token ngắn hạn, vô hiệu client ở AS |
| Đi qua proxy giải mã TLS | Mất danh tính (bài 3) | Không ảnh hưởng |

Hai cách bổ sung cho nhau: nhiều hệ thống dùng mTLS cho kênh **và** token cho quyền. RFC 8705 còn gắn token với chứng
chỉ client (certificate-bound token): token bị đánh cắp không dùng được nếu thiếu khoá riêng.

### Các loại token

| Token | Cho ai | Đặc điểm |
|-------|--------|----------|
| Access token | Resource server | Ngắn hạn (phút); gửi trong `Authorization: Bearer` |
| Refresh token | Authorization server | Dài hạn hơn; đổi lấy access token mới; phải lưu kỹ, xoay vòng mỗi lần dùng |
| ID token (OIDC) | Client | Cho client biết người dùng là ai; **không** gửi tới API |

"Bearer" nghĩa là ai cầm token thì dùng được, như tiền mặt. Vì vậy token chỉ đi qua TLS, thời hạn ngắn, không ghi vào
log, không đặt trong URL.

## 5. OpenID Connect

OIDC là lớp **xác thực** đặt trên OAuth 2.0: thêm scope `openid`, **ID token** (luôn là JWT), endpoint `userinfo` và
cơ chế khám phá cấu hình:

```
https://issuer.example/.well-known/openid-configuration   → issuer, authorization_endpoint, token_endpoint, jwks_uri...
https://issuer.example/.well-known/jwks.json              → các khoá công khai để kiểm tra chữ ký token
```

ID token trả lời "ai vừa đăng nhập, ở đâu, lúc nào, cho client nào":

```json
{ "iss": "https://issuer.example", "sub": "248289761001", "aud": "my-web-app",
  "exp": 1795803339, "iat": 1795802439, "nonce": "n-0S6_WzA2Mj", "email": "alice@example.com" }
```

Client phải kiểm tra chữ ký, `iss`, `aud` = client_id của mình, `exp`, và `nonce` khớp giá trị nó đã gửi (chống phát lại).

Ranh giới cần giữ: **ID token cho client, access token cho API.** API nhận ID token thay access token là sai: `aud` của
ID token là client, không phải API.

## 6. JWT

JWT (JSON Web Token) là định dạng token tự chứa: `header.payload.signature`, mỗi phần Base64URL.

```
eyJraWQiOiJkZW1vLWtleSIsImFsZyI6IlJTMjU2... . eyJpc3MiOiJodHRwczovL2F1dGguZGVtby5sb2Nh... . <chữ ký>
{"kid":"demo-key","alg":"RS256"}               {"iss":"https://auth.demo.local","sub":"alice","aud":"orders-api",
                                                 "exp":1795803339,"iat":1795802439,"scope":"orders:read"}
```

(Lấy từ demo Spring Boot ở bài 7.)

- Payload chỉ **được ký, không được mã hoá**: ai cũng đọc được. Không đặt bí mật hay dữ liệu cá nhân nhạy cảm vào đó
  (muốn mã hoá thì cần JWE).
- Ưu điểm lớn: resource server kiểm tra **cục bộ** bằng khoá công khai, không cần gọi về AS cho mỗi request.
- Nhược điểm tương ứng: không thu hồi được trước `exp`. Bù lại bằng access token ngắn hạn (5–15 phút) + refresh token,
  hoặc dùng opaque token + introspection khi cần thu hồi tức thì.

| Thuật toán | Khoá | Ai tạo được token | Dùng khi |
|------------|------|-------------------|----------|
| HS256 | Một secret chung (HMAC) | **Mọi** bên có secret, kể cả mọi API kiểm tra token | Một service tự phát và tự kiểm tra |
| RS256 / PS256 | RSA: AS giữ khoá riêng, API giữ khoá công khai | Chỉ AS | Nhiều API, AS độc lập (mặc định của hầu hết AS) |
| ES256 | ECDSA P-256 | Chỉ AS | Như RS256, token ngắn hơn |

### Kiểm tra JWT gồm những gì

[JwtValidationDemo.java](../demos/JwtValidationDemo.java) viết tay từng bước bằng JDK để thấy rõ. Bản `broken` làm điều
mà rất nhiều code tự viết làm: Base64-decode payload rồi tin nó.

```
mode=broken
valid token (alice, USER)       -> ACCEPTED as sub=alice role=USER
payload edited to role=ADMIN    -> ACCEPTED as sub=alice role=ADMIN
alg=none, no signature          -> ACCEPTED as sub=mallory role=ADMIN
expired one hour ago            -> ACCEPTED as sub=alice role=USER
issued for audience billing-api -> ACCEPTED as sub=alice role=USER
RESULT: WRONG (4 forged or unusable tokens were accepted)
```

Bản `fixed`:

```java
if (!"HS256".equals(claim(header, "alg"))) return "REJECTED: ...";            // 1. server chọn thuật toán, không phải token
byte[] expected = hmac(parts[0] + "." + parts[1]);                           // 2. chữ ký trên đúng byte header.payload
if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) return "REJECTED: ...";
// chỉ từ đây payload mới đáng đọc
if (exp <= now) ...                                                          // 3. thời hạn
if (!ISSUER.equals(iss)) ...                                                 // 4. đúng issuer
if (!AUDIENCE.equals(aud)) ...                                               // 5. token phát cho CHÍNH API này
```

```
mode=fixed
valid token (alice, USER)       -> ACCEPTED as sub=alice role=USER
payload edited to role=ADMIN    -> REJECTED: signature does not match
alg=none, no signature          -> REJECTED: algorithm 'none' is not the configured HS256
expired one hour ago            -> REJECTED: expired
issued for audience billing-api -> REJECTED: audience 'billing-api' is not orders-api
RESULT: CORRECT (only the genuine, unexpired token for this API is accepted)
```

Dòng cuối đáng chú ý nhất: token **thật**, chữ ký hợp lệ, cùng issuer, chỉ khác `aud`. Thiếu kiểm tra audience thì mọi
token phát cho bất kỳ API nào của công ty đều dùng được ở API của bạn. Spring Security mặc định **không** kiểm tra `aud`;
bài 7 cấu hình nó.

Các bẫy kinh điển khác:

- **Nhầm thuật toán** (algorithm confusion): server chấp nhận cả HS256 lẫn RS256 và dùng khoá công khai RSA làm secret
  HMAC; kẻ tấn công ký HS256 bằng chính khoá công khai (vốn ai cũng có). Chống: cố định thuật toán theo khoá.
- Lấy khoá theo `jku`/`x5u` trong header token: kẻ tấn công trỏ tới khoá của hắn. Chỉ lấy khoá từ `jwks_uri` đã cấu hình.
- Secret HS256 ngắn, đoán được: bị dò offline từ một token bất kỳ. Tối thiểu 256 bit ngẫu nhiên.

Đừng tự viết như demo trong code thật: dùng `NimbusJwtDecoder` của Spring Security, nó làm các bước trên và xoay vòng
khoá theo JWKS.

## 7. RBAC và ABAC

| | RBAC (Role-Based) | ABAC (Attribute-Based) |
|---|---|---|
| Quyết định dựa trên | Vai trò của chủ thể: `ADMIN`, `USER` | Thuộc tính của chủ thể, tài nguyên, hành động, môi trường |
| Ví dụ luật | `ADMIN` được xoá đơn hàng | Được xem đơn hàng nếu `order.ownerId == user.id`, hoặc `user.department == order.department` và trong giờ làm việc |
| Ưu | Đơn giản, dễ kiểm toán | Biểu đạt được luật chi tiết, theo từng bản ghi |
| Nhược | Bùng nổ vai trò khi luật phức tạp | Khó kiểm thử, khó trả lời "ai được làm gì" |
| Trong Spring | `hasRole('ADMIN')`, `hasAuthority('SCOPE_orders:read')` | `@PreAuthorize("#order.ownerId == authentication.name")`, `AuthorizationManager` tuỳ biến |

Thực tế thường kết hợp: RBAC/scope để chặn thô ở tầng URL, ABAC để kiểm tra quyền sở hữu ở tầng dữ liệu. Thiếu tầng
thứ hai là lỗi IDOR/BOLA, lỗ hổng số một của API theo OWASP: người dùng đã xác thực đổi `/orders/1001` thành
`/orders/1002` và đọc được đơn của người khác.

Với token OAuth: `scope` là quyền **client được uỷ quyền**, không phải quyền của người dùng. API vẫn phải kiểm tra người
dùng trong token có quyền với **bản ghi cụ thể** đó không.

## 8. Chọn cơ chế

| Tình huống | Chọn |
|------------|------|
| Web app render ở server, một domain | Session cookie (`HttpOnly`, `Secure`, `SameSite`) + CSRF token |
| SPA / mobile, đăng nhập người dùng | OIDC Authorization Code + PKCE; API nhận access token |
| Service gọi service trong hệ thống của bạn | mTLS, và/hoặc OAuth client credentials |
| Đối tác bên ngoài gọi API của bạn | OAuth client credentials, hoặc API key + chữ ký HMAC (bài 6) |
| Bạn nhận webhook | Chữ ký HMAC + timestamp (bài 6) |
| Công cụ nội bộ, script | Basic auth qua TLS, hoặc token cá nhân có hạn |

## 9. Tự kiểm tra

1. Một API trả 401 cho request có token hợp lệ nhưng thiếu scope. Đúng hay sai?
2. Vì sao Base64 trong Basic auth không bảo vệ mật khẩu?
3. Vì sao PKCE cần thiết khi đã có `client_secret`? Và khi client là app mobile?
4. API của bạn nhận ID token thay vì access token. Sai ở đâu?
5. Tại sao HS256 không hợp khi 10 microservice cùng kiểm tra token do một AS phát?

Đáp án ngắn: (1) Sai; thiếu quyền là 403 (`insufficient_scope`), 401 dành cho token thiếu/sai/hết hạn. (2) Base64 đảo
ngược được bởi bất kỳ ai; chỉ TLS bảo vệ được nó trên đường truyền. (3) PKCE bảo đảm chỉ đúng phiên đã bắt đầu luồng mới
đổi được `code`; app mobile không giữ được secret (ai cũng giải nén được app), nên PKCE là bảo vệ duy nhất. (4) `aud` của
ID token là client, nó dùng để client biết người dùng là ai; API phải kiểm tra access token có `aud` là chính API.
(5) Cả 10 service đều phải có secret, nên bất kỳ service nào bị chiếm cũng **tạo** được token hợp lệ cho cả hệ thống;
với RS256 các service chỉ có khoá công khai.

Bài tiếp theo: [06 — Bảo mật API](06-bao-mat-api.md).
