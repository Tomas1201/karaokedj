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
            EnumSet<OrtProvider> providers = OrtEnvironment.getEnvironment().getAvailableProviders();
            
            if (providers.contains(OrtProvider.CUDA)) {
                try {
                    opts.addCUDA(0);
                    gpuEnabled = true;
                    accelerator = "NVIDIA CUDA";
                } catch (Exception e) {
                    log.warn("CUDA detectado pero falló al cargar (¿faltan librerías CUDA/cuDNN?). Intentando DirectML...");
                    opts.close();
                    opts = new SessionOptions();
                    opts.setOptimizationLevel(OptLevel.ALL_OPT);
                    opts.setIntraOpNumThreads(ORT_THREADS);
                    opts.setInterOpNumThreads(1);
                }
            }

            if (!gpuEnabled && providers.contains(OrtProvider.DIRECT_ML)) {
                try {
                    opts.addDirectML(0);
                    gpuEnabled = true;
                    accelerator = "DirectML";
                } catch (Exception e) {
                    log.warn("DirectML detectado pero falló al cargar.");
                    opts.close();
                    opts = new SessionOptions();
                    opts.setOptimizationLevel(OptLevel.ALL_OPT);
                    opts.setIntraOpNumThreads(ORT_THREADS);
                    opts.setInterOpNumThreads(1);
                }
            }

            if (!gpuEnabled && providers.contains(OrtProvider.CORE_ML)) {
                try {
                    opts.addCoreML();
                    gpuEnabled = true;
                    accelerator = "Apple CoreML";
                } catch (Exception e) {
                    log.warn("CoreML detectado pero falló al cargar.");
                    opts.close();
                    opts = new SessionOptions();
                    opts.setOptimizationLevel(OptLevel.ALL_OPT);
                    opts.setIntraOpNumThreads(ORT_THREADS);
                    opts.setInterOpNumThreads(1);
                }
            }

            if (!gpuEnabled) {
                String msg = "❌ Error al intentar habilitar la GPU (faltan dependencias CUDA/DirectML nativas). Fallback a la CPU.";
                log.warn(msg);
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

                if (providers.contains(OrtProvider.CUDA)) {
                    try (SessionOptions testOpts = new SessionOptions()) {
                        testOpts.addCUDA(0);
                        return "GPU: " + gpuName + " (CUDA)";
                    } catch (Exception e) {
                        // ignore and fallback
                    }
                }
                if (providers.contains(OrtProvider.DIRECT_ML)) {
                    try (SessionOptions testOpts = new SessionOptions()) {
                        testOpts.addDirectML(0);
                        return "GPU: " + gpuName + " (DirectML)";
                    } catch (Exception e) {
                        // ignore
                    }
                }
                if (providers.contains(OrtProvider.CORE_ML)) {
                    try (SessionOptions testOpts = new SessionOptions()) {
                        testOpts.addCoreML();
                        return "GPU: Apple Silicon (CoreML)";
                    } catch (Exception e) {
                        // ignore
                    }
                }
                return "CPU (Sin librerías CUDA/DirectML)";
            } catch (Exception e) {
                return "CPU (Error en ONNX)";
            }
        }
        return "CPU";
    }
}
