package org.Roclh.service.edge;

import lombok.extern.slf4j.Slf4j;
import org.Roclh.config.ws.CaddyLogWebSocketHandler;
import org.Roclh.service.process.AbstractProcessManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class CaddyProcessManager extends AbstractProcessManager<CaddyLogWebSocketHandler> {

    public CaddyProcessManager(@Value("${pohr.edge.home}") String home,
                               CaddyLogWebSocketHandler logHandler) {
        super(logHandler,
                Path.of(home, "edge", "bin", binName()),
                Path.of(home, "edge", "Caddyfile"),
                Path.of(home, "edge", "caddy-manager.pid"));
    }

    @Override
    protected String serviceName() {
        return "caddy";
    }

    @Override
    protected List<String> startCommand() {
        return List.of(binary.toString(), "run", "--config", configPath.toString());
    }

    @Override
    protected List<String> reloadCommand() {
        return List.of(binary.toString(), "reload", "--config", configPath.toString());
    }

    @Override
    public void validate() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                binary.toString(), "validate", "--config", configPath.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(15, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("Caddyfile validation failed:\n" + out);
        }
    }

    private static String binName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "caddy.exe" : "caddy";
    }
}