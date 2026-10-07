package org.Roclh.service.telegram;

import lombok.extern.slf4j.Slf4j;
import org.Roclh.config.ws.TelemtLogWebSocketHandler;
import org.Roclh.service.process.AbstractProcessManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TelemtProcessManager extends AbstractProcessManager<TelemtLogWebSocketHandler> {

    public TelemtProcessManager(@Value("${xray.home}") String home,
                                TelemtLogWebSocketHandler logHandler) {
        super(logHandler,
                Path.of(home, "bin", binName()),
                Path.of(home, "config", "telemt.toml"),
                Path.of(home, "telemt.pid"));
    }

    @Override
    protected String serviceName() {
        return "telemt";
    }

    @Override
    protected List<String> startCommand() {
        return List.of(binary.toString(), configPath.toString());
    }

    @Override
    protected Map<String, String> startEnvironment() {
        return Map.of("RUST_LOG", "info");
    }

    @Override
    protected void cleanupBeforeStart() {
        killAllByName(binName());
    }

    @Override
    protected void cleanupAfterStop() {
        killAllByName(binName());
    }

    private static String binName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "telemt.exe" : "telemt";
    }
}