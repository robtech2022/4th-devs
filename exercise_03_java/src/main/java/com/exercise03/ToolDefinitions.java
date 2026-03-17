package com.exercise03;

import java.util.List;
import java.util.Map;

public final class ToolDefinitions {
    public static List<Map<String, Object>> tools() {
        return List.of(
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "check_package",
                                "description", "Check the status and location of a package. Use this to get package information.",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "packageid", Map.of(
                                                        "type", "string",
                                                        "description", "The package ID (e.g., PKG12345678)"
                                                )
                                        ),
                                        "required", List.of("packageid")
                                )
                        )
                ),
                Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", "redirect_package",
                                "description", "Redirect a package to a new destination. Requires packageid, destination code, and security code.",
                                "parameters", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "packageid", Map.of(
                                                        "type", "string",
                                                        "description", "The package ID to redirect"
                                                ),
                                                "destination", Map.of(
                                                        "type", "string",
                                                        "description", "The destination code where the package should be sent"
                                                ),
                                                "code", Map.of(
                                                        "type", "string",
                                                        "description", "The security code for the redirect operation"
                                                )
                                        ),
                                        "required", List.of("packageid", "destination", "code")
                                )
                        )
                )
        );
    }
}
