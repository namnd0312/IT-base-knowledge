# Spring Security — từ con số không đến bảo vệ được một ứng dụng thật

Bộ tài liệu tự học Spring Security trong Spring Boot: hiểu nó chặn request thế nào, đăng nhập và
phân quyền chạy ra sao, vì sao có CSRF/CORS, làm REST API với JWT, và cách test, debug khi bị 401/403.
Kèm một [demo chạy được](../demo/) có 18 test, mọi đoạn code và output trong tài liệu đều lấy từ demo này.

Phiên bản đã kiểm chứng: **Spring Boot 4.1.2, Spring Security 7.1.2, JDK 21**. Phần lớn nội dung đúng
với cả Spring Boot 3.x (Security 6.x); chỗ nào khác sẽ ghi rõ.

English: [../en/README.md](../en/README.md)

## Lộ trình đọc

| # | Tài liệu | Nội dung | Thời gian |
|---|----------|----------|-----------|
| 1 | [01-nen-tang-va-khai-niem-cot-loi.md](01-nen-tang-va-khai-niem-cot-loi.md) | Authentication và authorization, các khái niệm lõi, chuyện gì xảy ra khi thêm starter | 30 phút |
| 2 | [02-kien-truc-filter-chain.md](02-kien-truc-filter-chain.md) | Request đi qua những filter nào, `SecurityContext`, 401 và 403 sinh ra ở đâu | 40 phút |
| 3 | [03-authentication-xac-thuc.md](03-authentication-xac-thuc.md) | `AuthenticationManager`, `UserDetailsService`, lưu mật khẩu, form login, session, logout | 45 phút |
| 4 | [04-authorization-phan-quyen.md](04-authorization-phan-quyen.md) | Luật theo URL, role và authority, `@PreAuthorize`, phân quyền theo dữ liệu | 40 phút |
| 5 | [05-csrf-cors-session-va-header.md](05-csrf-cors-session-va-header.md) | CSRF, CORS, session fixation, security header: bản chất và khi nào được tắt | 40 phút |
| 6 | [06-jwt-va-rest-api-stateless.md](06-jwt-va-rest-api-stateless.md) | JWT là gì, làm API stateless đúng cách, refresh token, các cạm bẫy | 45 phút |
| 7 | [07-oauth2-va-openid-connect.md](07-oauth2-va-openid-connect.md) | OAuth2, OIDC, đăng nhập bằng Google, resource server với Keycloak | 40 phút |
| 8 | [08-testing-debug-va-bang-tra-loi.md](08-testing-debug-va-bang-tra-loi.md) | Viết test bảo mật, bật log, bảng tra lỗi thường gặp, checklist trước khi lên production | 30 phút |
| 9 | [09-thuc-hanh-voi-demo.md](09-thuc-hanh-voi-demo.md) | Lab từng bước bằng `curl`: form login, CSRF, JWT, 401/403, method security | 60–90 phút |

Gợi ý: đọc 1 → 2 rồi làm ngay lab phần A và B của bài 9 để thấy lý thuyết chạy thật, sau đó quay lại 3–8.

Nếu đang kẹt lỗi thật (401, 403, CORS, vòng lặp redirect): đọc thẳng [bảng tra ở bài 8](08-testing-debug-va-bang-tra-loi.md#3-bảng-tra-lỗi-thường-gặp).

## Bản đồ kiến thức

```
                        ┌─────────────────────────────┐
   HTTP request ───────▶│  Security filter chain (02) │
                        └──────────────┬──────────────┘
                 ┌─────────────────────┼─────────────────────┐
                 ▼                     ▼                     ▼
        Bạn là ai? (03)        Bạn được làm gì? (04)   Chống tấn công (05)
        form login, session    luật URL, role,         CSRF, CORS, header,
        JWT (06), OAuth2 (07)  @PreAuthorize           session fixation
                 └─────────────────────┼─────────────────────┘
                                       ▼
                          Test, debug, checklist (08)
```

## Demo

Một ứng dụng Spring Boot có hai "thế giới" bảo mật chạy song song:

| Phần | URL | Cách xác thực | Trạng thái | CSRF |
|------|-----|---------------|------------|------|
| REST API | `/api/**` | JWT trong header `Authorization: Bearer` | Stateless | Tắt |
| Web | còn lại | Form login | Session cookie `JSESSIONID` | Bật |

User có sẵn: `user` / `password` (role USER) và `admin` / `admin123` (role USER, ADMIN).

| File | Minh hoạ |
|------|----------|
| [ApiSecurityConfig.java](../demo/src/main/java/demo/security/config/ApiSecurityConfig.java) | Filter chain stateless, luật theo URL, resource server JWT |
| [WebSecurityConfig.java](../demo/src/main/java/demo/security/config/WebSecurityConfig.java) | Filter chain form login và session |
| [UserAndPasswordConfig.java](../demo/src/main/java/demo/security/config/UserAndPasswordConfig.java) | `UserDetailsService`, `PasswordEncoder`, `AuthenticationManager` |
| [JwtConfig.java](../demo/src/main/java/demo/security/config/JwtConfig.java) | Ký và kiểm tra JWT, đổi claim thành quyền |
| [AuthController.java](../demo/src/main/java/demo/security/auth/AuthController.java), [TokenService.java](../demo/src/main/java/demo/security/auth/TokenService.java) | Đổi username/password lấy JWT |
| [NoteService.java](../demo/src/main/java/demo/security/api/NoteService.java) | `@PreAuthorize`, `@PostAuthorize` (quyền theo chủ sở hữu) |
| [JsonSecurityErrorHandlers.java](../demo/src/main/java/demo/security/error/JsonSecurityErrorHandlers.java) | Trả 401/403 dạng JSON |
| [ApiJwtSecurityTests.java](../demo/src/test/java/demo/security/ApiJwtSecurityTests.java), [WebFormLoginSecurityTests.java](../demo/src/test/java/demo/security/WebFormLoginSecurityTests.java) | Test bảo mật với MockMvc |

## Chạy nhanh

Yêu cầu: JDK 21 và Maven 3.9 trở lên.

```bash
cd springboot/security/demo
mvn test                 # 18 test, phải BUILD SUCCESS
mvn spring-boot:run      # chạy ở http://localhost:8088
```

```bash
# Terminal khác
curl -i http://localhost:8088/api/me            # 401: chưa có token
curl -s -X POST http://localhost:8088/api/auth/login \
     -H 'Content-Type: application/json' \
     -d '{"username":"user","password":"password"}'   # nhận accessToken
```

Demo dùng cổng 8088. Đổi cổng: `mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=9000`.

Secret ký JWT trong [application.yml](../demo/src/main/resources/application.yml) chỉ là giá trị demo.
Dự án thật luôn nạp secret từ biến môi trường hoặc secret manager và không commit vào git.
