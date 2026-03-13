package com.toolusejava;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class SandboxUtils {
    private final Path root;

    public SandboxUtils(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public void initializeSandbox() throws IOException {
        if (Files.exists(root)) {
            try (var walk = Files.walk(root)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            }
                        });
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof IOException io) {
                    throw io;
                }
                throw ex;
            }
        }
        Files.createDirectories(root);
    }

    public Path resolveSandboxPath(String relativePath) {
        String safeInput = relativePath == null ? "" : relativePath;
        Path resolved = root.resolve(safeInput).normalize();

        if (!resolved.startsWith(root)) {
            throw new RuntimeException("Access denied: path \"" + safeInput + "\" is outside sandbox");
        }

        return resolved;
    }
}
