package org.Roclh.service.xray;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;


@Slf4j
@Service
public class XrayRealityService {

    public record KeyPair(String privateKey, String publicKey) {}

    @Value("${xray.home}")
    private String xrayHome;

    private final SecureRandom random = new SecureRandom();

    /** Требует установленный xray-бинарник. */
    public KeyPair generateKeyPair() throws IOException {
        return runX25519(null);
    }

    /** Восстанавливает publicKey из privateKey (xray x25519 -i <priv>). */
    public KeyPair deriveKeyPair(String privateKey) throws IOException {
        return runX25519(privateKey);
    }

    public String generateShortId() {
        byte[] bytes = new byte[8];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private KeyPair runX25519(String privateKey) throws IOException {
        Path binary = Path.of(xrayHome, "bin", binaryName());
        if (!Files.exists(binary)) {
            throw new IOException("Xray binary not found for x25519: " + binary);
        }
        ProcessBuilder pb = new ProcessBuilder(binary.toString(), "x25519");
        if (privateKey != null && !privateKey.isBlank()) {
            pb.command().add("-i");
            pb.command().add(privateKey);
        }
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("xray x25519 timed out");
            }
            String priv = extract(output, "PrivateKey:", "Private key:");
            String pub = extract(output, "Password (PublicKey):", "Public key:", "PublicKey:");
            if (priv == null || pub == null) {
                throw new IOException("Unexpected `xray x25519` output: " + output);
            }
            return new KeyPair(priv, pub);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("xray x25519 interrupted", e);
        }
    }

    private static String extract(String output, String... prefixes) {
        for (String line : output.split("\\R")) {
            String t = line.trim();
            for (String prefix : prefixes) {
                if (t.startsWith(prefix)) {
                    return t.substring(prefix.length()).trim();
                }
            }
        }
        return null;
    }

    private String binaryName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "xray.exe" : "xray";
    }
}