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
        try {
            syncClosedPositions(config, state, broker);
        } catch (Exception error) {
            System.err.println("Closed-position sync unavailable; will retry: "
                    + error.getMessage());
        }
        String pendingOrderId = state.read("pendingOrderId", "");
        broker.syncPositions();
        List<String> openPositionIds = broker.openPositionIds(config.symbol());
        Side openPositionSide = broker.openPositionSide(config.symbol());
        for (String positionId : openPositionIds) {
            addStateId(state, "executedPositionIds", positionId);
        }
        List<String> brokerPendingOrderIds = broker.pendingOrderIds(config.symbol());
        if (!openPositionIds.isEmpty() || openPositionSide != null) {
            Set<String> ordersToCancel = new HashSet<>(brokerPendingOrderIds);
            if (!pendingOrderId.isBlank()) ordersToCancel.add(pendingOrderId);
            for (String orderId : ordersToCancel) broker.cancelPendingOrder(orderId);
            if (!pendingOrderId.isBlank()) {
                markProcessedSetup(state, state.read("pendingSignal", ""));
                clearPendingOrder(state);
            }
            System.out.println("Open position exists; new entries are blocked and pending orders canceled.");
            return false;
        }
        if (pendingOrderId.isBlank() && !brokerPendingOrderIds.isEmpty()) {
            for (String orderId : brokerPendingOrderIds) broker.cancelPendingOrder(orderId);
            long currentSetupStart = Math.floorDiv(Instant.now().toEpochMilli(), 30 * 60_000L)
                    * 30 * 60_000L;
            state.write("processedSetupStart", Long.toString(currentSetupStart));
            System.out.println("Canceled untracked pending order(s); setup is blocked for this window.");
            return false;
        }
        if (!pendingOrderId.isBlank()) {
            for (String orderId : brokerPendingOrderIds) {
                if (!pendingOrderId.equals(orderId)) broker.cancelPendingOrder(orderId);
            }
        }

        long now = Instant.now().toEpochMilli();
        double multiplier = broker.offsetMultiplier();
        List<Candle> minutes = broker.candles("M1", 60);
        List<Candle> halfHours = broker.candles("M30", 3);
        List<Candle> hours = broker.candles("H1", 2);
        long currentMinute = Math.floorDiv(now, 60_000L);
        if (currentMinute != lastReportedMinute) {
            System.out.print(strategy.currentWindowReport(
                    minutes, halfHours, hours, now, openPositionSide, multiplier));
            System.out.print(relativeStrength.currentSetupReport(minutes, halfHours, hours, now));
            lastReportedMinute = currentMinute;
        }

        long pendingSetupEnd = Long.parseLong(state.read("pendingSetupEnd", "0"));
        if (!pendingOrderId.isBlank()) {
            if (!broker.pendingOrderExists(pendingOrderId)) {
                markProcessedSetup(state, state.read("pendingSignal", ""));
                clearPendingOrder(state);
                pendingOrderId = "";
                System.out.println("Pending STOP order disappeared; this setup will not be retried.");
            }
        }
        if (!pendingOrderId.isBlank()) {
            if (now >= pendingSetupEnd) {
                broker.cancelPendingOrder(pendingOrderId);
                markProcessedSetup(state, state.read("pendingSignal", ""));
                clearPendingOrder(state);
                System.out.println("\n◇ Canceled pending STOP order : setup window closed.");
            }
            return false;
        }

        Signal signal = strategy.pendingSignal(minutes, halfHours, hours, now, multiplier);
        if (signal == null) {
            return strategy.touchConditionMet(minutes, halfHours, hours, now, multiplier);
        }
        if (signal.setupStart() == processedSetupStart(state)) return false;
        // if (!relativeStrength.allows(signal)) {
        //     System.out.println("🗣️ Relative-strength rejected setup; no STOP ORDER submitted.");
        //     return;
        // }
        // Reserve this 30-minute setup before sending the request so an ambiguous API response
        // cannot cause a second order attempt for the same setup.
        state.write("processedSetupStart", Long.toString(signal.setupStart()));
        state.write("processedSetup", signal.key());
        String orderId = broker.createPendingOrder(signal,
                MatchTrader.PendingOrderType.STOP, signal.entryLevel());
        state.write("pendingOrderId", orderId);
        state.write("pendingSetupEnd", Long.toString(signal.setupStart() + 30 * 60_000L));
        state.write("pendingSignal", signal.key());
        return false;
    }

    private static void syncClosedPositions(BotConfig config, LocalState state,
                                             MatchTrader broker) throws Exception {
        Path file = Path.of("data", "closedPosition.csv");
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.writeString(file,
                    "SYMBOL,ID,SIDE,WIN_LOSS,OPEN_TIME (IST),SL_LENGTH,TP_LENGTH,"
                            + "OPEN_PRICE,SL,TP,CLOSE_PRICE,CLOSE_TIME (IST),VOLUME,"
                            + "SWAP,COMMISSIONS,NET_PROFIT"
                            + System.lineSeparator(),
                    StandardOpenOption.CREATE);
        }
        Set<String> synced = csvPositionIds(file);
        for (ClosedTrade trade : broker.closedPositions(config.symbol())) {
            if (!synced.add(trade.id())) continue;
            ZonedDateTime openTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(trade.entryTime()), ZoneId.of("Asia/Kolkata"));
            ZonedDateTime closeTime = ZonedDateTime.ofInstant(
                    Instant.ofEpochMilli(trade.closeTime()), ZoneId.of("Asia/Kolkata"));
            String result = trade.result().isBlank()
                    ? trade.profitLoss() > 0 ? "WIN" : trade.profitLoss() < 0 ? "LOSS" : ""
                    : trade.result();
            String row = String.format(Locale.ROOT,
                    "%s,%s,%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%.8f,%.2f,%.2f,%.2f%n",
                    trade.symbol(), trade.id(), trade.side(), result,
                    openTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    trade.stopPoints(), trade.targetPoints(), trade.entryPrice(),
                    trade.stopLevel(), trade.targetLevel(), trade.closePrice(),
                    closeTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    trade.volume(), trade.swap(), trade.commission(), trade.profitLoss());
            Files.writeString(file, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        state.write("closedPositionIds", String.join(",", synced));
    }

    private static Set<String> csvPositionIds(Path file) throws IOException {
        Set<String> ids = new HashSet<>();
        for (String line : Files.readAllLines(file)) {
            String[] columns = line.split(",", -1);
            if (columns.length > 1 && !"ID".equalsIgnoreCase(columns[1])) {
                String id = columns[1].trim();
                if (!id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    private static long processedSetupStart(LocalState state) throws IOException {
        String setupStart = state.read("processedSetupStart", "");
        if (!setupStart.isBlank()) {
            try {
                return Long.parseLong(setupStart);
            } catch (NumberFormatException ignored) {
                return Long.MIN_VALUE;
            }
        }
        String legacyKey = state.read("processedSetup", "");
        String[] fields = legacyKey.split(":");
        if (fields.length >= 2) {
            try {
                return Long.parseLong(fields[1]);
            } catch (NumberFormatException ignored) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }

    private static void markProcessedSetup(LocalState state, String signalKey) throws IOException {
        if (signalKey == null || signalKey.isBlank()) return;
        String[] fields = signalKey.split(":");
        if (fields.length >= 2) {
            state.write("processedSetupStart", fields[1]);
        }
        state.write("processedSetup", signalKey);
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

    static String formatIst(long timestamp) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp),
                ZoneId.of("Asia/Kolkata")).toString();
    }
}
