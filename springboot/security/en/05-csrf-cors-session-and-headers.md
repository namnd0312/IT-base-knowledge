# 05 — CSRF, CORS, session and security headers

Goal: after this lesson you can explain which attack each mechanism defends against, and make a reasoned decision
about when CSRF may be turned off and how to configure CORS, instead of copying `csrf.disable()` from the internet.

## 1. CSRF (Cross-Site Request Forgery)

### The attack

You are logged in to `bank.com` (the browser holds the session cookie). You open `evil.com` in another tab. That page contains:

```html
<form action="https://bank.com/transfer" method="post">
  <input name="to" value="attacker"><input name="amount" value="50000000">
</form>
<script>document.forms[0].submit()</script>
```

The browser sends the request to `bank.com` and **automatically attaches your session cookie**. The server sees a
valid session and performs the transfer. The attacker needs neither your password nor the ability to read the
cookie; they only borrow your browser.

The condition for CSRF: **credentials that the browser sends automatically** (cookies, stored HTTP Basic).

### The defence: a token other sites do not know

```
1. The server generates a CSRF token tied to the session and embeds it in the form:
     <input name="_csrf" type="hidden" value="qlgmYz1F...">
2. The legitimate form submits the token → CsrfFilter compares → allowed
3. evil.com cannot read bank.com's page (same-origin policy), so it does not know the token
   → the forged request lacks the token → 403
```

Spring Security enables this for every state-changing request: `POST`, `PUT`, `PATCH`, `DELETE`.
`GET`, `HEAD`, `OPTIONS` are skipped, so **never use `GET` to change data**.

Run for real on the demo, logged in but `POST`ing without a token:

```bash
curl -i -b cookies.txt -X POST http://localhost:8088/web/profile/display-name -d 'value=An'
# HTTP/1.1 403
```

A detail worth knowing: every time the `/login` page loads, the `_csrf` value in the HTML differs even within the
same session. Spring masks the real token with a random value on each render (against the BREACH attack); the
server still recovers the same underlying token.

### When CSRF may be turned off

| Application | Authenticates with | CSRF |
|-------------|--------------------|------|
| Server-rendered web (Thymeleaf, JSP) | Session cookie | **On**. Templates insert the token |
| SPA + backend using a session cookie | Session cookie | **On**, configured SPA-style (below) |
| REST API for mobile or other services | `Authorization: Bearer` | Can be off |
| SPA keeping the token in memory, attaching the header itself | `Authorization: Bearer` | Can be off |
| API using JWT but **keeping the JWT in a cookie** | Cookie | **On**. It is still an auto-sent cookie |

The rule: **CSRF depends on whether the browser sends the credentials automatically**, not on "stateless" or
"uses JWT". The demo turns CSRF off for the `/api/**` chain because that chain only accepts the `Authorization` header:

```java
http.securityMatcher("/api/**")
    .csrf(csrf -> csrf.disable())
    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

The web chain keeps the default. Turn it off per chain, never for the whole application.

### CSRF for an SPA using a session cookie

An SPA has no server-rendered form, so it needs another way to get the token: the server puts the token in an
`XSRF-TOKEN` cookie (readable by JavaScript), the SPA reads it and sends it back in the `X-XSRF-TOKEN` header.
Axios and Angular do this automatically.

```java
http.csrf(csrf -> csrf.spa());   // Spring Security 7
```

Spring Security 6 has no `spa()`; configure it by hand with `CookieCsrfTokenRepository.withHttpOnlyFalse()` plus a
request handler, following the "Single-Page Applications" section of the official documentation.

Skipping CSRF for a group of URLs (for example webhooks called by an external system and authenticated by their own signature):

```java
http.csrf(csrf -> csrf.ignoringRequestMatchers("/webhooks/**"));
```

## 2. CORS (Cross-Origin Resource Sharing)

### CORS is not a server protection mechanism

Browsers enforce the **same-origin policy**: JavaScript at `https://app.example.com` cannot by default read
responses from `https://api.example.com` (a different origin: a different scheme, domain or port is enough).
CORS is how the server **relaxes** that policy for origins it trusts.

| | CSRF | CORS |
|---|---|---|
| What it is | An attack | A relaxation mechanism of the browser |
| Configured to | Block forged requests | Let a frontend on another origin call the API |
| Enforced by | The server | The browser |
| `curl`, Postman, mobile apps | Still checked | Irrelevant, never blocked |

CORS errors only appear in browsers. Postman working while the browser reports a CORS error is normal.

### Preflight

For "non-simple" requests (an `Authorization` header, `Content-Type: application/json`, `PUT`/`DELETE`), the
browser first sends an `OPTIONS` request to ask for permission:

```
OPTIONS /api/me
Origin: https://app.example.com
Access-Control-Request-Method: GET
Access-Control-Request-Headers: authorization
        │
        ▼ the server answers
Access-Control-Allow-Origin: https://app.example.com
Access-Control-Allow-Methods: GET, POST, PUT, DELETE
Access-Control-Allow-Headers: Authorization, Content-Type
        │
        ▼ allowed → only now does the browser send the real request
```

A preflight **carries no token**. If Spring Security demands authentication for the `OPTIONS` request too, the
preflight gets 401 and the browser reports a CORS error. So CORS must be handled **before** authentication, with `http.cors(...)`.

### Configuration

```java
@Bean
CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of("https://app.example.com"));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(true);   // allow cookies; enable only if you really use cookies
    config.setMaxAge(3600L);            // the browser caches the preflight result for 1 hour

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
}

// in the SecurityFilterChain
http.cors(Customizer.withDefaults());   // uses the CorsConfigurationSource bean above
```

Notes:

- Configuring only `@CrossOrigin` or `WebMvcConfigurer.addCorsMappings` and forgetting `http.cors(...)` leaves the
  preflight blocked by Spring Security before it reaches Spring MVC.
- `allowedOrigins("*")` together with `allowCredentials(true)` is forbidden by the standard; Spring throws. List specific origins.
- Do not reflect back whatever `Origin` arrives. That equals letting any website read the data of a logged-in user.
- An origin has no trailing `/`: `https://app.example.com`, not `https://app.example.com/`.

## 3. Session fixation

The attack: the attacker obtains a valid (not logged-in) session id from the server, tricks the victim into using
that same id (via a link or a planted cookie), and waits for the victim to log in. If the server keeps the id, the
attacker now holds a logged-in session.

The defence: **change the session id right after a successful login**. Spring Security does it out of the box.
Observe it on the demo: the `JSESSIONID` cookie in the response to `POST /login` differs from the one before.

```
Before login:      Set-Cookie: JSESSIONID=465474FC788A12D5F2E6102881BA1B8E
After POST /login: Set-Cookie: JSESSIONID=05B9E91983AFA64D2F718745167625CC
```

Nothing to configure. Just do not turn it off (`sessionFixation(f -> f.none())`).

## 4. Security headers

Spring Security adds them to every response. The real headers from the demo:

```
X-Content-Type-Options: nosniff
X-XSS-Protection: 0
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Pragma: no-cache
Expires: 0
X-Frame-Options: DENY
```

| Header | Defends against |
|--------|-----------------|
| `X-Content-Type-Options: nosniff` | The browser guessing the content type and running an uploaded file as script |
| `X-Frame-Options: DENY` | Clickjacking: another site embeds your page in an invisible `<iframe>` to trick users into clicking |
| `Cache-Control: no-store` | Pages with private data being kept in a browser or proxy cache |
| `X-XSS-Protection: 0` | Turns off the old browser XSS filter, which itself caused vulnerabilities. Replaced by CSP |
| `Strict-Transport-Security` (HSTS) | Downgrade to HTTP. Only sent when the request arrives over HTTPS, so you will not see it locally |

The header you need to configure yourself is **Content-Security-Policy**: the most effective browser-side XSS defence.

```java
http.headers(headers -> headers
        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'"))
        .frameOptions(frame -> frame.sameOrigin())   // relax if you need same-origin iframes yourself
        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000)));
```

## 5. XSS and what Spring Security does not do for you

XSS (Cross-Site Scripting): the attacker manages to inject JavaScript into your page, and the script runs with the
victim's privileges. Spring Security only limits the damage (`HttpOnly` cookies, CSP). The root defence belongs to
the rendering layer:

- Always escape data when printing it into HTML. Thymeleaf `th:text` escapes; `th:utext` does not.
- Do not concatenate user input into HTML or into `innerHTML`.
- Validate and limit input on the server.

Likewise, SQL injection is decided by how queries are written (use parameters), not by Spring Security.

## 6. Quick decision table

| Question | Answer |
|----------|--------|
| Can I turn CSRF off? | Only when no credential is sent automatically by the browser |
| A frontend on another domain gets CORS errors? | Declare a `CorsConfigurationSource` + `http.cors(...)`, list specific origins |
| The `OPTIONS` preflight gets 401? | `http.cors(...)` is missing |
| I need to embed a page in my own iframe? | `frameOptions(f -> f.sameOrigin())` |
| Session cookie going to production? | `HttpOnly`, `Secure`, `SameSite=Lax` or stricter |

## 7. Self-check

1. An API uses JWT, but the frontend keeps the JWT in an `HttpOnly` cookie. Is turning CSRF off safe?
2. Postman calls the API fine, the browser reports a CORS error. Is the server broken?
3. Why is `GET /account/delete?id=5` a dangerous design even with CSRF on?
4. Which attack does `X-Frame-Options: DENY` defend against?

Short answers: (1) No. The cookie is still sent automatically, so CSRF still applies. (2) The server has not allowed
the frontend's origin; Postman does not apply the same-origin policy and is unaffected. (3) CSRF protection skips
`GET` by default; an `<img src=...>` tag on another page is enough to trigger it. (4) Clickjacking.

Next: [06 — JWT and stateless REST API](06-jwt-and-stateless-rest-api.md).
