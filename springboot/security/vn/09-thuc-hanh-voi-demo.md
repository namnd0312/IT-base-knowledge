# 09 — Thực hành với demo

Mỗi phần đi theo cùng một vòng: **gửi request → quan sát kết quả → đối chiếu với code → giải thích bằng lý thuyết**.
Output mẫu lấy từ Spring Boot 4.1.2, Spring Security 7.1.2, JDK 21. Giá trị token, session id của bạn sẽ khác.

## Chuẩn bị

Dùng hai terminal.

**T1** chạy ứng dụng với log DEBUG:

```bash
cd springboot/security/demo
mvn spring-boot:run -Dspring-boot.run.arguments=--logging.level.org.springframework.security=DEBUG
```

Chờ dòng `Started SecurityDemoApplication`. Kéo lên trên tìm hai dòng `Will secure ...`: đó là danh sách filter
của hai chain (giải thích ở [bài 2](02-kien-truc-filter-chain.md)).

**T2** gửi request:

```bash
B=http://localhost:8088
```

User có sẵn: `user` / `password` (USER), `admin` / `admin123` (USER, ADMIN).

Bản đồ endpoint:

| Endpoint | Chain | Yêu cầu |
|----------|-------|---------|
| `GET /` | Web | Công khai |
| `GET /web/home` | Web | Đăng nhập |
| `POST /web/profile/display-name` | Web | Đăng nhập + CSRF token |
| `POST /api/auth/login` | API | Công khai |
| `GET /api/public/hello` | API | Công khai |
| `GET /api/me` | API | Token hợp lệ |
| `GET /api/admin/stats` | API | Role ADMIN |
| `GET /api/notes/{id}` | API | Chủ ghi chú hoặc ADMIN |
| `DELETE /api/notes/{id}` | API | Role ADMIN |

---

## Phần A — Form login, session và CSRF

Code: [WebSecurityConfig.java](../demo/src/main/java/demo/security/config/WebSecurityConfig.java)

### A1. Trang công khai và trang cần đăng nhập

```bash
curl -i $B/
curl -i -c cookies.txt $B/web/home
```

```
HTTP/1.1 200
Trang chu cong khai. Thu vao /web/home de bi chuyen sang /login.

HTTP/1.1 302
Set-Cookie: JSESSIONID=AD68BE146C4700E87F39046E300D5C76; Path=/; HttpOnly
Location: http://localhost:8088/login
```

Giải thích: `/` khớp `permitAll()`. `/web/home` rơi vào `anyRequest().authenticated()`; user đang ẩn danh nên
`ExceptionTranslationFilter` gọi entry point của form login: chuyển hướng về `/login`. Session được tạo để nhớ URL bạn định vào.

`-c cookies.txt` lưu cookie nhận được; `-b cookies.txt` gửi cookie đã lưu. Hai cờ này đóng vai trình duyệt.

### A2. Lấy CSRF token từ trang login

```bash
CSRF=$(curl -s -b cookies.txt -c cookies.txt $B/login \
       | sed -nE 's/.*name="_csrf" type="hidden" value="([^"]+)".*/\1/p')
echo $CSRF
```

Trang login do Spring sinh có sẵn `<input name="_csrf" type="hidden" value="...">`. Chạy lệnh hai lần sẽ thấy hai giá trị
khác nhau dù cùng session: token được che bằng giá trị ngẫu nhiên mỗi lần hiển thị.

### A3. Đăng nhập thiếu CSRF token, rồi sai mật khẩu

```bash
curl -i -b cookies.txt -X POST $B/login -d "username=user&password=password"
```

```
HTTP/1.1 403
```

Mật khẩu đúng vẫn 403. Ở T1:

```
CsrfFilter : Invalid CSRF token found for http://localhost:8088/login
```

`CsrfFilter` đứng trước `UsernamePasswordAuthenticationFilter` nên request bị chặn trước cả khi mật khẩu được xem xét.

```bash
curl -i -b cookies.txt -c cookies.txt -X POST $B/login \
     --data-urlencode "username=user" --data-urlencode "password=sai" --data-urlencode "_csrf=$CSRF"
```

```
HTTP/1.1 302
Location: http://localhost:8088/login?error
```

### A4. Đăng nhập đúng

```bash
curl -i -b cookies.txt -c cookies.txt -X POST $B/login \
     --data-urlencode "username=user" --data-urlencode "password=password" --data-urlencode "_csrf=$CSRF"
```

```
HTTP/1.1 302
Set-Cookie: JSESSIONID=EBCB28D51CFA106F111EAE0C530E71C5; Path=/; HttpOnly
Location: http://localhost:8088/web/home?continue
```

Hai điều cần thấy:

- `JSESSIONID` **đổi** so với A1: chống session fixation.
- `Location` là `/web/home`, trang bạn định vào lúc đầu, không phải `/`: Spring đã lưu request gốc.

```bash
curl -b cookies.txt $B/web/home
# Xin chao user, ban dang dung session cookie.
```

Server nhận ra bạn chỉ nhờ cookie. Xoá `cookies.txt` là "mất đăng nhập".

### A5. CSRF với request ghi dữ liệu

```bash
curl -i -b cookies.txt -X POST $B/web/profile/display-name -d 'value=An'
# HTTP/1.1 403
```

Đã đăng nhập, vẫn 403: đây chính xác là request mà một trang độc hại có thể bắt trình duyệt của bạn gửi đi.

Lấy token mới rồi gửi lại. Token ở A2 không dùng được nữa vì Spring **đổi CSRF token sau khi đăng nhập**
(log: `CsrfAuthenticationStrategy : Replaced CSRF Token`). Trang xác nhận đăng xuất `GET /logout` có sẵn token mới:

```bash
CSRF=$(curl -s -b cookies.txt $B/logout \
       | sed -nE 's/.*name="_csrf" type="hidden" value="([^"]+)".*/\1/p')

curl -i -b cookies.txt -X POST $B/web/profile/display-name \
     --data-urlencode 'value=An' --data-urlencode "_csrf=$CSRF"
```

```
HTTP/1.1 200
Da doi ten hien thi cua user thanh: An
```

Token cũng gửi được qua header (cách SPA thường làm):

```bash
curl -i -b cookies.txt -X POST $B/web/profile/display-name \
     -H "X-CSRF-TOKEN: $CSRF" --data-urlencode 'value=Binh'
# HTTP/1.1 200
```

### A6. Security header

```bash
curl -s -i -b cookies.txt $B/web/home | grep -E '^(X-|Cache|Pragma|Expires)'
```

```
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
```

Không dòng cấu hình nào trong demo tạo ra chúng: `HeaderWriterFilter` bật mặc định. Ý nghĩa từng header ở [bài 5](05-csrf-cors-session-va-header.md#4-security-header).

### A7. Hai chain không dùng chung cách xác thực

```bash
curl -i -b cookies.txt $B/api/me
# HTTP/1.1 401
```

Đang đăng nhập ở phần web nhưng `/api/me` vẫn 401. Request khớp `securityMatcher("/api/**")` nên đi chain API;
chain đó là `STATELESS`, không đọc session, chỉ nhận bearer token.

Ngược lại, HTTP Basic không được bật ở chain web:

```bash
curl -i -u user:password $B/web/home
# HTTP/1.1 302   (bị chuyển về /login)
```

### A8. Đăng xuất

```bash
curl -i -b cookies.txt -c cookies.txt -X POST $B/logout --data-urlencode "_csrf=$CSRF"
curl -i -b cookies.txt $B/web/home
```

```
HTTP/1.1 302
Location: http://localhost:8088/login?logout

HTTP/1.1 302
Location: http://localhost:8088/login
```

Session bị huỷ ở server. Cookie cũ dù còn trong file cũng vô nghĩa: đây là ưu điểm "thu hồi được ngay" của session.

---

## Phần B — REST API với JWT

Code: [ApiSecurityConfig.java](../demo/src/main/java/demo/security/config/ApiSecurityConfig.java),
[JwtConfig.java](../demo/src/main/java/demo/security/config/JwtConfig.java),
[AuthController.java](../demo/src/main/java/demo/security/auth/AuthController.java)

### B1. Công khai và 401

```bash
curl -i $B/api/public/hello
curl -i $B/api/me
```

```
HTTP/1.1 200
{"message":"Xin chao, endpoint nay ai cung goi duoc"}

HTTP/1.1 401
WWW-Authenticate: Bearer
Content-Type: application/json;charset=UTF-8

{"status":401,"error":"Unauthorized","message":"Chua xac thuc hoac token khong hop le"}
```

Cùng là "chưa đăng nhập" nhưng chain web trả 302 còn chain API trả 401 JSON: khác nhau ở `AuthenticationEntryPoint`
([JsonSecurityErrorHandlers.java](../demo/src/main/java/demo/security/error/JsonSecurityErrorHandlers.java)).

Log T1 cho `/api/me`:

```
FilterChainProxy              : Securing GET /api/me
AnonymousAuthenticationFilter : Set SecurityContextHolder to anonymous SecurityContext
```

Không có dòng `Secured`: request dừng trong chain.

URL không tồn tại cũng trả 401 chứ không phải 404:

```bash
curl -s -o /dev/null -w '%{http_code}\n' $B/api/khong-ton-tai
# 401
```

Bảo mật chạy trước việc tìm controller. Điều này tốt: người ngoài không dò được endpoint nào tồn tại.

### B2. Đăng nhập sai và đúng

```bash
curl -i -X POST $B/api/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"user","password":"x"}'
# HTTP/1.1 401
```

```
DaoAuthenticationProvider : Failed to authenticate since password does not match stored value
ProviderManager           : Authentication failed with provider DaoAuthenticationProvider since Bad credentials
```

Request này không cần CSRF token: chain API đã tắt CSRF vì không dùng cookie.

```bash
curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' \
     -d '{"username":"user","password":"password"}'
```

```json
{"accessToken":"eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOi...","tokenType":"Bearer","expiresIn":900}
```

Lưu token vào biến:

```bash
TOKEN=$(curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' \
        -d '{"username":"user","password":"password"}' \
        | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
```

### B3. Mổ xẻ token

```bash
echo $TOKEN | cut -d. -f1 | base64 -d; echo
echo $TOKEN | cut -d. -f2 | base64 -d; echo
```

```
{"alg":"HS256"}
{"iss":"spring-security-study-demo","sub":"user","exp":1795709569,"iat":1795708669,"roles":["ROLE_USER"]}
```

Không cần secret để đọc. Nếu `base64 -d` báo lỗi độ dài, thêm một hoặc hai dấu `=` vào cuối chuỗi, hoặc dán token vào jwt.io
(chỉ với token demo, không bao giờ dán token production lên trang web bên ngoài).

Đối chiếu với [TokenService.java](../demo/src/main/java/demo/security/auth/TokenService.java): từng claim ứng với một dòng trong `JwtClaimsSet.builder()`.

### B4. Gọi API bằng token

```bash
curl -s $B/api/me -H "Authorization: Bearer $TOKEN"
```

```json
{"authorities":["FACTOR_BEARER","ROLE_USER"],"username":"user","tokenExpiresAt":"2026-11-26T16:12:49Z"}
```

```
JwtAuthenticationProvider       : Authenticated token
BearerTokenAuthenticationFilter : Set SecurityContextHolder to JwtAuthenticationToken [...
                                  Details=WebAuthenticationDetails [RemoteIpAddress=0:0:0:0:0:0:0:1, SessionId=null],
                                  Granted Authorities=[FactorGrantedAuthority [authority=FACTOR_BEARER, ...], ROLE_USER]]
FilterChainProxy                : Secured GET /api/me
```

`SessionId=null`: không có session. Mỗi request tự chứng minh danh tính.

Thiếu tiền tố `Bearer` thì token không được nhận ra:

```bash
curl -s -o /dev/null -w '%{http_code}\n' $B/api/me -H "Authorization: $TOKEN"
# 401
```

### B5. Sửa token

```bash
curl -i $B/api/me -H "Authorization: Bearer ${TOKEN}x"
# HTTP/1.1 401
```

```
ProviderManager : Authentication failed with provider JwtAuthenticationProvider since
                  An error occurred while attempting to decode the Jwt: Signed JWT rejected: Invalid signature
```

Phản hồi cho client chỉ nói chung chung; lý do chi tiết chỉ nằm trong log server. Đó là chủ ý.

### B6. 403: phân quyền theo URL

```bash
curl -i $B/api/admin/stats -H "Authorization: Bearer $TOKEN"
```

```
HTTP/1.1 403
{"status":403,"error":"Forbidden","message":"Khong du quyen truy cap tai nguyen nay"}
```

Token hợp lệ (đã xác thực), nhưng luật `requestMatchers("/api/admin/**").hasRole("ADMIN")` từ chối. So với B1:
không token là 401, có token thiếu quyền là 403.

Bằng token admin:

```bash
ADMIN=$(curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' \
        -d '{"username":"admin","password":"admin123"}' \
        | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

curl -s $B/api/admin/stats -H "Authorization: Bearer $ADMIN"
# {"totalUsers":2,"note":"Chi ADMIN thay duoc"}
```

---

## Phần C — Method security: quyền theo dữ liệu

Code: [NoteService.java](../demo/src/main/java/demo/security/api/NoteService.java)

Ghi chú 1 thuộc `user`, ghi chú 2 thuộc `admin`.

```bash
curl -s $B/api/notes/1 -H "Authorization: Bearer $TOKEN"
# {"id":1,"owner":"user","content":"Ghi chu rieng cua user"}

curl -i $B/api/notes/2 -H "Authorization: Bearer $TOKEN"
# HTTP/1.1 403
```

Cùng URL mẫu, cùng user, cùng luật URL (`authenticated()`), kết quả khác nhau vì dữ liệu khác nhau. Log:

```
FilterChainProxy : Secured GET /api/notes/2
AuthorizationManagerAfterMethodInterceptor : Failed to authorize ... NoteService.findById(long) ...
   ExpressionAuthorizationDecision [granted=false,
   expressionAttribute=returnObject.owner == authentication.name or hasRole('ADMIN')]
```

Lần này **có** dòng `Secured`: request đã qua filter chain, vào tới controller, và bị từ chối ở tầng service.

```bash
curl -s $B/api/notes/1 -H "Authorization: Bearer $ADMIN"                              # 200: ADMIN đọc được mọi ghi chú
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE $B/api/notes/1 -H "Authorization: Bearer $TOKEN"   # 403
curl -s -o /dev/null -w '%{http_code}\n' -X DELETE $B/api/notes/2 -H "Authorization: Bearer $ADMIN"   # 204
curl -s -o /dev/null -w '%{http_code}\n' $B/api/notes/99 -H "Authorization: Bearer $TOKEN"            # 404
```

---

## Phần D — CORS preflight

```bash
curl -i -X OPTIONS $B/api/me \
     -H 'Origin: https://app.example.com' -H 'Access-Control-Request-Method: GET'
# HTTP/1.1 401
```

Demo chưa cấu hình CORS. Preflight không mang token nên bị chặn như mọi request ẩn danh; trình duyệt sẽ báo lỗi CORS
và không gửi request thật. Sửa ở bài tập 5 bên dưới.

---

## Phần E — Chạy test

```bash
mvn test
```

```
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in demo.security.WebFormLoginSecurityTests
Tests run: 11, Failures: 0, Errors: 0, Skipped: 0 -- in demo.security.ApiJwtSecurityTests
BUILD SUCCESS
```

Mỗi bước bạn vừa làm bằng tay ở phần A, B, C đều có một test tương ứng. Đọc hai file test và ghép từng test với bước lab.

---

## Bài tập

Sau mỗi thay đổi: chạy `mvn test` xem test nào đỏ và vì sao, rồi thử lại bằng `curl`.

| # | Việc cần làm | Điều rút ra |
|---|--------------|-------------|
| 1 | Trong `ApiSecurityConfig`, chuyển dòng `anyRequest().authenticated()` lên đầu | Ứng dụng không khởi động được. Đọc thông báo lỗi |
| 2 | Xoá `@EnableMethodSecurity` rồi cho `user` đọc ghi chú 2 | Annotation còn nguyên nhưng không còn bảo vệ gì. Test nào bắt được? |
| 3 | Trong `JwtConfig`, bỏ dòng `setAuthorityPrefix("")`. Gọi `/api/me` và `/api/admin/stats` bằng token admin | Quyền thành `SCOPE_ROLE_ADMIN`, admin cũng 403 |
| 4 | Xoá `.csrf(csrf -> csrf.disable())` ở chain API, thử đăng nhập qua API | `POST /api/auth/login` bị 403. Hiểu vì sao API stateless tắt CSRF |
| 5 | Thêm bean `CorsConfigurationSource` cho `https://app.example.com` và `http.cors(Customizer.withDefaults())` vào chain API. Chạy lại phần D | Preflight trả 200 kèm `Access-Control-Allow-Origin` |
| 6 | Đặt `app.jwt.ttl-minutes: 1`, lấy token, chờ khoảng hai phút rồi gọi `/api/me` | 401 do hết hạn. Decoder cho phép lệch đồng hồ 60 giây nên không hết hạn đúng phút thứ nhất |
| 7 | Thêm role `STAFF`, user `staff`, và endpoint `/api/reports` cho `hasAnyRole("ADMIN", "STAFF")`. Viết test cho cả ba user | Tự viết trọn một luật kèm test |
| 8 | Thêm `PUT /api/notes/{id}` chỉ cho chủ ghi chú, dùng `@PreAuthorize` với một bean kiểm tra | Phân quyền theo dữ liệu cho thao tác ghi |
| 9 | Thay `InMemoryUserDetailsManager` bằng `UserDetailsService` đọc từ database (H2 + Spring Data JPA) | Nối nguồn user thật, theo mẫu ở [bài 3](03-authentication-xac-thuc.md#từ-database-dự-án-thật) |

Hoàn thành bài 7, 8, 9 là bạn đã tự làm được phần bảo mật cơ bản của một dự án thật.
Trước khi đưa lên production, đi qua [checklist ở bài 8](08-testing-debug-va-bang-tra-loi.md#4-checklist-trước-khi-lên-production).
