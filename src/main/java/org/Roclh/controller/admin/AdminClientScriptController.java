package org.Roclh.controller.admin;

import lombok.RequiredArgsConstructor;
import org.Roclh.service.ClientScriptService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;

@Controller
@RequestMapping("/admin/client-scripts")
@RequiredArgsConstructor
public class AdminClientScriptController {

    private final ClientScriptService scriptService;

    @GetMapping
    public String list(Model model) {
        model.addAttribute("scripts", scriptService.list());
        return "admin/client-scripts";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("name", "");
        model.addAttribute("content", "");
        model.addAttribute("isNew", true);
        model.addAttribute("bundled", false);
        model.addAttribute("matchesBundled", false);
        return "admin/client-script-form";
    }

    @PostMapping
    public String create(@RequestParam String name,
                         @RequestParam String content,
                         RedirectAttributes flash) {
        try {
            scriptService.write(name, content);
            flash.addFlashAttribute("successMessage", "flash.script.created");
            return "redirect:/admin/client-scripts/" + name + "/edit";
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/admin/client-scripts/new";
        }
    }

    @GetMapping("/{name}/edit")
    public String editForm(@PathVariable String name, Model model) throws IOException {
        if (!scriptService.exists(name)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Script not found: " + name);
        }
        model.addAttribute("name", name);
        model.addAttribute("content", scriptService.read(name));
        model.addAttribute("isNew", false);
        model.addAttribute("bundled", scriptService.isBundled(name));
        model.addAttribute("matchesBundled", scriptService.matchesBundled(name));
        return "admin/client-script-form";
    }

    @PostMapping("/{name}")
    public String save(@PathVariable String name,
                       @RequestParam String content,
                       RedirectAttributes flash) {
        try {
            scriptService.write(name, content);
            flash.addFlashAttribute("successMessage", "flash.script.saved");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/client-scripts/" + name + "/edit";
    }

    @PostMapping("/{name}/reset")
    public String reset(@PathVariable String name, RedirectAttributes flash) {
        try {
            scriptService.reset(name);
            flash.addFlashAttribute("successMessage", "flash.script.reset");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/client-scripts/" + name + "/edit";
    }

    @PostMapping("/{name}/delete")
    public String delete(@PathVariable String name, RedirectAttributes flash) {
        try {
            scriptService.delete(name);
            flash.addFlashAttribute("successMessage", "flash.script.deleted");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/client-scripts";
    }
}