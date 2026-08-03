package com.example.custominventoryplugin.debug;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TEMPORARY debug instrumentation for the creative-mode duplication
 * investigation. Appends one NDJSON line per call.
 *
 * DELETE THIS CLASS and its call sites once the bug is confirmed fixed.
 */
public final class DebugLog {

    private static final Path PATH = Paths.get("/home/ec2-user/.cursor/debug-32b2d8.log");
    private static final String RUN = "run1";
    /** Some suspected paths may fire every tick; don't fill the disk. */
    private static final int MAX_LINES = 4000;
    private static final AtomicInteger WRITTEN = new AtomicInteger();

    private DebugLog() { }

    public static void log(String hypothesisId, String location, String message, Map<String, Object> data) {
        if (WRITTEN.incrementAndGet() > MAX_LINES) return;
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{\"sessionId\":\"32b2d8\",\"runId\":\"").append(RUN)
              .append("\",\"hypothesisId\":\"").append(hypothesisId)
              .append("\",\"location\":\"").append(location)
              .append("\",\"message\":\"").append(esc(message))
              .append("\",\"timestamp\":").append(System.currentTimeMillis())
              .append(",\"data\":{");
            boolean first = true;
            for (Map.Entry<String, Object> e : data.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(esc(e.getKey())).append("\":");
                Object v = e.getValue();
                if (v instanceof Number || v instanceof Boolean) sb.append(v);
                else sb.append('"').append(esc(String.valueOf(v))).append('"');
            }
            sb.append("}}\n");
            Files.writeString(PATH, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // Instrumentation must never break the server.
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }
}
