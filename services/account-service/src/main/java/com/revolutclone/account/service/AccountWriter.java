package com.revolutclone.account.service;

import com.revolutclone.account.domain.Account;
import com.revolutclone.account.domain.AccountRepository;
import com.revolutclone.account.domain.AccountState;
import com.revolutclone.account.domain.AccountType;
import com.revolutclone.account.error.DuplicateVaultException;
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
 *
 * <p>{@link #openVault} (task 06.6) is the same shape for a {@code VAULT}-type account, named
 * rather than always {@code ""} - exactly the case {@code V3__account.sql}'s own comment
 * anticipated: "wallets always use ''; vaults (Phase 06) will have real names." Called from
 * {@code InternalVaultAccountController}, not from a live user request - analytics-service owns
 * the {@code vault} row but not the account behind it (plans/phase-06-vaults-analytics.md's
 * "Vaults" section), so it opens the account here and stores the id it gets back.
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

    /**
     * Opens a {@code VAULT}-type account, named rather than always {@code ""} - see this class's
     * own javadoc. {@code unique(user_id, currency, type, name)} is what makes a second vault
     * with the same name and currency for the same user a {@link DuplicateVaultException} rather
     * than a silent duplicate.
     */
    @Transactional
    public Account openVault(UUID userId, String currency, String name) {
        Account account = new Account(
                UUID.randomUUID(), userId, currency, AccountType.VAULT, name, AccountState.ACTIVE);
        try {
            repository.saveAndFlush(account);
        } catch (DataIntegrityViolationException duplicate) {
            throw new DuplicateVaultException(currency, name);
        }
        outboxAppender.append(TOPIC, account.getId().toString(),
                new AccountOpenedV1(account.getId(), userId, currency, AccountType.VAULT.name()));
        return account;
    }
}
