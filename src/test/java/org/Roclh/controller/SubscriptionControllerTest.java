package org.Roclh.controller;

import org.Roclh.model.ClientConfig;
import org.Roclh.service.ClientConfigService;
import org.Roclh.service.PublicUrlResolver;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.xray.XrayConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Подписка не должна быть отпечатком: нейтральный {@code Profile-Title} и
 * отбрасывание клиентских заголовков, которые светят бренд, домен или токен.
 */
class SubscriptionControllerTest {

    private static final String TOKEN = "s3cr3t-token-value";
    private static final String PUBLIC_URL = "https://pohr-roclh.xyz";

    private SubscriptionService subscriptionService;
    private XrayConfigService xrayConfigService;
    private ClientConfigService clientConfigService;
    private PublicUrlResolver publicUrlResolver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(SubscriptionService.class);
        xrayConfigService = mock(XrayConfigService.class);
        clientConfigService = mock(ClientConfigService.class);
        publicUrlResolver = mock(PublicUrlResolver.class);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new SubscriptionController(
                        subscriptionService, xrayConfigService, clientConfigService, publicUrlResolver))
                .build();

        when(subscriptionService.findByToken(TOKEN))
                .thenReturn(Optional.of(new org.Roclh.model.Subscription()));
        when(xrayConfigService.buildVlessLinks(any()))
                .thenReturn(List.of("vless://uuid@127.0.0.1:443?security=reality#vless-1"));
        when(publicUrlResolver.resolve(any())).thenReturn(PUBLIC_URL);
    }

    @Test
    @DisplayName("Default Profile-Title нейтральный, без бренда")
    void defaultProfileTitleIsNeutral() throws Exception {
        mockMvc.perform(get("/sub/{token}", TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string("Profile-Title", "Subscription"))
                .andExpect(header().string("Profile-Update-Interval", "6"));
    }

    @Test
    @DisplayName("Заголовки клиента, светящие бренд/домен/токен, отбрасываются")
    void leakyClientHeadersAreDropped() throws Exception {
        ClientConfig cfg = ClientConfig.builder().clientName("happ").headers("x").build();
        when(clientConfigService.findByUserAgent("Happ/1.0")).thenReturn(Optional.of(cfg));

        Map<String, String> parsed = new LinkedHashMap<>();
        parsed.put("profile-title", "Pohr");
        parsed.put("profile-update-interval", "6");
        parsed.put("profile-web-page-url", "https://pohr-roclh.xyz/home");
        parsed.put("x-custom-token", "{{TOKEN}}");
        parsed.put("x-keep", "ok");
        when(clientConfigService.parseHeaders(anyString())).thenReturn(parsed);

        mockMvc.perform(get("/sub/{token}", TOKEN).header("User-Agent", "Happ/1.0"))
                .andExpect(status().isOk())
                .andExpect(header().string("Profile-Title", "Subscription"))
                .andExpect(header().string("Profile-Update-Interval", "6"))
                .andExpect(header().doesNotExist("Profile-Web-Page-Url"))
                .andExpect(header().doesNotExist("X-Custom-Token"))
                .andExpect(header().string("X-Keep", "ok"));
    }
}
