package com.executionbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class MarketDataStore {
    private final Path file;
    private final int maxAgeMinutes;

    MarketDataStore(Path file, int maxAgeMinutes) {
        this.file = file;
        this.maxAgeMinutes = maxAgeMinutes;
    }

    void append(MarketSnapshot snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        String line = String.format(java.util.Locale.ROOT,
                "%d,%d,%s,%s,%s,%s,%d,%s,%s,%s,%s,%d,%s,%s,%s,%s%n",
                snapshot.observedAt(), snapshot.oneMinute().openTime(),
                value(snapshot.oneMinute().open()), value(snapshot.oneMinute().high()),
                value(snapshot.oneMinute().low()), value(snapshot.oneMinute().close()),
                snapshot.thirtyMinute().openTime(), value(snapshot.thirtyMinute().open()),
                value(snapshot.thirtyMinute().high()), value(snapshot.thirtyMinute().low()),
                value(snapshot.thirtyMinute().close()), snapshot.oneHour().openTime(),
                value(snapshot.oneHour().open()), value(snapshot.oneHour().high()),
                value(snapshot.oneHour().low()), value(snapshot.oneHour().close()));
        Files.writeString(file, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    List<Candle> candles(int timeframeMinutes) throws IOException {
        if (!Files.exists(file)) return List.of();
        long cutoff = Instant.now().minusSeconds(maxAgeMinutes * 60L).toEpochMilli();
        List<Candle> result = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            String[] p = line.split(",");
            if (p.length != 16 || Long.parseLong(p[0]) < cutoff) continue;
            int base = timeframeMinutes == 1 ? 1 : timeframeMinutes == 30 ? 6 : 11;
            result.add(new Candle(Long.parseLong(p[base]), Long.parseLong(p[base]) + timeframeMinutes * 60_000L,
                    Double.parseDouble(p[base + 1]), Double.parseDouble(p[base + 2]),
                    Double.parseDouble(p[base + 3]), Double.parseDouble(p[base + 4])));
        }

        return result.stream().collect(java.util.stream.Collectors.toMap(Candle::openTime,
                c -> c, MarketDataStore::merge)).values().stream()
                .sorted(Comparator.comparingLong(Candle::openTime)).toList();
    }

    long latestObservedAt() throws IOException {
        if (!Files.exists(file)) return 0;
        long latest = 0;
        for (String line : Files.readAllLines(file)) {
            String[] p = line.split(",");
            if (p.length == 16) latest = Math.max(latest, Long.parseLong(p[0]));
        }
        return latest;
    }

    private static Candle merge(Candle a, Candle b) {
        return new Candle(a.openTime(), a.closeTime(), a.open(), Math.max(a.high(), b.high()),
                Math.min(a.low(), b.low()), b.close());
    }

    private static String value(double value) {
        return String.format(java.util.Locale.ROOT, "%.8f", value);
    }
}
