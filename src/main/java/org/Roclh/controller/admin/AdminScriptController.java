package org.Roclh.controller.admin;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.Roclh.model.User;
import org.Roclh.model.script.*;
import org.Roclh.repository.UserRepository;
import org.Roclh.service.PublicUrlResolver;
import org.Roclh.service.script.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.*;

@Slf4j
@Controller
@RequestMapping("/admin/scripts")
@RequiredArgsConstructor
public class AdminScriptController {

    private final ScriptCatalogService catalog;
    private final ScriptVersionService versionService;
    private final ScriptErrorReportService errorService;
    private final UserRepository userRepository;
    private final PublicUrlResolver urlResolver;

    // =====================================================================
    // Список
    // =====================================================================

    @GetMapping
    public String list(Model model, HttpServletRequest req) {
        String base = urlResolver.resolve(req);
        List<Row> rows = new ArrayList<>();
        for (Script s : catalog.listAll()) {
            ScriptVersion active = versionService.getActive(s.getId()).orElse(null);
            Set<String> deps = catalog.getDirectDependencies(s.getName());
            Set<String> rev = catalog.getReverseDependencies(s.getName());
            rows.add(new Row(s, active, deps, rev));
        }
        model.addAttribute("scripts", rows);
        model.addAttribute("newErrorCount", errorService.countNew());
        model.addAttribute("errors24h", errorService.countLast24h());
        return "admin/script/scripts";
    }

    public record Row(Script script,
                      ScriptVersion activeVersion,
                      Set<String> deps,
                      Set<String> reverseDeps) {}

    // =====================================================================
    // Детали + история версий
    // =====================================================================

    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        Script s = catalog.findById(id).orElse(null);
        if (s == null) {
            flash.addFlashAttribute("errorMessage", "Скрипт не найден");
            return "redirect:/admin/scripts";
        }
        List<ScriptVersion> history = versionService.getHistory(id);
        model.addAttribute("script", s);
        model.addAttribute("activeVersion", versionService.getActive(id).orElse(null));
        model.addAttribute("history", history);
        model.addAttribute("deps", catalog.getDirectDependencies(s.getName()));
        model.addAttribute("reverseDeps", catalog.getReverseDependencies(s.getName()));
        return "admin/script/script-detail";
    }

    @PostMapping("/{id}/reseed")
    public String reseed(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            catalog.reseedFromBundled(id);
            flash.addFlashAttribute("successMessage", "Скрипт сброшен к встроенной версии");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/scripts/" + id;
    }

    // =====================================================================
    // Редактирование метаданных
    // =====================================================================

    @PostMapping("/{id}/meta")
    public String saveMeta(@PathVariable UUID id,
                           @RequestParam String displayName,
                           @RequestParam(required = false) String description,
                           @RequestParam(defaultValue = "any") String platform,
                           @RequestParam ScriptAccessLevel accessLevel,
                           @RequestParam(defaultValue = "false") boolean entrypoint,
                           @RequestParam(defaultValue = "1000") int sortOrder,
                           @RequestParam(defaultValue = "false") boolean enabled,
                           RedirectAttributes flash) {
        try {
            catalog.updateMetadata(id, displayName, description, platform,
                    accessLevel, entrypoint, sortOrder, enabled);
            flash.addFlashAttribute("successMessage", "Метаданные сохранены");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/scripts/" + id;
    }

    // =====================================================================
    // Редактирование содержимого
    // =====================================================================

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        Script s = catalog.findById(id).orElse(null);
        if (s == null) {
            flash.addFlashAttribute("errorMessage", "Скрипт не найден");
            return "redirect:/admin/scripts";
        }
        try {
            String content = java.nio.file.Files.readString(
                    java.nio.file.Path.of(System.getProperty("pohr.scripts.home", "./scripts"), s.getName()));
            model.addAttribute("script", s);
            model.addAttribute("content", content);
        } catch (IOException e) {
            flash.addFlashAttribute("errorMessage", "Не удалось прочитать файл: " + e.getMessage());
            return "redirect:/admin/scripts/" + id;
        }
        return "admin/script/script-edit";
    }

    @PostMapping("/{id}/content")
    public String saveContent(@PathVariable UUID id,
                              @RequestParam String content,
                              RedirectAttributes flash) {
        try {
            catalog.saveContent(id, content);
            flash.addFlashAttribute("successMessage", "Содержимое сохранено, версия обновлена");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/scripts/" + id;
    }

    // =====================================================================
    // Версии: bump major, rollback
    // =====================================================================

    @PostMapping("/{id}/bump-major")
    public String bumpMajor(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            catalog.bumpMajor(id);
            flash.addFlashAttribute("successMessage", "Мажорная версия поднята");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/scripts/" + id;
    }

    @PostMapping("/{id}/rollback")
    public String rollback(@PathVariable UUID id,
                           @RequestParam UUID versionId,
                           RedirectAttributes flash) {
        try {
            catalog.rollback(id, versionId);
            flash.addFlashAttribute("successMessage", "Откат выполнен, создана новая версия");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/scripts/" + id;
    }

    // =====================================================================
// Создание нового скрипта
// =====================================================================

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("mode", "create");
        model.addAttribute("platform", "any");
        model.addAttribute("accessLevel", ScriptAccessLevel.ADMIN);
        model.addAttribute("sortOrder", 1000);
        model.addAttribute("enabled", true);
        return "admin/script/script-new";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam(required = false) String content,
                         @RequestParam(required = false) String displayName,
                         @RequestParam(required = false) String description,
                         @RequestParam(defaultValue = "any") String platform,
                         @RequestParam(defaultValue = "ADMIN") ScriptAccessLevel accessLevel,
                         @RequestParam(defaultValue = "false") boolean entrypoint,
                         @RequestParam(defaultValue = "1000") int sortOrder,
                         @RequestParam(defaultValue = "false") boolean enabled,
                         RedirectAttributes flash,
                         Model model) {
        try {
            Script s = catalog.createScript(name, content, displayName, description,
                    platform, accessLevel, entrypoint, sortOrder, enabled);
            flash.addFlashAttribute("successMessage", "Скрипт создан");
            return "redirect:/admin/scripts/" + s.getId();
        } catch (IllegalArgumentException e) {
            model.addAttribute("mode", "create");
            model.addAttribute("errorMessage", e.getMessage());
            model.addAttribute("name", name);
            model.addAttribute("content", content);
            model.addAttribute("displayName", displayName);
            model.addAttribute("description", description);
            model.addAttribute("platform", platform);
            model.addAttribute("accessLevel", accessLevel);
            model.addAttribute("entrypoint", entrypoint);
            model.addAttribute("sortOrder", sortOrder);
            model.addAttribute("enabled", enabled);
            return "admin/script/script-new";
        } catch (IOException e) {
            model.addAttribute("mode", "create");
            model.addAttribute("errorMessage", "Ошибка записи: " + e.getMessage());
            model.addAttribute("name", name);
            model.addAttribute("content", content);
            return "admin/script/script-new";
        }
    }

    // =====================================================================
    // Удаление
    // =====================================================================

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            catalog.delete(id);
            flash.addFlashAttribute("successMessage", "Скрипт удалён");
            return "redirect:/admin/scripts";
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/admin/scripts/" + id;
        }
    }

    // =====================================================================
    // Error reports
    // =====================================================================

    @GetMapping("/reports")
    public String reports(@RequestParam(required = false) ScriptErrorStatus status,
                          Model model) {
        List<ScriptErrorReport> reports = status == null
                ? errorService.listAll()
                : errorService.listByStatus(status);
        model.addAttribute("reports", reports);
        model.addAttribute("filterStatus", status);
        return "admin/script/reports";
    }

    @GetMapping("/reports/{id}")
    public String reportDetail(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        ScriptErrorReport r = errorService.findById(id).orElse(null);
        if (r == null) {
            flash.addFlashAttribute("errorMessage", "Отчёт не найден");
            return "redirect:/admin/scripts/reports";
        }
        model.addAttribute("report", r);
        return "admin/script/report-detail";
    }

    @PostMapping("/reports/{id}/in-progress")
    public String markInProgress(@PathVariable UUID id, RedirectAttributes flash) {
        errorService.markInProgress(id);
        flash.addFlashAttribute("successMessage", "Отмечен как «в работе»");
        return "redirect:/admin/scripts/reports/" + id;
    }

    @PostMapping("/reports/{id}/resolve")
    public String resolve(@PathVariable UUID id,
                          Authentication auth,
                          RedirectAttributes flash) {
        UUID userId = userRepository.findByUsername(auth.getName())
                .map(User::getId).orElse(null);
        errorService.resolve(id, userId);
        errorService.deleteResolved(id);
        flash.addFlashAttribute("successMessage", "Отчёт разобран и удалён");
        return "redirect:/admin/scripts/reports";
    }
}