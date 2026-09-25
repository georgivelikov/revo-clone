package com.revolutclone.ledger.consumer;

import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.contracts.events.LedgerAccountCreatedV1;
import com.revolutclone.ledger.domain.AccountBalance;
import com.revolutclone.ledger.domain.AccountBalanceRepository;
import com.revolutclone.ledger.domain.LedgerAccount;
import com.revolutclone.ledger.domain.LedgerAccountRepository;
import com.revolutclone.ledger.domain.LedgerAccountType;
import com.revolutclone.messaging.api.EventHandler;
import com.revolutclone.messaging.api.OutboxAppender;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-02-ledger.md's task list item 8: on {@link AccountOpenedV1}, open the matching
 * {@code ledger_account} (with a zero-initialised {@code account_balance} row, same convention
 * as every other account-creation path in this service - see
 * {@code SystemAccountBootstrapTest}), then publish {@link LedgerAccountCreatedV1} so
 * {@code account-service} can fill in its own {@code ledger_account_id}. Mirrors
 * account-service's {@code UserRegisteredEventHandler}: business logic here, redelivery
 * protection one layer up in the {@code IdempotentHandler} this bean is wrapped by (see
 * {@link AccountOpenedKafkaListener}).
 *
 * <p>{@code owner_ref} is {@code "user:" + userId}, matching the convention every user-owned
 * {@link LedgerAccount} in this codebase uses (see every test in this module) - the ledger does
 * not know about account-service's own {@code accountId}, only the user who owns the money, per
 * the phase's boundary rule that this service stays ignorant of payments/cards/FX beyond
 * balanced entries and reservations.
 */
@Component
public class AccountOpenedEventHandler implements EventHandler<AccountOpenedV1> {

    private static final String OUTBOX_TOPIC = "ledger.entry.v1";

    private final LedgerAccountRepository ledgerAccountRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final OutboxAppender outboxAppender;

    public AccountOpenedEventHandler(LedgerAccountRepository ledgerAccountRepository,
            AccountBalanceRepository accountBalanceRepository, OutboxAppender outboxAppender) {
        this.ledgerAccountRepository = ledgerAccountRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.outboxAppender = outboxAppender;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<AccountOpenedV1> envelope) {
        AccountOpenedV1 event = envelope.payload();
        LedgerAccountType type = mapType(event.type());

        LedgerAccount ledgerAccount = new LedgerAccount(UUID.randomUUID(), "user:" + event.userId(), type,
                event.currency());
        ledgerAccountRepository.save(ledgerAccount);

        AccountBalance balance = new AccountBalance(ledgerAccount.getId(), event.currency());
        accountBalanceRepository.save(balance);

        outboxAppender.append(OUTBOX_TOPIC, ledgerAccount.getId().toString(),
                new LedgerAccountCreatedV1(event.accountId(), ledgerAccount.getId()));
    }

    /**
     * account-service's own {@code AccountType} is {@code WALLET}/{@code VAULT} (phase 06); this
     * service's {@link LedgerAccountType} spells the wallet case {@code USER_WALLET} to leave
     * {@code VAULT} free for the ledger's own system-account-adjacent naming. Anything else is a
     * genuine data-shape mismatch between the two services and must fail loudly rather than
     * silently mis-file the account.
     */
    private static LedgerAccountType mapType(String accountServiceType) {
        return switch (accountServiceType) {
            case "WALLET" -> LedgerAccountType.USER_WALLET;
            case "VAULT" -> LedgerAccountType.VAULT;
            default -> throw new IllegalArgumentException(
                    "unrecognized account-service account type: " + accountServiceType);
        };
    }
}
