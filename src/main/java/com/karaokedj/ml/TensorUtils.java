package com.karaokedj.ml;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtException;


/** Serialización y extracción de tensores ONNX (serialización de tensores para los modelos ML). */
public final class TensorUtils {

    private TensorUtils() {
    }

    /** Aplana un arreglo 2D (mel espectrograma) en row-major. */
    public static float[] flatten2D(float[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        float[] flat = new float[rows * cols];
        for (int i = 0; i < rows; i++) {
            System.arraycopy(matrix[i], 0, flat, i * cols, cols);
        }
        return flat;
    }

    public static long[] shape1D(int dim) {
        return new long[]{dim};
    }

    public static long[] shape3D(int a, int b, int c) {
        return new long[]{a, b, c};
    }

    public static long[] shape4D(float[][][][] arr) {
        return new long[]{arr.length, arr[0].length, arr[0][0].length, arr[0][0][0].length};
    }

    /** Aplana un tensor 4D row-major. Requiere dimensiones homogéneas (salida de ORT). */
    public static float[] flatten4D(float[][][][] arr) {
        int d0 = arr.length, d1 = arr[0].length, d2 = arr[0][0].length, d3 = arr[0][0][0].length;
        float[] flat = new float[d0 * d1 * d2 * d3];
        int off = 0;
        for (int i = 0; i < d0; i++)
            for (int j = 0; j < d1; j++)
                for (int k = 0; k < d2; k++) {
                    System.arraycopy(arr[i][j][k], 0, flat, off, d3);
                    off += d3;
                }
        return flat;
    }

    /**
     * Extrae los logits del último paso como vector 1D.
     * ONNX Runtime puede devolver el valor con dimensionalidad distinta a la declarada
     * según la versión; se normaliza tomando siempre la última posición temporal.
     */
    public static float[] extractLastLogits(OnnxValue value) throws OrtException {
        Object raw = ((OnnxTensor) value).getValue();
        if (raw instanceof float[] arr) return arr;
        if (raw instanceof float[][] arr) return arr[arr.length - 1];
        if (raw instanceof float[][][] arr) return arr[arr.length - 1][arr[0].length - 1];
        throw new RuntimeException("Unexpected logits type: " + raw.getClass().getName());
    }

    /** Normaliza salidas KV de ORT a float[4D] (algunas versiones devuelven 3D). */
    public static float[][][][] extract4DFloats(OnnxValue value) throws OrtException {
        Object raw = ((OnnxTensor) value).getValue();
        if (raw instanceof float[][][][] arr) return arr;
        if (raw instanceof float[][][] arr) {
            float[][][][] result = new float[1][1][arr.length][arr[0].length];
            System.arraycopy(arr, 0, result[0][0], 0, arr.length);
            return result;
        }
        throw new RuntimeException("Unexpected 4D tensor type: " + raw.getClass().getName());
    }
}
