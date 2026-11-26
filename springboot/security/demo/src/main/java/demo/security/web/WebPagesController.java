package demo.security.web;

import java.security.Principal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cac "trang" cua phan web dung session (tra text cho gon, khong can template).
 * Dung de thuc hanh form login, cookie JSESSIONID va CSRF.
 */
@RestController
public class WebPagesController {

    /** permitAll. */
    @GetMapping("/")
    public String index() {
        return "Trang chu cong khai. Thu vao /web/home de bi chuyen sang /login.";
    }

    /** Can dang nhap. Chua dang nhap -> 302 ve /login. */
    @GetMapping("/web/home")
    public String home(Principal principal) {
        return "Xin chao " + principal.getName() + ", ban dang dung session cookie.";
    }

    /**
     * Request thay doi du lieu (POST) trong chain co session:
     * thieu CSRF token -> 403 du da dang nhap.
     */
    @PostMapping("/web/profile/display-name")
    public String changeDisplayName(@RequestParam String value, Principal principal) {
        return "Da doi ten hien thi cua " + principal.getName() + " thanh: " + value;
    }
}
