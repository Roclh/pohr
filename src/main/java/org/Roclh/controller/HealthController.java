package org.Roclh.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Liveness-проверка без внешнего отпечатка.
 *
 * <p>Отдаёт ровно {@code {"status":"ok"}} — без имени сервиса, версии и
 * времени сборки. Анонимно endpoint недоступен: внешний сканер не должен
 * получать ни тело, ни редирект на панель (см. {@code SecurityConfig}).</p>
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }
}
