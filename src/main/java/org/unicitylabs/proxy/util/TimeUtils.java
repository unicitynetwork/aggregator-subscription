package org.unicitylabs.proxy.util;

import io.github.bucket4j.TimeMeter;

public final class TimeUtils {
    private TimeUtils() {
    }

    public static long currentTimeMillis(TimeMeter timeMeter) {
        return timeMeter.currentTimeNanos() / 1_000_000;
    }
}