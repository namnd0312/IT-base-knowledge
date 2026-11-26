# 04 — Authorization: phân quyền

Mục tiêu: sau bài này bạn viết được luật phân quyền theo URL và theo method, chọn đúng tầng để đặt từng loại
luật, và tránh được các lỗi làm hở quyền.

## 1. Hai tầng phân quyền

| | Theo request (URL) | Theo method |
|---|---|---|
| Khai báo | `authorizeHttpRequests` trong `SecurityFilterChain` | `@PreAuthorize`, `@PostAuthorize` trên method |
| Chạy ở | `AuthorizationFilter`, trước controller | Proxy AOP bọc quanh bean |
| Biết gì | URL, HTTP method, user | Tham số method, giá trị trả về, user |
| Hợp cho | Luật thô: "khu `/admin` chỉ cho ADMIN" | Luật mịn: "chỉ chủ đơn hàng mới xem được đơn này" |

Dùng cả hai (phòng thủ nhiều lớp): URL chặn thô ở cổng, method bảo vệ nghiệp vụ dù được gọi từ controller nào.

## 2. Phân quyền theo URL

[ApiSecurityConfig.java](../demo/src/main/java/demo/security/config/ApiSecurityConfig.java):

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
        .requestMatchers("/api/public/**").permitAll()
        .requestMatchers("/api/admin/**").hasRole("ADMIN")
        .anyRequest().authenticated())
```

### Quy tắc số một: luật khớp đầu tiên thắng

Luật được xét **từ trên xuống**. Cụ thể đặt trước, chung đặt sau.

```java
// SAI: dòng đầu khớp mọi thứ, luật ADMIN phía dưới không bao giờ được xét
.requestMatchers("/api/**").authenticated()
.requestMatchers("/api/admin/**").hasRole("ADMIN")

// ĐÚNG
.requestMatchers("/api/admin/**").hasRole("ADMIN")
.requestMatchers("/api/**").authenticated()
```

### Quy tắc số hai: luôn có dòng chốt

Kết thúc bằng `anyRequest().authenticated()` hoặc `anyRequest().denyAll()`. Endpoint mới thêm sau này sẽ mặc định
bị khoá thay vì mặc định mở. Đây là nguyên tắc **deny by default**.

### Các luật có sẵn

| Luật | Cho qua khi |
|------|-------------|
| `permitAll()` | Ai cũng được, kể cả chưa đăng nhập |
| `denyAll()` | Không ai được |
| `authenticated()` | Đã đăng nhập (kể cả qua remember-me) |
| `fullyAuthenticated()` | Đã đăng nhập bằng thông tin thật trong phiên này, không tính remember-me |
| `anonymous()` | Chưa đăng nhập (ví dụ trang đăng ký) |
| `hasRole("ADMIN")` | Có authority `ROLE_ADMIN` |
| `hasAnyRole("ADMIN", "STAFF")` | Có một trong các role |
| `hasAuthority("product:write")` | Có đúng authority đó |
| `hasAnyAuthority(...)` | Có một trong các authority |
| `access(authorizationManager)` | Logic tự viết |

Ví dụ tổng hợp (đã biên dịch trên Spring Security 7.1.2):

```java
http.authorizeHttpRequests(auth -> auth
        .requestMatchers("/css/**", "/js/**").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
        .requestMatchers(HttpMethod.POST, "/api/products").hasAuthority("product:write")
        .requestMatchers("/api/reports/**").hasAnyRole("ADMIN", "STAFF")
        .requestMatchers("/api/internal/**").access(officeHoursOnly())
        .requestMatchers("/actuator/health").permitAll()
        .requestMatchers("/actuator/**").hasRole("ADMIN")
        .requestMatchers("/account/delete").fullyAuthenticated()
        .anyRequest().denyAll());
```

### Cú pháp đường dẫn

| Mẫu | Khớp |
|-----|------|
| `/api/users` | Đúng đường dẫn đó |
| `/api/users/*` | Một đoạn: `/api/users/5`, không khớp `/api/users/5/orders` |
| `/api/users/**` | Mọi cấp bên dưới, và cả `/api/users` |
| `/api/users/{id}` | Một đoạn, có đặt tên biến |

### Luật tự viết với `AuthorizationManager`

```java
static AuthorizationManager<RequestAuthorizationContext> officeHoursOnly() {
    return (Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) -> {
        int hour = java.time.LocalTime.now().getHour();
        boolean granted = authentication.get().isAuthenticated() && hour >= 8 && hour < 18;
        return new AuthorizationDecision(granted);
    };
}
```

`AuthorizationManager` là interface đứng sau mọi quyết định phân quyền của Spring Security hiện đại:
nhận `Authentication` và ngữ cảnh, trả về cho phép hay từ chối.

## 3. Role và authority

Về kỹ thuật chỉ có một thứ: `GrantedAuthority`, là một chuỗi. "Role" là authority bắt đầu bằng `ROLE_`.

| Viết | Tìm authority |
|------|---------------|
| `hasRole("ADMIN")` | `ROLE_ADMIN` |
| `hasAuthority("ROLE_ADMIN")` | `ROLE_ADMIN` |
| `hasAuthority("ADMIN")` | `ADMIN` (không khớp `ROLE_ADMIN`) |
| `User.roles("ADMIN")` | Tạo ra `ROLE_ADMIN` |
| `User.authorities("ADMIN")` | Tạo ra `ADMIN` |

Lỗi 403 "rõ ràng tôi là admin" phần lớn do lệch tiền tố: database lưu `ADMIN`, code kiểm `hasRole("ADMIN")`.
Đừng viết `.hasRole("ROLE_ADMIN")` trong `authorizeHttpRequests`: Spring báo lỗi ngay lúc khởi động vì tiền tố bị lặp.

Cách tổ chức quyền:

| Mô hình | Ví dụ | Khi nào |
|---------|-------|---------|
| Chỉ role | `ROLE_ADMIN`, `ROLE_USER` | Ứng dụng nhỏ, ít loại người dùng |
| Role + permission | Role `EDITOR` gồm `article:read`, `article:write`; code kiểm theo permission | Hệ thống lớn. Thêm role mới chỉ cần ghép permission, không sửa code |

### Phân cấp role

Để ADMIN tự động có mọi quyền của STAFF và USER mà không phải gán cả ba:

```java
@Bean
static RoleHierarchy roleHierarchy() {
    return RoleHierarchyImpl.withDefaultRolePrefix()
            .role("ADMIN").implies("STAFF")
            .role("STAFF").implies("USER")
            .build();
}
```

## 4. Phân quyền theo method

Bật một lần ([ApiSecurityConfig.java](../demo/src/main/java/demo/security/config/ApiSecurityConfig.java)):

```java
@Configuration
@EnableMethodSecurity
public class ApiSecurityConfig { ... }
```

| Annotation | Chạy khi | Dùng để |
|------------|----------|---------|
| `@PreAuthorize` | Trước method | Chặn không cho gọi. Dùng nhiều nhất |
| `@PostAuthorize` | Sau method | Quyết định dựa trên giá trị trả về |
| `@PreFilter` / `@PostFilter` | Trước / sau | Lọc phần tử của collection tham số / kết quả |

Biểu thức viết bằng SpEL:

| Biểu thức | Nghĩa |
|-----------|-------|
| `hasRole('ADMIN')` | Như luật URL |
| `authentication.name` | Username hiện tại |
| `#id`, `#request.ownerId` | Tham số của method |
| `returnObject` | Giá trị trả về (chỉ trong `@PostAuthorize`) |
| `@orderSecurity.isOwner(#id, authentication)` | Gọi method của một bean |

### Ví dụ: quyền theo chủ sở hữu

[NoteService.java](../demo/src/main/java/demo/security/api/NoteService.java):

```java
@PostAuthorize("returnObject.owner == authentication.name or hasRole('ADMIN')")
public Note findById(long id) { ... }

@PreAuthorize("hasRole('ADMIN')")
public void delete(long id) { ... }
```

URL `/api/notes/**` chỉ đòi "đã đăng nhập". Luật "chỉ chủ ghi chú hoặc ADMIN" không viết được ở tầng URL vì
tầng đó không biết ghi chú số 2 thuộc về ai.

Kết quả chạy thật, user `user` đọc ghi chú của `admin`:

```
HTTP/1.1 403
{"status":403,"error":"Forbidden","message":"Khong du quyen truy cap tai nguyen nay"}
```

Log DEBUG tương ứng cho thấy đúng biểu thức nào từ chối:

```
AuthorizationManagerAfterMethodInterceptor : Failed to authorize ... NoteService.findById(long) ...
   ExpressionAuthorizationDecision [granted=false,
   expressionAttribute=returnObject.owner == authentication.name or hasRole('ADMIN')]
```

Thiếu lớp kiểm tra này là lỗ hổng **IDOR / Broken Object Level Authorization**: user đổi số id trên URL và đọc được
dữ liệu của người khác. Nó đứng đầu danh sách OWASP API Security, và không cấu hình URL nào chặn được.

`@PostAuthorize` chỉ dùng cho method **đọc**. Method đã chạy xong rồi mới kiểm tra, nên nếu method có ghi dữ liệu
thì dữ liệu đã bị ghi. Với thao tác ghi, dùng `@PreAuthorize` kèm một bean kiểm tra:

```java
@PreAuthorize("@noteSecurity.isOwner(#id, authentication.name) or hasRole('ADMIN')")
public void update(long id, String content) { ... }
```

### Cạm bẫy của method security

Method security hoạt động nhờ **proxy**. Annotation chỉ có tác dụng khi lời gọi đi qua proxy.

| Tình huống | Annotation có chạy không |
|------------|--------------------------|
| Bean khác gọi vào (`controller` gọi `service.delete()`) | Có |
| Gọi nội bộ trong cùng class (`this.delete()`) | **Không**: không qua proxy |
| Method `private` | **Không** |
| Object tự tạo bằng `new`, không phải Spring bean | **Không** |
| Quên `@EnableMethodSecurity` | **Không**, và không có cảnh báo nào |

Trường hợp cuối nguy hiểm nhất: annotation nằm đó, trông an toàn, nhưng không làm gì. Luôn có test chứng minh
user thiếu quyền bị 403 (bài 8).

## 5. Đặt luật ở đâu

| Loại luật | Tầng |
|-----------|------|
| Công khai hay phải đăng nhập | URL |
| Cả một khu vực theo role (`/admin/**`) | URL |
| Theo dữ liệu: chủ sở hữu, cùng phòng ban, cùng tenant | Method (service) |
| Nghiệp vụ dùng chung bởi nhiều controller, job, listener | Method (service) |
| Lọc dữ liệu trả về theo user | Tốt nhất là ngay trong câu truy vấn (`WHERE owner = :user`) |

Ẩn nút trên giao diện không phải là phân quyền. Mọi kiểm tra phải nằm ở server.

## 6. Tự kiểm tra

1. Thứ tự hai luật `anyRequest().authenticated()` rồi `requestMatchers("/admin/**").hasRole("ADMIN")` có vấn đề gì?
2. Database lưu quyền là `ADMIN`. `hasRole("ADMIN")` trả về gì?
3. Vì sao luật "chỉ chủ đơn hàng xem được đơn" không đặt được ở `authorizeHttpRequests`?
4. Method `a()` trong service gọi `this.b()`, `b()` có `@PreAuthorize("hasRole('ADMIN')")`. User thường gọi `a()` thì sao?

Đáp án ngắn: (1) Spring từ chối khởi động: không được khai báo luật sau `anyRequest()`. Dù có cho phép thì luật
ADMIN cũng không bao giờ được xét. (2) Từ chối, vì nó tìm `ROLE_ADMIN`. (3) Tầng URL không biết đơn hàng thuộc về ai.
(4) `b()` chạy bình thường, không bị kiểm tra, vì lời gọi nội bộ không qua proxy.

Bài tiếp theo: [05 — CSRF, CORS, session và header](05-csrf-cors-session-va-header.md).
