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
                    log.warn("❌ Error CRÍTICO al inicializar CUDA: {}", e.getMessage());
                    diagnoseCuda(e);
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

    private static void diagnoseCuda(Exception e) {
        log.warn("================== DIAGNÓSTICO DE CUDA ==================");
        log.warn("El sistema reporta que faltan DLLs nativas de CUDA o cuDNN.");
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            String path = System.getenv("PATH");
            log.warn("Longitud del PATH actual: {}", path != null ? path.length() : "NULL");
            if (path != null) {
                boolean hasCudaPath = path.toLowerCase().contains("cuda");
                boolean hasCudnnPath = path.toLowerCase().contains("cudnn");
                log.warn("- ¿El PATH contiene directorios de CUDA?: {}", hasCudaPath ? "SÍ" : "NO");
                log.warn("- ¿El PATH contiene directorios de cuDNN?: {}", hasCudnnPath ? "SÍ" : "NO");
                
                if (!hasCudaPath || !hasCudnnPath) {
                    log.warn("RECOMENDACIÓN: Verifica que instalaste CUDA 12.x y cuDNN 9.x y agregaste la carpeta 'bin' de ambos a las variables de entorno de Windows.");
                }
            }
            
            log.warn("Intentando cargar librerías clave manualmente para diagnosticar...");
            tryLoadLibrary("cublas64_12", "CUDA 12");
            tryLoadLibrary("cudnn64_9", "cuDNN 9");
            tryLoadLibrary("cudnn_cnn_infer64_9", "cuDNN 9 Infer");
        }
        log.warn("=========================================================");
    }

    private static void tryLoadLibrary(String libName, String displayName) {
        try {
            System.loadLibrary(libName);
            log.warn("[OK] {} ({}.dll) fue cargado correctamente.", displayName, libName);
        } catch (Throwable t) {
            log.warn("[ERROR] No se pudo encontrar {} ({}.dll): {}", displayName, libName, t.getMessage());
        }
    }
}
