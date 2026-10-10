package com.exchange.perf.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Where a benchmark puts a number. One line of JSON per measurement, appended to
 * {@code build/perf/results.jsonl} of whichever module ran it, so a JVM forked per test class
 * (the engine chunk does that) still adds to one file, and {@link ReportRenderer} reads
 * every module's file afterwards.
 *
 * <p>A measurement names its section and metric as the report shows them, carries the
 * topology it ran on ({@code PERF_TOPOLOGY}: {@code single} or {@code cluster}, the report's
 * two columns), the workload in one sentence, and for a latency its percentiles rather than an
 * average, which hides the tail a trader feels.
 */
public final class Report {

    /** One measurement. {@code p50/p95/p99} are null for anything that is not a latency. */
    public record Measurement(String section, String metric, String topology, double value, String unit,
                              Double p50, Double p95, Double p99, Integer samples, String workload,
                              String meaning, long at) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path FILE = Path.of(System.getProperty("perf.results", "build/perf/results.jsonl"));

    private Report() {
    }

    public static String topology() {
        String t = System.getenv("PERF_TOPOLOGY");
        return t == null || t.isBlank() ? "single" : t.trim();
    }

    /** A rate, a size, a duration: one number. */
    public static void value(String section, String metric, double value, String unit, String workload, String meaning) {
        write(new Measurement(section, metric, topology(), value, unit, null, null, null, null, workload, meaning, System.currentTimeMillis()));
    }

    /** A latency: the percentiles of every sample, and how many there were. */
    public static void latency(String section, String metric, List<Long> samplesNanos, String workload, String meaning) {
        long[] sorted = samplesNanos.stream().mapToLong(Long::longValue).sorted().toArray();
        double p50 = percentile(sorted, 50), p95 = percentile(sorted, 95), p99 = percentile(sorted, 99);
        write(new Measurement(section, metric, topology(), p50, "ms", p50, p95, p99, sorted.length, workload, meaning, System.currentTimeMillis()));
    }

    /** Milliseconds at a percentile, nearest-rank. */
    public static double percentile(long[] sortedNanos, int p) {
        if (sortedNanos.length == 0) return Double.NaN;
        int rank = (int) Math.ceil(p / 100.0 * sortedNanos.length) - 1;
        return sortedNanos[Math.max(0, Math.min(rank, sortedNanos.length - 1))] / 1_000_000.0;
    }

    private static synchronized void write(Measurement m) {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, JSON.writeValueAsString(m) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // The console line too, so a run reads as it goes.
        System.out.printf(Locale.ROOT, "PERF %s | %s | %s%n", m.section(), m.metric(), line(m));
    }

    static String line(Measurement m) {
        if (m.p50() != null) {
            return String.format(Locale.ROOT, "p50 %.1f ms · p95 %.1f ms · p99 %.1f ms (n=%d)", m.p50(), m.p95(), m.p99(), m.samples());
        }
        return String.format(Locale.ROOT, "%s %s", number(m.value()), m.unit());
    }

    static String number(double v) {
        if (Double.isNaN(v)) return "—";
        if (Math.abs(v) >= 100) return String.format(Locale.ROOT, "%,.0f", v);
        if (Math.abs(v) >= 10) return String.format(Locale.ROOT, "%.1f", v);
        return String.format(Locale.ROOT, "%.2f", v);
    }

    /** For tests and the renderer: the file this JVM writes to. */
    static Path file() {
        return FILE;
    }

    static List<String> lines(Path file) throws IOException {
        return Files.exists(file) ? Files.readAllLines(file) : List.of();
    }

    static Measurement parse(String json) {
        try {
            return JSON.readValue(json, Measurement.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static boolean isLatency(Measurement m) {
        return m.p50() != null;
    }

    static List<String> sections() {
        return Arrays.asList(Sections.ALL);
    }
}
