package org.Roclh.controller;

import org.Roclh.controller.admin.AdminInviteController;
import org.Roclh.model.InviteToken;
import org.Roclh.model.User;
import org.Roclh.repository.UserRepository;
import org.Roclh.service.InviteService;
import org.Roclh.service.PanelUrlResolver;
import org.Roclh.service.PublicUrlResolver;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Инвайты ведут на хост панели ({@code pohr.panel-url}), а не на сайт-легенду:
 * после принятия приглашения пользователь попадает в /home и должен остаться
 * в той же сессии, то есть на том же origin.
 */
class InviteFlowPanelUrlTest {

    private static final String PANEL_URL = "https://panel.pohr-roclh.xyz";
    private static final String TOKEN = "invite-token-1";

    private InviteService inviteService;
    private UserService userService;
    private MockMvc acceptMvc;
    private MockMvc adminMvc;

    @BeforeEach
    void setUp() {
        inviteService = mock(InviteService.class);
        userService = mock(UserService.class);
        SubscriptionService subscriptionService = mock(SubscriptionService.class);
        PublicUrlResolver publicUrlResolver = mock(PublicUrlResolver.class);
        when(publicUrlResolver.resolve(any())).thenReturn("https://pohr-roclh.xyz");

        PanelUrlResolver panelUrlResolver = new PanelUrlResolver(publicUrlResolver);
        ReflectionTestUtils.setField(panelUrlResolver, "panelUrl", PANEL_URL);

        acceptMvc = MockMvcBuilders
                .standaloneSetup(new InviteAcceptController(
                        inviteService, userService, subscriptionService, panelUrlResolver))
                .build();

        UserRepository userRepository = mock(UserRepository.class);
        adminMvc = MockMvcBuilders
                .standaloneSetup(new AdminInviteController(inviteService, userRepository, panelUrlResolver))
                .build();
    }

    @Test
    @DisplayName("Принятие инвайта уводит на /home хоста панели")
    void acceptRedirectsToPanelHome() throws Exception {
        when(inviteService.isUsable(TOKEN)).thenReturn(true);
        when(userService.usernameTaken("newuser", null)).thenReturn(false);
        when(userService.create("newuser", "password123", "USER", true))
                .thenReturn(User.builder().id(UUID.randomUUID()).username("newuser")
                        .role("USER").enabled(true).build());
        when(inviteService.findByToken(TOKEN)).thenReturn(Optional.of(invite()));

        acceptMvc.perform(post("/invite/{token}", TOKEN)
                        .param("username", "newuser")
                        .param("password", "password123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", PANEL_URL + "/home"));
    }

    @Test
    @DisplayName("Негодный инвайт уводит на /login хоста панели")
    void unusableInviteRedirectsToPanelLogin() throws Exception {
        when(inviteService.isUsable(TOKEN)).thenReturn(false);

        acceptMvc.perform(get("/invite/{token}", TOKEN))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", PANEL_URL + "/login"));
    }

    @Test
    @DisplayName("Ссылка приглашения в админке строится от хоста панели")
    void adminInviteUrlUsesPanelHost() throws Exception {
        when(inviteService.findByToken(TOKEN)).thenReturn(Optional.of(invite()));

        adminMvc.perform(get("/admin/invites/{token}", TOKEN).principal(() -> "admin"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("inviteUrl", PANEL_URL + "/invite/" + TOKEN));
    }

    private static InviteToken invite() {
        return InviteToken.builder()
                .token(TOKEN)
                .role("USER")
                .createdBy(UUID.randomUUID())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
