package com.mcptranslatorjava;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ProjectPaths {
    private static final String PROJECT_NAME = "01_03_mcp_translator_java";

    private ProjectPaths() {
    }

    public static Path projectRoot() {
        Path cwd = Path.of(".").toAbsolutePath().normalize();
        List<Path> candidates = List.of(
                cwd.resolve("4th-devs").resolve(PROJECT_NAME),
                cwd.resolve(PROJECT_NAME),
                cwd,
                cwd.resolve("..").normalize().resolve(PROJECT_NAME),
                cwd.resolve("..").normalize().resolve("4th-devs").resolve(PROJECT_NAME),
                cwd.resolve("..").normalize().resolve("..").normalize().resolve("4th-devs").resolve(PROJECT_NAME)
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("pom.xml"))) {
                return candidate.normalize();
            }
        }

        throw new RuntimeException("Unable to resolve project root for " + PROJECT_NAME);
    }
}
