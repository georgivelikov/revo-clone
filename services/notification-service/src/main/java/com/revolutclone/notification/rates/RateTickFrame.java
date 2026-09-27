package com.revolutclone.notification.rates;

import java.time.Instant;

/** One frame on {@link RateTickBroadcaster#DESTINATION} - the only shape the browser needs. */
public record RateTickFrame(String pair, String mid, Instant asOf) {
}
