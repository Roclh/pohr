package org.Roclh.service.edge;

import lombok.extern.slf4j.Slf4j;
import org.Roclh.config.ws.NginxLogWebSocketHandler;
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
public class NginxProcessManager extends AbstractProcessManager<NginxLogWebSocketHandler> {

    public NginxProcessManager(@Value("${pohr.edge.home}") String home,
                               NginxLogWebSocketHandler logHandler) {
        super(logHandler,
                Path.of(home, "edge", "bin", binName()),
                Path.of(home, "edge", "nginx.conf"),
                Path.of(home, "edge", "nginx-manager.pid"));
    }

    @Override protected String serviceName() { return "nginx"; }

    @Override protected List<String> startCommand() {
        return List.of(binary.toString(),
                "-e", "/dev/stderr",
                "-g", "daemon off;",
                "-c", configPath.toString());
    }

    @Override protected List<String> reloadCommand() {
        return List.of(binary.toString(), "-s", "reload");
    }

    @Override public void validate() throws IOException, InterruptedException {
        runCommand("nginx config validation failed",
                binary.toString(), "-e", "/dev/stderr", "-t", "-c", configPath.toString());
    }

    /** Общий хелпер: запускает команду, ждёт, бросает при ненулевом exit. */
    private void runCommand(String errMsg, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(15, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException(errMsg + ":\n" + out);
        }
    }

    private static String binName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "nginx.exe" : "nginx";
    }
}