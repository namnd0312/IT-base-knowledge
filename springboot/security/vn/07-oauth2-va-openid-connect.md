# 07 — OAuth2 và OpenID Connect

Mục tiêu: sau bài này bạn hiểu OAuth2 giải quyết bài toán gì, phân biệt được các vai trò và luồng, biết ứng dụng Spring
của mình đang đóng vai nào và cần starter nào. Bài này thiên về bản đồ khái niệm; phần chạy thật cần một nhà cung cấp
danh tính (Google, Keycloak) nên không nằm trong demo.

## 1. Bài toán

Ứng dụng in ảnh muốn lấy ảnh từ Google Photos của bạn. Cách tệ: bạn đưa mật khẩu Google cho ứng dụng đó.
Nó sẽ có toàn quyền trên tài khoản của bạn, và bạn chỉ thu hồi được bằng cách đổi mật khẩu.

OAuth2 là giao thức **uỷ quyền**: bạn cho phép ứng dụng thứ ba truy cập một phần tài nguyên của bạn, trong thời gian
giới hạn, **mà không đưa mật khẩu**. Thứ ứng dụng nhận được là một access token có phạm vi (scope) hẹp.

## 2. Bốn vai trò

| Vai trò | Là ai | Trong ví dụ |
|---------|-------|-------------|
| Resource Owner | Người sở hữu dữ liệu | Bạn |
| Client | Ứng dụng muốn truy cập dữ liệu | Ứng dụng in ảnh |
| Authorization Server | Nơi xác thực user và phát token | Google Accounts, Keycloak, Auth0 |
| Resource Server | API giữ dữ liệu, nhận token | Google Photos API |

Ứng dụng Spring Boot của bạn có thể đóng một hoặc nhiều vai:

| Vai của ứng dụng bạn | Tình huống | Starter (Spring Boot 4) | DSL |
|----------------------|-----------|-------------------------|-----|
| Client | "Đăng nhập bằng Google"; gọi API của bên khác thay mặt user | `spring-boot-starter-security-oauth2-client` | `oauth2Login()`, `oauth2Client()` |
| Resource Server | API của bạn nhận access token do nơi khác phát | `spring-boot-starter-security-oauth2-resource-server` | `oauth2ResourceServer()` |
| Authorization Server | Tự vận hành nơi phát token | `spring-boot-starter-oauth2-authorization-server` | (cấu hình riêng) |

Demo của bộ tài liệu này là một resource server tự phát token cho chính mình: một dạng rút gọn, đủ dùng cho một service đơn lẻ.

## 3. OAuth2 và OpenID Connect

| | OAuth2 | OpenID Connect (OIDC) |
|---|---|---|
| Mục đích | Uỷ quyền: "ứng dụng này được làm gì" | Xác thực: "user này là ai" |
| Thứ nhận được | Access token | Access token + **ID token** |
| ID token | Không có | Một JWT chứa thông tin user: `sub`, `email`, `name` |
| Quan hệ | Nền tảng | Một lớp mỏng xây trên OAuth2 |

"Đăng nhập bằng Google" là OIDC. Access token dành cho API (resource server) đọc; ID token dành cho client đọc để
biết ai vừa đăng nhập. Đừng gửi ID token làm thông tin xác thực tới API.

## 4. Các luồng (grant type)

| Luồng | Dùng khi | Trạng thái |
|-------|----------|------------|
| **Authorization Code + PKCE** | Có người dùng: web, SPA, mobile | Chuẩn, dùng cho mọi trường hợp có user |
| **Client Credentials** | Service gọi service, không có user | Chuẩn |
| Refresh Token | Lấy access token mới khi hết hạn | Chuẩn, đi kèm các luồng trên |
| Device Code | Thiết bị không có bàn phím (TV, CLI) | Chuẩn |
| Implicit | SPA đời cũ: token trả thẳng trên URL | **Bỏ**. Thay bằng Authorization Code + PKCE |
| Resource Owner Password | Client tự thu username, password | **Bỏ**. Phá vỡ đúng mục tiêu của OAuth2 |

### Authorization Code + PKCE từng bước

```
User         Client (ứng dụng của bạn)        Authorization Server          Resource Server
 │ bấm "Đăng nhập"      │                              │                           │
 │─────────────────────▶│                              │                           │
 │                      │ 1. sinh code_verifier (ngẫu nhiên), giữ lại              │
 │                      │    code_challenge = SHA256(code_verifier)                │
 │  2. 302 tới /authorize?client_id=...&redirect_uri=...                           │
 │     &scope=openid profile&state=...&code_challenge=...                          │
 │◀─────────────────────│                              │                           │
 │ 3. đăng nhập và đồng ý cấp quyền (mật khẩu chỉ nhập ở đây)                      │
 │────────────────────────────────────────────────────▶│                           │
 │  4. 302 về redirect_uri?code=...&state=...          │                           │
 │◀────────────────────────────────────────────────────│                           │
 │─────────────────────▶│                              │                           │
 │                      │ 5. POST /token: code + code_verifier (+ client_secret)   │
 │                      │─────────────────────────────▶│                           │
 │                      │ 6. access_token, id_token, refresh_token                 │
 │                      │◀─────────────────────────────│                           │
 │                      │ 7. GET /api/... Authorization: Bearer <access_token>     │
 │                      │─────────────────────────────────────────────────────────▶│
```

Vì sao đi vòng qua `code` thay vì trả token luôn ở bước 4: URL ở bước 4 đi qua trình duyệt, nằm trong lịch sử và log.
`code` chỉ dùng được một lần, sống vài chục giây, và vô dụng nếu thiếu `code_verifier`. Token thật chỉ đi qua kênh
server tới server ở bước 5 và 6.

| Tham số | Chống gì |
|---------|----------|
| `state` | CSRF trên chính luồng đăng nhập: client kiểm tra `state` trả về khớp với cái đã gửi |
| `code_challenge` / `code_verifier` (PKCE) | Kẻ chặn được `code` không đổi được ra token vì không có `code_verifier` |
| `redirect_uri` đăng ký trước | Authorization server chỉ trả `code` về địa chỉ đã đăng ký, không về trang của kẻ tấn công |

Spring Security tự lo `state`, PKCE, đổi code lấy token. Bạn không tự viết các bước này.

## 5. Ứng dụng là Client: đăng nhập bằng Google

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          google:
            client-id: ${GOOGLE_CLIENT_ID}
            client-secret: ${GOOGLE_CLIENT_SECRET}
            scope: openid, profile, email
```

```java
http
    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
    .oauth2Login(Customizer.withDefaults());
```

- Google, GitHub, Facebook, Okta có sẵn cấu hình endpoint; chỉ cần `client-id` và `client-secret`.
- Redirect URI cần đăng ký ở Google Console theo mẫu của Spring: `{baseUrl}/login/oauth2/code/google`.
- Sau khi đăng nhập, principal là `OidcUser`. Đọc bằng `@AuthenticationPrincipal OidcUser user`, rồi `user.getEmail()`.
- Ứng dụng vẫn dùng session để nhớ user sau khi đăng nhập. `oauth2Login` chỉ thay bước "kiểm tra mật khẩu".
- `client-secret` là bí mật thật: nạp từ biến môi trường, không commit.

Nhà cung cấp khác (Keycloak, công ty tự vận hành) thì khai thêm `provider` với `issuer-uri`; Spring tự đọc cấu hình
từ `{issuer-uri}/.well-known/openid-configuration`.

Việc bạn phải tự quyết: ánh xạ user từ Google sang tài khoản và **quyền** trong hệ thống của bạn. Google chỉ cho biết
"đây là ai"; role trong ứng dụng là dữ liệu của bạn.

## 6. Ứng dụng là Resource Server: nhận token từ Keycloak

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/demo
```

```java
http
    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
```

Với `issuer-uri`, Spring tự làm:

1. Tải metadata, tìm địa chỉ JWKS (danh sách public key).
2. Verify chữ ký mỗi token bằng public key tương ứng; tự tải lại khi authorization server xoay khoá.
3. Kiểm tra `exp`, và `iss` phải khớp `issuer-uri`.

So với demo (bài 6): không còn `JwtEncoder`, không còn secret, không còn endpoint `/login` của riêng bạn. Việc phát token
thuộc về authorization server.

Hai điểm hay vướng:

- **Quyền nằm ở claim khác.** Mặc định Spring đọc `scope` thành `SCOPE_xxx`. Keycloak đặt role trong
  `realm_access.roles`. Cần viết `JwtAuthenticationConverter` riêng để đọc đúng chỗ (cùng kỹ thuật như bài 6).
- **Kiểm tra `aud`.** Mặc định không kiểm. Nếu cùng authorization server phát token cho nhiều API, thêm validator
  audience để token của API khác không dùng được ở API của bạn.

### Scope và role

| | Scope | Role |
|---|---|---|
| Giới hạn | **Ứng dụng client** được làm gì thay mặt user | **User** được làm gì |
| Ví dụ | `photos.read` | `ADMIN` |
| Ai cấp | User đồng ý ở màn hình cấp quyền | Quản trị viên hệ thống |

Quyền hiệu lực là phần giao: ứng dụng có scope `orders.write` nhưng user không có quyền sửa đơn thì vẫn bị từ chối.

## 7. Service gọi service: Client Credentials

Không có user. Service A tự xác thực với authorization server bằng `client_id` + `client_secret`, nhận access token,
rồi gọi service B. Service B là resource server như mục 6.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          inventory:
            provider: company-auth
            client-id: order-service
            client-secret: ${ORDER_SERVICE_SECRET}
            authorization-grant-type: client_credentials
            scope: inventory.read
        provider:
          company-auth:
            issuer-uri: https://auth.example.com/realms/demo
```

Spring quản lý việc lấy, lưu tạm và làm mới token qua `OAuth2AuthorizedClientManager`; bạn gắn nó vào HTTP client
(`RestClient`, `WebClient`) để token tự được thêm vào mỗi request.

## 8. Chọn kiến trúc

| Tình huống | Gợi ý |
|-----------|-------|
| Một ứng dụng web, user riêng, render phía server | Form login + session (bài 3). Không cần OAuth2 |
| Một backend API + một frontend/mobile của chính bạn | Tự phát JWT như demo, hoặc session + CSRF kiểu SPA |
| Muốn "đăng nhập bằng Google/GitHub" | `oauth2Login` |
| Nhiều service, nhiều ứng dụng, cần đăng nhập một lần (SSO) | Authorization server riêng (Keycloak...); các API là resource server |
| Service gọi service | Client Credentials |

Tự xây authorization server (đăng ký, quên mật khẩu, MFA, refresh, thu hồi, khoá tài khoản) là một khối lượng lớn
và nhiều chỗ để sai. Khi nhu cầu vượt quá một service, dùng sản phẩm có sẵn thường rẻ và an toàn hơn.

## 9. Tự kiểm tra

1. Access token và ID token khác nhau thế nào, ai là người đọc từng loại?
2. Vì sao bước 4 trả về `code` chứ không trả thẳng access token?
3. API của bạn nhận token từ Keycloak nhưng `hasRole("ADMIN")` luôn 403 dù user có role admin. Vì sao?
4. Service chạy job ban đêm cần gọi API nội bộ. Dùng luồng nào?

Đáp án ngắn: (1) Access token để resource server đọc và quyết định quyền truy cập; ID token để client biết user là ai.
(2) URL đi qua trình duyệt nên dễ lộ; `code` dùng một lần, sống ngắn, và cần `code_verifier` mới đổi được ra token.
(3) Spring mặc định đọc quyền từ claim `scope`; role của Keycloak nằm ở `realm_access.roles`, cần converter riêng.
(4) Client Credentials.

Bài tiếp theo: [08 — Testing, debug và bảng tra lỗi](08-testing-debug-va-bang-tra-loi.md).
