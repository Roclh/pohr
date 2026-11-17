package org.Roclh.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Публичный адрес панели: логин, регистрация по инвайту, выдача ссылок.
 *
 * <p>Панель живёт на отдельном хосте ({@code pohr.panel-url}), чтобы apex
 * отдавал сайт-легенду. Если адрес не задан, возвращаются относительные
 * ссылки — это прежнее поведение для установок, где панель наружу не
 * публикуется (доступ только через SSH-туннель).</p>
 */
@Component
@RequiredArgsConstructor
public class PanelUrlResolver {

    private final PublicUrlResolver publicUrlResolver;

    @Value("${pohr.panel-url:}")
    private String panelUrl;

    /**
     * База абсолютных ссылок на панель: заданный {@code pohr.panel-url}, иначе
     * публичный origin текущего запроса (сайт/подписки).
     */
    public String base(HttpServletRequest request) {
        String configured = configured();
        return configured != null ? configured : publicUrlResolver.resolve(request);
    }

    /**
     * Цель для {@code redirect:}. При заданном {@code pohr.panel-url} — абсолютный
     * адрес панели (сессия должна ставиться на её origin), иначе относительный путь.
     */
    public String redirect(String path) {
        String configured = configured();
        return configured == null ? path : configured + path;
    }

    /** Заданный адрес панели без завершающих слэшей; {@code null} — не публикуется. */
    public String configured() {
        if (panelUrl == null || panelUrl.isBlank()) {
            return null;
        }
        return panelUrl.trim().replaceAll("/+$", "");
    }
}
