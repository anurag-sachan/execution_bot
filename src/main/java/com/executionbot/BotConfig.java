package com.executionbot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

record BotConfig(String symbol, String matchTraderBaseUrl,
             String email, String password, String tradingAccountId,
                 String systemUuid, String tradingApiToken, String cookie,
                 int riskCap, double pointValuePerLot, double maxLots, double spreadPoints,
                 int pollSeconds, int marketCacheMinutes, int maxRetries) {
    BotConfig withRiskCap(int value) {
        return new BotConfig(symbol, matchTraderBaseUrl, email, password,
            tradingAccountId, systemUuid, tradingApiToken, cookie, value,
                pointValuePerLot, maxLots, spreadPoints, pollSeconds, marketCacheMinutes, maxRetries);
    }
    static BotConfig fromEnvironment() {
        Map<String, String> e = new HashMap<>(loadDotEnv());
        e.putAll(System.getenv());
        return new BotConfig(value(e, "BOT_SYMBOL", "BTCUSD"),
                // value(e, "MATCHTRADER_BASE_URL", "https://trade.toponetrader.com"),
                value(e, "MATCHTRADER_BASE_URL", "https://mtr-competition.fundingpips.com"),
                required(e, "MATCHTRADER_EMAIL", "email"),
                required(e, "MATCHTRADER_PASSWORD", "password"),
                required(e, "MATCHTRADER_ACCOUNT_ID", "tradingAccountId"),
                value(e, "", "MATCHTRADER_SYSTEM_UUID", "systemUuid"),
                value(e, "", "MATCHTRADER_API_TOKEN", "tradingApiToken"),
                value(e, "", "MATCHTRADER_COOKIE", "Cookie"),
                integer(e, "BOT_RISK_CAP", 4000),
                decimal(e, "BOT_POINT_VALUE_PER_LOT", 1.0),
                decimal(e, "BOT_MAX_LOTS", 2.0),
                decimal(e, "BOT_SPREAD_POINTS", 22.0),
                integer(e, "BOT_POLL_SECONDS", 15),
                integer(e, "BOT_MARKET_CACHE_MINUTES", 24 * 60),
                integer(e, "BOT_MAX_RETRIES", 5));
    }

    private static Map<String, String> loadDotEnv() {
        Path path = Path.of(".env");
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }

        Map<String, String> values = new HashMap<>();
        try {
            for (String rawLine : Files.readAllLines(path)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                int colon = line.indexOf(':');
                if (separator < 0 || (colon >= 0 && colon < separator)) {
                    separator = colon;
                }
                if (separator <= 0) {
                    throw new IllegalArgumentException(
                            "Invalid .env entry; expected KEY=value or key:value");
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                values.put(key, unquote(value));
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Unable to read .env", error);
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String required(Map<String, String> env, String... keys) {
        String result = firstValue(env, keys);
        if (result == null || result.isBlank()) {
            throw new IllegalArgumentException("Missing required environment variable " + keys[0]);
        }
        return result;
    }

    private static String value(Map<String, String> env, String key, String fallback) {
        String result = firstValue(env, key);
        return result == null || result.isBlank() ? fallback : result;
    }

    private static String value(Map<String, String> env, String fallback, String... keys) {
        String result = firstValue(env, keys);
        return result == null || result.isBlank() ? fallback : result;
    }

    private static String firstValue(Map<String, String> env, String... keys) {
        for (String key : keys) {
            String result = env.get(key);
            if (result != null && !result.isBlank()) {
                return result;
            }
        }
        return null;
    }

    private static int integer(Map<String, String> env, String key, int fallback) {
        return Integer.parseInt(value(env, key, Integer.toString(fallback)));
    }

    private static double decimal(Map<String, String> env, String key, double fallback) {
        return Double.parseDouble(value(env, key, Double.toString(fallback)));
    }
}
