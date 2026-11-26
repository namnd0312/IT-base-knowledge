package demo.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/** Kiem thu chain web (form login + session + CSRF). */
@SpringBootTest
@AutoConfigureMockMvc
class WebFormLoginSecurityTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void homePageIsPublic() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void anonymousIsRedirectedToLoginPage() throws Exception {
        mockMvc.perform(get("/web/home"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void formLoginWithCorrectPasswordAuthenticates() throws Exception {
        mockMvc.perform(formLogin().user("user").password("password"))
                .andExpect(authenticated().withUsername("user").withRoles("USER"));
    }

    @Test
    void formLoginWithWrongPasswordFails() throws Exception {
        mockMvc.perform(formLogin().user("user").password("sai"))
                .andExpect(unauthenticated())
                .andExpect(redirectedUrl("/login?error"));
    }

    /** @WithMockUser dat san 1 Authentication vao SecurityContext, bo qua buoc dang nhap. */
    @Test
    @WithMockUser(username = "user")
    void authenticatedUserSeesHome() throws Exception {
        mockMvc.perform(get("/web/home"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("Xin chao user")));
    }

    @Test
    @WithMockUser(username = "user")
    void postWithoutCsrfTokenIsForbiddenEvenWhenLoggedIn() throws Exception {
        mockMvc.perform(post("/web/profile/display-name").param("value", "An"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "user")
    void postWithCsrfTokenSucceeds() throws Exception {
        mockMvc.perform(post("/web/profile/display-name").param("value", "An").with(csrf()))
                .andExpect(status().isOk());
    }
}
