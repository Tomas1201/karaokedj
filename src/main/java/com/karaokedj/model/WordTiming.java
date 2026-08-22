package com.karaokedj.model;

public class WordTiming {

    private final String word;
    private final long startMs;
    private final long endMs;

    public WordTiming(String word, long startMs, long endMs) {
        this.word = word;
        this.startMs = startMs;
        this.endMs = endMs;
    }

    public String getWord() {
        return word;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    @Override
    public String toString() {
        return String.format("[%d-%d ms] %s", startMs, endMs, word);
    }
}
