# 06 — JWT và REST API stateless

Mục tiêu: sau bài này bạn hiểu JWT ở mức đọc được từng phần của nó, dựng được API stateless bằng hỗ trợ có sẵn
của Spring Security (không tự viết filter), và biết các giới hạn của JWT để không dùng sai chỗ.

## 1. JWT là gì

JWT (JSON Web Token) là một chuỗi gồm ba phần ngăn bởi dấu chấm. Token thật do demo phát:

```
eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzcHJpbmctc2VjdXJpdHktc3R1ZHktZGVtbyIsInN1YiI6InVzZXIiLCJleHAiOjE3OTU3MDk1NjksImlhdCI6MTc5NTcwODY2OSwicm9sZXMiOlsiUk9MRV9VU0VSIl19.H74MFxgWFQSLnOxnciobEolK-N7F6ideqJPW2ox0NfM
└────── header ─────┘ └──────────────────────────────── payload ───────────────────────────────────────────────────────────────────────┘ └──────────────── signature ───────────────┘
```

| Phần | Nội dung sau khi giải base64url | Ý nghĩa |
|------|----------------------------------|---------|
| Header | `{"alg":"HS256"}` | Thuật toán ký |
| Payload | `{"iss":"spring-security-study-demo","sub":"user","exp":1795709569,"iat":1795708669,"roles":["ROLE_USER"]}` | Các claim: thông tin về user và token |
| Signature | HMAC-SHA256(header + "." + payload, secret) | Chứng minh token do server phát và chưa bị sửa |

Claim chuẩn hay dùng:

| Claim | Nghĩa |
|-------|-------|
| `sub` | Subject: token này của ai (username hoặc user id) |
| `iss` | Issuer: ai phát |
| `aud` | Audience: phát cho service nào dùng |
| `exp` | Hết hạn lúc nào (giây từ 1970) |
| `iat` | Phát lúc nào |
| `jti` | Id duy nhất của token (dùng khi cần thu hồi) |

### Hai sự thật phải thuộc

1. **Payload không được mã hoá.** Base64 chỉ là cách viết khác; ai cầm token cũng đọc được. Không bao giờ đặt mật khẩu,
   số thẻ hay dữ liệu nhạy cảm vào JWT. Tự thử:

   ```bash
   echo 'eyJpc3MiOiJzcHJpbmctc2VjdXJpdHktc3R1ZHktZGVtbyIsInN1YiI6InVzZXIiLCJleHAiOjE3OTU3MDk1NjksImlhdCI6MTc5NTcwODY2OSwicm9sZXMiOlsiUk9MRV9VU0VSIl19' | base64 -d
   ```

2. **Chữ ký chống sửa, không chống đọc.** Đổi một ký tự trong payload (ví dụ tự nâng mình lên `ROLE_ADMIN`) thì chữ ký
   không còn khớp và server từ chối. Demo, thêm một ký tự vào cuối token:

   ```
   HTTP/1.1 401
   WWW-Authenticate: Bearer
   ```
   ```
   ProviderManager : Authentication failed with provider JwtAuthenticationProvider since
                     An error occurred while attempting to decode the Jwt: Signed JWT rejected: Invalid signature
   ```

## 2. Luồng làm việc

```
┌────────┐  1. POST /api/auth/login {username, password}   ┌────────┐
│        │ ──────────────────────────────────────────────▶ │        │ kiểm tra mật khẩu
│ Client │  2. {accessToken: "eyJ...", expiresIn: 900}     │ Server │ ký JWT bằng secret
│        │ ◀────────────────────────────────────────────── │        │
│        │                                                 │        │
│        │  3. GET /api/me                                 │        │ verify chữ ký + hạn dùng
│        │     Authorization: Bearer eyJ...                │        │ KHÔNG tra database
│        │ ──────────────────────────────────────────────▶ │        │ KHÔNG có session
│        │  4. 200 {...}                                   │        │
└────────┘ ◀────────────────────────────────────────────── └────────┘
```

Server không lưu gì về phiên đăng nhập. Mọi thông tin cần để nhận ra user nằm trong token, và chữ ký bảo đảm
thông tin đó đáng tin. Đó là ý nghĩa của "stateless".

## 3. Cài đặt trong Spring Security

Rất nhiều hướng dẫn tự viết một `JwtAuthenticationFilter` dài cả trăm dòng cùng thư viện `jjwt`. Không cần:
Spring Security có sẵn **OAuth2 Resource Server** làm đúng việc đó, đã được kiểm thử kỹ, xử lý đủ trường hợp biên.

Dependency (Spring Boot 4; trên Boot 3 là `spring-boot-starter-oauth2-resource-server`):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
</dependency>
```

### Bước 1: khoá ký, encoder, decoder

[JwtConfig.java](../demo/src/main/java/demo/security/config/JwtConfig.java):

```java
@Bean
SecretKey jwtSecretKey(@Value("${app.jwt.secret}") String secret) {
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < 32) {
        throw new IllegalStateException("app.jwt.secret phai dai toi thieu 32 byte cho HS256");
    }
    return new SecretKeySpec(bytes, "HmacSHA256");
}

@Bean
JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
}

@Bean
JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
    return NimbusJwtDecoder.withSecretKey(jwtSecretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
}
```

`JwtDecoder` tự kiểm tra chữ ký và thời hạn (`exp`, `nbf`). Bạn không viết dòng kiểm tra nào.

### Bước 2: phát token

[TokenService.java](../demo/src/main/java/demo/security/auth/TokenService.java):

```java
JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer("spring-security-study-demo")
        .subject(authentication.getName())
        .issuedAt(now)
        .expiresAt(now.plus(ttl))                 // 15 phút
        .claim(JwtConfig.ROLES_CLAIM, roles)
        .build();

JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
```

### Bước 3: đổi claim thành quyền

Mặc định Spring đọc claim `scope` và thêm tiền tố `SCOPE_`. Demo đặt quyền trong claim `roles` nên phải chỉ lại:

```java
@Bean
JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
    authorities.setAuthoritiesClaimName(ROLES_CLAIM);
    authorities.setAuthorityPrefix("");          // giá trị trong claim đã là "ROLE_USER"

    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authorities);
    return converter;
}
```

Quên bước này là nguyên nhân số một của "token hợp lệ mà `hasRole` luôn 403": quyền bị đọc từ sai claim hoặc sai tiền tố.
Gọi `/api/me` để xem quyền thực tế Spring nhận được:

```json
{"authorities":["FACTOR_BEARER","ROLE_USER"],"username":"user","tokenExpiresAt":"2026-11-26T16:12:49Z"}
```

### Bước 4: filter chain

[ApiSecurityConfig.java](../demo/src/main/java/demo/security/config/ApiSecurityConfig.java):

```java
http
        .securityMatcher("/api/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                .authenticationEntryPoint(errorHandlers)
                .accessDeniedHandler(errorHandlers))
        .exceptionHandling(ex -> ex
                .authenticationEntryPoint(errorHandlers)
                .accessDeniedHandler(errorHandlers));
```

Dòng `oauth2ResourceServer(...jwt...)` cài `BearerTokenAuthenticationFilter` vào chain. Filter này đọc header
`Authorization: Bearer ...`, gọi `JwtDecoder`, rồi đặt `JwtAuthenticationToken` vào `SecurityContext`.

Đọc thông tin token trong controller:

```java
@GetMapping("/me")
public Map<String, Object> me(Authentication authentication, @AuthenticationPrincipal Jwt jwt) {
    jwt.getSubject();            // "user"
    jwt.getExpiresAt();
    jwt.getClaimAsStringList("roles");
    ...
}
```

## 4. Khoá đối xứng và bất đối xứng

| | HS256 (đối xứng) | RS256 / ES256 (bất đối xứng) |
|---|---|---|
| Khoá | Một secret dùng cho cả ký và verify | Private key để ký, public key để verify |
| Ai verify được | Ai có secret, và người đó cũng **ký được** | Ai có public key; không ký được |
| Hợp với | Một service vừa phát vừa kiểm tra (như demo) | Nhiều service cùng kiểm tra token do một nơi phát |
| Phân phối khoá | Phải giữ bí mật ở mọi nơi dùng | Public key công bố qua endpoint JWKS |

Trong hệ thống microservice, luôn dùng bất đối xứng: nếu chia sẻ secret HS256 cho mười service thì service nào bị lộ
cũng giả được token của toàn hệ thống. Khi token do Keycloak, Auth0, hay Spring Authorization Server phát,
resource server chỉ cần một dòng cấu hình và Spring tự tải public key (bài 7):

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://auth.example.com/realms/demo
```

## 5. Giới hạn của JWT và cách xử lý

### Không thu hồi được

Token đã phát thì hợp lệ đến khi hết hạn. User đăng xuất, bị khoá tài khoản, bị đổi quyền: token cũ vẫn dùng được.
Đây là cái giá của việc không tra database.

| Cách giảm thiểu | Đánh đổi |
|-----------------|----------|
| Access token sống ngắn (5–15 phút) | Cần refresh token để không bắt đăng nhập lại liên tục |
| Danh sách chặn theo `jti` (lưu Redis đến khi token hết hạn) | Mỗi request phải tra Redis: mất một phần tính stateless |
| Lưu "phiên bản token" theo user, tăng khi đổi mật khẩu hoặc khoá | Mỗi request phải tra user |

### Refresh token

```
Access token:  sống ngắn (15 phút), gửi kèm mọi request API
Refresh token: sống dài (ngày, tuần), CHỈ gửi tới endpoint làm mới token,
               lưu ở server (database) → thu hồi được
```

Khi access token hết hạn, client đổi refresh token lấy access token mới. Thực hành tốt:

- Refresh token là chuỗi ngẫu nhiên, lưu **dạng đã băm** ở server, không cần là JWT.
- **Xoay vòng**: mỗi lần dùng thì cấp refresh token mới và vô hiệu cái cũ. Thấy cái cũ bị dùng lại nghĩa là đã lộ:
  thu hồi cả chuỗi.
- Đăng xuất là xoá refresh token ở server.

Demo không cài refresh token để giữ trọng tâm. Nếu dự án cần đăng nhập, đăng ký, refresh, thu hồi, đăng nhập mạng xã hội,
hãy cân nhắc dùng một authorization server có sẵn (Keycloak, Spring Authorization Server) thay vì tự xây.

### Lưu token ở đâu phía trình duyệt

| Nơi lưu | XSS đọc được token | CSRF | Ghi chú |
|---------|--------------------|------|---------|
| `localStorage` / `sessionStorage` | Có | Không bị | Một lỗi XSS là mất token |
| Biến trong bộ nhớ JavaScript | Khó hơn | Không bị | Mất khi tải lại trang; kết hợp refresh token trong cookie |
| Cookie `HttpOnly` + `Secure` + `SameSite` | Không | **Có**: phải bật chống CSRF | Thực chất là quay về mô hình cookie |

Không có lựa chọn nào miễn nhiễm. Mô hình được khuyến nghị hiện nay cho SPA là **BFF (Backend For Frontend)**:
trình duyệt chỉ giữ cookie session `HttpOnly` với backend của chính nó, backend giữ token và gọi API thay.

### Các cạm bẫy khác

| Cạm bẫy | Hậu quả | Cách tránh |
|---------|---------|------------|
| Secret ngắn, dễ đoán, hoặc commit vào git | Ai cũng ký được token admin | Tối thiểu 32 byte ngẫu nhiên, nạp từ biến môi trường |
| Chấp nhận `alg: none` hoặc để token tự chọn thuật toán | Bỏ qua kiểm tra chữ ký | Chỉ định cứng thuật toán ở decoder, như demo `.macAlgorithm(MacAlgorithm.HS256)` |
| Không kiểm `iss`, `aud` | Token phát cho service khác dùng được ở service bạn | Thêm validator cho issuer và audience |
| Token không có `exp` hoặc hạn rất dài | Lộ là dùng được mãi | Luôn đặt hạn ngắn |
| Nhét quá nhiều dữ liệu vào claim | Header phình to, dữ liệu cũ (quyền đã đổi nhưng token chưa) | Chỉ để định danh và quyền tối thiểu |
| Ghi token vào log, đặt trên URL | Lộ token qua log, lịch sử trình duyệt | Chỉ truyền qua header, che token khi log |
| Dùng HTTP thường | Token bị nghe lén | Luôn HTTPS |

## 6. Khi nào không nên dùng JWT

- Web render phía server, một domain: session cookie đơn giản hơn, thu hồi được ngay, ít thứ để sai hơn.
- Cần thu hồi tức thì là yêu cầu cứng (ngân hàng, quản trị): session, hoặc opaque token có tra cứu.

JWT đáng dùng khi có nhiều service cần xác minh danh tính mà không gọi về một nơi trung tâm cho mỗi request,
hoặc khi client không phải trình duyệt.

## 7. Tự kiểm tra

1. Ai đó chụp được access token của bạn. Họ đọc được gì và làm được gì?
2. User bị khoá tài khoản lúc 10:00, token của họ hết hạn lúc 10:12. Trong 12 phút đó chuyện gì xảy ra?
3. Token hợp lệ, chứa `"roles":["ADMIN"]`, nhưng `hasRole("ADMIN")` trả 403. Kiểm tra gì trước?
4. Vì sao hệ thống mười service không nên dùng chung một secret HS256?

Đáp án ngắn: (1) Đọc được toàn bộ payload, và dùng token như bạn cho đến khi hết hạn. (2) Token vẫn được chấp nhận,
trừ khi có danh sách chặn hoặc kiểm tra trạng thái user. (3) `JwtAuthenticationConverter`: đọc đúng claim chưa và tiền tố
ra sao; ở đây cần authority `ROLE_ADMIN` nhưng claim chỉ cho `ADMIN`. (4) Service nào cũng ký được token; lộ ở một nơi là
sập cả hệ thống.

Bài tiếp theo: [07 — OAuth2 và OpenID Connect](07-oauth2-va-openid-connect.md).
