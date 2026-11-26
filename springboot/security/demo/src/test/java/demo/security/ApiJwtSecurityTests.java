package demo.security;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Kiem thu chain /api/** (stateless + JWT).
 *
 * Hai cach co Authentication trong test:
 *   1. Di that qua /api/auth/login de lay token (kiem ca luong phat + verify token).
 *   2. Dung jwt() cua spring-security-test de "gia lap" 1 JWT da verify (nhanh, tap trung vao phan quyen).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiJwtSecurityTests {

    @Autowired
    MockMvc mockMvc;

    @Test
    void publicEndpointNeedsNoToken() throws Exception {
        mockMvc.perform(get("/api/public/hello")).andExpect(status().isOk());
    }

    @Test
    void protectedEndpointWithoutTokenReturns401Json() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void garbageTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"user\",\"password\":\"sai-mat-khau\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginThenCallProtectedEndpointWithIssuedToken() throws Exception {
        String token = login("user", "password");

        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("user"))
                .andExpect(jsonPath("$.authorities", hasItem("ROLE_USER")));
    }

    @Test
    void userTokenIsForbiddenOnAdminUrl() throws Exception {
        String token = login("user", "password");

        mockMvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void adminTokenCanAccessAdminUrl() throws Exception {
        String token = login("admin", "admin123");

        mockMvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    // ---- Method security: quyen theo du lieu, dat o NoteService ----

    @Test
    void ownerCanReadOwnNote() throws Exception {
        mockMvc.perform(get("/api/notes/1").with(jwt().jwt(j -> j.subject("user"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owner").value("user"));
    }

    @Test
    void otherUserCannotReadSomeoneElsesNote() throws Exception {
        mockMvc.perform(get("/api/notes/2").with(jwt().jwt(j -> j.subject("user"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReadAnyNote() throws Exception {
        mockMvc.perform(get("/api/notes/1")
                        .with(jwt().jwt(j -> j.subject("admin"))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void onlyAdminCanDeleteNote() throws Exception {
        mockMvc.perform(delete("/api/notes/1").with(jwt().jwt(j -> j.subject("user"))))
                .andExpect(status().isForbidden());
    }

    /** API stateless khong dung cookie nen POST khong can CSRF token. */
    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }
}
