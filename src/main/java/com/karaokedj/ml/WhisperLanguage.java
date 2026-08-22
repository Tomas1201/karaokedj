package com.karaokedj.ml;

/**
 * Idiomas soportados por el prompt de Whisper multilingüe.
 *
 * El idioma se inyecta como token de lenguaje en el prompt inicial:
 * [SOT, &lt;|lang|&gt;, TRANSCRIBE]. Con {@link #AUTO} el prompt es solo [SOT]
 * y el propio modelo predice el idioma del audio.
 */
public enum WhisperLanguage {

    AUTO("auto", "Detectar automáticamente", -1),
    ES("es", "Español", 50262),
    EN("en", "English", 50259),
    FR("fr", "Français", 50265),
    PT("pt", "Português", 50267),
    DE("de", "Deutsch", 50261),
    IT("it", "Italiano", 50274);

    private final String code;
    private final String displayName;
    private final int token;

    WhisperLanguage(String code, String displayName, int token) {
        this.code = code;
        this.displayName = displayName;
        this.token = token;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /** Token de lenguaje; -1 para AUTO. */
    public int token() {
        return token;
    }

    public String uiLabel() {
        return displayName();
    }

    public static WhisperLanguage fromCode(String code) {
        for (WhisperLanguage lang : values()) {
            if (lang.code.equalsIgnoreCase(code)) return lang;
        }
        return ES;
    }
}
