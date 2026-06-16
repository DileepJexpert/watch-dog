package com.sentinel.intelligence.anomaly;

import lombok.Data;

/**
 * In-memory baseline model for a specific service+metric+time-slot combination.
 * Uses Welford's online algorithm for numerically stable running mean/variance.
 */
@Data
public class BaselineModel {

    private final String serviceName;
    private final String metricName;
    private final int hourOfDay;
    private final int dayOfWeek;

    private double mean = 0.0;
    private double m2 = 0.0; // Welford's M2 accumulator
    private int count = 0;

    public synchronized void update(double value) {
        count++;
        double delta = value - mean;
        mean += delta / count;
        double delta2 = value - mean;
        m2 += delta * delta2;
    }

    public double getStdDev() {
        if (count < 2) return 0.0;
        return Math.sqrt(m2 / (count - 1));
    }

    public double getZScore(double value) {
        double stdDev = getStdDev();
        if (stdDev == 0.0) return 0.0;
        return Math.abs((value - mean) / stdDev);
    }

    public boolean hasEnoughSamples(int minSamples) {
        return count >= minSamples;
    }
}
