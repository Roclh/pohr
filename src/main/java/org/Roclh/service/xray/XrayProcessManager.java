package org.Roclh.service.xray;

import lombok.extern.slf4j.Slf4j;
import org.Roclh.config.ws.XrayLogWebSocketHandler;
import org.Roclh.service.process.AbstractProcessManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class XrayProcessManager extends AbstractProcessManager<XrayLogWebSocketHandler> {

    private final XrayConfigMaterializer materializer;

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
        return Map.of("XRAY_LOCATION_ASSET", configPath.getParent().toString());
    }

    @Override
    protected void prepareBeforeStart() {
        materializer.materialize();
    }

    private static String binName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "xray.exe" : "xray";
    }
}