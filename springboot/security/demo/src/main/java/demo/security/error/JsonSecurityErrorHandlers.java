package demo.security.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Tra loi bao mat dang JSON cho REST API thay vi trang HTML / redirect mac dinh.
 *
 *   AuthenticationEntryPoint -> 401: chua biet ban la ai (thieu token, token sai, het han)
 *   AccessDeniedHandler      -> 403: biet ban la ai nhung ban khong du quyen
 *
 * Luu y: khong tra chi tiet loi noi bo (vi du ly do token sai) ra cho client.
 */
@Component
public class JsonSecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // RFC 6750: 401 cua bearer token phai kem header WWW-Authenticate
        response.setHeader("WWW-Authenticate", "Bearer");
        write(response, HttpStatus.UNAUTHORIZED, "Chua xac thuc hoac token khong hop le");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(response, HttpStatus.FORBIDDEN, "Khong du quyen truy cap tai nguyen nay");
    }

    private static void write(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(
                "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\"}"
                        .formatted(status.value(), status.getReasonPhrase(), message));
    }
}
