package com.karaokedj.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Registro del estado de memoria del proceso.
 *
 * Combina el heap de la JVM con el RSS real del proceso (Linux) — el RSS es lo
 * que el OOM killer observa, e incluye la memoria nativa de ONNX Runtime que
 * no aparece en las estadísticas del heap.
 */
public final class MemoryMonitor {

    private static final Logger log = LoggerFactory.getLogger(MemoryMonitor.class);
    private static final String PROC_STATUS = "/proc/self/status";

    private MemoryMonitor() {
    }

    public static void log(String label) {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long totalMb = rt.totalMemory() / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);

        String rss = readProcessRss();
        if (rss != null) {
            log.info("Memory {}: heap {}/{}MB (max {}) | proceso RSS: {}", label, usedMb, totalMb, maxMb, rss);
        } else {
            log.info("Memory {}: heap {}/{}MB (max {})", label, usedMb, totalMb, maxMb);
        }
    }

    /** Lee VmRSS/VmSwap de /proc/self/status; null si no está disponible (no-Linux). */
    private static String readProcessRss() {
        if (!Path.of(PROC_STATUS).toFile().canRead()) return null;
        try {
            String vmRss = null;
            String vmSwap = null;
            for (String line : Files.readAllLines(Path.of(PROC_STATUS))) {
                if (line.startsWith("VmRSS:")) {
                    vmRss = line.substring(6).trim();
                } else if (line.startsWith("VmSwap:")) {
                    vmSwap = line.substring(7).trim();
                }
            }
            if (vmRss == null) return null;
            return vmSwap != null && !vmSwap.startsWith("0 ")
                    ? vmRss + " (+swap " + vmSwap + ")"
                    : vmRss;
        } catch (Exception e) {
            return null;
        }
    }
}
