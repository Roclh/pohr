package org.Roclh.controller.admin;

import org.Roclh.model.User;
import org.Roclh.model.mcp.McpToken;
import org.Roclh.repository.UserRepository;
import org.Roclh.service.PublicUrlResolver;
import org.Roclh.service.mcp.McpSetupService;
import org.Roclh.service.mcp.McpTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

class AdminMcpControllerTest {

    private static final String USERNAME = "admin";
    private static final String RAW_TOKEN = "pohr_abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

    private McpTokenService tokenService;
    private McpSetupService setupService;
    private UserRepository userRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        tokenService = mock(McpTokenService.class);
        setupService = mock(McpSetupService.class);
        PublicUrlResolver urlResolver = mock(PublicUrlResolver.class);
        userRepository = mock(UserRepository.class);

        User admin = User.builder()
                .id(UUID.randomUUID())
                .username(USERNAME)
                .passwordHash("x")
                .role("ADMIN")
                .enabled(true)
                .build();
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(admin));
        when(urlResolver.resolve(any())).thenReturn("https://pohr.example.com");
        when(tokenService.listForUser(any())).thenReturn(List.of());

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminMcpController(tokenService, setupService, urlResolver, userRepository))
                .build();
    }

    @Test
    @DisplayName("GET /admin/mcp отдаёт страницу и endpoint")
    void page() throws Exception {
        mockMvc.perform(get("/admin/mcp").principal(() -> USERNAME))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/mcp"))
                .andExpect(model().attribute("mcpUrl", "https://pohr.example.com/mcp"));
    }

    @Test
    @DisplayName("POST /admin/mcp/tokens создаёт токен и кладёт его в сессию")
    void createToken() throws Exception {
        McpToken token = McpToken.builder().id(UUID.randomUUID()).build();
        when(tokenService.create(eq(USERNAME), anyString()))
                .thenReturn(new McpTokenService.CreatedToken(token, RAW_TOKEN));

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/admin/mcp/tokens").param("name", "DSH").session(session)
                        .principal(() -> USERNAME))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/mcp"))
                .andExpect(flash().attributeExists("successMessage"));

        org.assertj.core.api.Assertions.assertThat(session.getAttribute("pohr.mcp.newToken"))
                .isEqualTo(RAW_TOKEN);
    }

    @Test
    @DisplayName("POST /admin/mcp/install создаёт токен и отдаёт .ps1")
    void installNow() throws Exception {
        McpToken token = McpToken.builder().id(UUID.randomUUID()).build();
        when(tokenService.create(eq(USERNAME), anyString()))
                .thenReturn(new McpTokenService.CreatedToken(token, RAW_TOKEN));
        when(setupService.renderBat(anyString(), eq(RAW_TOKEN), anyString())).thenReturn("@echo off");

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/admin/mcp/install").session(session).principal(() -> USERNAME))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("pohr-dsh-mcp-setup.bat")))
                .andExpect(content().string("@echo off"));

        org.assertj.core.api.Assertions.assertThat(session.getAttribute("pohr.mcp.newToken"))
                .isEqualTo(RAW_TOKEN);
    }

    @Test
    @DisplayName("POST /admin/mcp/setup.bat проверяет токен и отдаёт установщик")
    void setupBat() throws Exception {
        when(tokenService.matchesUser(RAW_TOKEN, USERNAME)).thenReturn(true);
        when(setupService.renderBat(anyString(), eq(RAW_TOKEN), anyString())).thenReturn("@echo off");

        mockMvc.perform(post("/admin/mcp/setup.bat").param("token", RAW_TOKEN)
                        .principal(() -> USERNAME))
                .andExpect(status().isOk())
                .andExpect(content().string("@echo off"));
    }

    @Test
    @DisplayName("POST /admin/mcp/setup.bat с чужим токеном отвечает 403")
    void setupBatRejectsToken() throws Exception {
        when(tokenService.matchesUser(RAW_TOKEN, USERNAME)).thenReturn(false);

        mockMvc.perform(post("/admin/mcp/setup.bat").param("token", RAW_TOKEN)
                        .principal(() -> USERNAME))
                .andExpect(status().isForbidden());
    }
}
