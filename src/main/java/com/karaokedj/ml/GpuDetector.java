package com.karaokedj.ml;

import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;
import oshi.hardware.HardwareAbstractionLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GpuDetector {
    private static final Logger log = LoggerFactory.getLogger(GpuDetector.class);

    private static String detectedGpuName = null;
    private static boolean detected = false;

    public static boolean hasDedicatedGpu() {
        ensureDetected();
        return detectedGpuName != null;
    }

    public static String getGpuName() {
        ensureDetected();
        return detectedGpuName;
    }

    private static synchronized void ensureDetected() {
        if (detected) return;
        detected = true;
        try {
            SystemInfo si = new SystemInfo();
            HardwareAbstractionLayer hal = si.getHardware();

            for (GraphicsCard gc : hal.getGraphicsCards()) {
                String name = gc.getName() != null ? gc.getName().toLowerCase() : "";
                long vramMb = gc.getVRam() / (1024 * 1024);
                
                log.info("Found GPU: {} with {} MB VRAM", gc.getName(), vramMb);

                boolean isIntegrated = name.contains("intel hd") || 
                                       name.contains("intel uhd") || 
                                       name.contains("iris") ||
                                       name.contains("microsoft basic render");

                if ((!isIntegrated && vramMb >= 1024) || 
                    name.contains("nvidia") || 
                    name.contains("geforce") || 
                    name.contains("radeon") || 
                    name.contains("rtx") || 
                    name.contains("gtx")) {
                    log.info("Dedicated GPU detected!");
                    detectedGpuName = gc.getName();
                    return;
                }
            }
        } catch (Throwable t) {
            log.warn("Failed to detect GPU hardware with OSHI: {}", t.getMessage());
        }
    }
}
