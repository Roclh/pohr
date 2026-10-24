package org.Roclh.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.Roclh.model.User;
import org.Roclh.repository.UserRepository;
import org.Roclh.service.PublicUrlResolver;
import org.Roclh.service.mcp.McpSetupService;
import org.Roclh.service.mcp.McpTokenService;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;

/**
 * Настройка MCP для DeepSeek Harness: выпуск токенов, страница /admin/mcp
 * и генерация .bat-установщика (UAC + встроенный .ps1).
 */
@Controller
@RequestMapping("/admin/mcp")
@RequiredArgsConstructor
public class AdminMcpController {

    private static final String SESSION_NEW_TOKEN = "pohr.mcp.newToken";
    private static final String SETUP_FILE_NAME = "pohr-dsh-mcp-setup.bat";

    private final McpTokenService tokenService;
    private final McpSetupService setupService;
    private final PublicUrlResolver urlResolver;
    private final UserRepository userRepository;

    @GetMapping
    public String page(Model model, HttpServletRequest request,
                       HttpServletResponse response, Principal auth) {
        response.setHeader("Cache-Control", "no-store");
        User user = currentUser(auth);
        String mcpUrl = mcpUrl(request);

        String newToken = sessionToken(request, false);
        model.addAttribute("tokens", tokenService.listForUser(user.getId()));
        model.addAttribute("mcpUrl", mcpUrl);
        model.addAttribute("newToken", newToken);
        model.addAttribute("manualYaml", manualYaml(mcpUrl,
                newToken == null ? "pohr_ВАШ_ТОКЕН" : newToken));
        return "admin/mcp";
    }

    @PostMapping("/tokens")
    public String createToken(@RequestParam(required = false) String name,
                              HttpServletRequest request,
                              Principal auth,
                              RedirectAttributes flash) {
        try {
            McpTokenService.CreatedToken created =
                    tokenService.create(auth.getName(), name);
            HttpSession session = request.getSession(true);
            session.setAttribute(SESSION_NEW_TOKEN, created.rawToken());
            flash.addFlashAttribute("successMessage",
                    "Токен создан. Он показан на странице один раз — скопируйте или скачайте установщик.");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/mcp";
    }

    @PostMapping("/tokens/{id}/revoke")
    public String revoke(@PathVariable UUID id,
                         Principal auth,
                         RedirectAttributes flash) {
        try {
            tokenService.revoke(id, currentUser(auth).getId());
            flash.addFlashAttribute("successMessage", "Токен отозван");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/mcp";
    }

    @PostMapping("/tokens/forget")
    public String forget(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.removeAttribute(SESSION_NEW_TOKEN);
        }
        return "redirect:/admin/mcp";
    }

    /**
     * Один клик: создать токен и сразу отдать установщик.
     * Токен остаётся в сессии, поэтому после скачивания его можно увидеть
     * на /admin/mcp (если браузер не открыл файл в новой вкладке).
     */
    @PostMapping("/install")
    public ResponseEntity<byte[]> installNow(HttpServletRequest request, Principal auth) {
        McpTokenService.CreatedToken created = tokenService.create(auth.getName(), "DeepSeek Harness");
        request.getSession(true).setAttribute(SESSION_NEW_TOKEN, created.rawToken());
        return installerResponse(created.rawToken(), request);
    }

    /**
     * Отдаёт установщик с токеном. Токен передаётся в теле POST, а не в URL:
     * так он не попадает в access-логи и историю браузера.
     */
    @PostMapping("/setup.bat")
    public ResponseEntity<byte[]> setup(@RequestParam String token,
                                        HttpServletRequest request,
                                        Principal auth) {
        if (!tokenService.matchesUser(token, auth.getName())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return installerResponse(token, request);
    }

    private ResponseEntity<byte[]> installerResponse(String token, HttpServletRequest request) {
        String script = setupService.renderBat(mcpUrl(request), token, urlResolver.resolve(request));
        byte[] body = script.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(SETUP_FILE_NAME)
                .build());
        headers.setCacheControl(CacheControl.noStore());
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    private User currentUser(Principal auth) {
        return userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Пользователь не найден: " + auth.getName()));
    }

    private String mcpUrl(HttpServletRequest request) {
        return urlResolver.resolve(request) + "/mcp";
    }

    private String sessionToken(HttpServletRequest request, boolean create) {
        HttpSession session = request.getSession(create);
        if (session == null) return null;
        Object value = session.getAttribute(SESSION_NEW_TOKEN);
        return value == null ? null : value.toString();
    }

    private String manualYaml(String mcpUrl, String token) {
        return """
                - insert:
                    - id: mcp-pohr
                      name: "@deepseek-ai/dsh-mcp-client"
                      config:
                        serverName: pohr
                        transport: streamable-http
                        url: "%s"
                        headers:
                          Authorization: "Bearer %s"
                        toolCallTimeoutMs: 120000
                """.formatted(mcpUrl, token);
    }
}
