package com.executionbot;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.time.format.DateTimeFormatter;

public final class Main {
    private static long lastReportedMinute = Long.MIN_VALUE;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        LocalState state = new LocalState(Path.of("data", "database.txt"));
        BotConfig config = BotConfig.fromEnvironment();
        config = config.withRiskCap(Integer.parseInt(
                state.read("riskCap", Integer.toString(config.riskCap()))));
        MatchTrader broker = new MatchTrader(config);
        Strategy strategy = new Strategy(config);
        RelativeStrengthFilter relativeStrength = new RelativeStrengthFilter();

        System.out.printf("\n---------- Execution Bot : %s | 📍(%s) ----------%n", config.symbol(),
                ZoneId.of("Asia/Kolkata"));
        while (true) {
            boolean touchActive = false;
            try {
                touchActive = runCycle(config, state, broker, strategy, relativeStrength);
            } catch (Exception error) {
                System.err.println("Cycle failed: " + error.getMessage());
            }
            long wait = touchActive ? 1_000L : Duration.ofSeconds(config.pollSeconds()).toMillis();
            Thread.sleep(wait);
        }
    }

    private static boolean runCycle(BotConfig config, LocalState state,
                                   MatchTrader broker, Strategy strategy,
                                   RelativeStrengthFilter relativeStrength) throws Exception {
        syncClosedPositions(config, state, broker);
        String pendingOrderId = state.read("pendingOrderId", "");
        broker.syncPositions();
        Side openPositionSide = broker.openPositionSide(config.symbol());
        if (!pendingOrderId.isBlank() && openPositionSide != null) {
            System.out.println("Tracked STOP order filled; open position synchronized.");
            for (String positionId : broker.openPositionIds(config.symbol())) {
                addStateId(state, "executedPositionIds", positionId);
            }
            state.write("processedSetup", state.read("pendingSignal", ""));
            clearPendingOrder(state);
            return false;
        }

        long now = Instant.now().toEpochMilli();
        List<Candle> minutes = broker.candles("M1", 60);
        List<Candle> halfHours = broker.candles("M30", 3);
        List<Candle> hours = broker.candles("H1", 2);
        long currentMinute = Math.floorDiv(now, 60_000L);
        if (currentMinute != lastReportedMinute) {
            System.out.print(strategy.currentWindowReport(
                    minutes, halfHours, hours, now, openPositionSide));
            System.out.print(relativeStrength.currentSetupReport(minutes, halfHours, hours, now));
            lastReportedMinute = currentMinute;
        }

        long pendingSetupEnd = Long.parseLong(state.read("pendingSetupEnd", "0"));
        if (!pendingOrderId.isBlank()) {
            if (now >= pendingSetupEnd) {
                broker.cancelPendingOrder(pendingOrderId);
                state.write("processedSetup", state.read("pendingSignal", ""));
                clearPendingOrder(state);
                System.out.println("\n◇ Canceled pending STOP order : setup window closed.");
            }
            return false;
        }

        Signal signal = strategy.pendingSignal(minutes, halfHours, hours, now);
        if (signal == null) return strategy.touchConditionMet(minutes, halfHours, hours, now);
        if (signal.key().equals(state.read("processedSetup", ""))) return false;
        // if (!relativeStrength.allows(signal)) {
        //     System.out.println("🗣️ Relative-strength rejected setup; no STOP ORDER submitted.");
        //     return;
        // }
        String orderId = broker.createPendingOrder(signal,
                MatchTrader.PendingOrderType.STOP, signal.entryLevel());
        state.write("pendingOrderId", orderId);
        state.write("pendingSetupEnd", Long.toString(signal.setupStart() + 30 * 60_000L));
        state.write("pendingSignal", signal.key());
        return false;
    }

    private static void syncClosedPositions(BotConfig config, LocalState state,
                                             MatchTrader broker) throws Exception {
        Set<String> synced = new HashSet<>();
        String saved = state.read("closedPositionIds", "");
        if (!saved.isBlank()) {
            for (String id : saved.split(",")) {
                if (!id.isBlank()) synced.add(id);
            }
        }
        Path file = Path.of("data", "closedPosition.csv");
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.writeString(file,
                    "DATETIME (IST),SIDE,ENTRY_PRICE,SL_POINTS,TP_POINTS,"
                            + "SL_LEVEL,TP_LEVEL,VOLUME,WIN_LOSS,PROFIT_LOSS"
                            + System.lineSeparator(),
                    StandardOpenOption.CREATE);
        }
        Set<String> executed = stateIds(state, "executedPositionIds");
        for (ClosedTrade trade : broker.closedPositions(config.symbol())) {
            if (!executed.contains(trade.id())) continue;
            if (!synced.add(trade.id())) continue;
            ZonedDateTime entryTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(trade.entryTime()), ZoneId.of("Asia/Kolkata"));
            String result = trade.result().isBlank()
                    ? trade.profitLoss() > 0 ? "WIN" : trade.profitLoss() < 0 ? "LOSS" : ""
                    : trade.result();
            String dateTime = String.format(Locale.ROOT, "%s, %s, %s (IST)",
                    entryTime.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")),
                    entryTime.getDayOfWeek(),
                    entryTime.format(DateTimeFormatter.ofPattern("h:mm a"))
                            .toLowerCase(Locale.ROOT));
            String row = String.format(Locale.ROOT,
                    "\"%s\",%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.8f,%s,%.2f%n",
                    dateTime,
                    trade.side(), trade.entryPrice(), trade.stopPoints(), trade.targetPoints(),
                    trade.stopLevel(), trade.targetLevel(), trade.volume(), result,
                    trade.profitLoss());
            Files.writeString(file, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        state.write("closedPositionIds", String.join(",", synced));
    }

    private static void clearPendingOrder(LocalState state) throws IOException {
        state.write("pendingOrderId", "");
        state.write("pendingSetupEnd", "0");
        state.write("pendingSignal", "");
    }

    private static void addStateId(LocalState state, String key, String id) throws IOException {
        if (id == null || id.isBlank()) return;
        Set<String> ids = new HashSet<>();
        String existing = state.read(key, "");
        if (!existing.isBlank()) {
            for (String value : existing.split(",")) {
                if (!value.isBlank()) ids.add(value);
            }
        }
        if (ids.add(id)) state.write(key, String.join(",", ids));
    }

    private static Set<String> stateIds(LocalState state, String key) throws IOException {
        Set<String> ids = new HashSet<>();
        String value = state.read(key, "");
        if (!value.isBlank()) {
            for (String id : value.split(",")) {
                if (!id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    static String formatIst(long timestamp) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp),
                ZoneId.of("Asia/Kolkata")).toString();
    }
}
