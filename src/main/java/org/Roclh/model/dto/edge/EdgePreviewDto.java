package org.Roclh.model.dto.edge;

/**
 * Результат preview: сгенерированные конфиги, их хэши и unified diff
 * к текущим файлам на диске. Ничего не записывается и не применяется.
 */
public record EdgePreviewDto(
        EdgeConfigFileDto nginx,
        EdgeConfigFileDto caddy,
        String nginxDiff,
        String caddyDiff
) {
    public boolean same() {
        return nginx.same() && caddy.same();
    }
}
