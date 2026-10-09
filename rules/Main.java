package com.template;

import java.io.IOException;
import java.time.Instant;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class Main {
    private static final long ONE_MINUTE_MS = 60_000L;
    private static final long THIRTY_MINUTES_MS = 30 * ONE_MINUTE_MS;
    private static final long ONE_HOUR_MS = 60 * ONE_MINUTE_MS;
    private static final int MIN_STOP_POINTS = 70;
    private static final int MAX_STOP_POINTS_EXCLUSIVE = 400;
    private static final int STOP_STEP_POINTS = 50;
    private static final int MIN_TARGET_POINTS = 200;
    private static final int MAX_TARGET_POINTS = 1000;
    private static final int TARGET_STEP_POINTS = 100;
    // private static final Set<Integer> REDUCED_RISK_TIME_SLOTS = Set.of(9, 24, 34); // 04:30, 12:00, 17:00 IST
    private static final Set<Integer> REDUCED_RISK_TIME_SLOTS = Set.of(9, 24, 34); // 04:30, 12:00, 17:00 IST
    private static final double REDUCED_RISK_MULTIPLIER = 0.1;
    private static final double DEFAULT_SPREAD_POINTS = 15.0;
    private static final double DEFAULT_COMMISSION_PERCENT_OF_RISK = 5.88; //20 spreads, dont work -> (2.5)5ers (3)ftraders would work -> high commission/less spreads even better net_r
    private static double spreadPoints = DEFAULT_SPREAD_POINTS;
    private static double commissionPercentOfRisk = DEFAULT_COMMISSION_PERCENT_OF_RISK;
    // Spread handling. The nominal SL/TP combos were tuned at REF spread; at a wider spread both are
    // scaled by spread/ref so the share of the stop eaten by the spread (and the RR) stays the same.
    private static final double DEFAULT_REF_SPREAD_POINTS = 15.0;
    private static double refSpreadPoints = DEFAULT_REF_SPREAD_POINTS;
    private static double slTpScale = 1.0;
    // true  = broker fills (candles = bid chart): longs execute at the ask (1h level + spread), shorts at
    //         the bid (1h level); SL/TP are measured from the execution price, so a loss is exactly -SL and
    //         a win exactly +TP in points; the spread shows up in the hit rate, not as a deduction.
    // false = legacy: signal level is the fill and the spread is deducted from every trade's P&L.
    private static boolean brokerFills = true;
    // How SL/TP are adapted to the spread:
    //   add   (default) effective SL = SL + spread, effective TP = effective SL * TP / SL, so the RR is unchanged
    //                   (70/300 at spread 15 -> 85 / 364.2857). With broker fills the stop then triggers exactly
    //                   the nominal SL away from the signal level on the bid chart.
    //   scale           previous behaviour: both multiplied by max(1, spread / ref-spread), rounded.
    //   none            nominal SL/TP used as they are.
    private static String slTpAdjust = "add";
    private static final DateTimeFormatter IST_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.of("Asia/Kolkata"));
    private static final List<StopTarget> BUY_COMBINATIONS = List.of(
            new StopTarget(70, 300), new StopTarget(270, 300));
    private static final List<StopTarget> SELL_COMBINATIONS = List.of(
            new StopTarget(70, 400), new StopTarget(70, 600),
            new StopTarget(70, 800), new StopTarget(220, 800));
        private static final ScheduleRule AVOID = new ScheduleRule(true, 0, 0);
        private static final Map<Integer, ScheduleRule> BUY_TIME_RULES = buildSchedule(
            timeRule("00:00", "00:30", AVOID),
            timeRule("00:30", "01:00", tradeRule(70, 300)),
            timeRule("01:00", "01:30", AVOID),
            timeRule("01:30", "03:30", tradeRule(70, 300)),
            timeRule("03:30", "04:00", AVOID),
            timeRule("04:00", "05:00", tradeRule(70, 300)),
            timeRule("05:00", "05:30", tradeRule(70, 600)),
            timeRule("05:30", "07:00", tradeRule(270, 300)),
            timeRule("07:00", "09:00", tradeRule(70, 300)),
            timeRule("09:00", "09:30", AVOID),
            timeRule("09:30", "10:00", tradeRule(70, 300)),
            timeRule("10:00", "11:00", tradeRule(70, 300)),
            timeRule("11:00", "11:30", AVOID),
            timeRule("11:30", "13:00", tradeRule(70, 300)),
            timeRule("13:00", "13:30", AVOID),
            timeRule("13:30", "16:00", AVOID),
            timeRule("16:00", "17:00", tradeRule(70, 300)),
            timeRule("17:00", "17:30", AVOID),
            timeRule("17:30", "18:30", tradeRule(70, 300)),
            timeRule("18:30", "19:00", AVOID),
            timeRule("19:00", "19:30", tradeRule(70, 300)),
            timeRule("19:30", "20:00", AVOID),
            timeRule("20:00", "20:30", tradeRule(70, 800)),
            timeRule("20:30", "21:00", tradeRule(70, 300)),
            timeRule("21:00", "21:30", AVOID),
            timeRule("21:30", "22:30", tradeRule(70, 300)),
            timeRule("22:30", "23:00", tradeRule(270, 300)),
            timeRule("23:00", "24:00", tradeRule(70, 300)));
        private static final Map<Integer, ScheduleRule> SELL_TIME_RULES = buildSchedule(
            timeRule("00:00", "00:30", tradeRule(220, 1500)),
            timeRule("00:30", "01:00", tradeRule(50, 1500)),
            timeRule("01:00", "01:30", AVOID),
            timeRule("01:30", "02:00", tradeRule(50, 1500)),
            timeRule("02:00", "03:00", AVOID),
            timeRule("03:00", "03:30", tradeRule(50, 680)),
            timeRule("03:30", "04:00", AVOID),
            timeRule("04:00", "04:30", tradeRule(50, 680)),
            timeRule("04:30", "05:30", AVOID),
            timeRule("05:30", "06:00", tradeRule(50, 680)),
            timeRule("06:00", "06:30", tradeRule(50, 1500)),
            timeRule("06:30", "07:00", tradeRule(50, 400)),
            timeRule("07:00", "07:30", tradeRule(50, 1500)),
            timeRule("07:30", "08:00", tradeRule(50, 400)),
            timeRule("08:00", "08:30", AVOID),
            timeRule("08:30", "09:00", tradeRule(50, 1500)),
            timeRule("09:00", "09:30", AVOID),
            timeRule("09:30", "10:00", tradeRule(50, 300)),
            timeRule("10:00", "11:00", AVOID),
            timeRule("11:00", "11:30", tradeRule(50, 680)),
            timeRule("11:30", "12:30", AVOID),
            timeRule("12:30", "13:00", tradeRule(50, 680)),
            timeRule("13:00", "13:30", AVOID),
            timeRule("13:30", "14:00", tradeRule(50, 400)),
            timeRule("14:00", "14:30", AVOID),
            timeRule("14:30", "15:00", tradeRule(50, 1500)),
            timeRule("15:00", "16:30", AVOID),
            timeRule("16:30", "17:00", tradeRule(50, 680)),
            timeRule("17:00", "17:30", tradeRule(50, 1500)),
            timeRule("17:30", "18:00", tradeRule(220, 1500)),
            timeRule("18:00", "19:00", tradeRule(50, 1500)),
            timeRule("19:00", "19:30", tradeRule(50, 680)),
            timeRule("19:30", "20:00", tradeRule(50, 680)),
            timeRule("20:00", "20:30", tradeRule(50, 400)),
            timeRule("20:30", "21:00", tradeRule(50, 1500)),
            timeRule("21:00", "21:30", AVOID),
            timeRule("21:30", "23:00", tradeRule(50, 400)),
            timeRule("23:00", "23:30", AVOID),
            timeRule("23:30", "24:00", tradeRule(50, 680)));
        private static final Map<Side, EnumSet<DayOfWeek>> BEST_DAYS = Map.of(
            Side.LONG, EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SUNDAY),
            Side.SHORT, EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.FRIDAY, DayOfWeek.SUNDAY)
            );
        // Calendar days of the month (IST entry date) on which no trade is taken.
        private static final Set<Integer> EXCLUDED_DAYS_OF_MONTH = Set.of(10,14,15);

    public static void main(String[] args) throws IOException {
        parseCostInputs(args);
        StringBuilder rawLog = new StringBuilder();
        rawLog.append("RAW PERFORMANCE DATA\n")
              .append("spread_points=").append(formatPrice(spreadPoints))
              .append(",commission_percent_of_risk=")
              .append(String.format(Locale.ROOT, "%.4f", commissionPercentOfRisk))
              .append(",broker_fills=").append(brokerFills ? 1 : 0)
              .append(",ref_spread_points=").append(formatPrice(refSpreadPoints))
              .append(",sl_tp_scale=").append(String.format(Locale.ROOT, "%.4f", slTpScale))
              .append(",sl_tp_add=").append(slTpAdjust.equals("add") ? 1 : 0).append("\n\n");
        List<List<String>> candles1h = dataRows(Csvreader.read1hCSV());
        List<List<String>> candles30m = dataRows(Csvreader.read30mCSV());
        List<List<String>> candles1m = dataRows(Csvreader.read1mCSV());

        Map<Long, Double> hourlyOpen = new HashMap<>();
        for (List<String> candle : candles1h) {
            hourlyOpen.put(Long.parseLong(candle.get(0)), Double.parseDouble(candle.get(1)));
        }

        List<Candle> minuteCandles = new ArrayList<>();
        for (List<String> candle : candles1m) {
            minuteCandles.add(new Candle(
                    Long.parseLong(candle.get(0)),
                    Double.parseDouble(candle.get(1)),
                    Double.parseDouble(candle.get(2)),
                    Double.parseDouble(candle.get(3)),
                    Double.parseDouble(candle.get(4))));
        }

        List<Signal> signals = buildSignals(candles30m, minuteCandles, hourlyOpen);
        runGridSearch(signals, minuteCandles, rawLog);
        runRequestedCombinations(signals, minuteCandles, rawLog);
        runScheduledBacktest(signals, minuteCandles, rawLog);
        Files.writeString(Path.of("raw_data_performance.log"), rawLog.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writeRulesFile();
    }

    /**
     * Writes the live strategy constraints to backtest_rules.txt. It is generated from the same
     * constants the backtest uses, so it cannot drift from the code, and it is overwritten on every
     * run. generate_performance_html.py reads it for section A of the dashboard.
     */
    private static void writeRulesFile() throws IOException {
        StringBuilder out = new StringBuilder("BACKTEST RULES (IST)\n");
        appendScheduleRules(out, "BUY_TIME_RULES", BUY_TIME_RULES);
        appendScheduleRules(out, "SELL_TIME_RULES", SELL_TIME_RULES);

        out.append("\nBEST_DAYS (side,days...)\n");
        for (Side side : new Side[] {Side.LONG, Side.SHORT}) {
            out.append(side);
            for (DayOfWeek day : DayOfWeek.values()) {
                if (BEST_DAYS.get(side).contains(day)) out.append(',').append(day);
            }
            out.append('\n');
        }

        // Stop-size / weekday exclusions, evaluated through isExcludedStopDay itself.
        // "*" means the day is excluded for every stop size used on that side.
        out.append("\nEXCLUDED_STOP_DAYS (side,stop_points|*,day)\n");
        for (Side side : new Side[] {Side.LONG, Side.SHORT}) {
            Map<Integer, ScheduleRule> schedule = side == Side.LONG ? BUY_TIME_RULES : SELL_TIME_RULES;
            Set<Integer> stops = new TreeSet<>();
            schedule.values().stream().filter(r -> !r.avoid).forEach(r -> stops.add(r.stopPoints));
            for (DayOfWeek day : DayOfWeek.values()) {
                List<Integer> excluded = new ArrayList<>();
                for (int stop : stops) {
                    if (isExcludedStopDay(side, new ScheduleRule(false, stop, 0), day)) excluded.add(stop);
                }
                if (excluded.isEmpty()) continue;
                if (excluded.size() == stops.size()) {
                    out.append(side).append(",*,").append(day).append('\n');
                } else {
                    for (int stop : excluded) out.append(side).append(',').append(stop).append(',').append(day).append('\n');
                }
            }
        }

        out.append("\nEXCLUDED_DAYS_OF_MONTH\n")
           .append(String.join(",", new TreeSet<>(EXCLUDED_DAYS_OF_MONTH).stream().map(String::valueOf).toList()))
           .append('\n');

        out.append("\nBUY_COMBINATIONS (sl,tp)\n");
        for (StopTarget c : BUY_COMBINATIONS) out.append(c.stopPoints).append(',').append(c.targetPoints).append('\n');
        out.append("\nSELL_COMBINATIONS (sl,tp)\n");
        for (StopTarget c : SELL_COMBINATIONS) out.append(c.stopPoints).append(',').append(c.targetPoints).append('\n');

        out.append("\nSCHEDULED_COMBINATIONS (side,sl,tp)\n");
        appendScheduledCombinations(out, "LONG", BUY_TIME_RULES);
        appendScheduledCombinations(out, "SHORT", SELL_TIME_RULES);

        out.append("\nROUND_NUMBER_FILTER\nskip when a multiple of 500 lies between entry and SL (inclusive)\n");
        out.append("\nRISK_MULTIPLIER_BY_ENTRY_TIME (IST)\n");
        for (int slot : new TreeSet<>(REDUCED_RISK_TIME_SLOTS)) {
            out.append(halfHourLabel(slot)).append(',')
               .append(String.format(Locale.ROOT, "%.1f", REDUCED_RISK_MULTIPLIER)).append('\n');
        }
        out.append("\nCOSTS\nspread_points=").append(formatPrice(spreadPoints))
           .append(",commission_percent_of_risk=")
           .append(String.format(Locale.ROOT, "%.4f", commissionPercentOfRisk))
           .append(",broker_fills=").append(brokerFills ? 1 : 0)
           .append(",ref_spread_points=").append(formatPrice(refSpreadPoints))
           .append(",sl_tp_scale=").append(String.format(Locale.ROOT, "%.4f", slTpScale))
           .append(",sl_tp_add=").append(slTpAdjust.equals("add") ? 1 : 0).append('\n');
        out.append("\nSL_TP_ADJUST\n").append(slTpAdjust).append('\n');
        out.append("\nEFFECTIVE_COMBINATIONS (nominal_sl,nominal_tp,effective_sl,effective_tp)\n");
        java.util.Set<String> seenCombos = new java.util.LinkedHashSet<>();
        for (Map<Integer, ScheduleRule> sched : List.of(BUY_TIME_RULES, SELL_TIME_RULES)) {
            for (int slot = 0; slot < 48; slot++) {
                ScheduleRule r = sched.get(slot);
                if (!r.avoid) seenCombos.add(r.stopPoints + "," + r.targetPoints);
            }
        }
        for (StopTarget c : BUY_COMBINATIONS) seenCombos.add(c.stopPoints + "," + c.targetPoints);
        for (StopTarget c : SELL_COMBINATIONS) seenCombos.add(c.stopPoints + "," + c.targetPoints);
        for (String key : seenCombos) {
            String[] p = key.split(",");
            int sl = Integer.parseInt(p[0]);
            int tp = Integer.parseInt(p[1]);
            out.append(sl).append(',').append(tp).append(',')
               .append(String.format(Locale.ROOT, "%.4f,%.4f", effStop(sl), effTarget(sl, tp))).append('\n');
        }
        out.append("\nFILL_MODEL\n").append(brokerFills
            ? "broker (candles = bid chart): long executes at ask = 1h level + spread, short at bid = 1h level; entry_price/stop/target/exit in the trade log are execution prices (short SL/TP are ask-side, triggered when the bid chart is spread lower); SL/TP measured from execution price; loss=-SL, win=+TP"
            : "legacy: fill at signal level, spread deducted from every trade").append('\n');

        Files.writeString(Path.of("backtest_rules.txt"), out.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Emits the 48-slot schedule as merged "start,end,AVOID" or "start,end,sl,tp" ranges. */
    private static void appendScheduleRules(StringBuilder out, String name, Map<Integer, ScheduleRule> schedule) {
        out.append('\n').append(name).append(" (start,end,AVOID|sl,tp)\n");
        int start = 0;
        for (int slot = 1; slot <= 48; slot++) {
            if (slot == 48 || !schedule.get(slot).equals(schedule.get(start))) {
                ScheduleRule rule = schedule.get(start);
                out.append(slotLabel(start)).append(',').append(slotLabel(slot)).append(',')
                   .append(rule.avoid ? "AVOID" : rule.stopPoints + "," + rule.targetPoints).append('\n');
                start = slot;
            }
        }
    }

    private static void appendScheduledCombinations(StringBuilder out, String side,
            Map<Integer, ScheduleRule> schedule) {
        Set<String> combinations = new TreeSet<>(Comparator.comparingInt((String value) ->
                Integer.parseInt(value.substring(0, value.indexOf(','))))
                .thenComparingInt(value -> Integer.parseInt(value.substring(value.indexOf(',') + 1))));
        schedule.values().stream()
                .filter(rule -> !rule.avoid)
                .forEach(rule -> combinations.add(rule.stopPoints + "," + rule.targetPoints));
        for (String combination : combinations) {
            out.append(side).append(',').append(combination).append('\n');
        }
    }

    private static String slotLabel(int slot) {
        return slot == 48 ? "24:00" : String.format(Locale.ROOT, "%02d:%02d", slot / 2, (slot % 2) * 30);
    }

    private static void parseCostInputs(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--spread=")) {
                spreadPoints = Double.parseDouble(arg.substring("--spread=".length()));
            } else if (arg.startsWith("--commission=")) {
                commissionPercentOfRisk = Double.parseDouble(arg.substring("--commission=".length()));
            } else if (arg.startsWith("--ref-spread=")) {
                refSpreadPoints = Double.parseDouble(arg.substring("--ref-spread=".length()));
            } else if (arg.startsWith("--sl-tp-adjust=")) {
                slTpAdjust = arg.substring("--sl-tp-adjust=".length());
                if (!slTpAdjust.equals("add") && !slTpAdjust.equals("scale") && !slTpAdjust.equals("none")) {
                    throw new IllegalArgumentException("--sl-tp-adjust must be add, scale or none");
                }
            } else if (arg.startsWith("--spread-model=")) {
                String model = arg.substring("--spread-model=".length());
                if (!model.equals("broker") && !model.equals("legacy")) {
                    throw new IllegalArgumentException("--spread-model must be broker or legacy");
                }
                brokerFills = model.equals("broker");
            }
        }
        if (spreadPoints < 0 || commissionPercentOfRisk < 0) {
            throw new IllegalArgumentException("Spread and commission must be >= 0");
        }
        // --ref-spread=0 switches the scaling off.
        slTpScale = refSpreadPoints > 0 ? Math.max(1.0, spreadPoints / refSpreadPoints) : 1.0;
        if (brokerFills && !slTpAdjust.equals("add") && spreadPoints >= MIN_STOP_POINTS * slTpScale) {
            throw new IllegalArgumentException("Spread must be smaller than the smallest stop (" + MIN_STOP_POINTS + ")");
        }
    }

    /** Stop / target distance actually traded after spread scaling (nominal values are what the rules list). */
    private static double effStop(int nominalStop) {
        switch (slTpAdjust) {
            case "add":   return nominalStop + spreadPoints;
            case "scale": return Math.round(nominalStop * slTpScale);
            default:      return nominalStop;
        }
    }

    /** Target distance actually traded; in "add" mode it keeps the nominal TP/SL ratio exactly. */
    private static double effTarget(int nominalStop, int nominalTarget) {
        switch (slTpAdjust) {
            case "add":   return effStop(nominalStop) * nominalTarget / nominalStop;
            case "scale": return Math.round(nominalTarget * slTpScale);
            default:      return nominalTarget;
        }
    }

    /*
     * Broker price model (candles are the BID chart):
     *   LONG  buys at the ASK  -> fill = 1h level + spread;  SL / TP are sell orders at the bid,
     *         so the execution prices ARE the bid-chart levels.
     *   SHORT sells at the BID -> fill = 1h level;           SL / TP are buy orders at the ASK
     *         (= bid + spread), so the bid chart must reach the execution price minus the spread.
     * The log prints execution prices; findExit() converts them to bid-chart triggers.
     */

    /** Price the trade is executed at: ask (1h level + spread) for longs, bid (1h level) for shorts. */
    private static double fillPrice(Signal signal) {
        return signal.entryPrice + (brokerFills && signal.side == Side.LONG ? spreadPoints : 0.0);
    }

    /** Execution price of the stop: fill - SL for longs, fill + SL for shorts. */
    private static double stopLevel(Signal signal, double stopPoints) {
        double base = fillPrice(signal);
        return signal.side == Side.LONG ? base - stopPoints : base + stopPoints;
    }

    /** Execution price of the target: fill + TP for longs, fill - TP for shorts. */
    private static double targetLevel(Signal signal, double targetPoints) {
        double base = fillPrice(signal);
        return signal.side == Side.LONG ? base + targetPoints : base - targetPoints;
    }

    /** Bid-chart level that fires an exit whose execution price is {@code executionPrice}. */
    private static double bidChartTrigger(Signal signal, double executionPrice) {
        return brokerFills && signal.side == Side.SHORT ? executionPrice - spreadPoints : executionPrice;
    }

    private static double transactionCostPoints(double stopPoints) {
        // Broker mode: the spread is already in the fill / trigger geometry, only commission is deducted.
        return (brokerFills ? 0.0 : spreadPoints) + stopPoints * commissionPercentOfRisk / 100.0;
    }

    private static void printEntryCandles(List<Signal> signals, List<Candle> candles) {
        System.out.println("\nCandidate entry candles (IST):");
        System.out.println("entry_time_ist,side,30m_prev_high_minus_360,1h_open_minus_90,30m_prev_low_plus_360,1h_open_plus_110");
        for (Signal signal : signals) {
            Candle candle = candles.get(signal.entryIndex);
            String longTouchLevel = signal.side == Side.LONG
                ? formatPrice(signal.thirtyMinuteLevel) : "";
            String longEntryLevel = signal.side == Side.LONG
                ? formatPrice(signal.entryPrice) : "";
            String shortTouchLevel = signal.side == Side.SHORT
                ? formatPrice(signal.thirtyMinuteLevel) : "";
            String shortEntryLevel = signal.side == Side.SHORT
                ? formatPrice(signal.entryPrice) : "";
            System.out.printf(Locale.ROOT, "%s,%s,%s,%s,%s,%s%n",
                formatIst(candle.timestamp), signal.side, longTouchLevel,
                longEntryLevel, shortTouchLevel, shortEntryLevel);
        }
    }

    private static String formatIst(long timestamp) {
        return IST_FORMAT.format(Instant.ofEpochMilli(timestamp));
    }

    private static String formatPrice(double price) {
        return String.format(Locale.ROOT, "%.2f", price);
    }

    private static List<Signal> buildSignals(List<List<String>> candles30m,
                                            List<Candle> minuteCandles,
                                            Map<Long, Double> hourlyOpen) {
        List<Signal> signals = new ArrayList<>();
        for (int setupIndex = 1; setupIndex < candles30m.size(); setupIndex++) {
            List<String> previousThirtyMinute = candles30m.get(setupIndex - 1);
            long setupStart = Long.parseLong(candles30m.get(setupIndex).get(0));
            long setupEnd = setupStart + THIRTY_MINUTES_MS;
            long hourStart = Math.floorDiv(setupStart, ONE_HOUR_MS) * ONE_HOUR_MS;
            Double oneHourOpen = hourlyOpen.get(hourStart);
            if (oneHourOpen == null) {
                continue;
            }

            double oneHourHighLevel = oneHourOpen + 110;
            double oneHourLowLevel = oneHourOpen - 90;
            double previousThirtyMinuteHigh = Double.parseDouble(previousThirtyMinute.get(2));
            double previousThirtyMinuteLow = Double.parseDouble(previousThirtyMinute.get(3));
            double longSupport = previousThirtyMinuteHigh - 360;
            double shortResistance = previousThirtyMinuteLow + 360;

            if (longSupport < oneHourLowLevel) {
                SignalHit hit = findEntryIndex(minuteCandles, setupStart, setupEnd,
                        longSupport, oneHourLowLevel, Side.LONG);
                if (hit != null) {
                    signals.add(new Signal(setupStart, hit.touchIndex, hit.entryIndex, Side.LONG,
                        longSupport, oneHourLowLevel));
                }
            }

            if (shortResistance > oneHourHighLevel) {
                SignalHit hit = findEntryIndex(minuteCandles, setupStart, setupEnd,
                        shortResistance, oneHourHighLevel, Side.SHORT);
                if (hit != null) {
                    signals.add(new Signal(setupStart, hit.touchIndex, hit.entryIndex, Side.SHORT,
                        shortResistance, oneHourHighLevel));
                }
            }
        }

        signals.sort(Comparator.comparingInt(Signal::entryIndex)
                .thenComparing(signal -> signal.side));
        return signals;
    }

    private static SignalHit findEntryIndex(List<Candle> candles, long setupStart, long setupEnd,
                                      double touchLevel, double entryLevel, Side side) {
        int low = 0;
        int high = candles.size();
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (candles.get(middle).timestamp < setupStart) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }

        int touchIndex = -1;
        for (int i = low; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            if (candle.timestamp >= setupEnd) {
                break;
            }

            if (touchIndex < 0) {
                boolean touched = side == Side.LONG
                        ? candle.low <= touchLevel
                        : candle.high >= touchLevel;
                if (touched) {
                    touchIndex = i;
                }
            } else if (i > touchIndex) {
                boolean entered = side == Side.LONG
                        ? candle.high >= entryLevel
                        : candle.low <= entryLevel;
                if (entered) {
                    return new SignalHit(touchIndex, i);
                }
            }
        }
        return null;
    }

    private static void runGridSearch(List<Signal> signals, List<Candle> candles, StringBuilder rawLog) {
        List<Evaluation> longResults = new ArrayList<>();
        List<Evaluation> shortResults = new ArrayList<>();
        List<Evaluation> combinedResults = new ArrayList<>();

        for (int stopPoints = MIN_STOP_POINTS;
             stopPoints < MAX_STOP_POINTS_EXCLUSIVE;
             stopPoints += STOP_STEP_POINTS) {
            for (int targetPoints = MIN_TARGET_POINTS;
                 targetPoints <= MAX_TARGET_POINTS;
                 targetPoints += TARGET_STEP_POINTS) {
                longResults.add(evaluate(signals, candles, Side.LONG, stopPoints, targetPoints));
                shortResults.add(evaluate(signals, candles, Side.SHORT, stopPoints, targetPoints));
                combinedResults.add(evaluate(signals, candles, Side.BOTH, stopPoints, targetPoints));
            }
        }

        Comparator<Evaluation> profitabilityOrder = Comparator
                .comparingDouble(Evaluation::netPnlPoints).reversed()
                .thenComparing(Comparator.comparingDouble(Evaluation::netR).reversed())
                .thenComparing(Comparator.comparingDouble(Evaluation::winRate).reversed());
        longResults.sort(profitabilityOrder);
        shortResults.sort(profitabilityOrder);
        combinedResults.sort(profitabilityOrder);

        rawLog.append("\nGRID SEARCH\n")
              .append("signals,long=").append(countSignals(signals, Side.LONG))
              .append(",short=").append(countSignals(signals, Side.SHORT))
              .append(",total=").append(signals.size()).append("\n");
        appendResults(rawLog, "LONG", longResults);
        appendResults(rawLog, "SHORT", shortResults);
        appendResults(rawLog, "COMBINED", combinedResults);
    }

    private static ScheduleRule tradeRule(int stopPoints, int targetPoints) {
        return new ScheduleRule(false, stopPoints, targetPoints);
    }

    private static ScheduleEntry timeRule(String start, String end, ScheduleRule rule) {
        return new ScheduleEntry(toHalfHourSlot(start), toHalfHourSlot(end), rule);
    }

    private static Map<Integer, ScheduleRule> buildSchedule(ScheduleEntry... entries) {
        Map<Integer, ScheduleRule> schedule = new HashMap<>();
        for (ScheduleEntry entry : entries) {
            for (int slot = entry.startSlot; slot < entry.endSlot; slot++) {
                if (schedule.put(slot, entry.rule) != null) {
                    throw new IllegalArgumentException("Overlapping schedule at half-hour slot " + slot);
                }
            }
        }
        if (schedule.size() != 48) {
            throw new IllegalArgumentException("Schedule must define all 48 half-hour slots");
        }
        return Map.copyOf(schedule);
    }

    private static int toHalfHourSlot(String time) {
        if (time.equals("24:00")) {
            return 48;
        }
        String[] parts = time.split(":");
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        if (hour < 0 || hour > 23 || (minute != 0 && minute != 30)) {
            throw new IllegalArgumentException("Invalid half-hour boundary: " + time);
        }
        return hour * 2 + minute / 30;
    }

    private static boolean isExcludedStopDay(Side side, ScheduleRule rule, DayOfWeek day) {
        if (side == Side.LONG) {
            return (rule.stopPoints == 70 && day == DayOfWeek.SATURDAY)
                    || (rule.stopPoints == 270 && day == DayOfWeek.WEDNESDAY);
        }
        return day == DayOfWeek.SATURDAY;
    }

        private static void runScheduledBacktest(List<Signal> signals, List<Candle> candles, StringBuilder rawLog)
            throws IOException {
        List<ScheduledSignal> scheduledSignals = new ArrayList<>();
        int avoidedByHour = 0;
        int unlistedTimes = 0;
        int avoidedByDay = 0;

        for (Signal signal : signals) {
            ZonedDateTime entryTime = Instant.ofEpochMilli(
                    candles.get(signal.entryIndex).timestamp).atZone(ZoneId.of("Asia/Kolkata"));
            Map<Integer, ScheduleRule> timeRules = signal.side == Side.LONG
                    ? BUY_TIME_RULES : SELL_TIME_RULES;
            ScheduleRule rule = timeRules.get(halfHourBucket(entryTime));
            if (rule == null) {
                unlistedTimes++;
            } else if (rule.avoid) {
                avoidedByHour++;
            } else if (!BEST_DAYS.get(signal.side).contains(entryTime.getDayOfWeek())
                    || isExcludedStopDay(signal.side, rule, entryTime.getDayOfWeek())
                    || EXCLUDED_DAYS_OF_MONTH.contains(entryTime.getDayOfMonth())) {
                avoidedByDay++;
            } else {
                scheduledSignals.add(new ScheduledSignal(signal,
                        new EffectiveRule(effStop(rule.stopPoints),
                                effTarget(rule.stopPoints, rule.targetPoints)),
                        REDUCED_RISK_TIME_SLOTS.contains(halfHourBucket(entryTime))
                                ? REDUCED_RISK_MULTIPLIER : 1.0));
            }
        }

        Map<StrengthKey, Double> highlightedStrength = buildHighlightedStrength(scheduledSignals, candles);
        int entries = 0;
        int wins = 0;
        int losses = 0;
        int openTrades = 0;
        int skippedWhileOpen = 0;
        int rejectedByNegativeStrength = 0;
        int nextAvailableIndex = -1;
        double netPnlPoints = 0;
        double netR = 0;
        Map<DayOfWeek, BucketStats> weekdayStats = new EnumMap<>(DayOfWeek.class);
        Map<Integer, BucketStats> halfHourStats = new TreeMap<>();
        Map<Integer, BucketStats> dayOfMonthStats = new TreeMap<>();
        List<TakenTrade> takenTrades = new ArrayList<>();

        for (ScheduledSignal scheduled : scheduledSignals) {
            Signal signal = scheduled.signal;
            if (signal.entryIndex <= nextAvailableIndex) {
                skippedWhileOpen++;
                continue;
            }

            if (isRoundNumberFiltered(signal, scheduled.rule.stopPoints)) {
                continue;
            }

            StrengthKey strengthKey = strengthKey(signal, candles, scheduled.rule);
            double currentStrength = highlightedStrength.getOrDefault(strengthKey, 0.0);
            if (highlightedStrength.containsKey(strengthKey) && currentStrength < 0.0) {
                rejectedByNegativeStrength++;
                continue;
            }

            entries++;
            Exit exit = findExit(candles, signal,
                    scheduled.rule.stopPoints, scheduled.rule.targetPoints);
            nextAvailableIndex = exit == null ? candles.size() : exit.candleIndex;
            takenTrades.add(new TakenTrade(signal, scheduled.rule, scheduled.riskMultiplier, exit));

            if (exit == null) {
                openTrades++;
            } else {
                if (exit.win) {
                    wins++;
                } else {
                    losses++;
                }
                netPnlPoints += exit.pnlPoints * scheduled.riskMultiplier;
                netR += exit.pnlPoints / scheduled.rule.stopPoints * scheduled.riskMultiplier;
            }

            ZonedDateTime entryTime = Instant.ofEpochMilli(
                    candles.get(signal.entryIndex).timestamp).atZone(ZoneId.of("Asia/Kolkata"));
            weekdayStats.computeIfAbsent(entryTime.getDayOfWeek(), ignored -> new BucketStats())
                    .add(exit, scheduled.rule.stopPoints, scheduled.riskMultiplier);
            halfHourStats.computeIfAbsent(halfHourBucket(entryTime), ignored -> new BucketStats())
                    .add(exit, scheduled.rule.stopPoints, scheduled.riskMultiplier);
            dayOfMonthStats.computeIfAbsent(entryTime.getDayOfMonth(), ignored -> new BucketStats())
                    .add(exit, scheduled.rule.stopPoints, scheduled.riskMultiplier);
        }

        int closedTrades = wins + losses;
        double winRate = closedTrades == 0 ? 0 : wins * 100.0 / closedTrades;
        double expectancy = entries == 0 ? 0 : netPnlPoints / entries;
        List<Double> scheduleR = takenTrades.stream()
                .filter(t -> t.exit != null)
                .map(t -> t.exit.pnlPoints / t.rule.stopPoints * t.riskMultiplier)
                .toList();
        double alpha = calculateAlpha(scheduleR, 0.0);
        double sharpe = calculateSharpeRatio(scheduleR, 0.0);
        String summary = String.format(Locale.ROOT,
            "signals=%d,eligible_by_schedule=%d,entries=%d,wins=%d,losses=%d,open=%d,skipped_while_open=%d,rejected_by_negative_strength=%d,avoided_by_time=%d,avoided_by_day=%d,unlisted_times=%d,win_rate_pct=%.2f,net_points=%.2f,net_R=%.2f,expectancy_points=%.2f,alpha_R_per_trade=%.4f,sharpe=%.4f,spread_points=%.2f,commission_pct_of_risk=%.4f",
            signals.size(), scheduledSignals.size(), entries, wins, losses, openTrades,
            skippedWhileOpen, rejectedByNegativeStrength, avoidedByHour, avoidedByDay, unlistedTimes,
            winRate, netPnlPoints, netR, expectancy, alpha, sharpe, spreadPoints, commissionPercentOfRisk);
        appendScheduleLog(summary, takenTrades, weekdayStats, halfHourStats, dayOfMonthStats,
            signals, candles, rawLog);
        System.out.println("Backtest complete: schedule summary written to backtest_schedule.log; raw performance written to raw_data_performance.log");
    }

        private static void appendScheduleLog(String summary, List<TakenTrade> takenTrades,
                         Map<DayOfWeek, BucketStats> weekdayStats,
                         Map<Integer, BucketStats> halfHourStats,
                         Map<Integer, BucketStats> dayOfMonthStats,
                         List<Signal> signals, List<Candle> candles, StringBuilder rawLog)
            throws IOException {
        StringBuilder logEntry = new StringBuilder()
            .append("TRADES TAKEN (IST):\n")
            .append("trade,setup_start_ist,side,entry_time_ist,touch_time_ist,risk_multiplier,entry_price,touch_level,sl_points,tp_points,stop_price,target_price,exit_time_ist,exit_price,result,pnl_points,net_R\n");

        for (int i = 0; i < takenTrades.size(); i++) {
            TakenTrade trade = takenTrades.get(i);
            Signal signal = trade.signal;
            EffectiveRule rule = trade.rule;
            Candle entryCandle = candles.get(signal.entryIndex);
            double stopPrice = stopLevel(signal, rule.stopPoints);
            double targetPrice = targetLevel(signal, rule.targetPoints);
            String exitTime = trade.exit == null ? ""
                : formatIst(candles.get(trade.exit.candleIndex).timestamp);
            String exitPrice = trade.exit == null ? ""
                : formatPrice(trade.exit.win ? targetPrice : stopPrice);
            String result = trade.exit == null ? "OPEN" : trade.exit.win ? "WIN" : "LOSS";
            String pnl = trade.exit == null ? "" : formatPrice(trade.exit.pnlPoints);
            String netR = trade.exit == null ? ""
                : String.format(Locale.ROOT, "%.6f", trade.exit.pnlPoints / rule.stopPoints * trade.riskMultiplier);
            logEntry.append(String.format(Locale.ROOT,
                "%d,%s,%s,%s,%s,%.1f,%.2f,%.2f,%.3f,%.3f,%.2f,%.2f,%s,%s,%s,%s,%s%n",
                i + 1, formatIst(signal.setupStart), signal.side,
                formatIst(entryCandle.timestamp), formatIst(candles.get(signal.touchIndex).timestamp),
                trade.riskMultiplier, fillPrice(signal),
                signal.thirtyMinuteLevel, rule.stopPoints, rule.targetPoints,
                stopPrice, targetPrice, exitTime, exitPrice, result, pnl, netR));
        }

        logEntry.append("\nSCHEDULE-FILTERED STRATEGY (IST):\n")
            .append(summary).append('\n')
            .append("dimension,bucket,entries,wins,losses,open,win_rate_pct,net_points,net_R,expectancy_points,alpha_R_per_trade,sharpe\n");

        for (DayOfWeek day : DayOfWeek.values()) {
            BucketStats stats = weekdayStats.get(day);
            if (stats != null) {
            appendCalendarLogRow(logEntry, "weekday", day.toString(), stats);
            }
        }
        for (Map.Entry<Integer, BucketStats> entry : halfHourStats.entrySet()) {
            appendCalendarLogRow(logEntry, "half_hour_ist",
                halfHourLabel(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<Integer, BucketStats> entry : dayOfMonthStats.entrySet()) {
            appendCalendarLogRow(logEntry, "day_of_month",
                Integer.toString(entry.getKey()), entry.getValue());
        }
        logEntry.append('\n');

        appendRawTrades(rawLog, takenTrades, signals, candles);
        appendRawScheduleAnalysis(rawLog, summary, weekdayStats, halfHourStats, dayOfMonthStats);
        Files.writeString(Path.of("backtest_schedule.log"), logEntry,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }

        private static void runRequestedCombinations(List<Signal> signals, List<Candle> candles, StringBuilder rawLog) {
        appendRequestedSide(rawLog, "BUY", Side.LONG, BUY_COMBINATIONS, signals, candles);
        appendRequestedSide(rawLog, "SELL", Side.SHORT, SELL_COMBINATIONS, signals, candles);
    }

    private static void appendRequestedSide(StringBuilder output, String label, Side side,
                                            List<StopTarget> combinations,
                                            List<Signal> signals, List<Candle> candles) {
        List<Evaluation> results = new ArrayList<>();
        for (StopTarget combination : combinations) {
            results.add(evaluate(signals, candles, side,
                    combination.stopPoints, combination.targetPoints));
            appendCalendarAnalysis(output, label, side, combination, signals, candles);
        }

        Comparator<Evaluation> returnOrder = Comparator
                .comparingDouble(Evaluation::netPnlPoints).reversed()
                .thenComparing(Comparator.comparingDouble(Evaluation::expectancyPoints).reversed());
        Comparator<Evaluation> expectancyOrder = Comparator
                .comparingDouble(Evaluation::expectancyPoints).reversed()
                .thenComparing(Comparator.comparingDouble(Evaluation::netPnlPoints).reversed());

        output.append("\n").append(label).append(" REQUESTED SL/TP COMBINATIONS (raw):\n");
        appendCombinationResults(output, results.stream().sorted(returnOrder).toList());
        output.append("\n").append(label).append(" REQUESTED SL/TP COMBINATIONS BY EXPECTANCY:\n");
        appendCombinationResults(output, results.stream().sorted(expectancyOrder).toList());
    }

    private static void appendCalendarAnalysis(StringBuilder output, String label, Side side,
                                              StopTarget combination, List<Signal> signals, List<Candle> candles) {
        Map<DayOfWeek, BucketStats> weekdayStats = new EnumMap<>(DayOfWeek.class);
        Map<Integer, BucketStats> halfHourStats = new TreeMap<>();
        Map<Integer, BucketStats> dayOfMonthStats = new TreeMap<>();
        int nextAvailableIndex = -1;

        for (Signal signal : signals) {
            if (signal.side != side || signal.entryIndex <= nextAvailableIndex) continue;
            double sl = effStop(combination.stopPoints);
            double tp = effTarget(combination.stopPoints, combination.targetPoints);
            if (isRoundNumberFiltered(signal, sl)) continue;

            Exit exit = findExit(candles, signal, sl, tp);
            nextAvailableIndex = exit == null ? candles.size() : exit.candleIndex;
            ZonedDateTime entryTime = Instant.ofEpochMilli(candles.get(signal.entryIndex).timestamp)
                    .atZone(ZoneId.of("Asia/Kolkata"));
            weekdayStats.computeIfAbsent(entryTime.getDayOfWeek(), ignored -> new BucketStats())
                    .add(exit, sl);
            halfHourStats.computeIfAbsent(halfHourBucket(entryTime), ignored -> new BucketStats())
                    .add(exit, sl);
            dayOfMonthStats.computeIfAbsent(entryTime.getDayOfMonth(), ignored -> new BucketStats())
                    .add(exit, sl);
        }

        output.append("\n").append(label).append(" SL/TP ")
              .append(combination.stopPoints).append("/").append(combination.targetPoints)
              .append(" WEEKDAY ANALYSIS (IST):\n");
        appendCalendarRows(output, "weekday", weekdayStats);
        output.append(label).append(" SL/TP ").append(combination.stopPoints).append("/")
              .append(combination.targetPoints).append(" HALF-HOUR ANALYSIS (IST):\n");
        for (Map.Entry<Integer, BucketStats> entry : halfHourStats.entrySet()) {
            appendCalendarLogRow(output, "half_hour_ist", halfHourLabel(entry.getKey()), entry.getValue());
        }
        output.append(label).append(" SL/TP ").append(combination.stopPoints).append("/")
              .append(combination.targetPoints).append(" DAY-OF-MONTH NET-R ANALYSIS (IST):\n");
        for (int day = 1; day <= 31; day++) {
            BucketStats stats = dayOfMonthStats.get(day);
            if (stats != null) appendCalendarLogRow(output, "day_of_month", Integer.toString(day), stats);
        }
    }

    private static void appendCalendarRows(StringBuilder output, String dimension, Map<DayOfWeek, BucketStats> stats) {
        output.append("dimension,bucket,entries,wins,losses,open,win_rate_pct,net_points,net_R,expectancy_points,alpha_R_per_trade,sharpe\n");
        for (DayOfWeek day : DayOfWeek.values()) {
            BucketStats bucket = stats.get(day);
            if (bucket != null) appendCalendarLogRow(output, dimension, day.toString(), bucket);
        }
    }

    private static void appendCombinationResults(StringBuilder output, List<Evaluation> results) {
        output.append("sl_points,tp_points,rr,entries,wins,losses,open,skipped,win_rate_pct,net_points,net_r,expectancy_points,alpha_R_per_trade,sharpe\n");
        for (Evaluation result : results) {
            output.append(String.format(Locale.ROOT,
                    "%d,%d,%.3f,%d,%d,%d,%d,%d,%.2f,%.2f,%.4f,%.2f,%.4f,%.4f%n",
                    result.stopPoints, result.targetPoints,
                    (double) result.targetPoints / result.stopPoints, result.entries, result.wins,
                    result.losses, result.openTrades, result.skipped, result.winRate(),
                    result.netPnlPoints, result.netR, result.expectancyPoints(),
                    result.alpha, result.sharpe));
        }
    }

        private static int halfHourBucket(ZonedDateTime time) {
        return time.getHour() * 2 + time.getMinute() / 30;
        }

        private static StrengthKey strengthKey(Signal signal, List<Candle> candles,
                                               EffectiveRule rule) {
            ZonedDateTime touchTime = Instant.ofEpochMilli(
                    candles.get(signal.touchIndex).timestamp).atZone(ZoneId.of("Asia/Kolkata"));
            return new StrengthKey(signal.side, touchTime.getMinute(),
                    rule.stopPoints, rule.targetPoints);
        }

        /**
         * Builds the historical pre-entry strength lookup from the unfiltered
         * scheduled population. WIN/LOSS magnitudes are first compared with the
         * same 2x rule used by the analyzer; the retained bucket stores WIN+LOSS.
         */
        private static Map<StrengthKey, Double> buildHighlightedStrength(
                List<ScheduledSignal> scheduledSignals, List<Candle> candles) {
            Map<StrengthOutcomeKey, Double> outcomeSums = new HashMap<>();
            int nextAvailableIndex = -1;
            for (ScheduledSignal scheduled : scheduledSignals) {
                Signal signal = scheduled.signal;
                if (signal.entryIndex <= nextAvailableIndex
                        || isRoundNumberFiltered(signal, scheduled.rule.stopPoints)) {
                    continue;
                }
                Exit exit = findExit(candles, signal,
                        scheduled.rule.stopPoints, scheduled.rule.targetPoints);
                nextAvailableIndex = exit == null ? candles.size() : exit.candleIndex;
                if (exit == null) {
                    continue;
                }
                StrengthKey bucket = strengthKey(signal, candles, scheduled.rule);
                Result outcome = exit.win ? Result.WIN : Result.LOSS;
                double netR = exit.pnlPoints / scheduled.rule.stopPoints
                        * scheduled.riskMultiplier;
                outcomeSums.merge(new StrengthOutcomeKey(bucket, outcome), netR, Double::sum);
            }

            Map<StrengthKey, Double> highlighted = new HashMap<>();
            Map<StrengthKey, Double> allBuckets = new HashMap<>();
            Set<StrengthKey> bucketKeys = new HashSet<>();
            for (StrengthOutcomeKey key : outcomeSums.keySet()) {
                bucketKeys.add(key.bucket);
            }
            for (StrengthKey bucket : bucketKeys) {
                double win = outcomeSums.getOrDefault(
                        new StrengthOutcomeKey(bucket, Result.WIN), 0.0);
                double loss = outcomeSums.getOrDefault(
                        new StrengthOutcomeKey(bucket, Result.LOSS), 0.0);
                if (Math.abs(win) > 2.0 * Math.abs(loss)
                        || Math.abs(loss) > 2.0 * Math.abs(win)) {
                    allBuckets.put(bucket, win + loss);
                }
            }
            highlighted.putAll(allBuckets);
            return highlighted;
        }

        private static String halfHourLabel(int bucket) {
        int startMinute = bucket * 30;
        int endMinute = (startMinute + 30) % (24 * 60);
        return String.format(Locale.ROOT, "%02d:%02d-%02d:%02d",
            startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60);
        }

        private static void appendCalendarLogRow(StringBuilder output, String dimension,
                             String bucket, BucketStats stats) {
        output.append(String.format(Locale.ROOT,
            "%s,%s,%d,%d,%d,%d,%.2f,%.2f,%.4f,%.2f,%.4f,%.4f%n",
            dimension, bucket, stats.entries, stats.wins, stats.losses, stats.open,
            stats.winRate(), stats.netPnlPoints, stats.netR, stats.expectancyPoints(),
            stats.alpha(), stats.sharpe()));
        }

    private static Evaluation evaluate(List<Signal> signals, List<Candle> candles,
                                       Side mode, int nominalStop, int nominalTarget) {
        int entries = 0;
        int wins = 0;
        int losses = 0;
        int openTrades = 0;
        int skipped = 0;
        int nextAvailableIndex = -1;
        double netPnlPoints = 0;
        double netR = 0;
        List<Double> tradeR = new ArrayList<>();
        double stopPoints = effStop(nominalStop);
        double targetPoints = effTarget(nominalStop, nominalTarget);

        for (Signal signal : signals) {
            if (mode != Side.BOTH && signal.side != mode) {
                continue;
            }
            if (signal.entryIndex <= nextAvailableIndex) {
                skipped++;
                continue;
            }

            // Skip trade if a multiple of 500 lies between entry and SL.
            if (isRoundNumberFiltered(signal, stopPoints)) {
                skipped++;
                continue;
            }

            entries++;
            Exit exit = findExit(candles, signal, stopPoints, targetPoints);
            if (exit == null) {
                openTrades++;
                nextAvailableIndex = candles.size();
                continue;
            }

            nextAvailableIndex = exit.candleIndex;
            if (exit.win) {
                wins++;
            } else {
                losses++;
            }
            netPnlPoints += exit.pnlPoints;
            double r = exit.pnlPoints / stopPoints;
            netR += r;
            tradeR.add(r);
        }

        return new Evaluation(mode, nominalStop, nominalTarget, entries, wins, losses,
                openTrades, skipped, netPnlPoints, netR,
                calculateAlpha(tradeR, 0.0), calculateSharpeRatio(tradeR, 0.0));
    }

    private static boolean hasRoundNumberBetween(double entryPrice, double exitPrice) {
        double lower = Math.min(entryPrice, exitPrice);
        double upper = Math.max(entryPrice, exitPrice);

        // Small epsilon prevents floating-point precision issues.
        long firstMultiple = (long) Math.ceil((lower - 1e-9) / 500.0);
        long lastMultiple = (long) Math.floor((upper + 1e-9) / 500.0);

        return firstMultiple <= lastMultiple;
    }

    private static boolean isRoundNumberFiltered(Signal signal, double stopPoints) {
        double stopPrice = stopLevel(signal, stopPoints);
        return hasRoundNumberBetween(fillPrice(signal), stopPrice);
    }

    private static Exit findExit(List<Candle> candles, Signal signal,
                                 double stopPoints, double targetPoints) {
        double stopPrice = bidChartTrigger(signal, stopLevel(signal, stopPoints));
        double targetPrice = bidChartTrigger(signal, targetLevel(signal, targetPoints));

        for (int i = signal.entryIndex + 1; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            boolean stopHit = signal.side == Side.LONG
                    ? candle.low <= stopPrice
                    : candle.high >= stopPrice;
            boolean targetHit = signal.side == Side.LONG
                    ? candle.high >= targetPrice
                    : candle.low <= targetPrice;
            if (stopHit) {
                return new Exit(i, false, -stopPoints - transactionCostPoints(stopPoints));
            }
            if (targetHit) {
                return new Exit(i, true, targetPoints - transactionCostPoints(stopPoints));
            }
        }
        return null;
    }

    private static void appendResults(StringBuilder output, String label, List<Evaluation> results) {
        output.append("\n").append(label).append(" (ranked by net points):\n");
        output.append("rank,sl_points,target_points,rr,entries,wins,losses,open,skipped,win_rate_pct,net_points,net_r,expectancy_points,alpha_R_per_trade,sharpe\n");
        for (int i = 0; i < results.size(); i++) {
            Evaluation result = results.get(i);
            output.append(String.format(Locale.ROOT,
                    "%d,%d,%d,%.3f,%d,%d,%d,%d,%d,%.2f,%.2f,%.4f,%.2f,%.4f,%.4f%n",
                    i + 1, result.stopPoints, result.targetPoints,
                    (double) result.targetPoints / result.stopPoints,
                    result.entries, result.wins, result.losses, result.openTrades,
                    result.skipped, result.winRate(), result.netPnlPoints,
                    result.netR, result.expectancyPoints(), result.alpha, result.sharpe));
        }
    }

    private static void appendRawTrades(StringBuilder output, List<TakenTrade> takenTrades,
                                        List<Signal> signals, List<Candle> candles) {
        output.append("\nTRADES TAKEN (IST):\n")
              .append("trade,setup_start_ist,side,entry_time_ist,touch_time_ist,risk_multiplier,entry_price,touch_level,sl_points,tp_points,stop_price,target_price,exit_time_ist,exit_price,result,pnl_points,net_R\n");
        for (int i = 0; i < takenTrades.size(); i++) {
            TakenTrade trade = takenTrades.get(i);
            Signal signal = trade.signal;
            EffectiveRule rule = trade.rule;
            Candle entryCandle = candles.get(signal.entryIndex);
            double stopPrice = stopLevel(signal, rule.stopPoints);
            double targetPrice = targetLevel(signal, rule.targetPoints);
            String exitTime = trade.exit == null ? "" : formatIst(candles.get(trade.exit.candleIndex).timestamp);
            String exitPrice = trade.exit == null ? "" : formatPrice(trade.exit.win ? targetPrice : stopPrice);
            String result = trade.exit == null ? "OPEN" : trade.exit.win ? "WIN" : "LOSS";
            String pnl = trade.exit == null ? "" : formatPrice(trade.exit.pnlPoints);
            String netR = trade.exit == null ? "" : String.format(Locale.ROOT, "%.6f",
                    trade.exit.pnlPoints / rule.stopPoints * trade.riskMultiplier);
            output.append(String.format(Locale.ROOT,
                "%d,%s,%s,%s,%s,%.1f,%.2f,%.2f,%.3f,%.3f,%.2f,%.2f,%s,%s,%s,%s,%s%n",
                i + 1, formatIst(signal.setupStart), signal.side, formatIst(entryCandle.timestamp),
                formatIst(candles.get(signal.touchIndex).timestamp), trade.riskMultiplier,
                fillPrice(signal), signal.thirtyMinuteLevel, rule.stopPoints, rule.targetPoints,
                stopPrice, targetPrice, exitTime, exitPrice, result, pnl, netR));
        }
    }

    private static void appendRawScheduleAnalysis(StringBuilder output, String summary,
                                                   Map<DayOfWeek, BucketStats> weekdayStats,
                                                   Map<Integer, BucketStats> halfHourStats,
                                                   Map<Integer, BucketStats> dayOfMonthStats) {
        output.append("\nSCHEDULE-FILTERED STRATEGY (IST):\n")
              .append(summary).append('\n')
              .append("dimension,bucket,entries,wins,losses,open,win_rate_pct,net_points,net_R,expectancy_points,alpha_R_per_trade,sharpe\n");
        for (DayOfWeek day : DayOfWeek.values()) {
            BucketStats stats = weekdayStats.get(day);
            if (stats != null) appendCalendarLogRow(output, "weekday", day.toString(), stats);
        }
        for (Map.Entry<Integer, BucketStats> entry : halfHourStats.entrySet()) {
            appendCalendarLogRow(output, "half_hour_ist", halfHourLabel(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<Integer, BucketStats> entry : dayOfMonthStats.entrySet()) {
            appendCalendarLogRow(output, "day_of_month", Integer.toString(entry.getKey()), entry.getValue());
        }
    }

    private static long countSignals(List<Signal> signals, Side side) {
        return signals.stream().filter(signal -> signal.side == side).count();
    }

    private static List<List<String>> dataRows(List<List<String>> rows) {
        List<List<String>> result = new ArrayList<>();
        for (List<String> row : rows) {
            try {
                Long.parseLong(row.get(0));
                result.add(row);
            } catch (NumberFormatException ignored) {
                // CSV column-name rows are not candle data.
            }
        }
        return result;
    }

    private enum Side {
        LONG,
        SHORT,
        BOTH
    }

    private record Candle(long timestamp, double open, double high, double low, double close) {
    }

    private record Signal(long setupStart, int touchIndex, int entryIndex, Side side,
                          double thirtyMinuteLevel, double entryPrice) {
    }

    private record SignalHit(int touchIndex, int entryIndex) {
    }

    private record StopTarget(int stopPoints, int targetPoints) {
    }

    private record ScheduleRule(boolean avoid, int stopPoints, int targetPoints) {
    }

    private record ScheduleEntry(int startSlot, int endSlot, ScheduleRule rule) {
    }

    /** SL/TP distances actually traded (nominal rule after spread adjustment). */
    private record EffectiveRule(double stopPoints, double targetPoints) {
    }

    private record ScheduledSignal(Signal signal, EffectiveRule rule, double riskMultiplier) {
    }

    private record TakenTrade(Signal signal, EffectiveRule rule, double riskMultiplier, Exit exit) {
    }

    private record Exit(int candleIndex, boolean win, double pnlPoints) {
    }

    private record StrengthKey(Side side, int clockMinute,
                               double stopPoints, double targetPoints) {
    }

    private enum Result {
        WIN,
        LOSS
    }

    private record StrengthOutcomeKey(StrengthKey bucket, Result outcome) {
    }

    private record Evaluation(Side side, int stopPoints, int targetPoints,
                              int entries, int wins, int losses, int openTrades,
                              int skipped, double netPnlPoints, double netR,
                              double alpha, double sharpe) {
        private double winRate() {
            int closed = wins + losses;
            return closed == 0 ? 0 : wins * 100.0 / closed;
        }

        private double expectancyPoints() {
            return entries == 0 ? 0 : netPnlPoints / entries;
        }
    }

    private static final class BucketStats {
        private int entries;
        private int wins;
        private int losses;
        private int open;
        private double netPnlPoints;
        private double netR;
        private final List<Double> returnsR = new ArrayList<>();

        private void add(Exit exit, double stopPoints) {
            add(exit, stopPoints, 1.0);
        }

        private void add(Exit exit, double stopPoints, double riskMultiplier) {
            entries++;
            if (exit == null) {
                open++;
            } else if (exit.win) {
                wins++;
                netPnlPoints += exit.pnlPoints * riskMultiplier;
                netR += exit.pnlPoints / stopPoints * riskMultiplier;
                returnsR.add(exit.pnlPoints / stopPoints * riskMultiplier);
            } else {
                losses++;
                netPnlPoints += exit.pnlPoints * riskMultiplier;
                netR += exit.pnlPoints / stopPoints * riskMultiplier;
                returnsR.add(exit.pnlPoints / stopPoints * riskMultiplier);
            }
        }

        private double winRate() {
            int closed = wins + losses;
            return closed == 0 ? 0 : wins * 100.0 / closed;
        }

        private double expectancyPoints() {
            return entries == 0 ? 0 : netPnlPoints / entries;
        }

        private double alpha() {
            return calculateAlpha(returnsR, 0.0);
        }

        private double sharpe() {
            return calculateSharpeRatio(returnsR, 0.0);
        }
    }

    private static double calculateAlpha(List<Double> returns, double benchmarkReturnPerTrade) {
        if (returns.isEmpty()) return 0.0;
        double sum = 0.0;
        for (double r : returns) sum += r - benchmarkReturnPerTrade;
        return sum / returns.size();
    }

    private static double calculateSharpeRatio(List<Double> returns, double benchmarkReturnPerTrade) {
        int n = returns.size();
        if (n < 2) return 0.0;
        double mean = calculateAlpha(returns, benchmarkReturnPerTrade);
        double variance = 0.0;
        for (double r : returns) {
            double excess = (r - benchmarkReturnPerTrade) - mean;
            variance += excess * excess;
        }
        variance /= (n - 1);
        double stdDev = Math.sqrt(variance);
        return stdDev == 0.0 ? 0.0 : mean / stdDev * Math.sqrt(n);
    }
}
