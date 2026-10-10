package com.executionbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class MatchTrader {
    enum PendingOrderType {
        STOP, LIMIT
    }

    private final BotConfig config;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private String systemUuid;
    private String apiToken;
    private String cookie;

    MatchTrader(BotConfig config) {
        this.config = config;
        this.systemUuid = config.systemUuid();
        this.apiToken = config.tradingApiToken();
        this.cookie = config.cookie();
    }

    boolean hasOpenPosition(String symbol) throws Exception {
        for (JsonNode position : openPositions().path("positions")) {
            if (symbol.equals(position.path("symbol").asText())) return true;
        }
        return false;
    }

    Side openPositionSide(String symbol) throws Exception {
        for (JsonNode position : openPositions().path("positions")) {
            if (!symbol.equals(position.path("symbol").asText())) continue;
            return switch (position.path("side").asText("").toUpperCase(Locale.ROOT)) {
                case "BUY", "LONG" -> Side.LONG;
                case "SELL", "SHORT" -> Side.SHORT;
                default -> null;
            };
        }
        return null;
    }

    List<String> openPositionIds(String symbol) throws Exception {
        List<String> ids = new ArrayList<>();
        for (JsonNode position : openPositions().path("positions")) {
            if (symbol.equals(position.path("symbol").asText())) {
                String id = position.path("id").asText("");
                if (!id.isBlank()) ids.add(id);
            }
        }
        return ids;
    }

    boolean pendingOrderExists(String orderId) throws Exception {
        if (orderId == null || orderId.isBlank()) return false;
        try {
            JsonNode order = request("trading-edge", "GET",
                    "/pending-order/" + URLEncoder.encode(orderId, StandardCharsets.UTF_8), null);
            return !order.isMissingNode() && !order.isNull()
                    && !text(order, "id", "orderId").isBlank();
        } catch (IOException error) {
            if (!(error.getMessage().contains("404")
                    || error.getMessage().contains("ORDER_NOT_FOUND")
                    || error.getMessage().contains("Order not found"))) {
                throw error;
            }
            JsonNode root = request("trading-edge", "GET", "/pending-orders", null);
            JsonNode orders = root.isArray() ? root : firstArray(root,
                    "pendingOrders", "orders", "data");
            if (orders == null) throw error;
            for (JsonNode order : orders) {
                if (orderId.equals(text(order, "id", "orderId"))) return true;
            }
            return false;
        }
    }

    void syncPositions() throws Exception {
        List<String> rows = new ArrayList<>();
        for (JsonNode position : openPositions().path("positions")) {
            String id = position.path("id").asText();
            String symbol = position.path("symbol").asText();
            String side = position.path("side").asText();
            String volume = position.path("volume").asText();
            String stopLoss = position.path("stopLoss").asText("0");
            rows.add(String.join(",", id, symbol, side, volume, stopLoss));
        }
        Path file = Path.of("data", "OpenPositions.csv");
        Files.createDirectories(file.getParent());
        Files.write(file, rows, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    List<ClosedTrade> closedPositions(String symbol) throws Exception {
        Instant to = Instant.now();
        Instant from = to.minusSeconds(90L * 24L * 60L * 60L);
        String payload = String.format(Locale.ROOT,
                "{\"from\":\"%s\",\"to\":\"%s\","
                        + "\"symbolCalcTypes\":[\"FOREX\",\"CFD\",\"FOREXCFD\"]}",
                DateTimeFormatter.ISO_INSTANT.format(from),
                DateTimeFormatter.ISO_INSTANT.format(to));
        JsonNode root = request("trading-edge", "POST", "/closed-positions", payload);
        JsonNode rows = root.isArray() ? root : firstArray(root,
                "operations", "closedPositions", "positions", "data");
        List<ClosedTrade> trades = new ArrayList<>();
        if (rows == null) return trades;
        for (JsonNode row : rows) {
            if (!symbol.equals(text(row, "symbol", "instrument"))) continue;
            String id = text(row, "id", "positionId", "orderId", "dealId");
            if (id.isBlank()) continue;
            double volume = number(row, "volume", "quantity");
            if (!Double.isFinite(volume) || volume <= 0) {
                throw new IOException("Closed position " + id + " has invalid volume: " + volume);
            }
            double stopLevel = number(row, "stopLossPrice", "slPrice", "stopLoss");
            double targetLevel = number(row, "takeProfitPrice", "tpPrice", "takeProfit");
            double entryPrice = number(row, "openPrice", "entryPrice", "price");
            double stopPoints = number(row, "slPoints", "stopPoints");
            double targetPoints = number(row, "tpPoints", "targetPoints");
            if (stopPoints == 0 && stopLevel != 0) stopPoints = Math.abs(entryPrice - stopLevel);
            if (targetPoints == 0 && targetLevel != 0) {
                targetPoints = Math.abs(targetLevel - entryPrice);
            }
            trades.add(new ClosedTrade(
                    text(row, "symbol", "instrument"),
                    id,
                    timestamp(row, "openTime", "entryTime", "createdAt", "openedAt"),
                    text(row, "side", "orderSide"),
                    entryPrice, stopPoints, targetPoints, stopLevel, targetLevel,
                    number(row, "closePrice"),
                    timestamp(row, "time", "closeTime", "closedAt"),
                    1,
                    number(row, "swap") / volume,
                    number(row, "commission", "commissions") / volume,
                    text(row, "result", "outcome"),
                    number(row, "netProfit", "profit", "pnl", "profitLoss") / volume));
        }
        return trades;
    }

    void openManagedPosition(Signal signal) throws Exception {
        double stop = signal.stopPoints() + config.spreadPoints();
        double target = stop * signal.targetPoints() / signal.stopPoints();
        double equity = accountEquity();
        double currentPrice = latestCandleClose();
        double volume = equity * 2.0 / currentPrice;
        if (volume <= 0) throw new IllegalArgumentException("Calculated volume is not positive");

        String side = signal.side() == Side.LONG ? "BUY" : "SELL";
        JsonNode opened = request("POST", "/position/open", String.format(Locale.ROOT,
                "{\"instrument\":\"%s\",\"volume\":%.8f,\"orderSide\":\"%s\",\"isMobile\":false}",
                config.symbol(), volume, side));
        if (opened.path("orderId").asText("").isBlank()) {
            throw new IOException("MatchTrader did not return an orderId");
        }

        for (JsonNode position : openPositions().path("positions")) {
            if (!config.symbol().equals(position.path("symbol").asText())) continue;
            double openPrice = position.path("openPrice").asDouble();
            double stopPrice = signal.side() == Side.LONG ? openPrice - stop : openPrice + stop;
            double targetPrice = signal.side() == Side.LONG ? openPrice + target : openPrice - target;
            request("POST", "/position/edit", String.format(Locale.ROOT,
                    "{\"instrument\":\"%s\",\"id\":\"%s\",\"orderSide\":\"%s\",\"volume\":%.8f,\"slPrice\":%.8f,\"tpPrice\":%.8f,\"isMobile\":false}",
                    config.symbol(), position.path("id").asText(), position.path("side").asText(),
                    position.path("volume").asDouble(), stopPrice, targetPrice));
            System.out.printf("Opened %s volume=%.8f SL=%.2f TP=%.2f%n",
                    side, volume, stopPrice, targetPrice);
            return;
        }
        throw new IOException("Order accepted but no open position was returned");
    }

    private double accountEquity() throws Exception {
        JsonNode root = request("trading-edge", "GET", "/v2/balance", null);
        double equity = number(root, "equity", "accountEquity");
        if (equity == 0 && root.path("data").isObject()) {
            equity = number(root.path("data"), "equity", "accountEquity");
        }
        if (!Double.isFinite(equity) || equity <= 0) {
            throw new IOException("MatchTrader balance response did not contain a positive equity");
        }
        return equity;
    }

    private double latestCandleClose() throws Exception {
        List<Candle> latest = candles("M1", 1);
        double close = latest.get(latest.size() - 1).close();
        if (!Double.isFinite(close) || close <= 0) {
            throw new IOException("MatchTrader returned an invalid latest candle close: " + close);
        }
        return close;
    }

    String createPendingOrder(Signal signal, PendingOrderType type, double price) throws Exception {
        if (!Double.isFinite(price) || price <= 0) {
            throw new IllegalArgumentException("Pending order price must be positive and finite");
        }
        double stopDistance = signal.stopPoints() + config.spreadPoints();
        double targetDistance = stopDistance * signal.targetPoints() / signal.stopPoints();
        double executionPrice = signal.side() == Side.LONG
                ? price + config.spreadPoints() : price;
        double stopPrice = signal.side() == Side.LONG
                ? executionPrice - stopDistance : executionPrice + stopDistance;
        double targetPrice = signal.side() == Side.LONG
                ? executionPrice + targetDistance : executionPrice - targetDistance;
        double risk = config.riskCap() * signal.riskMultiplier();
        double equity = accountEquity();
        double currentPrice = latestCandleClose();
        double volume = equity * 2.0 / currentPrice;
        if (!Double.isFinite(volume) || volume <= 0) {
            throw new IllegalArgumentException(
                    "Calculated pending-order volume is not positive and finite");
        }

        String side = signal.side() == Side.LONG ? "BUY" : "SELL";
        JsonNode created = request("trading-edge", "POST", "/pending-order/create",
                String.format(Locale.ROOT,
                        "{\"orderSide\":\"%s\",\"slPrice\":%.8f,\"tpPrice\":%.8f,"
                                + "\"instrument\":\"%s\",\"volume\":%.8f,\"type\":\"%s\","
                                + "\"price\":%.8f,\"source\":\"Advanced view\"}",
                        side, stopPrice, targetPrice, config.symbol(), volume, type.name(), executionPrice));
        String orderId = created.path("id").asText(
                created.path("orderId").asText(""));
        if (orderId.isBlank()) {
            throw new IOException("MatchTrader pending order response did not contain an order id");
        }
        System.out.printf("\n⚠️ %s STOP order submitted: B/A_ENTRY_PRICE=%.2f SL=%.2f TP=%.2f%n",
                side, executionPrice, stopPrice, targetPrice);
        return orderId;
    }

    void cancelPendingOrder(String orderId) throws Exception {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Pending order id must not be blank");
        }
        try {
            request("trading-edge", "POST", "/pending-order/cancel",
                    String.format(Locale.ROOT, "{\"id\":\"%s\"}", escape(orderId)));
        } catch (IOException error) {
            if (!error.getMessage().contains("ORDER_NOT_FOUND")
                    && !error.getMessage().contains("Order not found")) {
                throw error;
            }
            System.out.println("Pending STOP order " + orderId
                    + " was already cancelled or filled; clearing local state.");
        }
    }

    List<Candle> candles(String interval, int amount) throws Exception {
        String symbol = URLEncoder.encode(config.symbol(), StandardCharsets.UTF_8);
        String path = "/candles?symbol=" + symbol + "&interval=" + interval
                + "&candleSide=BID&amount=" + amount;
        JsonNode root = request("market-data-api", "GET", path, null);
        JsonNode rows = root.isArray() ? root : root.path("candles");
        if (!rows.isArray() || rows.isEmpty()) {
            throw new IOException("MatchTrader returned no " + interval + " candles for "
                    + config.symbol());
        }
        List<Candle> result = new ArrayList<>();
        long duration = switch (interval) {
            case "M1" -> 60_000L;
            case "M30" -> 30 * 60_000L;
            case "H1" -> 60 * 60_000L;
            default -> throw new IllegalArgumentException("Unsupported candle interval " + interval);
        };
        for (JsonNode row : rows) {
            long openTime = longValue(row, "timestamp", "time", "openTime", "t");
            if (openTime < 10_000_000_000L) openTime *= 1_000L;
            result.add(new Candle(openTime, openTime + duration,
                    value(row, "open", "o"), value(row, "high", "h"),
                    value(row, "low", "l"), value(row, "close", "c")));
        }
        return result.stream().sorted(java.util.Comparator.comparingLong(Candle::openTime)).toList();
    }

    private static double value(JsonNode row, String... names) throws IOException {
        for (String name : names) {
            JsonNode field = row.get(name);
            if (field != null && field.isNumber()) return field.asDouble();
        }
        throw new IOException("MatchTrader candle is missing field " + String.join("/", names));
    }

    private static long longValue(JsonNode row, String... names) throws IOException {
        for (String name : names) {
            JsonNode field = row.get(name);
            if (field != null && field.isNumber()) return field.asLong();
        }
        throw new IOException("MatchTrader candle is missing field " + String.join("/", names));
    }

    private JsonNode openPositions() throws Exception {
        try {
            return request("trading-edge", "GET", "/open-positions", null);
        } catch (IOException firstFailure) {
            login();
            return request("trading-edge", "GET", "/open-positions", null);
        }

    }

    private void login() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create(config.matchTraderBaseUrl() + "/mtr-core-edge/v2/login"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(String.format(Locale.ROOT,
                        "{\"email\":\"%s\",\"password\":\"%s\"}",
                        escape(config.email()), escape(config.password())),
                        StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("MatchTrader login returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        String mainToken = null;
        for (String setCookie : response.headers().allValues("Set-Cookie")) {
            String cookieValue = setCookie.split(";", 2)[0].trim();
            if (cookieValue.startsWith("co-auth=")) {
                mainToken = cookieValue.substring("co-auth=".length());
                break;
            }
        }
        if (mainToken == null || mainToken.isBlank()) {
            throw new IOException("MatchTrader login did not return the co-auth cookie");
        }

        JsonNode root = mapper.readTree(response.body());
        String nextSystemUuid = "";
        String nextApiToken = "";
        for (JsonNode account : root.path("tradingAccounts")) {
            if (config.tradingAccountId().equals(account.path("tradingAccountId").asText())) {
                nextSystemUuid = account.path("system").path("uuid").asText();
                nextApiToken = account.path("tradingApiToken").asText();
                break;
            }
        }
        if (nextSystemUuid.isBlank() || nextApiToken.isBlank()) {
            throw new IOException("Configured MatchTrader account was not found or is incomplete");
        }
        systemUuid = nextSystemUuid;
        apiToken = nextApiToken;
        cookie = "co-auth=" + mainToken;
    }

    private JsonNode request(String method, String path, String body) throws Exception {
        return request("mtr-api", method, path, body);
    }

    private JsonNode request(String api, String method, String path, String body) throws Exception {
        return request(api, method, path, body, "application/json");
    }

    private JsonNode request(String api, String method, String path, String body,
                             String contentType) throws Exception {
        if (systemUuid.isBlank() || apiToken.isBlank() || cookie.isBlank()) login();
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= config.maxRetries(); attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                        config.matchTraderBaseUrl() + "/" + api + "/" + systemUuid + path))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Auth-trading-api", apiToken).header("Cookie", cookie)
                        .header("Accept", "application/json");
                if (body == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("Content-Type", contentType)
                            .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                }
                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() / 100 == 2) return mapper.readTree(response.body());
                lastFailure = new IOException("MatchTrader " + path + " returned HTTP "
                        + response.statusCode() + ": " + response.body());
                if (response.statusCode() == 401 || response.statusCode() == 403) {
                    login();
                }
            } catch (IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
                lastFailure = (IOException) failure;
            }
            Thread.sleep(Math.min(30_000L, 1_000L * attempt));
        }
        throw lastFailure == null ? new IOException("MatchTrader request failed") : lastFailure;
    }

    private static JsonNode firstArray(JsonNode root, String... names) {
        for (String name : names) {
            if (root.path(name).isArray()) return root.path(name);
        }
        return null;
    }

    private static String text(JsonNode row, String... names) {
        for (String name : names) {
            JsonNode value = row.get(name);
            if (value != null && !value.isNull()) return value.asText("");
        }
        return "";
    }

    private static double number(JsonNode row, String... names) {
        for (String name : names) {
            JsonNode value = row.get(name);
            if (value != null && value.isNumber()) return value.asDouble();
        }
        return 0;
    }

    private static long timestamp(JsonNode row, String... names) {
        for (String name : names) {
            JsonNode value = row.get(name);
            if (value == null || value.isNull()) continue;
            if (value.isNumber()) {
                long timestamp = value.asLong();
                return timestamp < 10_000_000_000L ? timestamp * 1_000L : timestamp;
            }
            if (value.isTextual()) {
                try {
                    return Instant.parse(value.asText()).toEpochMilli();
                } catch (RuntimeException ignored) {
                    // Try the next supported timestamp field.
                }
            }
        }
        return 0;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

record ClosedTrade(String symbol, String id, long entryTime, String side, double entryPrice,
                   double stopPoints, double targetPoints, double stopLevel,
                   double targetLevel, double closePrice, long closeTime, double volume,
                   double swap, double commission, String result, double profitLoss) {
}
