package com.revolutclone.ledger.chaos;

public record ChaosLedgerStateResponse(long delayMs, int failRatePercent, boolean rejectAll) {

    public static ChaosLedgerStateResponse from(ChaosLedgerState state) {
        return new ChaosLedgerStateResponse(state.delayMs(), state.failRatePercent(), state.rejectAll());
    }
}
