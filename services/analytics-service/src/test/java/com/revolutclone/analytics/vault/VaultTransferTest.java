package com.revolutclone.analytics.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.revolutclone.analytics.payment.InitiatePaymentCommand;
import com.revolutclone.analytics.testsupport.FakeAnalyticsClientsTestConfig;
import com.revolutclone.analytics.testsupport.FakePaymentInitiationClient;
import com.revolutclone.analytics.testsupport.FakeVaultAccountOpener;
import com.revolutclone.analytics.testsupport.NoOpJwtDecoderTestConfig;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-06-vaults-analytics.md's named test for task 06.6: "Move to vault is a normal
 * ledger entry; zero-sum preserved." Zero-sum itself is payment-service's and ledger-service's
 * own tested responsibility ({@code ZeroSumAfterTransfersTest} and friends) - what this test
 * proves is the plumbing this task actually adds: that {@link VaultTransferService} builds the
 * right {@link InitiatePaymentCommand} for each direction (the right source/target account, the
 * right amount and currency, the right user), so that the ordinary, already-proven transfer path
 * is what actually moves the money. A fake stands in for payment-service - see
 * {@link FakePaymentInitiationClient}'s own javadoc for why - and a fake stands in for
 * account-service too, for {@link VaultService#create}'s own synchronous edge.
 */
@Testcontainers
@SpringBootTest
@Import({NoOpJwtDecoderTestConfig.class, FakeAnalyticsClientsTestConfig.class})
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000",
        "revolut.analytics.account.base-url=http://account.invalid",
        "revolut.analytics.payment.base-url=http://payment.invalid"
})
class VaultTransferTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired
    private VaultService vaultService;

    @Autowired
    private VaultTransferService transferService;

    @Autowired
    private VaultRepository vaults;

    @Autowired
    private FakeVaultAccountOpener accountOpener;

    @Autowired
    private FakePaymentInitiationClient paymentClient;

    @BeforeEach
    void resetState() {
        vaults.deleteAll();
        accountOpener.reset();
        paymentClient.reset();
    }

    @Test
    void creatingAVaultOpensTheBackingAccountOnAccountServiceFirst() {
        UUID userId = UUID.randomUUID();

        Vault vault = vaultService.create(userId, "Holiday", "EUR", 50_000L, true, UUID.randomUUID());

        assertThat(accountOpener.calls()).hasSize(1);
        FakeVaultAccountOpener.Opened opened = accountOpener.calls().getFirst();
        assertThat(opened.userId()).isEqualTo(userId);
        assertThat(opened.currency()).isEqualTo("EUR");
        assertThat(opened.name()).isEqualTo("Holiday");

        // The vault row is stored with exactly the account id account-service handed back.
        assertThat(vault.getAccountId()).isEqualTo(opened.accountId());
        assertThat(vaults.findById(vault.getId())).isPresent();
        assertThat(vault.getGoalMinor()).isEqualTo(50_000L);
        assertThat(vault.isRoundupEnabled()).isTrue();
    }

    @Test
    void moveInIsWalletToVault() {
        UUID userId = UUID.randomUUID();
        UUID walletAccountId = UUID.randomUUID();
        Vault vault = vaultService.create(userId, "Rainy day", "EUR", null, false, null);

        transferService.transfer(vault.getId(), userId, TransferDirection.IN, walletAccountId, 437L, "EUR", "key-1");

        assertThat(paymentClient.calls()).hasSize(1);
        InitiatePaymentCommand command = paymentClient.calls().getFirst();
        assertThat(command.userId()).isEqualTo(userId);
        assertThat(command.sourceAccountId()).isEqualTo(walletAccountId);
        assertThat(command.targetAccountId()).isEqualTo(vault.getAccountId());
        assertThat(command.amountMinor()).isEqualTo(437L);
        assertThat(command.currency()).isEqualTo("EUR");
        // Namespaced so an unrelated transfer reusing the same client-chosen key never collides
        // with this one in payment-service's own unique(initiator_user_id, idempotency_key).
        assertThat(command.idempotencyKey()).isEqualTo("vault:" + vault.getId() + ":key-1");
    }

    @Test
    void moveOutIsVaultToWallet() {
        UUID userId = UUID.randomUUID();
        UUID walletAccountId = UUID.randomUUID();
        Vault vault = vaultService.create(userId, "Rainy day", "GBP", null, false, null);

        transferService.transfer(vault.getId(), userId, TransferDirection.OUT, walletAccountId, 1_000L, "GBP",
                "key-2");

        InitiatePaymentCommand command = paymentClient.calls().getFirst();
        assertThat(command.sourceAccountId()).isEqualTo(vault.getAccountId());
        assertThat(command.targetAccountId()).isEqualTo(walletAccountId);
        assertThat(command.amountMinor()).isEqualTo(1_000L);
        assertThat(command.currency()).isEqualTo("GBP");
    }

    @Test
    void transferValidationLikeFundsAndCurrencyMatchIsNeverDuplicatedHere() {
        // No assertion beyond "this class compiles and defers to payment-service": the fake never
        // rejects anything, exactly as this task's own scope note requires - that validation is
        // payment-service's job, proven by payment-service's own tests, not re-proven here.
        UUID userId = UUID.randomUUID();
        Vault vault = vaultService.create(userId, "Rainy day", "EUR", null, false, null);

        transferService.transfer(vault.getId(), userId, TransferDirection.IN, UUID.randomUUID(),
                Long.MAX_VALUE, "USD", "key-3");

        assertThat(paymentClient.calls()).hasSize(1);
    }

    @Test
    void transferToSomeoneElsesVaultIsNotFound() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Vault vault = vaultService.create(owner, "Rainy day", "EUR", null, false, null);

        assertThatThrownBy(() -> transferService.transfer(vault.getId(), stranger, TransferDirection.IN,
                UUID.randomUUID(), 100L, "EUR", "key-4"))
                .isInstanceOf(VaultNotFoundException.class);
        assertThat(paymentClient.calls()).isEmpty();
    }

    @Test
    void transferToAnUnknownVaultIsNotFound() {
        assertThatThrownBy(() -> transferService.transfer(UUID.randomUUID(), UUID.randomUUID(), TransferDirection.IN,
                UUID.randomUUID(), 100L, "EUR", "key-5"))
                .isInstanceOf(VaultNotFoundException.class);
    }

    @Test
    void updateChangesOnlyGoalAndRoundupSettings() {
        UUID userId = UUID.randomUUID();
        Vault vault = vaultService.create(userId, "Rainy day", "EUR", 1_000L, false, null);
        UUID newCard = UUID.randomUUID();

        Vault updated = vaultService.update(vault.getId(), userId, 2_000L, true, newCard);

        assertThat(updated.getGoalMinor()).isEqualTo(2_000L);
        assertThat(updated.isRoundupEnabled()).isTrue();
        assertThat(updated.getRoundupSourceCardId()).isEqualTo(newCard);
        // Never touched by an update.
        assertThat(updated.getName()).isEqualTo("Rainy day");
        assertThat(updated.getCurrency()).isEqualTo("EUR");
        assertThat(updated.getAccountId()).isEqualTo(vault.getAccountId());
    }

    @Test
    void createAdoptsAnAccountOpenedEarlierWhoseLocalRowWasNeverWritten() {
        // account-service already has the VAULT account (remote open succeeded, local insert did
        // not), so open() 409s. Without adoption that name is unusable and the vault never shows.
        UUID userId = UUID.randomUUID();
        UUID orphanedAccountId = UUID.randomUUID();
        accountOpener.seedExisting(userId, "EUR", "Seed round-up vault", orphanedAccountId);

        Vault vault = vaultService.create(userId, "Seed round-up vault", "EUR", 500_000L, true, null);

        assertThat(vault.getAccountId()).isEqualTo(orphanedAccountId);
        assertThat(vaultService.list(userId)).extracting(Vault::getAccountId).containsExactly(orphanedAccountId);
    }

    @Test
    void createStillConflictsWhenTheExistingAccountAlreadyHasAVault() {
        UUID userId = UUID.randomUUID();
        Vault first = vaultService.create(userId, "Holiday", "EUR", null, false, null);
        accountOpener.seedExisting(userId, "EUR", "Holiday", first.getAccountId());

        assertThatThrownBy(() -> vaultService.create(userId, "Holiday", "EUR", null, false, null))
                .isInstanceOf(DuplicateVaultAccountException.class);
        assertThat(vaultService.list(userId)).hasSize(1);
    }
}
