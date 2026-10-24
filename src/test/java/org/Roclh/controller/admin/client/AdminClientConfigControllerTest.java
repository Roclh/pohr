package org.Roclh.controller.admin.client;

import org.Roclh.model.ClientConfig;
import org.Roclh.model.dto.ClientConfigForm;
import org.Roclh.service.ClientConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Unit-тесты {@link AdminClientConfigController} на MockMvc.
 *
 * <p>Используется standalone-конфигурация MockMvc: поднимается только сам контроллер
 * с замоканным {@link ClientConfigService}, без Spring-контекста, БД, Liquibase и
 * security-фильтров (поэтому POST не требует CSRF-токена). Проверяются маппинги,
 * биндинг формы, валидация, flash-атрибуты и — главное — адреса редиректов.
 */
class AdminClientConfigControllerTest {

    private static final String NAME = "happ";
    private static final String UA = "Happ";
    private static final String HEADERS = "profile-title: Pohr";

    private ClientConfigService clientConfigService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        clientConfigService = mock(ClientConfigService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminClientConfigController(clientConfigService))
                .build();
    }

    @Test
    @DisplayName("GET /admin/client-configs отдаёт список конфигов")
    void listReturnsConfigs() throws Exception {
        when(clientConfigService.findAll()).thenReturn(List.of(
                config("happ", "Happ"),
                config("v2rayng", "v2rayNG")));

        mockMvc.perform(get("/admin/client-configs"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/client/client-configs"))
                .andExpect(model().attribute("configs", hasSize(2)));
    }

    @Test
    @DisplayName("GET .../{name}/edit заполняет форму текущими значениями")
    void editFormPopulatesFormFromConfig() throws Exception {
        when(clientConfigService.findByName(NAME))
                .thenReturn(Optional.of(config(NAME, UA)));

        mockMvc.perform(get("/admin/client-configs/{name}/edit", NAME))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/client/client-config-form"))
                .andExpect(model().attribute("name", NAME))
                .andExpect(model().attribute("form", new ClientConfigForm(true, UA, HEADERS)));
    }

    @Test
    @DisplayName("GET .../{unknown}/edit возвращает 404")
    void editFormReturns404ForUnknownConfig() throws Exception {
        when(clientConfigService.findByName("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/client-configs/{name}/edit", "unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST сохраняет конфиг и редиректит на существующую страницу редактирования")
    void savePersistsConfigAndRedirectsToEditPage() throws Exception {
        when(clientConfigService.update(NAME, true, UA, HEADERS))
                .thenReturn(config(NAME, UA));
        when(clientConfigService.findByName(NAME))
                .thenReturn(Optional.of(config(NAME, UA)));

        MvcResult result = mockMvc.perform(post("/admin/client-configs/{name}", NAME)
                        .param("enabled", "true")
                        .param("userAgentPattern", UA)
                        .param("headers", HEADERS))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/client-configs/happ/edit"))
                .andExpect(flash().attribute("successMessage", "flash.clientConfig.saved"))
                .andReturn();

        verify(clientConfigService).update(NAME, true, UA, HEADERS);

        // Регрессия: раньше редирект вёл на несуществующий /admin/client/client-configs/...
        // Проверяем, что цель редиректа реально обслуживается контроллером.
        mockMvc.perform(get(result.getResponse().getRedirectedUrl()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/client/client-config-form"));
    }

    @Test
    @DisplayName("POST не редиректит на несуществующий путь /admin/client/...")
    void saveNeverRedirectsToNonExistentAdminClientPath() throws Exception {
        when(clientConfigService.update(anyString(), anyBoolean(), anyString(), any()))
                .thenReturn(config(NAME, UA));

        MvcResult result = mockMvc.perform(post("/admin/client-configs/{name}", NAME)
                        .param("enabled", "true")
                        .param("userAgentPattern", UA)
                        .param("headers", HEADERS))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        assertThat(result.getResponse().getRedirectedUrl())
                .isEqualTo("/admin/client-configs/happ/edit")
                .doesNotStartWith("/admin/client/client-configs");
    }

    @Test
    @DisplayName("POST с пустым User-Agent pattern не сохраняет и возвращает форму с ошибкой")
    void saveRejectsBlankUserAgentPattern() throws Exception {
        mockMvc.perform(post("/admin/client-configs/{name}", NAME)
                        .param("enabled", "true")
                        .param("userAgentPattern", "")
                        .param("headers", HEADERS))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/client/client-config-form"))
                .andExpect(model().attribute("name", NAME))
                .andExpect(model().attributeHasFieldErrors("form", "userAgentPattern"));

        verify(clientConfigService, never()).update(anyString(), anyBoolean(), anyString(), any());
    }

    @Test
    @DisplayName("POST со слишком длинным User-Agent pattern не сохраняет конфиг")
    void saveRejectsTooLongUserAgentPattern() throws Exception {
        mockMvc.perform(post("/admin/client-configs/{name}", NAME)
                        .param("enabled", "true")
                        .param("userAgentPattern", "H".repeat(129))
                        .param("headers", HEADERS))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/client/client-config-form"))
                .andExpect(model().attributeHasFieldErrors("form", "userAgentPattern"));

        verify(clientConfigService, never()).update(anyString(), anyBoolean(), anyString(), any());
    }

    @Test
    @DisplayName("POST с ошибкой сервиса редиректит на форму с errorMessage и без successMessage")
    void saveServiceFailureRedirectsWithErrorFlash() throws Exception {
        when(clientConfigService.update(NAME, false, UA, HEADERS))
                .thenThrow(new IllegalArgumentException("Unknown client: happ"));

        MvcResult result = mockMvc.perform(post("/admin/client-configs/{name}", NAME)
                        .param("enabled", "false")
                        .param("userAgentPattern", UA)
                        .param("headers", HEADERS))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/client-configs/happ/edit"))
                .andExpect(flash().attribute("errorMessage", "Unknown client: happ"))
                .andReturn();

        Map<String, Object> flashMap = result.getFlashMap();
        assertThat(flashMap)
                .containsEntry("errorMessage", "Unknown client: happ")
                .doesNotContainKey("successMessage");
    }

    private static ClientConfig config(String name, String userAgentPattern) {
        return ClientConfig.builder()
                .clientName(name)
                .enabled(true)
                .userAgentPattern(userAgentPattern)
                .headers(HEADERS)
                .build();
    }
}
