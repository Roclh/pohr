package org.Roclh;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@EnableScheduling
@SpringBootApplication
public class PohrApplication {
    static void main(String[] args) {
        prepareDataDirectory();
        SpringApplication.run(PohrApplication.class, args);
    }

    private static void prepareDataDirectory() {
        try {
            Path dataDir = Path.of("data");
            if (!Files.exists(dataDir)) {
                Files.createDirectories(dataDir);
                log.info("Created data directory: {}", dataDir.toAbsolutePath());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create data directory", e);
        }
    }
}