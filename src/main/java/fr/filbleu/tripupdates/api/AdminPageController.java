package fr.filbleu.tripupdates.api;

import java.util.Map;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class AdminPageController {

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/")
    public String adminPage() {
        return "forward:/index.html";
    }

    @GetMapping("/visualisation")
    public String visualizationPage() {
        return "forward:/index.html";
    }

    @GetMapping("/api/admin/session")
    @ResponseBody
    public Map<String, Object> adminSession(
            Authentication authentication,
            @RequestAttribute("_csrf") CsrfToken csrfToken) {
        boolean admin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
        return Map.of("admin", admin, "csrfToken", csrfToken.getToken());
    }
}
