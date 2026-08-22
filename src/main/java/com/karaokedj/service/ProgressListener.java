package com.karaokedj.service;

public interface ProgressListener {

    void onStep(String step);

    default void onProgress(String step, long bytes) {
    }

    default void onDetail(String detail) {
    }

    default void onFraction(double fraction) {
    }
}
