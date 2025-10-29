package org.unicitylabs.proxy.model;

import io.github.bucket4j.TimeMeter;

import java.time.Instant;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.unicitylabs.proxy.util.TimeUtils.currentTimeMillis;

public class ApiKeyUtils {
    public static final int PAYMENT_VALIDITY_DAYS = 30;

    public static Instant getExpiryStartingFrom(TimeMeter timeMeter) {
        return Instant.ofEpochMilli(currentTimeMillis(timeMeter)).plus(PAYMENT_VALIDITY_DAYS, DAYS);
    }
}
