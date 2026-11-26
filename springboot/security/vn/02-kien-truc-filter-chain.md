# 02 — Kiến trúc filter chain

Mục tiêu: sau bài này bạn vẽ lại được đường đi của một request qua Spring Security, và khi gặp
401/403 thì biết nó sinh ra ở filter nào. Đây là bài quan trọng nhất: hiểu nó thì mọi cấu hình
ở các bài sau chỉ là "bật, tắt, thay một filter".

## 1. Spring Security là một chuỗi servlet filter

Spring Security đứng **trước** `DispatcherServlet`. Request bị chặn hay cho qua được quyết định
trước khi controller của bạn được gọi.

```
Client
  │
  ▼
┌──────────────────────── Servlet container (Tomcat) ────────────────────────┐
│ Filter khác (encoding...)                                                  │
│      │                                                                     │
│      ▼                                                                     │
│ DelegatingFilterProxy        ← filter của servlet, cầu nối sang Spring     │
│      │                                                                     │
│      ▼                                                                     │
│ FilterChainProxy             ← bean "springSecurityFilterChain"            │
│      │   chọn SecurityFilterChain ĐẦU TIÊN khớp với request                │
│      ├── SecurityFilterChain #1  (/api/**)   → 13 filter                   │
│      └── SecurityFilterChain #2  (còn lại)   → 15 filter                   │
│      │                                                                     │
│      ▼                                                                     │
│ DispatcherServlet → Controller                                             │
└────────────────────────────────────────────────────────────────────────────┘
```

| Thành phần | Vai trò |
|------------|---------|
| `DelegatingFilterProxy` | Tomcat không biết Spring bean. Filter này được đăng ký với Tomcat và chuyển việc cho bean bên trong Spring |
| `FilterChainProxy` | Cổng vào duy nhất của Spring Security. Chọn chain, và là nơi đặt breakpoint tốt nhất khi debug |
| `SecurityFilterChain` | Một danh sách filter + điều kiện "áp dụng cho request nào". Mỗi bean `SecurityFilterChain` bạn khai báo là một chain |

## 2. Bên trong một chain: danh sách filter thật

Bật log `org.springframework.security=DEBUG` rồi khởi động demo, Spring in ra đúng danh sách filter
của từng chain. Chain web (form login) của demo:

```
Will secure any request with filters:
  DisableEncodeUrlFilter, WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter,
  HeaderWriterFilter, CsrfFilter, LogoutFilter, UsernamePasswordAuthenticationFilter,
  DefaultResourcesFilter, DefaultLoginPageGeneratingFilter, DefaultLogoutPageGeneratingFilter,
  RequestCacheAwareFilter, SecurityContextHolderAwareRequestFilter,
  AnonymousAuthenticationFilter, ExceptionTranslationFilter, AuthorizationFilter
```

Chain API (JWT) của demo:

```
Will secure Or [PathPattern [/api/**]] with filters:
  DisableEncodeUrlFilter, WebAsyncManagerIntegrationFilter, SecurityContextHolderFilter,
  HeaderWriterFilter, LogoutFilter, OAuth2ProtectedResourceMetadataFilter,
  BearerTokenAuthenticationFilter, RequestCacheAwareFilter,
  SecurityContextHolderAwareRequestFilter, AnonymousAuthenticationFilter,
  SessionManagementFilter, ExceptionTranslationFilter, AuthorizationFilter
```

So sánh hai danh sách là thấy ngay cấu hình ảnh hưởng thế nào: chain API không có `CsrfFilter`
(vì `csrf.disable()`), và `UsernamePasswordAuthenticationFilter` được thay bằng `BearerTokenAuthenticationFilter`.

Không cần thuộc hết. Cần nắm các filter sau, theo đúng thứ tự chạy:

| Thứ tự | Filter | Làm gì |
|--------|--------|--------|
| 1 | `SecurityContextHolderFilter` | Nạp `SecurityContext` đã lưu (từ session) vào `SecurityContextHolder`; dọn sạch khi request kết thúc |
| 2 | `HeaderWriterFilter` | Ghi các security header vào response |
| 3 | `CsrfFilter` | Kiểm tra CSRF token với request thay đổi dữ liệu. Sai thì 403 |
| 4 | `LogoutFilter` | Bắt `POST /logout`, huỷ session |
| 5 | Filter xác thực: `UsernamePasswordAuthenticationFilter` (form), `BasicAuthenticationFilter` (Basic), `BearerTokenAuthenticationFilter` (JWT) | Đọc thông tin đăng nhập từ request, gọi `AuthenticationManager`, đặt kết quả vào `SecurityContext` |
| 6 | `AnonymousAuthenticationFilter` | Nếu tới đây vẫn chưa ai xác thực: đặt một `Authentication` "ẩn danh" |
| 7 | `ExceptionTranslationFilter` | Bắt exception bảo mật từ các bước phía sau, đổi thành 401 hoặc 403 |
| 8 | `AuthorizationFilter` | Áp các luật `authorizeHttpRequests`. Luôn là filter cuối |

Quy luật thứ tự: **nạp context → chống tấn công → xác thực → phân quyền**. Phân quyền đứng cuối vì
nó cần biết kết quả xác thực.

## 3. Một request đi qua chain như thế nào

Ví dụ: `GET /api/admin/stats` kèm JWT của user thường (không phải admin).

```
1. FilterChainProxy          khớp /api/** → dùng chain API
2. SecurityContextHolderFilter   context rỗng (stateless, không có session)
3. BearerTokenAuthenticationFilter
       đọc header Authorization → JwtDecoder verify chữ ký, hạn dùng → OK
       đặt JwtAuthenticationToken[user, ROLE_USER] vào SecurityContext
4. AnonymousAuthenticationFilter  bỏ qua vì đã có Authentication
5. ExceptionTranslationFilter     gọi tiếp, đứng chờ bắt exception
6. AuthorizationFilter
       luật: /api/admin/** cần hasRole("ADMIN") → user không có
       ném AccessDeniedException
7. ExceptionTranslationFilter     bắt được, user ĐÃ xác thực → AccessDeniedHandler → 403
```

Log DEBUG thật của đúng tình huống này (rút gọn):

```
FilterChainProxy                   : Securing GET /api/admin/stats
JwtAuthenticationProvider          : Authenticated token
BearerTokenAuthenticationFilter    : Set SecurityContextHolder to JwtAuthenticationToken [...
                                     Granted Authorities=[ROLE_USER, FactorGrantedAuthority [authority=FACTOR_BEARER, ...]]]
```

Không có dòng `Secured GET /api/admin/stats` nghĩa là request bị chặn trong chain, không tới controller.
So với request thành công, luôn có cặp `Securing ...` rồi `Secured ...`.

`FACTOR_BEARER` là authority Spring Security 7 tự thêm để ghi nhận "xác thực bằng yếu tố nào"
(phục vụ xác thực đa yếu tố). Đăng nhập bằng mật khẩu thì có `FACTOR_PASSWORD`. Nó không ảnh hưởng tới luật role.

## 4. 401 và 403 sinh ra ở đâu

Toàn bộ nằm ở `ExceptionTranslationFilter`:

```
                     Exception từ filter phía sau hoặc từ controller/service
                                          │
                 ┌────────────────────────┴────────────────────────┐
                 ▼                                                 ▼
      AuthenticationException                           AccessDeniedException
                 │                                                 │
                 │                              ┌──────────────────┴─────────────────┐
                 │                              ▼                                    ▼
                 │                    user đang ẨN DANH                    user ĐÃ xác thực
                 │                              │                                    │
                 ▼                              ▼                                    ▼
        AuthenticationEntryPoint ◀──────────────┘                          AccessDeniedHandler
        "mời đăng nhập"                                                    "bạn không có quyền"
        - web: 302 về /login                                               - 403
        - API: 401
```

Điểm tinh tế cần nhớ: **user ẩn danh bị từ chối quyền thì nhận 401 (hoặc redirect về login), không phải 403**.
Spring coi đó là "chưa đăng nhập" chứ không phải "thiếu quyền".

Demo thay cả hai handler bằng bản trả JSON ([JsonSecurityErrorHandlers.java](../demo/src/main/java/demo/security/error/JsonSecurityErrorHandlers.java)):

```java
@Component
public class JsonSecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, "Chua xac thuc hoac token khong hop le");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "Khong du quyen truy cap tai nguyen nay");
    }
}
```

Vì các filter này chạy trước `DispatcherServlet`, **`@ControllerAdvice` không bắt được lỗi do filter ném ra**.
Muốn đổi định dạng lỗi 401/403 thì phải cấu hình entry point và handler như trên.

Ngược lại, exception bảo mật ném ra **bên trong controller/service** (ví dụ từ `@PreAuthorize`) có đi qua
`@ControllerAdvice`. Nếu bạn có `@ExceptionHandler(Exception.class)` bắt tất cả, nó sẽ nuốt
`AccessDeniedException` và trả 500 thay vì 403. Cách xử lý: thêm handler riêng ném lại exception đó.

## 5. SecurityContextHolder: nơi giữ "ai đang đăng nhập"

```
SecurityContextHolder          (mặc định lưu bằng ThreadLocal: mỗi thread một context)
   └── SecurityContext
          └── Authentication
                 ├── principal
                 ├── credentials
                 └── authorities
```

- **Trong một request**: filter xác thực đặt `Authentication` vào; controller, service đọc ra.
  Cuối request, context bị xoá khỏi thread (thread được trả về pool, không được để sót thông tin).
- **Giữa các request**: `SecurityContextRepository` lo việc lưu và nạp lại. Với form login, nó lưu vào
  `HttpSession`. Với API stateless, không lưu gì: mỗi request tự xác thực lại bằng token.

Hệ quả của `ThreadLocal`: code chạy ở thread khác (`@Async`, `CompletableFuture`, thread tự tạo) **không thấy**
user hiện tại. Cần dùng `DelegatingSecurityContextExecutor` hoặc truyền thẳng thông tin user làm tham số.

## 6. Nhiều filter chain trong một ứng dụng

Demo có hai chain. Thứ tự xét do `@Order` quyết định, chain nào khớp trước thì dùng, **không rơi tiếp
xuống chain sau**:

```java
@Bean
@Order(1)
SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ...) throws Exception {
    http.securityMatcher("/api/**")      // chain này chỉ nhận /api/**
        ...
}

@Bean
@Order(2)
SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
    http                                  // không có securityMatcher = nhận mọi request còn lại
        ...
}
```

Hai khái niệm dễ lẫn:

| | `securityMatcher(...)` | `requestMatchers(...)` |
|---|---|---|
| Trả lời | Chain này có xử lý request không? | Trong chain, request cần quyền gì? |
| Dùng ở | Cấp `http` | Bên trong `authorizeHttpRequests` |

Lỗi kinh điển: đặt chain "nhận tất cả" ở `@Order(1)`. Nó nuốt hết request và chain phía sau không bao giờ chạy.
Luôn đặt chain có `securityMatcher` cụ thể trước, chain chung sau cùng.

## 7. Thêm filter của riêng bạn

```java
static class RequestIdFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Request-Id", java.util.UUID.randomUUID().toString());
        chain.doFilter(request, response);   // quên dòng này là request dừng tại đây
    }
}

http.addFilterBefore(new RequestIdFilter(), UsernamePasswordAuthenticationFilter.class);
```

Lưu ý:

- Vị trí chèn tính theo một filter chuẩn: `addFilterBefore`, `addFilterAfter`, `addFilterAt`.
- Đừng đánh dấu filter đó là `@Component`. Spring Boot tự đăng ký mọi bean kiểu `Filter` với Tomcat,
  nên filter sẽ áp cho mọi request của ứng dụng, nằm ngoài chain và sai thứ tự bạn muốn.
  Tạo bằng `new` trong cấu hình như trên.
- Trước khi tự viết filter xác thực (ví dụ filter đọc JWT), kiểm tra Spring đã có sẵn chưa. Với JWT là có:
  `oauth2ResourceServer` (bài 6).

## 8. `permitAll()` khác gì "bỏ qua bảo mật"

| | `permitAll()` | `web.ignoring()` (`WebSecurityCustomizer`) |
|---|---|---|
| Request có đi qua filter chain không | Có | Không |
| Có security header, CSRF, context | Có | Không |
| Dùng cho | Endpoint công khai | Hầu như không nên dùng; chỉ cho tài nguyên tĩnh nếu thật cần |

Ưu tiên `permitAll()`. Endpoint công khai vẫn nên được gắn security header và vẫn đọc được user nếu họ đã đăng nhập.

## 9. Tự kiểm tra

1. Vì sao `AuthorizationFilter` phải đứng sau `AnonymousAuthenticationFilter`?
2. Ứng dụng có `@ControllerAdvice` trả JSON đẹp cho mọi lỗi, nhưng lỗi 401 vẫn ra dạng mặc định. Vì sao?
3. Gọi `SecurityContextHolder.getContext().getAuthentication()` trong method `@Async` trả về `null`. Vì sao?
4. Hai chain: `@Order(1)` không có `securityMatcher`, `@Order(2)` có `securityMatcher("/api/**")`. Chuyện gì xảy ra với `/api/me`?

Đáp án ngắn: (1) Để luôn có một `Authentication` mà xét quyền, kể cả với người chưa đăng nhập. (2) 401 sinh ra
trong filter, trước `DispatcherServlet`; `@ControllerAdvice` chỉ thấy lỗi phát sinh trong controller.
(3) Context lưu theo `ThreadLocal`, thread khác không có. (4) Chain 1 nhận hết, chain 2 không bao giờ chạy.

Bài tiếp theo: [03 — Authentication](03-authentication-xac-thuc.md).
