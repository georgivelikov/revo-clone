package com.revolutclone.ledger.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, UUID> {

    /**
     * plans/phase-02-ledger.md's ### Reconciliation query, verbatim - one of the four places in
     * this project native Postgres SQL is explicitly sanctioned (see the phase's Portability
     * boundary table). Compares against {@code available + reserved}, never {@code available}
     * alone: a hold moves money between the two columns with no posting, so {@code available} on
     * its own drifts from the posting sum by the amount of every live reservation, and comparing
     * against it alone would flag every account with an active hold as "drifted" when it is not.
     */
    @Query(nativeQuery = true, value = """
            select la.id as "accountId",
                   coalesce(sum(case when p.direction = 'CREDIT' then p.amount_minor else -p.amount_minor end), 0)
                       as "computedMinor",
                   ab.available_minor + ab.reserved_minor as "cachedMinor",
                   ab.currency as "currency"
            from ledger_account la
            join account_balance ab on ab.ledger_account_id = la.id
            left join posting p on p.ledger_account_id = la.id
            group by la.id, ab.available_minor, ab.reserved_minor, ab.currency
            having coalesce(sum(case when p.direction = 'CREDIT' then p.amount_minor else -p.amount_minor end), 0)
                   <> ab.available_minor + ab.reserved_minor
            """)
    List<DriftRow> findDriftedAccounts();

    /** Flat projection for {@link #findDriftedAccounts()} - one row per account whose cached total disagrees. */
    interface DriftRow {
        UUID getAccountId();

        long getComputedMinor();

        long getCachedMinor();

        String getCurrency();
    }
}
