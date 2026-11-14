package org.Roclh.service.xray;

import lombok.extern.slf4j.Slf4j;
import org.Roclh.config.ws.XrayLogWebSocketHandler;
import org.Roclh.service.process.AbstractProcessManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class XrayProcessManager extends AbstractProcessManager<XrayLogWebSocketHandler> {

    private final XrayConfigMaterializer materializer;

    /**
     * Ограничение Go-кучи Xray. Без него GOGC=100 держит кучу около 2× от
     * live-набора, и на 480-МБ контейнере warmed-up Xray занимал ~195 МБ RSS
     * (замер 28.10.2026). GOMEMLIMIT заставляет GC работать ближе к лимиту.
     */
    @Value("${pohr.xray.gomemlimit:160MiB}")
    private String goMemLimit;

    /** Агрессивнее GC: меньше пик RSS ценой небольшого CPU (у нас 1 vCPU, ~3 %). */
    @Value("${pohr.xray.gogc:50}")
    private String goGc;

    public XrayProcessManager(@Value("${xray.home}") String home,
                              XrayLogWebSocketHandler logHandler,
                              XrayConfigMaterializer materializer) {
        super(logHandler,
                Path.of(home, "bin", binName()),
                Path.of(home, "config", "config.json"),
                Path.of(home, "xray.pid"));
        this.materializer = materializer;
    }

    @Override
    protected String serviceName() {
        return "xray";
    }

    @Override
    protected List<String> startCommand() {
        return List.of(binary.toString(), "run", "-c", configPath.toString());
    }

    @Override
    protected Map<String, String> startEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("XRAY_LOCATION_ASSET", configPath.getParent().toString());
        // Пустое значение — сознательный отказ от лимита (например, для отладки),
        // иначе Go упадёт на разборе пустой переменной.
        if (goMemLimit != null && !goMemLimit.isBlank()) {
            env.put("GOMEMLIMIT", goMemLimit.trim());
        }
        if (goGc != null && !goGc.isBlank()) {
            env.put("GOGC", goGc.trim());
        }
        return env;
    }

    @Override
    protected void prepareBeforeStart() {
        materializer.materialize();
    }

    private static String binName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "xray.exe" : "xray";
    }
}