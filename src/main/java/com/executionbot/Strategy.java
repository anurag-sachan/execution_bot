package com.executionbot;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class Strategy {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int LONG_LEVEL_OFFSET = 90;
    private static final int SHORT_LEVEL_OFFSET = 110;
    private static final SetOfDays BEST_DAYS = new SetOfDays();
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("h:mm a");
    private final BotConfig config;

    Strategy(BotConfig config) {
        this.config = config;
    }

    long currentWindowStart(List<Candle> halfHours, long observedAt) {
        if (halfHours.isEmpty()) return Long.MIN_VALUE;
        Candle window = halfHours.get(halfHours.size() - 1);
        if (window.openTime() > observedAt && halfHours.size() > 1) {
            window = halfHours.get(halfHours.size() - 2);
        }
        return window.openTime();
    }

    String currentWindowReport(List<Candle> minutes, List<Candle> halfHours,
                               List<Candle> hours, long observedAt, Side openPositionSide) {
        if (halfHours.isEmpty()) {
            return "30m window: unavailable (no 30-minute candles)";
        }
        Candle window = halfHours.get(halfHours.size() - 1);
        if (window.openTime() > observedAt && halfHours.size() > 1) {
            window = halfHours.get(halfHours.size() - 2);
        }
        long hourStart = Math.floorDiv(window.openTime(), 3_600_000L) * 3_600_000L;
        Candle hour = hours.stream().filter(c -> c.openTime() == hourStart).findFirst().orElse(null);
        ZonedDateTime windowTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(window.openTime()), IST);
        ZonedDateTime currentTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(observedAt), IST);
        boolean excludedDate = Set.of(10, 14, 15).contains(currentTime.getDayOfMonth());
        boolean ignoredDay = !BEST_DAYS.contains(Side.LONG, currentTime.getDayOfWeek());
        double risk = config.riskCap() * (reducedRisk(windowTime) ? 0.1 : 1.0);
        StringBuilder report = new StringBuilder();
        report.append(String.format(Locale.ROOT,
                "%n---------- %s%s, %s%s, %s (IST) [$%.2f] ----------%n",
                DATE_FORMAT.format(currentTime), excludedDate ? " (EXCLUDED)" : "",
                currentTime.getDayOfWeek(), ignoredDay ? " (IGNORED)" : "",
                TIME_FORMAT.format(currentTime), risk));
        if (hour == null) {
            report.append("current windows -> 1hr: unavailable | 30m: ")
                    .append(TIME_FORMAT.format(windowTime)).append('\n');
            return report.toString();
        }

        Candle previous = halfHours.size() > 1
                ? halfHours.get(halfHours.indexOf(window) - 1) : null;
        if (previous == null) {
            report.append("current windows -> 1hr: ")
                    .append(TIME_FORMAT.format(ZonedDateTime.ofInstant(
                            Instant.ofEpochMilli(hour.openTime()), IST)))
                    .append(" | 30m: ").append(TIME_FORMAT.format(windowTime)).append('\n')
                    .append("Levels unavailable (previous 30-minute candle missing)\n");
            return report.toString();
        }
        report.append("current windows -> 1hr: ")
                .append(TIME_FORMAT.format(ZonedDateTime.ofInstant(
                        Instant.ofEpochMilli(hour.openTime()), IST)))
                .append(" | 30m: ").append(TIME_FORMAT.format(windowTime)).append('\n')
                .append(touchStatus(minutes, window, previous, hour, openPositionSide))
                .append("---------------\n");
        report.append(String.format(Locale.ROOT,
                "SHORT ↓ 1H_entry_level=%.2f 30m_touch_level=%.2f%n"
                        + "LONG  ↑ 1H_entry_level=%.2f 30m_touch_level=%.2f%n",
                hour.open() + SHORT_LEVEL_OFFSET, previous.low() + 360,
                hour.open() - LONG_LEVEL_OFFSET, previous.high() - 360));
        report.append("---------------\n");
        for (Side side : Side.values()) {
            Rule rule = rule(side, windowTime);
            String exclusion = exclusionReason(side, rule, windowTime);
            if (rule == null) {
                report.append(side).append(": AVOID (no schedule rule)\n");
                continue;
            }
            double entry = side == Side.LONG
                    ? hour.open() - LONG_LEVEL_OFFSET : hour.open() + SHORT_LEVEL_OFFSET;
            double stopDistance = effectiveStop(rule.stop);
            double targetDistance = effectiveTarget(rule.stop, rule.target);
            double executionEntry = side == Side.LONG ? entry + config.spreadPoints() : entry;
            double stopPrice = side == Side.LONG
                    ? executionEntry - stopDistance : executionEntry + stopDistance;
            double targetPrice = side == Side.LONG
                    ? executionEntry + targetDistance : executionEntry - targetDistance;
            String avoidance = exclusion;
            
            if (avoidance.isEmpty()) {
                Double roundLevel = roundFilterLevel(side, entry, rule.stop);
                if (roundLevel != null) {
                    avoidance = String.format(Locale.ROOT,
                            "\n ⏺ ROUND NUMBER IN SL range : %.0f", roundLevel);
                }
            }
            report.append(String.format(Locale.ROOT,
                    "%s %s: B/A_ENTRY_PRICE=%.2f SL=%.2f TP=%.2f "
                            + "stop=%.0f target=%.2f (spread:%.0f)%s%n",
                    side == Side.SHORT ? "🔴 ↓" : "🟢 ↑", side,
                    executionEntry, stopPrice, targetPrice, stopDistance, targetDistance,
                    config.spreadPoints(),
                    avoidance.isEmpty() ? "" : " AVOID: " + avoidance));
        }
        return report.toString();
    }

    private String touchStatus(List<Candle> minutes, Candle setup, Candle previous,
                               Candle hour, Side openPositionSide) {
        if (openPositionSide != null) {
            return "⚪️ OPEN POSITION : " + openPositionSide + "\n";
        }
        double longTouch = previous.high() - 360;
        double shortTouch = previous.low() + 360;
        double longEntry = hour.open() - LONG_LEVEL_OFFSET;
        double shortEntry = hour.open() + SHORT_LEVEL_OFFSET;
        Candle longTouchCandle = null;
        Candle shortTouchCandle = null;
        for (Candle candle : minutes) {
            if (candle.openTime() < setup.openTime()) continue;
            if (candle.openTime() >= setup.closeTime()) break;
            if (longTouch < longEntry && longTouchCandle == null && candle.low() <= longTouch) {
                longTouchCandle = candle;
            }
            if (shortTouch > shortEntry && shortTouchCandle == null && candle.high() >= shortTouch) {
                shortTouchCandle = candle;
            }
        }
        if (longTouchCandle == null && shortTouchCandle == null) {
            return "WAITING FOR SETUP\n";
        }
        Candle touched = longTouchCandle != null
                && (shortTouchCandle == null
                || longTouchCandle.openTime() <= shortTouchCandle.openTime())
                ? longTouchCandle : shortTouchCandle;
        String level = touched == longTouchCandle && touched == shortTouchCandle
                ? "30M H/L"
                : touched == longTouchCandle ? "30M LOW" : "30M HIGH";
        return String.format(Locale.ROOT, "🔵 %s %s touched%n",
                TIME_FORMAT.format(Instant.ofEpochMilli(touched.openTime()).atZone(IST)), level);
    }

    Signal latestSignal(List<Candle> minutes, List<Candle> halfHours,
                        List<Candle> hours, long closedMinute) {
        if (halfHours.size() < 2 || minutes.isEmpty()) {
            return null;
        }
        Candle setup = halfHours.get(halfHours.size() - 1);
        if (setup.closeTime() > closedMinute) {
            setup = halfHours.get(halfHours.size() - 2);
        }
        long setupStart = setup.openTime();
        long setupEnd = setupStart + 30 * 60_000L;
        Candle previous = halfHours.get(halfHours.indexOf(setup) - 1);
        long hourStart = Math.floorDiv(setupStart, 3_600_000L) * 3_600_000L;
        Candle hour = hours.stream().filter(c -> c.openTime() == hourStart).findFirst().orElse(null);
        if (hour == null) {
            return null;
        }
        List<Signal> candidates = new ArrayList<>();
        addCandidate(candidates, Side.LONG, setupStart, setupEnd,
                previous.high() - 360, hour.open() - LONG_LEVEL_OFFSET, minutes);
        addCandidate(candidates, Side.SHORT, setupStart, setupEnd,
                previous.low() + 360, hour.open() + SHORT_LEVEL_OFFSET, minutes);
        return candidates.stream().filter(s -> s.entryTime() <= closedMinute)
                .max(java.util.Comparator.comparingLong(Signal::entryTime)).orElse(null);
    }

    Signal pendingSignal(List<Candle> minutes, List<Candle> halfHours,
                         List<Candle> hours, long observedAt) {
        if (halfHours.size() < 2 || minutes.isEmpty()) return null;
        Candle setup = halfHours.get(halfHours.size() - 1);
        if (setup.openTime() + 30 * 60_000L <= observedAt) return null;
        Candle previous = halfHours.get(halfHours.size() - 2);
        long hourStart = Math.floorDiv(setup.openTime(), 3_600_000L) * 3_600_000L;
        Candle hour = hours.stream().filter(c -> c.openTime() == hourStart).findFirst().orElse(null);
        if (hour == null) return null;

        List<Signal> candidates = new ArrayList<>();
        double longTouchLevel = previous.high() - 360;
        double longEntryLevel = hour.open() - LONG_LEVEL_OFFSET;
        if (longTouchLevel < longEntryLevel) {
            addPendingCandidate(candidates, Side.LONG, setup, longTouchLevel,
                    longEntryLevel, minutes, observedAt);
        }
        double shortTouchLevel = previous.low() + 360;
        double shortEntryLevel = hour.open() + SHORT_LEVEL_OFFSET;
        if (shortTouchLevel > shortEntryLevel) {
            addPendingCandidate(candidates, Side.SHORT, setup, shortTouchLevel,
                    shortEntryLevel, minutes, observedAt);
        }
        return candidates.stream()
                .max(java.util.Comparator.comparingLong(Signal::touchTime)).orElse(null);
    }

    private void addPendingCandidate(List<Signal> out, Side side, Candle setup,
                                     double touch, double entry, List<Candle> minutes,
                                     long observedAt) {
        if (side == Side.LONG ? touch >= entry : touch <= entry) return;
        int touchIndex = -1;
        for (int index = 0; index < minutes.size(); index++) {
            Candle candle = minutes.get(index);
            if (candle.openTime() < setup.openTime()) continue;
            if (candle.openTime() >= setup.closeTime()) break;
            if (touchIndex < 0) {
                if (side == Side.LONG ? candle.low() <= touch : candle.high() >= touch) {
                    touchIndex = index;
                }
            } else if (index > touchIndex
                    && (side == Side.LONG ? candle.high() >= entry : candle.low() <= entry)) {
                return;
            }
        }
        if (touchIndex < 0) return;
        if (minutes.get(touchIndex).openTime() + 60_000L > observedAt) return;

        ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(setup.openTime()), IST);
        Rule rule = rule(side, time);
        if (rule != null && allowed(side, rule, time) && !roundFiltered(side, entry, rule.stop)) {
            out.add(new Signal(side, setup.openTime(), minutes.get(touchIndex).openTime(),
                    minutes.get(touchIndex).openTime(),
                    touch, entry, rule.stop, rule.target, reducedRisk(time) ? 0.1 : 1.0));
        }
    }

    private void addCandidate(List<Signal> out, Side side, long start, long end,
                              double touch, double entry, List<Candle> minutes) {
        boolean setupValid = side == Side.LONG ? touch < entry : touch > entry;
        if (!setupValid) {
            return;
        }
        int touchIndex = -1;
        for (int i = 0; i < minutes.size(); i++) {
            Candle candle = minutes.get(i);
            if (candle.openTime() < start) continue;
            if (candle.openTime() >= end) break;
            if (touchIndex < 0) {
                if (side == Side.LONG ? candle.low() <= touch : candle.high() >= touch) touchIndex = i;
            } else if (side == Side.LONG ? candle.high() >= entry : candle.low() <= entry) {
                ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(candle.openTime()), IST);
                Rule rule = rule(side, time);
                if (rule != null && allowed(side, rule, time) && !roundFiltered(side, entry, rule.stop)) {
                    out.add(new Signal(side, start, minutes.get(touchIndex).openTime(),
                            candle.openTime(), touch, entry, rule.stop, rule.target,
                            reducedRisk(time) ? 0.1 : 1.0));
                }
                return;
            }
        }
    }

    private boolean allowed(Side side, Rule rule, ZonedDateTime time) {
        return exclusionReason(side, rule, time).isEmpty();
    }

    private String exclusionReason(Side side, Rule rule, ZonedDateTime time) {
        if (rule == null) return "no schedule rule";
        if (!BEST_DAYS.contains(side, time.getDayOfWeek())) return "day of week";
        if (Set.of(10, 14, 15).contains(time.getDayOfMonth())) return "day of month";
        if (side == Side.LONG && rule.stop == 70 && time.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return "LONG 70-point Saturday rule";
        }
        if (side == Side.LONG && rule.stop == 270 && time.getDayOfWeek() == DayOfWeek.WEDNESDAY) {
            return "LONG 270-point Wednesday rule";
        }
        if (side == Side.SHORT && (time.getDayOfWeek() == DayOfWeek.FRIDAY
                || time.getDayOfWeek() == DayOfWeek.SATURDAY)) {
            return "SHORT Friday/Saturday rule";
        }
        return "";
    }

    private Rule rule(Side side, ZonedDateTime t) {
        int slot = t.getHour() * 2 + (t.getMinute() >= 30 ? 1 : 0);
        return side == Side.LONG ? LONG_RULES.get(slot) : SHORT_RULES.get(slot);
    }

    private boolean reducedRisk(ZonedDateTime t) {
        int slot = t.getHour() * 2 + (t.getMinute() >= 30 ? 1 : 0);
        return slot == 9 || slot == 24 || slot == 34;
    }

    private double effectiveStop(int stop) {
        return stop + config.spreadPoints();
    }

    private double effectiveTarget(int stop, int target) {
        return effectiveStop(stop) * target / (double) stop;
    }

    private boolean roundFiltered(Side side, double entry, int stop) {
        return roundFilterLevel(side, entry, stop) != null;
    }

    private Double roundFilterLevel(Side side, double entry, int stop) {
        double execution = entry + (side == Side.LONG ? config.spreadPoints() : 0);
        double stopPrice = side == Side.LONG ? execution - stop : execution + stop;
        double low = Math.min(execution, stopPrice);
        double high = Math.max(execution, stopPrice);
        double firstRoundLevel = Math.ceil((low - 1e-9) / 500) * 500;
        return firstRoundLevel <= high + 1e-9 ? firstRoundLevel : null;
    }

    private record Rule(int stop, int target) {}
    private static final Map<Integer, Rule> LONG_RULES = schedule(new int[][]{
            {1,70,300},{3,70,300},{4,70,300},{5,70,300},{6,70,300},{7,70,300},
            {8,70,300},{9,70,300},{10,70,600},{11,270,300},{12,270,300},
            {13,270,300},{14,70,300},{15,70,300},{16,70,300},{17,70,300},
            {19,70,300},{20,70,300},{23,70,300},{24,70,300},{25,70,300},
            {32,70,300},{33,70,300},{35,70,300},{36,70,300},{38,70,300},
            {40,70,800},{41,70,300},{43,70,300},{44,70,300},{45,270,300},
            {46,70,300},{47,70,300}});
    private static final Map<Integer, Rule> SHORT_RULES = schedule(new int[][]{
            {0,220,1500},{1,50,1500},{3,50,1500},{6,50,680},{8,50,680},
            {11,50,680},{12,50,1500},{13,50,400},{14,50,1500},{15,50,400},
            {17,50,1500},{19,50,300},{22,50,680},{25,50,680},{27,50,400},
            {29,50,1500},{33,50,680},{34,50,1500},{35,220,1500},{36,50,1500},
            {37,50,1500},{38,50,680},{39,50,680},{40,50,400},{41,50,1500},
            {43,50,400},{44,50,400},{45,50,400},{47,50,680}});

    private static Map<Integer, Rule> schedule(int[][] rows) {
        Map<Integer, Rule> result = new HashMap<>();
        for (int[] row : rows) result.put(row[0], new Rule(row[1], row[2]));
        return Map.copyOf(result);
    }

    private static final class SetOfDays {
        private final Map<Side, EnumSet<DayOfWeek>> values = Map.of(
                Side.LONG, EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.FRIDAY, DayOfWeek.SUNDAY),
                Side.SHORT, EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.FRIDAY, DayOfWeek.SUNDAY));
        boolean contains(Side side, DayOfWeek day) { return values.get(side).contains(day); }
    }
}
