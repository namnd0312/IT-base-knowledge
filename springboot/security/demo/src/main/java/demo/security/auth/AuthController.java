package demo.security.auth;

import demo.security.auth.TokenService.IssuedToken;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Doi username/password lay JWT.
 *
 * Endpoint nay permitAll trong ApiSecurityConfig; viec kiem tra mat khau la do
 * AuthenticationManager lam, controller khong tu so sanh mat khau.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;

    public AuthController(AuthenticationManager authenticationManager, TokenService tokenService) {
        this.authenticationManager = authenticationManager;
        this.tokenService = tokenService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        // Token "chua xac thuc": chi mang thong tin nguoi dung khai bao
        Authentication unauthenticated =
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password());

        // Sai mat khau -> nem BadCredentialsException -> ExceptionTranslationFilter -> 401
        Authentication authenticated = authenticationManager.authenticate(unauthenticated);

        IssuedToken token = tokenService.issue(authenticated);
        return new LoginResponse(token.value(), "Bearer", token.expiresInSeconds());
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
    }
}
