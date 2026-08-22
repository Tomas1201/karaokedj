package com.karaokedj.model;

import java.nio.file.Path;

public class StemSeparationResult {

    private final Path vocalPath;
    private final Path instrumentalPath;
    private final double duration;

    public StemSeparationResult(Path vocalPath, Path instrumentalPath, double duration) {
        this.vocalPath = vocalPath;
        this.instrumentalPath = instrumentalPath;
        this.duration = duration;
    }

    public Path getVocalPath() {
        return vocalPath;
    }

    public Path getInstrumentalPath() {
        return instrumentalPath;
    }

    public double getDuration() {
        return duration;
    }
}
