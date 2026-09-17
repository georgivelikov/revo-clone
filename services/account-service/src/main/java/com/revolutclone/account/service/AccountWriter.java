package com.revolutclone.account.service;

import com.revolutclone.account.domain.Account;
import com.revolutclone.account.domain.AccountRepository;
import com.revolutclone.account.domain.AccountState;
import com.revolutclone.account.domain.AccountType;
import com.revolutclone.account.error.DuplicateWalletException;
import com.revolutclone.contracts.events.AccountOpenedV1;
import com.revolutclone.messaging.api.OutboxAppender;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place that inserts an {@link Account} row and appends {@link AccountOpenedV1} to the
 * outbox in a single local transaction, per
 * plans/phase-01-identity-account-gateway.md's "### account-service" design detail. Used both
 * by the {@code UserRegisteredV1} consumer (three calls, one per starter currency, all joining
 * the same outer transaction via REQUIRED propagation) and by {@code POST /api/accounts} (one
 * call, for a caller-requested additional currency).
 */
@Component
public class AccountWriter {

    /** Keyed by accountId, compacted - see plans/phase-00-foundations.md's topic catalogue. */
    private static final String TOPIC = "account.account.v1";

    private final AccountRepository repository;
    private final OutboxAppender outboxAppender;

    public AccountWriter(AccountRepository repository, OutboxAppender outboxAppender) {
        this.repository = repository;
        this.outboxAppender = outboxAppender;
    }

    @Transactional
    public Account openWallet(UUID userId, String currency) {
        Account account = new Account(
                UUID.randomUUID(), userId, currency, AccountType.WALLET, "", AccountState.ACTIVE);
        try {
            repository.saveAndFlush(account);
        } catch (DataIntegrityViolationException duplicate) {
            throw new DuplicateWalletException(currency);
        }
        outboxAppender.append(TOPIC, account.getId().toString(),
                new AccountOpenedV1(account.getId(), userId, currency, AccountType.WALLET.name()));
        return account;
    }
}
