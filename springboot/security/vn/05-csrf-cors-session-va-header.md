# 05 — CSRF, CORS, session và security header

Mục tiêu: sau bài này bạn giải thích được từng cơ chế chống lại kiểu tấn công nào, và quyết định có căn cứ khi nào
được tắt CSRF, cấu hình CORS thế nào, thay vì chép `csrf.disable()` từ trên mạng.

## 1. CSRF (Cross-Site Request Forgery)

### Kiểu tấn công

Bạn đang đăng nhập `bank.com` (trình duyệt giữ cookie session). Bạn mở `evil.com` ở tab khác. Trang đó chứa:

```html
<form action="https://bank.com/transfer" method="post">
  <input name="to" value="ke-tan-cong"><input name="amount" value="50000000">
</form>
<script>document.forms[0].submit()</script>
```

Trình duyệt gửi request tới `bank.com` và **tự động đính kèm cookie session của bạn**. Server thấy session hợp lệ
và thực hiện chuyển tiền. Kẻ tấn công không cần biết mật khẩu hay đọc được cookie; chúng chỉ mượn trình duyệt của bạn.

Điều kiện để CSRF xảy ra: **thông tin xác thực được trình duyệt tự gửi** (cookie, HTTP Basic đã lưu).

### Cách chống: token mà trang khác không biết

```
1. Server sinh CSRF token, gắn với session, nhúng vào form:
     <input name="_csrf" type="hidden" value="qlgmYz1F...">
2. Form hợp lệ gửi lên kèm token → CsrfFilter so khớp → cho qua
3. evil.com không đọc được trang của bank.com (same-origin policy) nên không biết token
   → request giả mạo thiếu token → 403
```

Spring Security bật sẵn cơ chế này cho mọi request thay đổi trạng thái: `POST`, `PUT`, `PATCH`, `DELETE`.
`GET`, `HEAD`, `OPTIONS` được bỏ qua, nên **không bao giờ dùng `GET` để thay đổi dữ liệu**.

Chạy thật trên demo, đã đăng nhập nhưng `POST` thiếu token:

```bash
curl -i -b cookies.txt -X POST http://localhost:8088/web/profile/display-name -d 'value=An'
# HTTP/1.1 403
```

Chi tiết đáng biết: mỗi lần tải trang `/login`, giá trị `_csrf` trong HTML lại khác nhau dù cùng session. Spring che
token thật bằng một giá trị ngẫu nhiên cho mỗi lần hiển thị (chống tấn công BREACH); server vẫn giải ra cùng một token gốc.

### Khi nào được tắt CSRF

| Ứng dụng | Xác thực bằng | CSRF |
|----------|---------------|------|
| Web render server (Thymeleaf, JSP) | Cookie session | **Bật**. Template tự chèn token |
| SPA + backend dùng cookie session | Cookie session | **Bật**, cấu hình kiểu SPA (bên dưới) |
| REST API cho mobile, service khác | `Authorization: Bearer` | Tắt được |
| SPA giữ token trong bộ nhớ, tự gắn header | `Authorization: Bearer` | Tắt được |
| API dùng JWT nhưng **để JWT trong cookie** | Cookie | **Bật**. Vẫn là cookie tự gửi |

Quy tắc: **CSRF phụ thuộc vào việc trình duyệt có tự gửi thông tin xác thực hay không**, không phụ thuộc vào
"stateless" hay "dùng JWT". Demo tắt CSRF cho chain `/api/**` vì chain đó chỉ nhận header `Authorization`:

```java
http.securityMatcher("/api/**")
    .csrf(csrf -> csrf.disable())
    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

Còn chain web giữ nguyên mặc định. Tắt theo từng chain, không tắt toàn ứng dụng.

### CSRF cho SPA dùng cookie session

SPA không có form do server render nên cần cách khác để lấy token: server đặt token vào cookie `XSRF-TOKEN`
(JavaScript đọc được), SPA đọc ra và gửi lại trong header `X-XSRF-TOKEN`. Axios và Angular làm việc này tự động.

```java
http.csrf(csrf -> csrf.spa());   // Spring Security 7
```

Trên Spring Security 6 chưa có `spa()`, cấu hình thủ công bằng `CookieCsrfTokenRepository.withHttpOnlyFalse()`
kèm request handler theo hướng dẫn "Single-Page Applications" trong tài liệu chính thức.

Bỏ qua CSRF cho một nhóm URL (ví dụ webhook do hệ thống ngoài gọi, đã xác thực bằng chữ ký riêng):

```java
http.csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/**"));
```

## 2. CORS (Cross-Origin Resource Sharing)

### CORS không phải cơ chế bảo vệ server

Trình duyệt có **same-origin policy**: JavaScript ở `https://app.example.com` mặc định không đọc được response
từ `https://api.example.com` (khác origin: chỉ cần khác giao thức, domain hoặc cổng). CORS là cách server
**nới lỏng** chính sách đó cho những origin nó tin.

| | CSRF | CORS |
|---|---|---|
| Là gì | Một kiểu tấn công | Một cơ chế nới lỏng của trình duyệt |
| Cấu hình để | Chặn request giả mạo | Cho phép frontend khác origin gọi API |
| Ai thực thi | Server | Trình duyệt |
| `curl`, Postman, mobile app | Vẫn bị kiểm | Không liên quan, không bị chặn |

Lỗi CORS chỉ xuất hiện trong trình duyệt. Postman gọi được mà trình duyệt báo lỗi CORS là hiện tượng bình thường.

### Preflight

Với request "không đơn giản" (có header `Authorization`, `Content-Type: application/json`, method `PUT`/`DELETE`),
trình duyệt gửi trước một request `OPTIONS` để hỏi phép:

```
OPTIONS /api/me
Origin: https://app.example.com
Access-Control-Request-Method: GET
Access-Control-Request-Headers: authorization
        │
        ▼ server trả lời
Access-Control-Allow-Origin: https://app.example.com
Access-Control-Allow-Methods: GET, POST, PUT, DELETE
Access-Control-Allow-Headers: Authorization, Content-Type
        │
        ▼ được phép → trình duyệt mới gửi request thật
```

Preflight **không mang token**. Nếu Spring Security đòi xác thực cả request `OPTIONS` thì preflight nhận 401 và
trình duyệt báo lỗi CORS. Vì vậy CORS phải được xử lý **trước** bước xác thực, bằng `http.cors(...)`.

### Cấu hình

```java
@Bean
CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://app.example.com"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(true);   // cho phép gửi cookie; chỉ bật khi thật sự dùng cookie
    config.setMaxAge(3600L);            // trình duyệt nhớ kết quả preflight 1 giờ

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
}

// trong SecurityFilterChain
http.cors(Customizer.withDefaults());   // dùng bean CorsConfigurationSource ở trên
```

Lưu ý:

- Chỉ cấu hình `@CrossOrigin` hay `WebMvcConfigurer.addCorsMappings` mà quên `http.cors(...)` thì preflight vẫn bị
  Spring Security chặn trước khi tới Spring MVC.
- `allowedOrigins("*")` kèm `allowCredentials(true)` bị cấm theo chuẩn; Spring ném lỗi. Liệt kê origin cụ thể.
- Đừng phản chiếu lại mọi `Origin` nhận được. Làm vậy tương đương cho phép mọi trang web đọc dữ liệu của user đang đăng nhập.
- Origin không có dấu `/` cuối: `https://app.example.com`, không phải `https://app.example.com/`.

## 3. Session fixation

Kiểu tấn công: kẻ tấn công lấy một session id hợp lệ (chưa đăng nhập) từ server, lừa nạn nhân dùng đúng id đó
(qua link, qua cookie cài sẵn), chờ nạn nhân đăng nhập. Nếu server giữ nguyên id, kẻ tấn công giờ nắm một phiên đã đăng nhập.

Cách chống: **đổi session id ngay sau khi đăng nhập thành công**. Spring Security làm sẵn. Quan sát trên demo:
cookie `JSESSIONID` nhận được ở response của `POST /login` khác với cookie trước đó.

```
Trước đăng nhập:  Set-Cookie: JSESSIONID=465474FC788A12D5F2E6102881BA1B8E
Sau POST /login:  Set-Cookie: JSESSIONID=05B9E91983AFA64D2F718745167625CC
```

Không cần cấu hình gì. Chỉ cần đừng tắt nó (`sessionFixation(f -> f.none())`).

## 4. Security header

Spring Security tự thêm vào mọi response. Header thật từ demo:

```
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
```

| Header | Chống gì |
|--------|----------|
| `X-Content-Type-Options: nosniff` | Trình duyệt tự đoán kiểu nội dung rồi chạy file tải lên như script |
| `X-Frame-Options: DENY` | Clickjacking: trang khác nhúng trang bạn vào `<iframe>` vô hình để lừa người dùng bấm |
| `Cache-Control: no-store` | Trang chứa dữ liệu riêng tư bị lưu trong cache của trình duyệt hoặc proxy |
| `X-XSS-Protection: 0` | Tắt bộ lọc XSS cũ của trình duyệt, vốn tự nó gây lỗ hổng. Thay bằng CSP |
| `Strict-Transport-Security` (HSTS) | Bị hạ cấp xuống HTTP. Chỉ được gửi khi request đi qua HTTPS, nên chạy local không thấy |

Header cần tự cấu hình thêm là **Content-Security-Policy**: lớp chống XSS hiệu quả nhất ở phía trình duyệt.

```java
http.headers(headers -> headers
        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
        .frameOptions(frame -> frame.sameOrigin())   // nới ra nếu chính bạn cần nhúng iframe cùng origin
        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)));
```

## 5. XSS và phần Spring Security không làm thay bạn

XSS (Cross-Site Scripting): kẻ tấn công chèn được JavaScript vào trang của bạn, script chạy với quyền của nạn nhân.
Spring Security chỉ giảm thiệt hại (cookie `HttpOnly`, CSP). Phòng chống gốc là việc của tầng hiển thị:

- Luôn escape dữ liệu khi in ra HTML. Thymeleaf `th:text` escape sẵn; `th:utext` thì không.
- Không ghép chuỗi người dùng nhập vào HTML, vào `innerHTML`.
- Kiểm tra và giới hạn dữ liệu đầu vào ở server.

Tương tự, SQL injection do cách viết truy vấn quyết định (dùng tham số hoá), không phải do Spring Security.

## 6. Bảng quyết định nhanh

| Câu hỏi | Trả lời |
|---------|---------|
| Tắt CSRF được không? | Chỉ khi không có thông tin xác thực nào được trình duyệt tự gửi |
| Frontend ở domain khác gọi API bị lỗi CORS? | Khai báo `CorsConfigurationSource` + `http.cors(...)`, liệt kê origin cụ thể |
| Preflight `OPTIONS` bị 401? | Thiếu `http.cors(...)` |
| Cần nhúng trang vào iframe của chính mình? | `frameOptions(f -> f.sameOrigin())` |
| Cookie session lên production? | `HttpOnly`, `Secure`, `SameSite=Lax` trở lên |

## 7. Tự kiểm tra

1. API dùng JWT, nhưng frontend lưu JWT trong cookie `HttpOnly`. Tắt CSRF có an toàn không?
2. Postman gọi API bình thường, trình duyệt báo lỗi CORS. Server có lỗi không?
3. Vì sao `GET /account/delete?id=5` là thiết kế nguy hiểm dù đã bật CSRF?
4. `X-Frame-Options: DENY` chống kiểu tấn công nào?

Đáp án ngắn: (1) Không. Cookie vẫn được trình duyệt tự gửi nên vẫn có CSRF. (2) Server chưa cho phép origin của
frontend; Postman không áp same-origin policy nên không bị ảnh hưởng. (3) CSRF mặc định không kiểm `GET`; một thẻ
`<img src=...>` ở trang khác là đủ để kích hoạt. (4) Clickjacking.

Bài tiếp theo: [06 — JWT và REST API stateless](06-jwt-va-rest-api-stateless.md).
