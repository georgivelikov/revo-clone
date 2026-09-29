package com.revolutclone.analytics.web;

import com.revolutclone.analytics.payment.InitiatedPayment;
import java.util.UUID;

public record VaultTransferResponse(UUID paymentId, String status) {

    public static VaultTransferResponse from(InitiatedPayment payment) {
        return new VaultTransferResponse(payment.paymentId(), payment.status());
    }
}
