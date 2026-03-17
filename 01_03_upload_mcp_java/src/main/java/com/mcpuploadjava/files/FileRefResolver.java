package com.mcpuploadjava.files;

import com.mcpuploadjava.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code {{file:path}}} placeholders in tool arguments by replacing them with
 * the Base64-encoded content of the referenced file.
 *
 * <p>The placeholder can appear anywhere inside a string value.
 * Path traversal outside the workspace root is rejected.
 */
public final class FileRefResolver {

    private static final Pattern FILE_REF = Pattern.compile("\\{\\{file:([^}]+)}}");

    private FileRefResolver() {}

    /**
     * Recursively walks {@code value} and resolves all {@code {{file:path}}} placeholders.
     *
     * @param value         the tool-argument map to process
     * @param workspaceRoot absolute path to the workspace directory
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> resolve(Map<String, Object> value, Path workspaceRoot) throws IOException {
        return (Map<String, Object>) resolveValue(value, workspaceRoot);
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private static Object resolveValue(Object value, Path workspaceRoot) throws IOException {
        if (value instanceof String str) {
            return resolveString(str, workspaceRoot);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(resolveValue(item, workspaceRoot));
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()),
                           resolveValue(entry.getValue(), workspaceRoot));
            }
            return result;
        }
        return value;
    }

    private static String resolveString(String str, Path workspaceRoot) throws IOException {
        Matcher matcher = FILE_REF.matcher(str);
        if (!matcher.find()) return str;

        StringBuilder sb = new StringBuilder();
        matcher.reset();
        while (matcher.find()) {
            String relativePath = matcher.group(1);
            Path   filePath     = workspaceRoot.resolve(relativePath).normalize();

            // Reject path traversal attempts
            if (!filePath.startsWith(workspaceRoot)) {
                Log.warn("File ref escapes workspace root (skipping): " + relativePath);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(""));
                continue;
            }

            try {
                byte[] bytes  = Files.readAllBytes(filePath);
                String base64 = Base64.getEncoder().encodeToString(bytes);
                Log.info("   Resolved: " + relativePath + " -> " + base64.length() + " chars");
                matcher.appendReplacement(sb, Matcher.quoteReplacement(base64));
            } catch (IOException ex) {
                Log.warn("   Failed to resolve file ref: " + relativePath + " - " + ex.getMessage());
                matcher.appendReplacement(sb, Matcher.quoteReplacement(""));
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
