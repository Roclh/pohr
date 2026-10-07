package org.Roclh.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.Subscription;
import org.Roclh.model.User;
import org.Roclh.model.script.Script;
import org.Roclh.model.script.ScriptAccessLevel;
import org.Roclh.model.script.ScriptDownload;
import org.Roclh.model.script.ScriptVersion;
import org.Roclh.repository.script.ScriptDownloadRepository;
import org.Roclh.service.ClientScriptService;
import org.Roclh.service.PublicUrlResolver;
import org.Roclh.service.SubscriptionService;
import org.Roclh.service.UserService;
import org.Roclh.service.script.*;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.*;

@Slf4j
@Controller
@RequestMapping("/sub/{token}/scripts")
@RequiredArgsConstructor
public class ScriptApiController {

    private final SubscriptionService subscriptionService;
    private final UserService userService;
    private final ClientScriptService fileService;
    private final PublicUrlResolver urlResolver;
    private final ScriptCatalogService catalog;
    private final ScriptVersionService versionService;
    private final ScriptRenderer renderer;
    private final ScriptSyncService syncService;
    private final ScriptErrorReportService errorService;
    private final ScriptUpdateService updateService;
    private final ScriptDownloadRepository downloadRepository;

    private record Ctx(UUID userId, boolean isAdmin) {}

    private Ctx resolve(String token) {
        Subscription sub = subscriptionService.findByToken(token).orElse(null);
        if (sub == null) return null;
        User user = userService.findById(sub.getUserId()).orElse(null);
        if (user == null) return null;
        boolean admin = UserService.ROLE_ADMIN.equals(user.getRole());
        return new Ctx(user.getId(), admin);
    }

    /**
     * Admin видит всё, включая disabled.
     * Не-админ — только enabled + PUBLIC.
     */
    private boolean canSee(Ctx ctx, Script s) {
        if (ctx.isAdmin()) return true;
        return s.isEnabled() && s.getAccessLevel() == ScriptAccessLevel.PUBLIC;
    }

    /** Для не-админа оставляем только PUBLIC и enabled имена. */
    private Set<String> visibleDeps(Ctx ctx, Set<String> names) {
        if (ctx.isAdmin()) return names;
        return names.stream()
                .filter(n -> catalog.findByName(n)
                        .map(s -> s.isEnabled() && s.getAccessLevel() == ScriptAccessLevel.PUBLIC)
                        .orElse(false))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String ip(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return req.getRemoteAddr();
    }

    private String ua(HttpServletRequest req) {
        return req.getHeader("User-Agent");
    }

    // =====================================================================
    // HTML: preview
    // =====================================================================

    @GetMapping("/{name}")
    public String preview(@PathVariable String token,
                          @PathVariable String name,
                          Model model,
                          HttpServletRequest req) {
        Ctx ctx = resolve(token);
        if (ctx == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        Script s = catalog.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!canSee(ctx, s)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);

        ScriptVersion active = versionService.getActive(s.getId()).orElse(null);
        if (active == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);

        String rawContent;
        try {
            rawContent = fileService.read(name);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }

        String base = urlResolver.resolve(req);
        String rendered = renderer.render(rawContent,
                new ScriptRenderer.RenderContext(base, token, active.getVersion()));

        log.info("Script preview: user={} admin={} script={} v={}",
                ctx.userId(), ctx.isAdmin(), name, active.getVersion());

        model.addAttribute("token", token);
        model.addAttribute("script", s);
        model.addAttribute("activeVersion", active);
        model.addAttribute("deps", visibleDeps(ctx, catalog.getDirectDependencies(name)));
        model.addAttribute("content", rendered);
        model.addAttribute("lineCount", (int) rendered.lines().count());
        model.addAttribute("size", rendered.getBytes().length);
        model.addAttribute("isAdmin", ctx.isAdmin());
        return "script-preview";
    }

    // =====================================================================
    // RAW: отдача файла
    // =====================================================================

    @GetMapping("/{name}/raw")
    public ResponseEntity<byte[]> raw(@PathVariable String token,
                                      @PathVariable String name,
                                      HttpServletRequest req) throws IOException {
        Ctx ctx = resolve(token);
        if (ctx == null) return ResponseEntity.notFound().build();

        Script s = catalog.findByName(name).orElse(null);
        if (s == null || !canSee(ctx, s)) return ResponseEntity.notFound().build();

        ScriptVersion active = versionService.getActive(s.getId()).orElse(null);
        if (active == null) return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();

        String rawContent = fileService.read(name);
        String base = urlResolver.resolve(req);
        byte[] bytes = renderer.renderBytes(name, rawContent,
                new ScriptRenderer.RenderContext(base, token, active.getVersion()));

        try {
            downloadRepository.save(ScriptDownload.builder()
                    .userId(ctx.userId())
                    .scriptId(s.getId())
                    .scriptVersion(active.getVersion())
                    .clientIp(ip(req))
                    .userAgent(ua(req))
                    .build());
        } catch (Exception e) {
            log.warn("Failed to log download of {}: {}", name, e.getMessage());
        }

        log.info("Script download: user={} admin={} script={} v={} ip={}",
                ctx.userId(), ctx.isAdmin(), name, active.getVersion(), ip(req));

        return ResponseEntity.ok()
                .header("X-Pohr-Script-Name", name)
                .header("X-Pohr-Script-Version", active.getVersion())
                .header("X-Pohr-Script-Hash", "sha256:" + active.getHash())
                .header("X-Pohr-Rendered-At", java.time.Instant.now().toString())
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bytes);
    }

    // =====================================================================
    // JSON: метаданные (для машин)
    // =====================================================================

    @GetMapping("/{name}/meta")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> meta(@PathVariable String token,
                                                    @PathVariable String name) {
        Ctx ctx = resolve(token);
        if (ctx == null) return ResponseEntity.notFound().build();

        Script s = catalog.findByName(name).orElse(null);
        if (s == null || !canSee(ctx, s)) return ResponseEntity.notFound().build();

        ScriptVersion active = versionService.getActive(s.getId()).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", s.getName());
        m.put("displayName", s.getDisplayName());
        m.put("description", s.getDescription());
        m.put("platform", s.getPlatform());
        m.put("entrypoint", s.isEntrypoint());
        if (active != null) {
            m.put("version", active.getVersion());
            m.put("hash", active.getHash());
            m.put("size", active.getSize());
            m.put("updatedAt", active.getCreatedAt().toString());
        }
        m.put("dependencies", visibleDeps(ctx, catalog.getDirectDependencies(name)));
        // reverseDeps — только админу: они могут раскрыть существование ADMIN-скриптов
        if (ctx.isAdmin()) {
            m.put("accessLevel", s.getAccessLevel().name());
            m.put("enabled", s.isEnabled());
            m.put("reverseDependencies", catalog.getReverseDependencies(name));
        }
        return ResponseEntity.ok(m);
    }

    // =====================================================================
    // POST: sync
    // =====================================================================

    public record SyncPayload(
            String rootScript,
            Map<String, String> versions,
            String reportedAt
    ) {}

    @PostMapping("/sync")
    @ResponseBody
    public ResponseEntity<?> sync(@PathVariable String token,
                                  @RequestBody SyncPayload payload,
                                  HttpServletRequest req) {
        Ctx ctx = resolve(token);
        if (ctx == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid token"));
        try {
            ScriptSyncService.SyncResponse resp = syncService.handle(
                    ctx.userId(),
                    new ScriptSyncService.SyncRequest(
                            payload.rootScript(), payload.versions(), payload.reportedAt()),
                    ip(req), ua(req));
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // =====================================================================
    // POST: error-report
    // =====================================================================

    public record ErrorPayload(
            String rootScript,
            String rootVersion,
            String stage,
            String reportText
    ) {}

    @PostMapping("/error-report")
    @ResponseBody
    public ResponseEntity<?> errorReport(@PathVariable String token,
                                         @RequestBody ErrorPayload payload,
                                         HttpServletRequest req) {
        Ctx ctx = resolve(token);
        if (ctx == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "invalid token"));
        UUID id = errorService.accept(ctx.userId(),
                new ScriptErrorReportService.ErrorRequest(
                        payload.rootScript(), payload.rootVersion(),
                        payload.stage(), payload.reportText(),
                        ip(req), ua(req)));
        return ResponseEntity.ok(Map.of("reportId", id.toString()));
    }

    // =====================================================================
    // JSON: проверка обновлений
    // =====================================================================

    @GetMapping("/updates")
    @ResponseBody
    public ResponseEntity<?> updates(@PathVariable String token,
                                     @RequestParam(defaultValue = "any") String platform) {
        Ctx ctx = resolve(token);
        if (ctx == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(
                updateService.checkForPlatform(ctx.userId(), platform, ctx.isAdmin()));
    }
}