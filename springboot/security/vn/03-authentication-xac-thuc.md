# 03 — Authentication: xác thực

Mục tiêu: sau bài này bạn hiểu chuỗi xử lý từ lúc người dùng gõ mật khẩu đến lúc có `Authentication`
trong `SecurityContext`, tự nối được nguồn user từ database, và lưu mật khẩu đúng cách.

## 1. Bộ máy xác thực

```
Filter xác thực (form / basic / bearer)
     │  tạo Authentication CHƯA xác thực từ request
     ▼
AuthenticationManager                    ← interface, một method: authenticate(Authentication)
     │  bản cài đặt mặc định: ProviderManager
     ▼
ProviderManager                          ← hỏi lần lượt từng provider "anh xử lý được loại này không?"
     ├── DaoAuthenticationProvider       ← username + password
     ├── JwtAuthenticationProvider       ← bearer token JWT
     └── ...
     │
     ▼ (với DaoAuthenticationProvider)
UserDetailsService.loadUserByUsername()  ← bạn cung cấp: tìm user ở đâu
PasswordEncoder.matches()                ← bạn cung cấp: so mật khẩu thế nào
     │
     ▼
Authentication ĐÃ xác thực → filter đặt vào SecurityContext
```

| Thành phần | Trách nhiệm | Bạn có phải viết không |
|------------|-------------|------------------------|
| `AuthenticationManager` | Điểm vào duy nhất để xác thực | Không |
| `AuthenticationProvider` | Xác thực một loại thông tin đăng nhập cụ thể | Hiếm khi (chỉ khi có cơ chế riêng: OTP, LDAP tuỳ biến...) |
| `UserDetailsService` | Tìm user theo username | **Có**, khi user nằm trong database của bạn |
| `UserDetails` | Thông tin user mà Spring cần: username, mật khẩu đã băm, quyền, trạng thái khoá | Dùng `User.builder()` có sẵn là đủ |
| `PasswordEncoder` | Băm và so khớp mật khẩu | Khai báo bean, không tự viết thuật toán |

Chỉ cần khai báo hai bean `UserDetailsService` và `PasswordEncoder`, Spring tự dựng `DaoAuthenticationProvider`
và `AuthenticationManager`. Log khởi động của demo xác nhận:

```
Global AuthenticationManager configured with UserDetailsService bean with name userDetailsService
```

## 2. Nguồn user

### Trong RAM (để học và test)

[UserAndPasswordConfig.java](../demo/src/main/java/demo/security/config/UserAndPasswordConfig.java):

```java
@Bean
UserDetailsService userDetailsService(PasswordEncoder encoder) {
    return new InMemoryUserDetailsManager(
            User.withUsername("user")
                    .password(encoder.encode("password"))
                    .roles("USER")            // thành authority "ROLE_USER"
                    .build(),
            User.withUsername("admin")
                    .password(encoder.encode("admin123"))
                    .roles("USER", "ADMIN")
                    .build());
}
```

### Từ database (dự án thật)

Viết một class cài `UserDetailsService`, chuyển entity của bạn thành `UserDetails`:

```java
record Account(String username, String passwordHash, boolean locked, List<String> roles) {}

interface AccountRepository {
    Optional<Account> findByUsername(String username);
}

@Service
class DatabaseUserDetailsService implements UserDetailsService {

    private final AccountRepository accounts;

    DatabaseUserDetailsService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Account account = accounts.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Khong tim thay user"));
        return User.withUsername(account.username())
                .password(account.passwordHash())          // chuỗi đã băm lấy từ DB
                .roles(account.roles().toArray(String[]::new))
                .accountLocked(account.locked())
                .build();
    }
}
```

Ghi nhớ:

- Method này **chỉ tìm user**, không so mật khẩu. So mật khẩu là việc của `DaoAuthenticationProvider`.
- Ném `UsernameNotFoundException` khi không thấy. Spring đổi nó thành `BadCredentialsException` trước khi
  trả ra ngoài, để kẻ tấn công không dò được username nào tồn tại. Thông báo lỗi đăng nhập của bạn cũng nên
  chung chung: "Sai tên đăng nhập hoặc mật khẩu".
- "Username" chỉ là khoá định danh: có thể là email, số điện thoại.
- Các cờ `accountLocked`, `disabled`, `accountExpired`, `credentialsExpired` được Spring kiểm tra tự động.

## 3. Lưu mật khẩu

### Nguyên tắc

| Cách lưu | Đánh giá |
|----------|----------|
| Plain text | Lộ database là lộ toàn bộ mật khẩu. Không bao giờ |
| Mã hoá hai chiều (AES...) | Có khoá là giải được. Không dùng cho mật khẩu |
| Băm nhanh (MD5, SHA-256), kể cả có salt | GPU thử hàng tỉ lần mỗi giây. Không đủ |
| **Băm chậm thích ứng**: bcrypt, argon2, scrypt, PBKDF2 | Đúng. Cố ý chậm, có salt riêng cho từng mật khẩu, tăng được độ khó theo thời gian |

Mật khẩu **không cần giải ngược**. Khi đăng nhập, băm mật khẩu vừa nhập rồi so với giá trị đã lưu.

### `PasswordEncoder` trong Spring

```java
@Bean
PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
}
```

Kết quả băm chuỗi `password` (chạy thật trên Spring Security 7.1.2):

```
{bcrypt}$2a$10$S65sxXUV6GKlkZZgDWsrwu1ep6FsQcBR.x6wHzNcI4SHAFW5cx8Uu
└──┬───┘└┬┘└┬┘└────────────────────────┬───────────────────────────┘
   │     │  │                          └ salt (22 ký tự) + hash (31 ký tự)
   │     │  └ cost = 10 → 2^10 vòng lặp. Tăng 1 là chậm gấp đôi
   │     └ phiên bản bcrypt
   └ tiền tố của DelegatingPasswordEncoder: cho biết băm bằng thuật toán nào
```

Băm cùng một mật khẩu hai lần ra hai chuỗi khác nhau vì salt ngẫu nhiên. Do đó so khớp phải dùng
`encoder.matches(raw, encoded)`, không so chuỗi bằng `equals`.

Vì sao nên dùng `DelegatingPasswordEncoder` thay vì `new BCryptPasswordEncoder()` trực tiếp: tiền tố `{bcrypt}`
cho phép mỗi mật khẩu tự khai thuật toán của nó. Vài năm sau đổi sang argon2, mật khẩu cũ vẫn kiểm tra được và
Spring tự băm lại theo thuật toán mới khi user đăng nhập thành công (nếu `UserDetailsService` cài thêm
`UserDetailsPasswordService`).

Lỗi hay gặp: `There is no PasswordEncoder mapped for the id "null"`. Nguyên nhân: mật khẩu trong database
không có tiền tố `{...}` trong khi đang dùng `DelegatingPasswordEncoder`. Sửa bằng cách thêm tiền tố vào dữ liệu,
hoặc dùng thẳng `BCryptPasswordEncoder` nếu toàn bộ dữ liệu là bcrypt.

Khi đăng ký user, luôn băm trước khi lưu:

```java
account.setPasswordHash(passwordEncoder.encode(rawPassword));
```

## 4. Form login: luồng đầy đủ

```java
http.formLogin(Customizer.withDefaults());
```

```
1. GET /web/home (chưa đăng nhập)
     AuthorizationFilter từ chối → ExceptionTranslationFilter
     → lưu URL gốc vào RequestCache → 302 Location: /login

2. GET /login
     DefaultLoginPageGeneratingFilter trả form HTML (có sẵn input ẩn _csrf)

3. POST /login  (username, password, _csrf)
     CsrfFilter kiểm tra token
     UsernamePasswordAuthenticationFilter → AuthenticationManager
        ├─ sai  → 302 /login?error
        └─ đúng → đổi session id (chống session fixation)
                  lưu SecurityContext vào HttpSession
                  302 về URL gốc đã lưu ở bước 1 (hoặc "/")

4. GET /web/home  kèm cookie JSESSIONID
     SecurityContextHolderFilter nạp context từ session → đã đăng nhập → 200
```

Tuỳ biến thường dùng:

```java
http.formLogin(form -> form
        .loginPage("/login").permitAll()      // trang login tự làm; nhớ permitAll kẻo redirect vô hạn
        .defaultSuccessUrl("/web/home")
        .failureUrl("/login?error"));
```

Khi dùng `loginPage` tự làm, form phải `POST` tới `/login` với tham số `username`, `password` và CSRF token.
Với Thymeleaf, `th:action` tự chèn token.

## 5. HTTP Basic

```java
http.httpBasic(Customizer.withDefaults());
```

Client gửi `Authorization: Basic base64(username:password)` trong **mỗi** request. Base64 không phải mã hoá,
nên chỉ dùng qua HTTPS. Hợp cho công cụ nội bộ, script, gọi giữa các service đơn giản; không hợp cho ứng dụng
người dùng cuối vì mật khẩu đi theo mọi request.

## 6. Tự xác thực trong controller (đăng nhập cho REST API)

Với API, không có form. Controller nhận JSON và gọi thẳng `AuthenticationManager`
([AuthController.java](../demo/src/main/java/demo/security/auth/AuthController.java)):

```java
@PostMapping("/login")
public LoginResponse login(@RequestBody LoginRequest request) {
    Authentication unauthenticated =
            UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password());

    // Sai mật khẩu → ném BadCredentialsException → ExceptionTranslationFilter → 401
    Authentication authenticated = authenticationManager.authenticate(unauthenticated);

    IssuedToken token = tokenService.issue(authenticated);
    return new LoginResponse(token.value(), "Bearer", token.expiresInSeconds());
}
```

Muốn tiêm `AuthenticationManager` thì phải lộ nó ra thành bean:

```java
@Bean
AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
    return configuration.getAuthenticationManager();
}
```

Controller **không tự so mật khẩu**. Giao cho `AuthenticationManager` để hưởng trọn các kiểm tra có sẵn
(tài khoản khoá, hết hạn, chống dò theo thời gian phản hồi).

Log DEBUG thật khi nhập sai rồi nhập đúng:

```
DaoAuthenticationProvider : Failed to authenticate since password does not match stored value
ProviderManager           : Authentication failed with provider DaoAuthenticationProvider since Bad credentials
...
DaoAuthenticationProvider : Authenticated user
```

## 7. Session

| Cấu hình | Ý nghĩa |
|----------|---------|
| `SessionCreationPolicy.IF_REQUIRED` | Mặc định. Tạo session khi cần (sau khi form login) |
| `SessionCreationPolicy.STATELESS` | Không tạo, không dùng session. Dành cho API dùng token |
| `sessionFixation(f -> f.changeSessionId())` | Mặc định. Đổi session id sau đăng nhập |
| `maximumSessions(1)` | Mỗi user chỉ có một phiên; đăng nhập nơi khác thì phiên cũ hết hiệu lực |

```java
http.sessionManagement(session -> session
        .sessionFixation(fixation -> fixation.changeSessionId())
        .maximumSessions(1)
        .maxSessionsPreventsLogin(false));   // false: phiên mới đá phiên cũ; true: chặn đăng nhập mới
```

Cookie session cần các cờ sau khi lên production (`application.yml`):

```yaml
server:
  servlet:
    session:
      timeout: 30m
      cookie:
        http-only: true     # JavaScript không đọc được → giảm thiệt hại khi bị XSS
        secure: true        # chỉ gửi qua HTTPS
        same-site: lax      # hạn chế gửi cookie từ site khác → lớp chống CSRF bổ sung
```

Chạy nhiều instance thì session trong RAM của từng máy không dùng chung được. Dùng Spring Session với Redis,
hoặc chuyển sang stateless.

## 8. Remember-me và logout

```java
http
    .rememberMe(remember -> remember
            .key("doi-thanh-gia-tri-bi-mat")
            .tokenValiditySeconds(14 * 24 * 3600))
    .logout(logout -> logout
            .logoutSuccessUrl("/login?logout")
            .deleteCookies("JSESSIONID"));
```

- **Remember-me**: cấp thêm một cookie sống lâu để tự đăng nhập lại khi session hết hạn. Tiện nhưng tăng rủi ro
  nếu cookie bị đánh cắp. Với thao tác nhạy cảm (đổi mật khẩu, thanh toán), yêu cầu `fullyAuthenticated()`
  để buộc nhập lại mật khẩu (bài 4).
- **Logout**: mặc định là `POST /logout` kèm CSRF token (không phải `GET`, để trang khác không thể đăng xuất hộ bạn
  bằng một thẻ `<img>`). Nó huỷ session, xoá `SecurityContext`, xoá cookie remember-me.

## 9. Tự kiểm tra

1. `UserDetailsService.loadUserByUsername` có so sánh mật khẩu không? Ai làm việc đó?
2. Vì sao băm cùng một mật khẩu bằng bcrypt hai lần ra hai chuỗi khác nhau mà vẫn đăng nhập được?
3. Vì sao Spring giấu `UsernameNotFoundException` thành `BadCredentialsException`?
4. Dùng `loginPage("/dang-nhap")` rồi ứng dụng báo "too many redirects". Thiếu gì?

Đáp án ngắn: (1) Không; `DaoAuthenticationProvider` gọi `PasswordEncoder.matches`. (2) Salt ngẫu nhiên nằm ngay
trong chuỗi kết quả; `matches` đọc salt đó ra để băm lại rồi so. (3) Chống dò username tồn tại. (4) Thiếu
`permitAll()` cho trang login: trang login cũng đòi đăng nhập nên tự chuyển về chính nó.

Bài tiếp theo: [04 — Authorization](04-authorization-phan-quyen.md).
