package com.karaokedj.ml;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtProvider;
import ai.onnxruntime.OrtSession.SessionOptions;
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;

/**
 * Configuración compartida de sesiones ONNX.
 *
 * Perfil de baja memoria pensado para máquinas con RAM limitada:
 * - Sin patrones de memoria cacheados (las activaciones no se retienen entre corridas)
 * - Arena con estrategia kSameAsRequested (crece solo lo estrictamente pedido,
 *   en vez de reservar por bloques grandes que quedan retenidos)
 */
public final class MlOptions {

    private static final Logger log = LoggerFactory.getLogger(MlOptions.class);

    /** Hilos intra-op moderados: buen throughput sin saturar la máquina. */
    static final int ORT_THREADS = 2;

    private MlOptions() {
    }

    static SessionOptions base() throws OrtException {
        SessionOptions opts = new SessionOptions();
        opts.setOptimizationLevel(OptLevel.ALL_OPT);
        opts.setIntraOpNumThreads(ORT_THREADS);
        opts.setInterOpNumThreads(1);

        boolean gpuEnabled = false;
        String accelerator = "";

        if (GpuDetector.hasDedicatedGpu()) {
            try {
                EnumSet<OrtProvider> providers = OrtEnvironment.getEnvironment().getAvailableProviders();
                
                if (providers.contains(OrtProvider.CUDA)) {
                    opts.addCUDA(0);
                    gpuEnabled = true;
                    accelerator = "NVIDIA CUDA";
                } else if (providers.contains(OrtProvider.DIRECT_ML)) {
                    opts.addDirectML(0);
                    gpuEnabled = true;
                    accelerator = "DirectML";
                } else if (providers.contains(OrtProvider.CORE_ML)) {
                    opts.addCoreML();
                    gpuEnabled = true;
                    accelerator = "Apple CoreML";
                } else {
                    String msg = "⚠️ GPU dedicada detectada, pero sin proveedores compatibles en ONNX (Falta CUDA/DirectML/CoreML). Se usará la CPU.";
                    log.warn(msg);
                    System.out.println("\n[INFO IA] " + msg + "\n");
                }
            } catch (Exception e) {
                String msg = "❌ Error al intentar habilitar la GPU. Fallback a la CPU. Error: " + e.getMessage();
                log.error(msg, e);
                System.out.println("\n[INFO IA] " + msg + "\n");
            }
        } else {
            String msg = "💻 PROCESAMIENTO POR CPU: No se detectó una GPU dedicada o compatible.";
            log.info(msg);
            System.out.println("\n[INFO IA] " + msg + "\n");
        }

        if (gpuEnabled) {
            String msg = "🚀 ACELERACIÓN GPU ACTIVADA: Utilizando " + accelerator + " para los modelos de IA.";
            log.info(msg);
            System.out.println("\n[INFO IA] " + msg + "\n");
        }

        return opts;
    }

    public static String getHardwareInfo() {
        if (GpuDetector.hasDedicatedGpu()) {
            try {
                EnumSet<OrtProvider> providers = OrtEnvironment.getEnvironment().getAvailableProviders();
                String gpuName = GpuDetector.getGpuName();
                if (gpuName == null || gpuName.isBlank()) gpuName = "GPU";

                if (providers.contains(OrtProvider.CUDA)) return "GPU: " + gpuName + " (CUDA)";
                if (providers.contains(OrtProvider.DIRECT_ML)) return "GPU: " + gpuName + " (DirectML)";
                if (providers.contains(OrtProvider.CORE_ML)) return "GPU: Apple Silicon (CoreML)";
                return "CPU (Sin proveedor ONNX compatible)";
            } catch (Exception e) {
                return "CPU (Error en ONNX)";
            }
        }
        return "CPU";
    }
}
