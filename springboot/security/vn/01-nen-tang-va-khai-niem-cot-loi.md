# 01 — Nền tảng và khái niệm cốt lõi

Mục tiêu: sau bài này bạn phân biệt được xác thực với phân quyền, gọi đúng tên các khái niệm mà
mọi tài liệu Spring Security dùng, và biết chính xác chuyện gì xảy ra khi thêm starter vào project.

## 1. Bảo mật ứng dụng web trả lời hai câu hỏi

| | Authentication (xác thực) | Authorization (phân quyền) |
|---|---|---|
| Câu hỏi | Bạn là ai? | Bạn được làm gì? |
| Đầu vào | Thông tin chứng minh danh tính: mật khẩu, token, cookie session | Danh tính đã xác thực + tài nguyên đang truy cập |
| Thất bại trả về | **401 Unauthorized** | **403 Forbidden** |
| Ví dụ đời thường | Bảo vệ soát thẻ nhân viên ở cổng | Thẻ của bạn không mở được phòng server |

Xác thực luôn chạy trước. Không biết bạn là ai thì không thể quyết định bạn được làm gì.

Tên mã 401 gây nhầm: "Unauthorized" thực ra nghĩa là **chưa xác thực**. Ghi nhớ:
401 = "tôi không biết bạn là ai", 403 = "tôi biết bạn là ai, và bạn không được phép".

Ngoài hai câu hỏi trên, Spring Security còn lo phần thứ ba: **chống các kiểu tấn công phổ biến**
(CSRF, session fixation, clickjacking...). Phần này phần lớn bật sẵn, xem bài 5.

## 2. Từ vựng bắt buộc

| Khái niệm | Nghĩa | Trong code |
|-----------|-------|------------|
| Principal | Chủ thể đang thao tác: thường là user, cũng có thể là một service | `authentication.getPrincipal()` |
| Credentials | Thứ chứng minh danh tính: mật khẩu, token | `authentication.getCredentials()` |
| Authority | Một quyền được cấp, là một chuỗi: `ROLE_ADMIN`, `product:write` | `GrantedAuthority` |
| Role | Authority có tiền tố `ROLE_`. Chỉ là quy ước đặt tên, không phải kiểu riêng | `hasRole("ADMIN")` khớp `ROLE_ADMIN` |
| Authentication | Object gói cả ba thứ trên + cờ "đã xác thực chưa" | `Authentication` |
| SecurityContext | Nơi giữ `Authentication` của request hiện tại | `SecurityContextHolder.getContext()` |

`Authentication` đóng hai vai, đây là điểm người mới hay rối:

```
Trước khi xác thực:  Authentication { principal="an", credentials="mat-khau", authenticated=false }
                     = lời khai của người dùng, chưa được kiểm tra
                                      │
                                      ▼  AuthenticationManager kiểm tra
Sau khi xác thực:    Authentication { principal=UserDetails(an), credentials=null,
                                      authorities=[ROLE_USER], authenticated=true }
                     = danh tính đã được xác nhận, mật khẩu bị xoá khỏi bộ nhớ
```

Lấy user hiện tại ở bất kỳ đâu trong code:

```java
Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
String username = authentication.getName();
```

Trong controller thì khai báo tham số, Spring tự tiêm (xem [ApiController.java](../demo/src/main/java/demo/security/api/ApiController.java)):

```java
@GetMapping("/me")
public Map<String, Object> me(Authentication authentication, @AuthenticationPrincipal Jwt jwt) { ... }
```

## 3. Stateful và stateless: hai cách "nhớ" người đã đăng nhập

HTTP không có trạng thái: mỗi request là độc lập. Sau khi đăng nhập, server cần một cách nhận ra
request tiếp theo vẫn là người đó.

| | Session (stateful) | Token (stateless) |
|---|---|---|
| Server lưu gì | Session trong RAM/Redis, chứa `SecurityContext` | Không lưu gì |
| Client giữ gì | Cookie `JSESSIONID` (chỉ là mã tra cứu, vô nghĩa nếu đứng riêng) | Token tự chứa thông tin (JWT) |
| Gửi kèm request | Trình duyệt tự gửi cookie | Client tự gắn header `Authorization: Bearer ...` |
| Thu hồi | Dễ: xoá session là xong | Khó: token còn hạn là còn dùng được |
| Nguy cơ đặc trưng | CSRF (vì cookie tự động gửi) | Lộ token (XSS, log) |
| Hợp với | Web render phía server, một domain | REST API cho mobile, SPA, service gọi service |

Không có cách nào "tốt hơn" tuyệt đối. Demo trong bộ tài liệu này chạy cả hai để bạn so sánh.

## 4. Chuyện gì xảy ra khi chỉ thêm starter

Thêm đúng một dependency, chưa viết dòng cấu hình nào:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
```

Spring Boot tự cấu hình theo nguyên tắc **secure by default** (mặc định khoá hết):

| Hành vi mặc định | Chi tiết |
|------------------|----------|
| Mọi endpoint đòi đăng nhập | Kể cả endpoint bạn vừa viết, kể cả `/actuator/**` |
| Tạo sẵn một user | Username `user`, mật khẩu ngẫu nhiên in ra log lúc khởi động: `Using generated security password: ...` |
| Bật form login | Trang `/login` sinh tự động; trình duyệt chưa đăng nhập bị chuyển về đây |
| Bật HTTP Basic | Client gửi header `Authorization: Basic ...` cũng đăng nhập được |
| Bật CSRF | Mọi `POST`/`PUT`/`DELETE` phải kèm CSRF token, không có thì 403 |
| Thêm security header | `X-Content-Type-Options`, `X-Frame-Options: DENY`, `Cache-Control: no-store`... |
| Chống session fixation | Đổi session id sau khi đăng nhập |

Hệ quả thực tế hay gặp ngay ngày đầu:

- Gọi API bằng Postman bị 401: đúng thiết kế, chưa đăng nhập.
- Đã đăng nhập mà `POST` vẫn 403: thiếu CSRF token (bài 5).
- Mật khẩu sinh ngẫu nhiên đổi sau mỗi lần restart: chỉ để thử nghiệm, không dùng cho gì khác.

Ngay khi bạn khai báo bean `SecurityFilterChain` hoặc `UserDetailsService` của riêng mình, phần mặc định
tương ứng lùi lại (user ngẫu nhiên biến mất, luật mặc định bị thay bằng luật của bạn).

## 5. Cấu hình tối thiểu thời hiện đại

Từ Spring Security 6, cấu hình là **khai báo bean `SecurityFilterChain`**, viết theo kiểu lambda:

```java
@Configuration
public class WebSecurityConfig {

    @Bean
    SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/error").permitAll()   // công khai
                        .anyRequest().authenticated())                // còn lại phải đăng nhập
                .formLogin(Customizer.withDefaults())
                .logout(Customizer.withDefaults());
        return http.build();
    }
}
```

Đọc từng dòng: "`/` và `/error` ai cũng vào được; mọi request khác phải đăng nhập; đăng nhập bằng form
mặc định; có đăng xuất". Đây chính là [WebSecurityConfig.java](../demo/src/main/java/demo/security/config/WebSecurityConfig.java) của demo.

### Tài liệu cũ trên mạng: bảng đổi tên

Rất nhiều bài hướng dẫn viết cho Spring Security 5 và không còn biên dịch được. Gặp thì đổi theo bảng:

| Cách cũ (Security 5, Boot 2) | Cách hiện tại (Security 6/7, Boot 3/4) |
|------------------------------|----------------------------------------|
| `extends WebSecurityConfigurerAdapter` + `configure(HttpSecurity)` | Bean `SecurityFilterChain` |
| `configure(AuthenticationManagerBuilder)` | Bean `UserDetailsService` + `PasswordEncoder` |
| `authorizeRequests()` | `authorizeHttpRequests()` |
| `antMatchers(...)`, `mvcMatchers(...)` | `requestMatchers(...)` |
| `http.csrf().disable().and()...` (nối chuỗi bằng `and()`) | Lambda: `http.csrf(csrf -> csrf.disable())` |
| `@EnableGlobalMethodSecurity(prePostEnabled = true)` | `@EnableMethodSecurity` |
| `javax.servlet.*` | `jakarta.servlet.*` |

Riêng Spring Boot 4 đổi tên vài starter so với Boot 3:

| Spring Boot 3 | Spring Boot 4 |
|---------------|---------------|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `spring-boot-starter-oauth2-resource-server` | `spring-boot-starter-security-oauth2-resource-server` |
| `spring-boot-starter-oauth2-client` | `spring-boot-starter-security-oauth2-client` |
| `spring-security-test` (khai báo riêng) | `spring-boot-starter-security-test` |

## 6. Tự kiểm tra

1. Request không kèm thông tin đăng nhập vào URL cần quyền ADMIN: trả 401 hay 403? Vì sao?
2. `hasRole("ADMIN")` và `hasAuthority("ADMIN")` khác nhau thế nào?
3. Vì sao ứng dụng dùng session cookie cần chống CSRF còn API dùng `Authorization` header thì không?
4. Sau khi xác thực xong, `authentication.getCredentials()` trả về gì? Vì sao thiết kế vậy?

Đáp án ngắn: (1) 401, vì chưa biết là ai nên chưa xét tới quyền. (2) `hasRole` tự thêm tiền tố nên tìm
`ROLE_ADMIN`; `hasAuthority` so khớp nguyên chuỗi `ADMIN`. (3) Trình duyệt tự gửi cookie cho mọi request
tới domain đó, kể cả request do trang độc hại kích hoạt; header `Authorization` thì không tự gửi.
(4) `null`: mật khẩu bị xoá để không nằm lại trong bộ nhớ hay lọt vào log.

Bài tiếp theo: [02 — Kiến trúc filter chain](02-kien-truc-filter-chain.md).
