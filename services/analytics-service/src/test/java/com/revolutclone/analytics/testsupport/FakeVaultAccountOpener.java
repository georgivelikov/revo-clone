package com.revolutclone.analytics.testsupport;

import com.revolutclone.analytics.vault.VaultAccountOpener;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A fake rather than a real account-service, for the same reason card-service's
 * {@code FakeLedgerReservations} is one: {@code testcontainers-kafka} cannot start on this
 * machine (docs/spring-boot-4-notes.md), so no test here can run account-service alongside
 * analytics-service. account-service's own side of "open a VAULT account" is proven where it
 * lives - this fake exists only to let {@code VaultServiceTest}/{@code VaultTransferTest} assert
 * that analytics-service asks for the right thing: the right user, currency and name.
 */
public class FakeVaultAccountOpener implements VaultAccountOpener {

    private final List<Opened> calls = new ArrayList<>();

    @Override
    public synchronized UUID open(UUID userId, String currency, String name) {
        UUID accountId = UUID.randomUUID();
        calls.add(new Opened(userId, currency, name, accountId));
        return accountId;
    }

    public synchronized List<Opened> calls() {
        return List.copyOf(calls);
    }

    public synchronized void reset() {
        calls.clear();
    }

    public record Opened(UUID userId, String currency, String name, UUID accountId) {
    }
}
