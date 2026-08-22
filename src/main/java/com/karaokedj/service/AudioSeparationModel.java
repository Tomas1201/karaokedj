package com.karaokedj.service;

import com.karaokedj.ml.BsRoformerModel;
import com.karaokedj.ml.DemucsModel;

/** Modelos de separación voz/instrumental disponibles en la UI. */
public enum AudioSeparationModel {

    DEMUCS("Demucs", "Rápido") {
        @Override
        public com.karaokedj.ml.SeparationModel createModel() {
            return new DemucsModel();
        }
    },
    BS_ROFORMER("BS-RoFormer", "Calidad, más lento") {
        @Override
        public com.karaokedj.ml.SeparationModel createModel() {
            return new BsRoformerModel();
        }
    };

    private final String displayName;
    private final String hint;

    AudioSeparationModel(String displayName, String hint) {
        this.displayName = displayName;
        this.hint = hint;
    }

    public String displayName() {
        return displayName;
    }

    public String uiLabel() {
        return displayName + " (" + hint + ")";
    }

    /** Instancia nueva del modelo ONNX correspondiente. */
    public abstract com.karaokedj.ml.SeparationModel createModel();

    public static AudioSeparationModel fromUiIndex(int index) {
        return values()[Math.max(0, Math.min(index, values().length - 1))];
    }
}
