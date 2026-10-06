package com.executionbot;

import java.time.ZonedDateTime;

record Signal(Side side, long setupStart, long touchTime, long entryTime,
              double touchLevel, double entryLevel, int stopPoints,
              int targetPoints, double riskMultiplier) {
    String key() {
        return side + ":" + setupStart + ":" + entryTime;
    }

    String entryTimeIst() {
        return ZonedDateTime.parse(Main.formatIst(entryTime)).toString();
    }
}

enum Side {
    LONG, SHORT
}
