package com.executionbot;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.nio.file.Path;
import java.util.List;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        LocalState state = new LocalState(Path.of("data", "database.txt"));
        BotConfig config = BotConfig.fromEnvironment();
        config = config.withRiskCap(Integer.parseInt(
                state.read("riskCap", Integer.toString(config.riskCap()))));
        MarketDataStore marketData = new MarketDataStore(
                Path.of("data", "market_snapshots.csv"), config.marketCacheMinutes());
        MatchTrader broker = new MatchTrader(config);
        Strategy strategy = new Strategy(config);

        System.out.printf("Execution bot started for %s (%s)%n", config.symbol(),
                ZoneId.of("Asia/Kolkata"));
        while (true) {
            try {
                runCycle(config, state, marketData, broker, strategy);
            } catch (Exception error) {
                System.err.println("Cycle failed: " + error.getMessage());
            }
            long wait = Duration.ofSeconds(config.pollSeconds()).toMillis();
            Thread.sleep(wait);
        }
    }

    private static void runCycle(BotConfig config, LocalState state, MarketDataStore marketData,
                                   MatchTrader broker, Strategy strategy) throws Exception {
        broker.syncPositions();
        if (broker.hasOpenPosition(config.symbol())) {
            System.out.println("Position already open; no new signal evaluated.");
            clearPendingOrder(state);
            return;
        }

        try {
            marketData.append(broker.snapshot());
        } catch (IOException | InterruptedException marketFailure) {
            System.err.println("MatchTrader market data unavailable; using local market cache: "
                    + marketFailure.getMessage());
        }
        if (marketData.latestObservedAt() < Instant.now().minusSeconds(120).toEpochMilli()) {
            System.err.println("Market cache is stale; no order will be submitted.");
            return;
        }
        long now = Instant.now().toEpochMilli();
        List<Candle> minutes = marketData.candles(1);
        List<Candle> halfHours = marketData.candles(30);
        List<Candle> hours = marketData.candles(60);

        String pendingOrderId = state.read("pendingOrderId", "");
        long pendingSetupEnd = Long.parseLong(state.read("pendingSetupEnd", "0"));
        if (!pendingOrderId.isBlank()) {
            if (now >= pendingSetupEnd) {
                broker.cancelPendingOrder(pendingOrderId);
                clearPendingOrder(state);
                System.out.println("Canceled pending STOP order after setup window closed.");
            }
            return;
        }

        Signal signal = strategy.pendingSignal(minutes, halfHours, hours, now);
        if (signal == null) return;
        System.out.printf("Signal %s setup=%s entry-level=%.2f touch=%.2f%n",
                signal.side(), signal.entryTimeIst(), signal.entryLevel(), signal.touchLevel());
        String orderId = broker.createPendingOrder(signal,
                MatchTrader.PendingOrderType.STOP, signal.entryLevel());
        state.write("pendingOrderId", orderId);
        state.write("pendingSetupEnd", Long.toString(signal.setupStart() + 30 * 60_000L));
        state.write("pendingSignal", signal.key());
    }

    private static void clearPendingOrder(LocalState state) throws IOException {
        state.write("pendingOrderId", "");
        state.write("pendingSetupEnd", "0");
        state.write("pendingSignal", "");
    }

    static String formatIst(long timestamp) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestamp),
                ZoneId.of("Asia/Kolkata")).toString();
    }
}
