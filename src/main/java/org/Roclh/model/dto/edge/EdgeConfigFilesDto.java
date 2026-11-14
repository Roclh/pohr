package org.Roclh.model.dto.edge;

/** Пара сгенерированных конфигов edge. */
public record EdgeConfigFilesDto(
        EdgeConfigFileDto nginx,
        EdgeConfigFileDto caddy
) {
    public boolean same() {
        return nginx.same() && caddy.same();
    }
}
