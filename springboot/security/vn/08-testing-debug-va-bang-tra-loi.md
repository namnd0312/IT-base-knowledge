# 08 — Testing, debug và bảng tra lỗi

Mục tiêu: sau bài này bạn viết được test chứng minh luật bảo mật đúng, biết bật log để tự tìm nguyên nhân 401/403,
và có một bảng tra cho các lỗi gặp nhiều nhất.

## 1. Test bảo mật

Luật bảo mật sai không làm ứng dụng báo lỗi: nó âm thầm mở cửa. Vì vậy **mỗi luật cần ít nhất một test cho trường hợp
bị từ chối**, không chỉ trường hợp được phép.

Dependency (Spring Boot 4; trên Boot 3 là `spring-boot-starter-test` + `spring-security-test`):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

Khung test:

```java
@SpringBootTest
@AutoConfigureMockMvc
class ApiJwtSecurityTests {

    @Autowired
    MockMvc mockMvc;
}
```

`@AutoConfigureMockMvc` trên Boot 4 nằm ở `org.springframework.boot.webmvc.test.autoconfigure`;
trên Boot 3 là `org.springframework.boot.test.autoconfigure.web.servlet`.

### Các cách "đăng nhập" trong test

| Công cụ | Làm gì | Dùng khi |
|---------|--------|----------|
| `@WithMockUser(username = "user", roles = "ADMIN")` | Đặt sẵn một `Authentication` vào `SecurityContext` | Test phân quyền cho chain dùng session; test service có `@PreAuthorize` |
| `.with(user("an").roles("USER"))` | Như trên nhưng theo từng request | Cần nhiều user khác nhau trong một test |
| `.with(jwt())` | Giả lập một JWT đã verify | Test phân quyền cho resource server |
| `.with(csrf())` | Thêm CSRF token hợp lệ | Mọi `POST`/`PUT`/`DELETE` vào chain bật CSRF |
| `formLogin()` | Thực hiện `POST /login` thật | Test chính luồng đăng nhập |
| Gọi endpoint login thật rồi dùng token | Đi hết luồng phát và verify token | Test tích hợp đầu cuối |

### Ví dụ từ demo

Chưa đăng nhập phải nhận 401 đúng định dạng ([ApiJwtSecurityTests.java](../demo/src/test/java/demo/security/ApiJwtSecurityTests.java)):

```java
@Test
void protectedEndpointWithoutTokenReturns401Json() throws Exception {
    mockMvc.perform(get("/api/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("WWW-Authenticate", "Bearer"))
            .andExpect(jsonPath("$.status").value(401));
}
```

Đi hết luồng: đăng nhập thật, lấy token, gọi API:

```java
@Test
void userTokenIsForbiddenOnAdminUrl() throws Exception {
    String token = login("user", "password");

    mockMvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + token))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403));
}
```

Giả lập JWT để test nhanh luật theo chủ sở hữu:

```java
@Test
void otherUserCannotReadSomeoneElsesNote() throws Exception {
    mockMvc.perform(get("/api/notes/2").with(jwt().jwt(j -> j.subject("user"))))
            .andExpect(status().isForbidden());
}

@Test
void adminCanReadAnyNote() throws Exception {
    mockMvc.perform(get("/api/notes/1")
                    .with(jwt().jwt(j -> j.subject("admin"))
                            .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
            .andExpect(status().isOk());
}
```

Luồng form login và CSRF ([WebFormLoginSecurityTests.java](../demo/src/test/java/demo/security/WebFormLoginSecurityTests.java)):

```java
@Test
void formLoginWithCorrectPasswordAuthenticates() throws Exception {
    mockMvc.perform(formLogin().user("user").password("password"))
            .andExpect(authenticated().withUsername("user").withRoles("USER"));
}

@Test
@WithMockUser(username = "user")
void postWithoutCsrfTokenIsForbiddenEvenWhenLoggedIn() throws Exception {
    mockMvc.perform(post("/web/profile/display-name").param("value", "An"))
            .andExpect(status().isForbidden());
}

@Test
@WithMockUser(username = "user")
void postWithCsrfTokenSucceeds() throws Exception {
    mockMvc.perform(post("/web/profile/display-name").param("value", "An").with(csrf()))
            .andExpect(status().isOk());
}
```

### Ma trận test tối thiểu cho mỗi endpoint

| Trường hợp | Kỳ vọng |
|-----------|---------|
| Không có thông tin đăng nhập | 401 (API) hoặc 302 về login (web) |
| Đăng nhập, thiếu quyền | 403 |
| Đăng nhập, đủ quyền | 2xx |
| Đủ quyền nhưng tài nguyên của người khác | 403 hoặc 404 |
| (Chain có CSRF) request ghi thiếu token | 403 |

Lưu ý: `@WithMockUser` và `jwt()` **bỏ qua bước xác thực**. Chúng chứng minh luật phân quyền đúng, không chứng minh
việc đăng nhập hay verify token hoạt động. Luôn giữ vài test đi luồng thật.

## 2. Debug

### Bật log

```yaml
logging:
  level:
    org.springframework.security: DEBUG
```

Hoặc bật tạm khi chạy:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--logging.level.org.springframework.security=DEBUG
```

Log cho biết: chain nào được chọn, filter xác thực kết luận gì, luật nào từ chối. `TRACE` chi tiết hơn nữa
(từng filter được gọi). Chỉ bật ở môi trường phát triển.

### Đọc log theo triệu chứng

Request thành công luôn có cặp:

```
FilterChainProxy : Securing GET /api/me
FilterChainProxy : Secured GET /api/me
```

Có `Securing` mà không có `Secured`: bị chặn trong chain. Nhìn các dòng ở giữa:

| Dòng log | Kết luận |
|----------|----------|
| `Set SecurityContextHolder to anonymous SecurityContext` | Không filter xác thực nào nhận ra user. Thiếu header, sai tiền tố `Bearer`, không gửi cookie |
| `Failed to authenticate since password does not match stored value` | Sai mật khẩu, hoặc mật khẩu lưu sai định dạng |
| `... Signed JWT rejected: Invalid signature` | Token bị sửa, hoặc ký bằng khoá khác với khoá verify |
| `Jwt expired at ...` | Token hết hạn; cũng có thể do lệch đồng hồ giữa các máy |
| `Set SecurityContextHolder to JwtAuthenticationToken [... Granted Authorities=[...]]` | Xác thực thành công. **Đọc danh sách authority ở đây** để so với luật |
| `Failed to authorize ... expressionAttribute=...` | Method security từ chối; biểu thức in ngay trong log |
| `Invalid CSRF token found for ...` | Thiếu hoặc sai CSRF token |

### Quy trình ba câu hỏi khi gặp 401/403

```
1. Request rơi vào chain nào?        → dòng "Will secure ..." lúc khởi động, securityMatcher, @Order
2. Spring nhận ra user là ai, có authority gì?  → dòng "Set SecurityContextHolder to ..."
3. Luật nào áp cho URL/method này?   → thứ tự requestMatchers, @PreAuthorize
```

Phần lớn lỗi lộ ra ở câu 2: authority thực tế khác với cái bạn nghĩ (thiếu tiền tố `ROLE_`, đọc sai claim).

### Endpoint tự soi

Một endpoint trả về chính `Authentication` hiện tại rất hữu ích khi phát triển, như `/api/me` của demo:

```json
{"authorities":["FACTOR_BEARER","ROLE_USER"],"username":"user","tokenExpiresAt":"2026-11-26T16:12:49Z"}
```

## 3. Bảng tra lỗi thường gặp

| Triệu chứng | Nguyên nhân hay gặp | Cách xử lý |
|-------------|---------------------|-----------|
| Mọi request 401 ngay sau khi thêm starter | Hành vi mặc định: khoá toàn bộ | Khai báo `SecurityFilterChain` với luật của bạn |
| `POST` trả 403 dù đã đăng nhập, `GET` thì được | Thiếu CSRF token | Gửi token; hoặc tắt CSRF **nếu** chain đó không dùng cookie (bài 5) |
| 403 dù user "có role admin" | Lệch tiền tố: dữ liệu là `ADMIN`, luật tìm `ROLE_ADMIN` | Thống nhất: `roles("ADMIN")` + `hasRole("ADMIN")`, hoặc `hasAuthority` cả hai phía |
| JWT hợp lệ nhưng `hasRole` luôn 403 | Quyền bị đọc từ claim `scope` thành `SCOPE_...` | Cấu hình `JwtAuthenticationConverter` (bài 6) |
| Chưa đăng nhập, truy cập trang admin: nhận 401/redirect chứ không phải 403 | Đúng thiết kế: ẩn danh bị coi là chưa xác thực | Không cần sửa |
| `There is no PasswordEncoder mapped for the id "null"` | Mật khẩu lưu không có tiền tố `{bcrypt}` | Thêm tiền tố, hoặc dùng thẳng `BCryptPasswordEncoder` |
| Đăng nhập luôn sai dù mật khẩu đúng | Lưu mật khẩu thô nhưng encoder là bcrypt; hoặc băm hai lần | Băm đúng một lần khi đăng ký, bằng cùng encoder |
| "Too many redirects" ở trang login | Trang login tự làm chưa `permitAll()` | `.loginPage("/login").permitAll()` |
| CSS/JS không tải ở trang login | Tài nguyên tĩnh cũng đòi đăng nhập | `requestMatchers("/css/**", "/js/**").permitAll()` |
| Trình duyệt báo lỗi CORS, Postman bình thường | Chưa cho phép origin, hoặc preflight bị chặn | `CorsConfigurationSource` + `http.cors(...)` (bài 5) |
| Trang lỗi trả 401 thay vì nội dung lỗi thật | `/error` đòi đăng nhập | `requestMatchers("/error").permitAll()` |
| `@PreAuthorize` không có tác dụng | Quên `@EnableMethodSecurity`; gọi nội bộ `this.x()`; method `private` | Bài 4, mục cạm bẫy |
| Lỗi quyền từ `@PreAuthorize` trả 500 | `@ExceptionHandler(Exception.class)` nuốt `AccessDeniedException` | Thêm handler riêng ném lại exception đó |
| `@ControllerAdvice` không đổi được định dạng 401/403 | Lỗi sinh ra trong filter, trước controller | Cấu hình `AuthenticationEntryPoint`, `AccessDeniedHandler` (bài 2) |
| Chain thứ hai không bao giờ chạy | Chain đầu không có `securityMatcher` nên nhận hết | Chain cụ thể `@Order` nhỏ hơn, chain chung đặt cuối |
| Filter tự viết chạy cho cả request ngoài chain | Filter là `@Component` nên Boot tự đăng ký với Tomcat | Tạo bằng `new` trong cấu hình |
| `getAuthentication()` là `null` trong `@Async` | `SecurityContext` lưu theo `ThreadLocal` | `DelegatingSecurityContextExecutor`, hoặc truyền user làm tham số |
| Đăng nhập xong vẫn bị coi là chưa đăng nhập (nhiều instance) | Session nằm trong RAM của instance khác | Spring Session + Redis, hoặc chuyển stateless |
| H2 console trắng trang | `X-Frame-Options: DENY` chặn iframe; CSRF chặn form | Chỉ ở môi trường dev: `frameOptions(sameOrigin)`, bỏ qua CSRF cho `/h2-console/**` |
| Swagger UI bị 401 | Chưa mở các đường dẫn của nó | `permitAll()` cho `/swagger-ui/**`, `/v3/api-docs/**` (cân nhắc tắt ở production) |
| Code chép từ mạng không biên dịch (`antMatchers`, `WebSecurityConfigurerAdapter`, `.and()`) | Viết cho Spring Security 5 | Bảng đổi tên ở bài 1 |

## 4. Checklist trước khi lên production

**Xác thực**

- [ ] Mật khẩu băm bằng bcrypt/argon2 qua `PasswordEncoder`; không có mật khẩu thô ở bất kỳ đâu, kể cả log.
- [ ] Thông báo đăng nhập sai không tiết lộ username có tồn tại hay không.
- [ ] Có giới hạn số lần thử đăng nhập (rate limit hoặc khoá tạm) để chống dò mật khẩu.
- [ ] Không còn user mặc định, mật khẩu demo, hay user trong RAM.

**Phân quyền**

- [ ] Luật kết thúc bằng `anyRequest().authenticated()` hoặc `denyAll()`.
- [ ] Mọi truy cập theo id có kiểm tra chủ sở hữu ở tầng service.
- [ ] Mỗi luật có test cho trường hợp bị từ chối.
- [ ] `/actuator/**` chỉ mở `health`; phần còn lại cần quyền hoặc chạy ở cổng nội bộ.

**Phiên và token**

- [ ] Cookie session: `HttpOnly`, `Secure`, `SameSite`.
- [ ] CSRF bật cho mọi chain xác thực bằng cookie.
- [ ] JWT: hạn ngắn, thuật toán chỉ định cứng, kiểm `iss`/`aud`, secret hoặc private key nằm ngoài mã nguồn.
- [ ] Có cách thu hồi quyền truy cập khi tài khoản bị khoá hoặc lộ.

**Hạ tầng và cấu hình**

- [ ] Toàn bộ qua HTTPS; bật HSTS.
- [ ] CORS liệt kê origin cụ thể, không dùng `*` với credentials.
- [ ] Có Content-Security-Policy.
- [ ] Không có secret trong git: kiểm tra `application.yml`, lịch sử commit.
- [ ] Log không chứa mật khẩu, token, cookie; mức log security về `INFO` hoặc cao hơn.
- [ ] Phản hồi lỗi không lộ stack trace hay chi tiết nội bộ.
- [ ] Dependency được cập nhật bản vá bảo mật.

Bài tiếp theo: [09 — Thực hành với demo](09-thuc-hanh-voi-demo.md).
