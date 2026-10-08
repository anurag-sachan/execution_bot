package com.template;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads only executed scheduled trades and applies the same 2x highlighting
 * rule used by generate_performance_html.py to the 0-59 minute buckets.
 */
public final class RelativeStrengthAnalyzer {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(IST);
    private static final Path TRADES = Path.of("backtest_schedule.log");
    private static final Path EVENTS = Path.of("relative_strength_analyzer.log");
    private static final long HOUR = 60 * 60_000L;
    private static final double EPSILON = 1e-9;

    private RelativeStrengthAnalyzer() {}

    public static void main(String[] args) throws IOException {
        List<Candle> candles = candles(Csvreader.read1mCSV());
        List<List<String>> thirty = rows(Csvreader.read30mCSV());
        Map<Long, Double> hourly = hourly(Csvreader.read1hCSV());
        List<Trade> trades = readTrades();
        Map<BucketKey, Double> highlighted = highlighted(trades);

        StringBuilder events = new StringBuilder();
        for (Trade trade : trades) {
            Setup setup = setupFor(trade, thirty, hourly, candles);
            emitEvents(events, trade, setup, candles, highlighted);
        }
        Files.writeString(EVENTS, events.toString());
        System.out.println("Relative strength events written to " + EVENTS);
    }

    private static Map<BucketKey, Double> highlighted(List<Trade> trades) {
        Map<BucketKey, Double> sums = new HashMap<>();
        for (Trade trade : trades) {
            int touchMinute = minute(trade.touchTime);
            int entryMinute = minute(trade.entryTime);
            sums.merge(new BucketKey(trade.side, EventType.TOUCH, touchMinute, trade.result), trade.netR, Double::sum);
            sums.merge(new BucketKey(trade.side, EventType.ENTRY, entryMinute, trade.result), trade.netR, Double::sum);
        }
        Map<BucketKey, Double> result = new HashMap<>();
        for (Side side : Side.values()) {
            for (EventType type : EventType.values()) {
                for (int minute = 0; minute < 60; minute++) {
                    double win = sums.getOrDefault(new BucketKey(side, type, minute, Result.WIN), 0.0);
                    double loss = sums.getOrDefault(new BucketKey(side, type, minute, Result.LOSS), 0.0);
                    if (Math.abs(win) > 2 * Math.abs(loss)) {
                        result.put(new BucketKey(side, type, minute, Result.WIN), win);
                    } else if (Math.abs(loss) > 2 * Math.abs(win)) {
                        result.put(new BucketKey(side, type, minute, Result.LOSS), loss);
                    }
                }
            }
        }
        return result;
    }

    private static void emitEvents(StringBuilder out, Trade trade, Setup setup,
                                   List<Candle> candles, Map<BucketKey, Double> highlighted) {
        String prefix = trade.side + "," + trade.result + "," + formatSlTp(trade.stopPoints, trade.targetPoints);
        if (setup == null) {
            out.append(prefix).append(" : NO_LEVEL_EVENTS\n");
            return;
        }
        int start = first(candles, setup.start);
        int exit = first(candles, trade.exitTime);
        if (start < 0 || exit < 0) {
            out.append(prefix).append(" : NO_LEVEL_EVENTS\n");
            return;
        }
        double strength = 0.0;
        List<String> chain = new ArrayList<>();
        Set<EventType> emitted = new HashSet<>();
        for (int i = start; i <= exit; i++) {
            Candle candle = candles.get(i);
            EventType type = eventAt(candle, setup);
            if (type == null || !emitted.add(type)) continue;
            Double value = highlighted.get(new BucketKey(trade.side, type,
                    minute(candle.timestamp), trade.result));
            if (value == null) continue;
            double previous = strength;
            strength += value;
            
            String direction = value >= 0 ? "Relative INC" : "Relative DEC";
            double changePct = Math.abs(previous) < EPSILON ? 0.0 : value / Math.abs(previous) * 100.0;
            chain.add(type.label + " @ " + TIME.format(Instant.ofEpochMilli(candle.timestamp))
                    + " : " + signed(value) + " (" + direction + ") : CHANGE "
                    + String.format(Locale.ROOT, "%.2f%%", changePct) + " : CURRENT STRENGTH "
                    + signed(strength));
        }
        out.append(prefix).append(" : ")
                .append(chain.isEmpty() ? "NO_HIGHLIGHTED_LEVEL_EVENT" : String.join(" -> ", chain))
                .append('\n');
    }

    private static EventType eventAt(Candle candle, Setup setup) {
        if (reached(candle, setup.side == Side.LONG ? setup.longTouch : setup.shortTouch)) return EventType.TOUCH;
        if (reached(candle, setup.entry)) return EventType.ENTRY;
        return null;
    }

    private static Setup setupFor(Trade trade, List<List<String>> thirty,
                                  Map<Long, Double> hourly, List<Candle> candles) {
        long start = trade.setupStart;
        Double open = hourly.get(Math.floorDiv(start, HOUR) * HOUR);
        if (open == null) return null;
        List<String> current = null;
        for (int i = 1; i < thirty.size(); i++) {
            if (Long.parseLong(thirty.get(i).get(0)) == start) {
                current = thirty.get(i);
                break;
            }
        }
        if (current == null) return null;
        List<String> previous = thirty.get(thirty.indexOf(current) - 1);
        double lower = open - 90;
        double upper = open + 110;
        double longTouch = Double.parseDouble(previous.get(2)) - 360;
        double shortTouch = Double.parseDouble(previous.get(3)) + 360;
        return new Setup(start, trade.side, trade.side == Side.LONG ? lower : upper,
                longTouch, shortTouch, lower, upper);
    }

    private static boolean reached(Candle candle, double level) {
        return candle.low <= level && candle.high >= level;
    }

    private static List<Trade> readTrades() throws IOException {
        List<Trade> result = new ArrayList<>();
        for (String line : Files.readAllLines(TRADES)) {
            String[] p = line.split(",");
            if (p.length < 17 || (!"WIN".equals(p[14]) && !"LOSS".equals(p[14]))) continue;
            result.add(new Trade(parseTime(p[1]), Side.valueOf(p[2]), parseTime(p[3]),
                    parseTime(p[4]), parseTime(p[12]), Result.valueOf(p[14]),
                    Double.parseDouble(p[8]), Double.parseDouble(p[9]), Double.parseDouble(p[16])));
        }
        return result;
    }

    private static List<Candle> candles(List<List<String>> rows) {
        List<Candle> result = new ArrayList<>();
        for (List<String> row : rows) {
            result.add(new Candle(Long.parseLong(row.get(0)), Double.parseDouble(row.get(2)),
                    Double.parseDouble(row.get(3)), Double.parseDouble(row.get(4)),
                    Double.parseDouble(row.get(1))));
        }
        return result;
    }

    private static Map<Long, Double> hourly(List<List<String>> rows) {
        Map<Long, Double> result = new HashMap<>();
        for (List<String> row : rows) result.put(Long.parseLong(row.get(0)), Double.parseDouble(row.get(1)));
        return result;
    }

    private static List<List<String>> rows(List<List<String>> rows) {
        return rows.stream().filter(row -> {
            try { Long.parseLong(row.get(0)); return true; }
            catch (NumberFormatException ignored) { return false; }
        }).toList();
    }

    private static int first(List<Candle> candles, long timestamp) {
        int low = 0, high = candles.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (candles.get(mid).timestamp < timestamp) low = mid + 1; else high = mid;
        }
        return low == candles.size() ? -1 : low;
    }

    private static int minute(long timestamp) {
        return Instant.ofEpochMilli(timestamp).atZone(IST).getMinute();
    }

    private static long parseTime(String value) {
        return ZonedDateTime.parse(value.replace(" ", "T") + "+05:30").toInstant().toEpochMilli();
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.6f", value);
    }

    private static String formatSlTp(double stopPoints, double targetPoints) {
        return String.format(Locale.ROOT, "%.3f/%.3f", stopPoints, targetPoints);
    }

    private enum Side { LONG, SHORT }
    private enum Result { WIN, LOSS }
    private enum EventType { TOUCH("30M_TOUCH_LEVEL"), ENTRY("1H_ENTRY_LEVEL");
        final String label; EventType(String label) { this.label = label; } }
    private record Candle(long timestamp, double high, double low, double close, double open) {}
    private record Trade(long setupStart, Side side, long entryTime, long touchTime,
                         long exitTime, Result result, double stopPoints,
                         double targetPoints, double netR) {}
    private record Setup(long start, Side side, double entry, double longTouch,
                         double shortTouch, double lower, double upper) {}
    private record BucketKey(Side side, EventType type, int minute, Result result) {}
}
