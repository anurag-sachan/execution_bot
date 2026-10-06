# Live execution bot

This separate Java 17 application follows the signal and schedule logic in
`src/main/java/com/template/Main.java` and submits positions through the same MatchTrader
API shape used by `algo`. It does not use Binance.

The Java backtest is the source of truth. The current Java code uses `hourly open - 90`
for long entries and `hourly open + 110` for short entries; this bot intentionally follows
those values rather than older prose in `backtest_rules.txt`. It uses completed MatchTrader
candles, requires touch and entry on different 1-minute candles, allows one open BTCUSD
position, applies the IST weekday/time/day-of-month filters, the 500-point round-number
filter, 10% risk slots, and broker-mode `SL + spread` sizing.

MatchTrader OHLC snapshots are stored in `data/market_snapshots.csv`. On an API
failure, the bot continues from that cache instead of failing immediately. The cache is
made from broker 1-minute OHLC history and bucketed into 1-minute, 30-minute, and
1-hour candles. Keep the process running so the local cache remains current.
Broker state is synchronized to `data/OpenPositions.csv`, and `data/database.txt` stores
the risk cap and the last submitted signal. Requests retry with backoff and refresh the
MatchTrader login when authentication expires.

After a configured 30-minute touch level is observed, the bot submits one
MatchTrader STOP pending order at the 1-hour entry level. The pending order is
canceled when that 30-minute setup closes unless it has filled into an open
position. Pending-order state is persisted in `data/database.txt` to prevent
duplicate orders across polling cycles or restarts.

Set credentials as environment variables or in a local `.env` file; do not
commit credentials:

```sh
export MATCHTRADER_EMAIL='...'
export MATCHTRADER_PASSWORD='...'
export MATCHTRADER_ACCOUNT_ID='...'
export BOT_RISK_CAP='1000'
export BOT_MAX_LOTS='2'
cd execution_bot
mvn package dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/classes:$(cat cp.txt)" com.executionbot.Main
```

The `.env` loader accepts both `KEY=value` and the existing `key:value` format.
Explicit environment variables take precedence over values from `.env`.

Optional variables include `MATCHTRADER_BASE_URL`, `MATCHTRADER_SYSTEM_UUID`,
`MATCHTRADER_API_TOKEN`, `MATCHTRADER_COOKIE`, `BOT_SPREAD_POINTS`,
`BOT_POINT_VALUE_PER_LOT`, `BOT_POLL_SECONDS`, `BOT_MARKET_CACHE_MINUTES`,
`BOT_MAX_RETRIES`, and `BOT_SYMBOL`.
