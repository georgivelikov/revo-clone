package com.revolutclone.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The per-transaction cap from plans/phase-03-payments-and-ui.md's validation list ("amount
 * positive and under a per-transaction cap"). Expressed in minor units, deliberately as one
 * number across every currency rather than a per-currency table: this is a blast-radius limit
 * on a single accepted instruction, not a regulatory threshold, and a cap that varies by
 * currency invites the units bug it is supposed to prevent.
 *
 * <p>It is emphatically <em>not</em> a balance check. payment-service does not know balances -
 * the ledger is the only thing that does, and it rejects an overdraw with
 * {@code INSUFFICIENT_FUNDS} after the fact. The cap exists so an obviously malformed amount
 * (a client that sent major units by mistake, say) is refused at the edge instead of being
 * accepted, published, and then rejected three services later.
 *
 * <p>The default is 100,000.00 in a two-digit currency, comfortably above the phase's own
 * verification step that sends 10,000.00 against a 500.00 balance and expects the failure to
 * come back from the ledger as {@code INSUFFICIENT_FUNDS}, not from this check.
 */
@ConfigurationProperties(prefix = "revolut.payments")
public record PaymentLimitsProperties(@DefaultValue("10000000") long maxAmountMinor) {
}
