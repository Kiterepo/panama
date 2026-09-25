package org.leo.server.panama.vpn.util;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class FileUtils {
    /** defaultValue is retained for source compatibility; unreadable configuration fails closed. */
    public static String read(String fileName, String defaultValue) {
        Path path = Paths.get(fileName).toAbsolutePath().normalize();
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            if (content.trim().isEmpty()) throw new IllegalArgumentException("Empty configuration: " + path);
            return content;
        } catch (IOException error) {
            throw new IllegalArgumentException("Cannot read configuration: " + path, error);
        }
    }
    public static String getCurrentPath() { return Paths.get("").toAbsolutePath().normalize().toString(); }
    public static String readFromResource(String file) {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(file)) {
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new IllegalStateException("Cannot read resource: " + file, error); }
    }
}
