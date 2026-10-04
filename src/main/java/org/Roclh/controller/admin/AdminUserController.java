package org.Roclh.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.Roclh.model.TelegramProxyUser;
import org.Roclh.model.User;
import org.Roclh.model.dto.UserDto;
import org.Roclh.repository.telegram.TelegramProxyUserRepository;
import org.Roclh.service.UserService;
import org.Roclh.service.telegram.TelegramProxyService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;
    private final TelegramProxyService telegramProxyService;
    private final TelegramProxyUserRepository telegramProxyUserRepository;

    @GetMapping
    public String list(Model model) {
        List<User> users = userService.findAll();
        Map<UUID, TelegramProxyUser> tgByUser = telegramProxyUserRepository.findAll().stream()
                .collect(Collectors.toMap(TelegramProxyUser::getUserId, Function.identity()));
        model.addAttribute("users", users);
        model.addAttribute("tgByUser", tgByUser);
        return "admin/users";
    }

    @GetMapping("/{id}/tg")
    public String tgDetail(@PathVariable UUID id, Model model, RedirectAttributes flash) {
        User u = userService.findById(id).orElse(null);
        TelegramProxyUser tg = telegramProxyUserRepository.findById(id).orElse(null);
        if (u == null || tg == null) {
            flash.addFlashAttribute("errorMessage", "error.user.notfound");
            return "redirect:/admin/users";
        }
        model.addAttribute("user", u);
        model.addAttribute("tg", telegramProxyService.toUserDto(tg));
        return "admin/user-tg";
    }

    @PostMapping("/{id}/tg/toggle")
    public String tgToggle(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            telegramProxyService.toggle(id);
            flash.addFlashAttribute("successMessage", "flash.telegram.userToggled");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/users/" + id + "/tg";
    }

    @PostMapping("/{id}/tg/regenerate")
    public String tgRegenerate(@PathVariable UUID id, RedirectAttributes flash) {
        try {
            telegramProxyService.regenerateSecret(id);
            flash.addFlashAttribute("successMessage", "flash.telegram.secretRegenerated");
        } catch (Exception e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/users/" + id + "/tg";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new UserDto("", "", UserService.ROLE_USER, true));
        model.addAttribute("mode", "create");
        return "admin/user-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") UserDto form,
                         BindingResult binding,
                         Model model,
                         RedirectAttributes flash) {
        if (form.password() == null || form.password().isBlank()) {
            binding.rejectValue("password", "password.required");
        } else if (form.password().length() < 8) {
            binding.rejectValue("password", "validation.password.minLength");
        }
        if (userService.usernameTaken(form.username(), null)) {
            binding.rejectValue("username", "validation.username.taken");
        }
        if (binding.hasErrors()) {
            model.addAttribute("mode", "create");
            return "admin/user-form";
        }
        userService.create(form.username(), form.password(), form.role(), form.enabled());
        flash.addFlashAttribute("successMessage", "flash.user.created");
        return "redirect:/admin/users";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable UUID id,
                           Model model,
                           RedirectAttributes flash) {
        User user = userService.findById(id).orElse(null);
        if (user == null) {
            flash.addFlashAttribute("errorMessage", "error.user.notfound");
            return "redirect:/admin/users";
        }
        UserDto form = new UserDto(user.getUsername(), "", user.getRole(), user.isEnabled());
        model.addAttribute("form", form);
        model.addAttribute("mode", "edit");
        model.addAttribute("userId", id);
        return "admin/user-form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable UUID id,
                         @Valid @ModelAttribute("form") UserDto form,
                         BindingResult binding,
                         Authentication auth,
                         Model model,
                         RedirectAttributes flash) {
        User existing = userService.findById(id).orElse(null);
        if (existing == null) {
            flash.addFlashAttribute("errorMessage", "error.user.notfound");
            return "redirect:/admin/users";
        }
        if (userService.usernameTaken(form.username(), id)) {
            binding.rejectValue("username", "validation.username.taken");
        }
        if (form.password() != null && !form.password().isBlank()
                && form.password().length() < 8) {
            binding.rejectValue("password", "validation.password.minLength");
        }
        if (!form.enabled() && existing.getUsername().equals(auth.getName())) {
            binding.rejectValue("enabled", "error.user.selfDisable");
        }
        if (!form.enabled() && UserService.ROLE_ADMIN.equals(existing.getRole())
                && userService.countAdmins() <= 1) {
            binding.rejectValue("enabled", "error.user.lastAdmin");
        }
        if (binding.hasErrors()) {
            model.addAttribute("mode", "edit");
            model.addAttribute("userId", id);
            return "admin/user-form";
        }
        userService.update(id, form.username(), form.password(), form.role(), form.enabled());
        flash.addFlashAttribute("successMessage", "flash.user.updated");
        return "redirect:/admin/users";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable UUID id,
                         Authentication auth,
                         RedirectAttributes flash) {
        User user = userService.findById(id).orElse(null);
        if (user == null) {
            flash.addFlashAttribute("errorMessage", "error.user.notfound");
            return "redirect:/admin/users";
        }
        if (user.getUsername().equals(auth.getName())) {
            flash.addFlashAttribute("errorMessage", "error.user.selfDelete");
            return "redirect:/admin/users";
        }
        if (UserService.ROLE_ADMIN.equals(user.getRole()) && userService.countAdmins() <= 1) {
            flash.addFlashAttribute("errorMessage", "error.user.lastAdmin");
            return "redirect:/admin/users";
        }
        userService.delete(id);
        flash.addFlashAttribute("successMessage", "flash.user.deleted");
        return "redirect:/admin/users";
    }
}