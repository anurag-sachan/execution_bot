package com.executionbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RelativeStrengthFilter {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    private static final Path BUCKETS = Path.of("rules", "net_r_buckets_0_59.csv");
    private final Map<Bucket, Double> netRByBucket;

    RelativeStrengthFilter() throws IOException {
        netRByBucket = loadBuckets();
    }

    String currentSetupReport(List<Candle> minutes, List<Candle> halfHours,
                              List<Candle> hours, long observedAt) {
        Candle setup = halfHours.stream()
                .filter(candle -> candle.openTime() <= observedAt)
                .max(Comparator.comparingLong(Candle::openTime))
                .orElse(null);
        if (setup == null || halfHours.indexOf(setup) == 0) {
            return "";
        }
        StringBuilder report = new StringBuilder();
        // report.append(String.format(Locale.ROOT,
        //         "\nMINUTES RSI %s%n",
        //         MINUTE.format(Instant.ofEpochMilli(setup.openTime()))));
        for (Side side : Side.values()) {
            report.append(eventsForCurrentSetup(minutes, hours, halfHours, setup,
                    side, observedAt));
        }
        return report.toString();
    }

    boolean allows(Signal signal) throws IOException {
        double strength = 0.0;
        Double touch = netRByBucket.get(new Bucket(signal.side(), Level.TOUCH,
                minute(signal.touchTime())));
        if (touch != null) strength += touch;
        return strength >= 0.0;
    }

    private String eventsForCurrentSetup(List<Candle> minutes, List<Candle> hours,
                                         List<Candle> halfHours, Candle setup,
                                         Side side, long observedAt) {
        int setupIndex = halfHours.indexOf(setup);
        Candle previous = halfHours.get(setupIndex - 1);
        long hourStart = Math.floorDiv(setup.openTime(), 3_600_000L) * 3_600_000L;
        Candle hour = hours.stream().filter(c -> c.openTime() == hourStart).findFirst().orElse(null);
        if (hour == null) {
            return String.format(Locale.ROOT, "%s RS : +0.000000%n", side);
        }
        double touch = side == Side.LONG ? previous.high() - 360 : previous.low() + 360;
        double entry = side == Side.LONG ? hour.open() - 90 : hour.open() + 110;
        if (side == Side.LONG ? touch >= entry : touch <= entry) {
            return String.format(Locale.ROOT, "%s RS : +0.000000 (invalid level relationship)%n", side);
        }
        double cumulative = 0.0;
        boolean touchSeen = false;
        boolean entrySeen = false;
        List<String> events = new ArrayList<>();
        for (Candle candle : minutes) {
            if (candle.openTime() < setup.openTime()) continue;
            if (candle.openTime() >= setup.closeTime() || candle.openTime() + 60_000L > observedAt) break;
            if (!touchSeen && reached(candle, touch)) {
                touchSeen = true;
                cumulative = appendEvent(events, side, cumulative, Level.TOUCH,
                        candle.openTime());
                break;
            }
            if (!entrySeen && reached(candle, entry)) {
                entrySeen = true;
                cumulative = appendEvent(events, side, cumulative, Level.ENTRY,
                        candle.openTime());
            }
        }
        if (events.isEmpty()) {
            return String.format(Locale.ROOT, "%s RS : %+.6f%n", side, cumulative);
        }
        return String.format(Locale.ROOT, "\n%s RS : %+.6f | EVENTS -> %s%n",
                side, cumulative, String.join(" | ", events));
    }

    private double appendEvent(List<String> events, Side side, double cumulative,
                               Level level, long timestamp) {
        double netR = netRByBucket.getOrDefault(
                new Bucket(side, level, minute(timestamp)), 0.0);
        cumulative += netR;
        String label = level == Level.ENTRY
                ? side == Side.LONG ? "1H_ENTRY_LEVEL_LOW" : "1H_ENTRY_LEVEL_HIGH"
                : side == Side.LONG ? "30M_LOW_TOUCH_LEVEL" : "30M_HIGH_TOUCH_LEVEL";
        events.add(String.format(Locale.ROOT, "%s @ %s : %+.6f",
                label, MINUTE.format(Instant.ofEpochMilli(timestamp)), netR));
        return cumulative;
    }

    private static boolean reached(Candle candle, double level) {
        return candle.low() <= level && candle.high() >= level;
    }

    private Map<Bucket, Double> loadBuckets() throws IOException {
        Map<OutcomeBucket, Double> outcomeSums = new HashMap<>();
        if (!Files.exists(BUCKETS)) {
            throw new IOException("Relative-strength bucket file not found: " + BUCKETS);
        }
        List<String> lines = Files.readAllLines(BUCKETS);
        if (lines.isEmpty()) return new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(",");
            if (fields.length < 63) continue;
            Level level;
            Side side;
            try {
                level = Level.valueOf(fields[0].replace("30M_TOUCH_LEVEL", "TOUCH")
                        .replace("1H_ENTRY_LEVEL", "ENTRY"));
                side = Side.valueOf(fields[1]);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            for (int minute = 0; minute < 60; minute++) {
                try {
                    double value = Double.parseDouble(fields[3 + minute].trim());
                    outcomeSums.merge(
                            new OutcomeBucket(side, level, minute, fields[2].trim()),
                            value, Double::sum);
                } catch (NumberFormatException ignored) {
                    // Ignore malformed cells while retaining other historical buckets.
                }
            }
        }
        return highlighted(outcomeSums);
    }

    private static Map<Bucket, Double> highlighted(Map<OutcomeBucket, Double> outcomeSums) {
        Map<Bucket, Double> result = new HashMap<>();
        for (Side side : Side.values()) {
            for (Level level : Level.values()) {
                for (int minute = 0; minute < 60; minute++) {
                    Bucket bucket = new Bucket(side, level, minute);
                    double win = outcomeSums.getOrDefault(
                            new OutcomeBucket(side, level, minute, "WIN"), 0.0);
                    double loss = outcomeSums.getOrDefault(
                            new OutcomeBucket(side, level, minute, "LOSS"), 0.0);
                    if (Math.abs(win) > 2.0 * Math.abs(loss)
                            || Math.abs(loss) > 2.0 * Math.abs(win)) {
                        result.put(bucket, win + loss);
                    }
                }
            }
        }
        return result;
    }

    private static int elapsedMinutes(int start, int end) {
        return Math.floorMod(end - start, 60);
    }

    private static int minute(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(IST).getMinute();
    }

    private enum Level { TOUCH, ENTRY }
    private record Bucket(Side side, Level level, int minute) {}
    private record OutcomeBucket(Side side, Level level, int minute, String result) {}
}
