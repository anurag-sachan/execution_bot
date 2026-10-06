package com.executionbot;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Strategy {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int LONG_LEVEL_OFFSET = 90;
    private static final int SHORT_LEVEL_OFFSET = 110;
    private static final SetOfDays BEST_DAYS = new SetOfDays();
    private final BotConfig config;

    Strategy(BotConfig config) {
        this.config = config;
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
        addPendingCandidate(candidates, Side.LONG, setup, previous.high() - 360,
                hour.open() - LONG_LEVEL_OFFSET, minutes);
        addPendingCandidate(candidates, Side.SHORT, setup, previous.low() + 360,
                hour.open() + SHORT_LEVEL_OFFSET, minutes);
        return candidates.stream()
                .max(java.util.Comparator.comparingLong(Signal::touchTime)).orElse(null);
    }

    private void addPendingCandidate(List<Signal> out, Side side, Candle setup,
                                     double touch, double entry, List<Candle> minutes) {
        if (side == Side.LONG ? touch >= entry : touch <= entry) return;
        Candle latest = minutes.get(minutes.size() - 1);
        boolean touched = false;
        for (Candle candle : minutes) {
            if (candle.openTime() < setup.openTime()) continue;
            if (candle.openTime() >= setup.closeTime()) break;
            if (side == Side.LONG ? candle.low() <= touch : candle.high() >= touch) {
                touched = true;
            }
        }
        if (!touched) return;
        boolean entryAlreadyCrossed = side == Side.LONG
                ? latest.high() >= entry : latest.low() <= entry;
        if (entryAlreadyCrossed) return;

        ZonedDateTime time = ZonedDateTime.ofInstant(Instant.ofEpochMilli(setup.openTime()), IST);
        Rule rule = rule(side, time);
        if (rule != null && allowed(side, rule, time) && !roundFiltered(side, entry, rule.stop)) {
            out.add(new Signal(side, setup.openTime(), setup.openTime(), setup.openTime(),
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
        return BEST_DAYS.contains(side, time.getDayOfWeek())
                && !Set.of(10, 14, 15).contains(time.getDayOfMonth())
                && !(side == Side.LONG && rule.stop == 70 && time.getDayOfWeek() == DayOfWeek.SATURDAY)
                && !(side == Side.LONG && rule.stop == 270 && time.getDayOfWeek() == DayOfWeek.WEDNESDAY)
                && !(side == Side.SHORT && (time.getDayOfWeek() == DayOfWeek.FRIDAY
                || time.getDayOfWeek() == DayOfWeek.SATURDAY));
    }

    private Rule rule(Side side, ZonedDateTime t) {
        int slot = t.getHour() * 2 + (t.getMinute() >= 30 ? 1 : 0);
        return side == Side.LONG ? LONG_RULES.get(slot) : SHORT_RULES.get(slot);
    }

    private boolean reducedRisk(ZonedDateTime t) {
        int slot = t.getHour() * 2 + (t.getMinute() >= 30 ? 1 : 0);
        return slot == 9 || slot == 24 || slot == 34;
    }

    private boolean roundFiltered(Side side, double entry, int stop) {
        double execution = entry + (side == Side.LONG ? config.spreadPoints() : 0);
        double stopPrice = side == Side.LONG ? execution - stop : execution + stop;
        double low = Math.min(execution, stopPrice);
        double high = Math.max(execution, stopPrice);
        return Math.ceil((low - 1e-9) / 500) <= Math.floor((high + 1e-9) / 500);
    }

    private record Rule(int stop, int target) {}
    private static final Map<Integer, Rule> LONG_RULES = schedule(new int[][]{
            {1,70,300},{3,70,300},{4,70,300},{5,70,300},{6,70,300},{7,70,300},
            {8,70,300},{9,70,300},{10,70,600},{11,270,300},{12,270,300},{13,270,300},
            {14,70,300},{15,70,300},{16,70,300},{17,70,300},{19,70,300},{20,70,300},
            {23,70,300},{24,70,300},{32,70,300},{33,70,300},{35,70,300},{36,70,300},
            {38,70,300},{40,70,800},{41,70,300},{43,70,300},{44,70,300},{45,270,300},
            {46,70,300},{47,70,300}});
    private static final Map<Integer, Rule> SHORT_RULES = schedule(new int[][]{
            {0,220,1500},{1,50,1500},{3,50,1500},{6,50,680},{8,50,680},{11,50,680},
            {12,50,1500},{13,50,400},{14,50,1500},{15,50,400},{17,50,1500},{19,50,300},
            {22,50,680},{25,50,680},{27,50,400},{29,50,1500},{33,50,680},{34,50,1500},
            {35,220,1500},{36,50,1500},{37,50,1500},{38,50,680},{39,50,680},{40,50,400},
            {41,50,1500},{43,50,400},{44,50,400},{45,50,400},{47,50,680}});

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
