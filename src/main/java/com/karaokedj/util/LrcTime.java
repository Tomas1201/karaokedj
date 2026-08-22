package com.karaokedj.util;

/**
 * Formato de tiempo para archivos LRC / Enhanced LRC.
 *
 * Unifica las 5 implementaciones duplicadas del proyecto:
 * [mm:ss.hh] (línea), &lt;mm:ss.hh&gt; (palabra), mm:ss (UI/log).
 */
public final class LrcTime {

    private LrcTime() {
    }

    /** Timestamp de línea: [mm:ss.hh] */
    public static String lrc(long ms) {
        StringBuilder sb = new StringBuilder(10);
        appendCore(sb, ms, '[', ']');
        return sb.toString();
    }

    /** Timestamp de palabra (Enhanced LRC): &lt;mm:ss.hh&gt; */
    public static String enhanced(long ms) {
        StringBuilder sb = new StringBuilder(10);
        appendCore(sb, ms, '<', '>');
        return sb.toString();
    }

    /** Versión sin asignaciones para bucles calientes: escribe directamente en el builder. */
    public static StringBuilder appendEnhanced(StringBuilder sb, long ms) {
        return appendCore(sb, ms, '<', '>');
    }

    /** mm:ss a partir de milisegundos (logs y mensajes). */
    public static String minutesSeconds(long ms) {
        return String.format("%02d:%02d", ms / 60000, (ms / 1000) % 60);
    }

    /** mm:ss a partir de segundos fraccionarios (etiquetas del reproductor). */
    public static String clock(double seconds) {
        int total = (int) seconds;
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    /** Parsea el primer timestamp LRC de la línea; -1 si no hay. */
    public static long parseLrcTimestamp(String line) {
        java.util.regex.Matcher m = LRC_TIMESTAMP.matcher(line);
        if (!m.find()) return -1;
        int minutes = Integer.parseInt(m.group(1));
        int seconds = Integer.parseInt(m.group(2));
        int hundredths = Integer.parseInt(m.group(3));
        return (long) minutes * 60_000 + (long) seconds * 1_000 + (long) hundredths * 10;
    }

    /** Elimina todos los timestamps [mm:ss.hh] de la línea. */
    public static String stripTimestamps(String line) {
        return STRIP_TIMESTAMPS.matcher(line).replaceAll("").trim();
    }

    public static final java.util.regex.Pattern LRC_TIMESTAMP =
            java.util.regex.Pattern.compile("\\[(\\d{2}):(\\d{2})\\.(\\d{2})\\]");
    private static final java.util.regex.Pattern STRIP_TIMESTAMPS =
            java.util.regex.Pattern.compile("\\[\\d{2}:\\d{2}\\.\\d{2}\\]");

    private static StringBuilder appendCore(StringBuilder sb, long ms, char open, char close) {
        int totalSeconds = (int) (ms / 1000);
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        int hundredths = (int) ((ms % 1000) / 10);

        sb.append(open);
        if (minutes < 10) sb.append('0');
        sb.append(minutes).append(':');
        if (seconds < 10) sb.append('0');
        sb.append(seconds).append('.');
        if (hundredths < 10) sb.append('0');
        sb.append(hundredths).append(close);
        return sb;
    }
}
