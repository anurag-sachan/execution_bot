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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

final class MatchTrader {
    enum PendingOrderType {
        STOP, LIMIT
    }

    private final BotConfig config;
    private final HttpClient client = HttpClient.newHttpClient();
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

    void openManagedPosition(Signal signal) throws Exception {
        double stop = signal.stopPoints() + config.spreadPoints();
        double target = stop * signal.targetPoints() / signal.stopPoints();
        double risk = config.riskCap() * signal.riskMultiplier();
        double volume = Math.min(config.maxLots(), risk / (stop * config.pointValuePerLot()));
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

    String createPendingOrder(Signal signal, PendingOrderType type, double price) throws Exception {
        if (!Double.isFinite(price) || price <= 0) {
            throw new IllegalArgumentException("Pending order price must be positive and finite");
        }
        double stopDistance = signal.stopPoints() + config.spreadPoints();
        double targetDistance = stopDistance * signal.targetPoints() / signal.stopPoints();
        double stopPrice = signal.side() == Side.LONG
                ? price - stopDistance : price + stopDistance;
        double targetPrice = signal.side() == Side.LONG
                ? price + targetDistance : price - targetDistance;
        double risk = config.riskCap() * signal.riskMultiplier();
        double volume = Math.min(config.maxLots(),
                risk / (stopDistance * config.pointValuePerLot()));
        if (volume <= 0) {
            throw new IllegalArgumentException("Calculated pending-order volume is not positive");
        }

        String side = signal.side() == Side.LONG ? "BUY" : "SELL";
        JsonNode created = request("trading-edge", "POST", "/pending-order/create",
                String.format(Locale.ROOT,
                        "{\"orderSide\":\"%s\",\"slPrice\":%.8f,\"tpPrice\":%.8f,"
                                + "\"instrument\":\"%s\",\"volume\":%.8f,\"type\":\"%s\","
                                + "\"price\":%.8f,\"source\":\"Advanced view\"}",
                        side, stopPrice, targetPrice, config.symbol(), volume, type.name(), price));
        String orderId = created.path("id").asText(
                created.path("orderId").asText(""));
        if (orderId.isBlank()) {
            throw new IOException("MatchTrader pending order response did not contain an order id");
        }
        return orderId;
    }

    void cancelPendingOrder(String orderId) throws Exception {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("Pending order id must not be blank");
        }
        request("trading-edge", "POST", "/pending-order/cancel",
                String.format(Locale.ROOT, "{\"id\":\"%s\"}", escape(orderId)));
    }

    MarketSnapshot snapshot() throws Exception {
        long now = Instant.now().getEpochSecond();
        String symbol = URLEncoder.encode(config.symbol(), StandardCharsets.UTF_8);
        String path = "/api/trading-view/history?symbol=" + symbol
                + "&resolution=1&from=" + (now - 300L * 60L) + "&to=" + now
                + "&shouldRetrieveOnlyFromCache=true&countback=300";
        JsonNode root = request("market-data-api", "GET", path, null);
        JsonNode timestamps = root.path("t");
        JsonNode opens = root.path("o");
        JsonNode highs = root.path("h");
        JsonNode lows = root.path("l");
        JsonNode closes = root.path("c");
        if (!timestamps.isArray() || !opens.isArray() || !highs.isArray()
                || !lows.isArray() || !closes.isArray() || timestamps.isEmpty()) {
            throw new IOException("MatchTrader returned no OHLC history for " + config.symbol());
        }
        TreeMap<Long, Candle> candles = new TreeMap<>();
        for (int i = 0; i < timestamps.size(); i++) {
            long openTime = timestamps.get(i).asLong() * 1_000L;
            candles.put(openTime, new Candle(openTime, openTime + 60_000L,
                    opens.get(i).asDouble(), highs.get(i).asDouble(),
                    lows.get(i).asDouble(), closes.get(i).asDouble()));
        }
        Candle latest = candles.lastEntry().getValue();
        return new MarketSnapshot(Instant.now().toEpochMilli(), latest,
                aggregate(candles, 30), aggregate(candles, 60));
    }

    private static Candle aggregate(TreeMap<Long, Candle> candles, int minutes) throws IOException {
        long bucket = Math.floorDiv(candles.lastKey(), minutes * 60_000L)
                * minutes * 60_000L;
        Candle result = null;
        for (Candle candle : candles.tailMap(bucket).values()) {
            result = result == null
                    ? new Candle(bucket, bucket + minutes * 60_000L, candle.open(),
                            candle.high(), candle.low(), candle.close())
                    : new Candle(bucket, bucket + minutes * 60_000L, result.open(),
                            Math.max(result.high(), candle.high()),
                            Math.min(result.low(), candle.low()), candle.close());
        }
        if (result == null) {
            throw new IOException("MatchTrader history did not contain the current candle");
        }
        return result;
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
        if (systemUuid.isBlank() || apiToken.isBlank() || cookie.isBlank()) login();
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= config.maxRetries(); attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                        config.matchTraderBaseUrl() + "/" + api + "/" + systemUuid + path))
                        .header("Auth-trading-api", apiToken).header("Cookie", cookie)
                        .header("Accept", "application/json");
                if (body == null) {
                    builder.method(method, HttpRequest.BodyPublishers.noBody());
                } else {
                    builder.header("Content-Type", "application/json")
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

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

record MarketSnapshot(long observedAt, Candle oneMinute, Candle thirtyMinute, Candle oneHour) {
}
