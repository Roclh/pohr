package org.Roclh.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.EnrollmentToken;
import org.Roclh.model.EuNode;
import org.Roclh.model.User;
import org.Roclh.model.dto.EuNodeDto;
import org.Roclh.model.dto.EuNodeForm;
import org.Roclh.model.dto.EuNodeProfileForm;
import org.Roclh.repository.UserRepository;
import org.Roclh.service.node.EuConfigService;
import org.Roclh.service.node.EuNodeProfileService;
import org.Roclh.service.node.EuNodeService;
import org.Roclh.service.xray.XrayConfigMaterializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Controller
@RequestMapping("/admin/nodes")
@RequiredArgsConstructor
public class AdminEuNodeController {

    private final EuNodeService nodeService;
    private final EuNodeProfileService profileService;
    private final EuConfigService euConfigService;
    private final UserRepository userRepository;
    private final XrayConfigMaterializer materializer;

    @Value("${pohr.public-url:}")
    private String publicUrlOverride;

    @GetMapping
    public String list(Model model) {
        List<EuNodeDto> nodes = new ArrayList<>(
                nodeService.findAll().stream()
                        .map(EuNodeDto::from)
                        .toList());
        model.addAttribute("nodes", nodes);
        return "admin/eu/eu-nodes";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new EuNodeForm("", 60));
        return "admin/eu/eu-node-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") EuNodeForm form,
                         BindingResult binding,
                         Authentication auth,
                         Model model,
                         RedirectAttributes flash) {
        if (nodeService.nameTaken(form.name())) {
            binding.rejectValue("name", "validation.node.name.taken");
        }
        if (binding.hasErrors()) {
            return "admin/eu/eu-node-form";
        }
        UUID userId = findUserId(auth);
        EnrollmentToken token = nodeService.createEnrollmentToken(
                form.name(), userId, Duration.ofMinutes(form.ttlMinutes()));
        flash.addFlashAttribute("successMessage", "flash.node.tokenCreated");
        return "redirect:/admin/nodes/enroll/" + token.getToken();
    }

    /** Страница с enroll-командой. Показывается один раз — потом токен протухнет. */
    @GetMapping("/enroll/{token}")
    public String enrollPage(@PathVariable String token,
                             HttpServletRequest request,
                             Model model,
                             RedirectAttributes flash) {
        EnrollmentToken et = nodeService.findToken(token).orElse(null);
        if (et == null) {
            flash.addFlashAttribute("errorMessage", "error.node.tokenNotFound");
            return "redirect:/admin/nodes";
        }
        String base = resolveBaseUrl(request);
        String enrollCmd = "curl -sL " + base + "/api/nodes/bootstrap.sh?token=" + token + " | sudo bash";
        model.addAttribute("token", et);
        model.addAttribute("enrollCommand", enrollCmd);
        model.addAttribute("expiresAt", et.getExpiresAt());
        return "admin/eu/eu-node-enroll";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        if (!populateDetail(id, model)) {
            flash.addFlashAttribute("errorMessage", "error.node.notfound");
            return "redirect:/admin/nodes";
        }
        return "admin/eu/eu-node-detail";
    }

    // --- Профиль REALITY (Т03) ------------------------------------------------

    /**
     * «Применить»: валидирует, сохраняет профиль, материализует RU-конфиг и
     * перезапускает Xray. Перезапуск рвёт клиентские сессии на несколько
     * секунд, поэтому это отдельное действие, а не сохранение формы.
     */
    @PostMapping("/{id}/profile")
    public String applyProfile(@PathVariable UUID id,
                               @ModelAttribute("profileForm") EuNodeProfileForm form,
                               Principal principal,
                               Model model,
                               RedirectAttributes flash) {
        try {
            EuNodeProfileService.ApplyResult result = profileService.apply(id, form, actor(principal));
            if (!result.applied()) {
                if (!populateDetail(id, model)) {
                    flash.addFlashAttribute("errorMessage", "error.node.notfound");
                    return "redirect:/admin/nodes";
                }
                model.addAttribute("profileForm", form.normalized());
                model.addAttribute("validation", result.validation());
                return "admin/eu/eu-node-detail";
            }
            flash.addFlashAttribute("successMessage", "flash.node.profileApplied");
            flash.addFlashAttribute("profileWarnings", result.validation().warnings());
            flash.addFlashAttribute("xrayRestarted", result.restarted());
        } catch (Exception e) {
            log.warn("Failed to apply profile to node {}: {}", id, e.getMessage(), e);
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/nodes/" + id;
    }

    /** «Проверить»: только валидация, без сохранения и без перезапуска Xray. */
    @PostMapping("/{id}/profile/validate")
    public String validateProfile(@PathVariable UUID id,
                                  @ModelAttribute("profileForm") EuNodeProfileForm form,
                                  Model model,
                                  RedirectAttributes flash) {
        EuNode node = nodeService.findById(id).orElse(null);
        if (node == null) {
            flash.addFlashAttribute("errorMessage", "error.node.notfound");
            return "redirect:/admin/nodes";
        }
        populateDetail(id, model);
        model.addAttribute("profileForm", form.normalized());
        model.addAttribute("validation", profileService.validate(node, form));
        return "admin/eu/eu-node-detail";
    }

    /** Сгенерированный EU-конфиг текущего профиля (с фолбэком на глобальные значения). */
    @GetMapping(value = "/{id}/profile/preview", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    public ResponseEntity<String> previewProfile(@PathVariable UUID id) {
        return nodeService.findById(id)
                .map(node -> {
                    String config = euConfigService.buildEuConfig(node);
                    return ResponseEntity.ok()
                            .header("X-Content-Sha256", euConfigService.computeHash(config))
                            .contentType(MediaType.TEXT_PLAIN)
                            .body(config);
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** «Откатить»: вернуть предыдущий профиль из истории узла. */
    @PostMapping("/{id}/profile/rollback")
    public String rollbackProfile(@PathVariable UUID id,
                                  Principal principal,
                                  RedirectAttributes flash) {
        try {
            EuNodeProfileService.ApplyResult result = profileService.rollback(id, actor(principal));
            flash.addFlashAttribute("successMessage", "flash.node.profileRolledBack");
            flash.addFlashAttribute("profileWarnings", result.validation().warnings());
            flash.addFlashAttribute("xrayRestarted", result.restarted());
        } catch (Exception e) {
            log.warn("Failed to roll back profile of node {}: {}", id, e.getMessage(), e);
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/nodes/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            EuNode node = nodeService.findById(id).orElseThrow();
            nodeService.delete(id);
            materializer.materialize();
            flash.addFlashAttribute("successMessage", "flash.node.deleted");
            log.info("Deleted EU node '{}'", node.getName());
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/nodes";
    }

    @PostMapping("/{id}/re-enroll")
    public String reEnroll(@PathVariable UUID id,
                           Authentication auth,
                           RedirectAttributes flash) {
        EuNode node = nodeService.findById(id).orElse(null);
        if (node == null) {
            flash.addFlashAttribute("errorMessage", "error.node.notfound");
            return "redirect:/admin/nodes";
        }
        UUID userId = findUserId(auth);
        EnrollmentToken token = nodeService.createEnrollmentToken(
                node.getName(), userId, Duration.ofHours(1));
        flash.addFlashAttribute("successMessage", "flash.node.tokenCreated");
        return "redirect:/admin/nodes/enroll/" + token.getToken();
    }

    @PostMapping("/{id}/rotate-tunnel")
    public String rotateTunnel(@PathVariable UUID id,
                               RedirectAttributes flash) {
        try {
            nodeService.rotateTunnel(id);
            materializer.materialize();
            flash.addFlashAttribute("successMessage", "flash.node.tunnelRotated");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/nodes/" + id;
    }

    @PostMapping("/{id}/regenerate-secret")
    public String regenerateSecret(@PathVariable UUID id,
                                   RedirectAttributes flash) {
        try {
            String newSecret = nodeService.regenerateSecret(id);
            flash.addFlashAttribute("successMessage", "flash.node.secretRegenerated");
            flash.addFlashAttribute("revealedSecret", newSecret);
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/nodes/" + id;
    }


    private boolean populateDetail(UUID id, Model model) {
        EuNode node = nodeService.findById(id).orElse(null);
        if (node == null) {
            return false;
        }
        model.addAttribute("node", EuNodeDto.from(node));
        model.addAttribute("rawNode", node);
        model.addAttribute("effective", profileService.effectiveProfile(node));
        model.addAttribute("history", profileService.history(id));
        if (!model.containsAttribute("profileForm")) {
            model.addAttribute("profileForm",
                    new EuNodeProfileForm(node.getSni(), node.getTarget(), node.getFingerprint()));
        }
        return true;
    }

    private static String actor(Principal principal) {
        return principal == null || principal.getName() == null ? "system" : principal.getName();
    }

    private String resolveBaseUrl(HttpServletRequest request) {
        if (publicUrlOverride != null && !publicUrlOverride.isBlank()) {
            return publicUrlOverride.replaceAll("/$", "");
        }
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null) scheme = request.getScheme();
        String host = request.getServerName();
        if ("localhost".equalsIgnoreCase(host) || "::1".equals(host)) host = "127.0.0.1";
        return scheme + "://" + host + ":" + request.getServerPort();
    }

    private UUID findUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + auth.getName()));
    }
}