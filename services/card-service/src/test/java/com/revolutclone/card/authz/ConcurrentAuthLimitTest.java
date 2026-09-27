package com.revolutclone.card.authz;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.card.domain.AuthorizationState;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardAuthorization;
import com.revolutclone.card.domain.CardState;
import com.revolutclone.card.domain.DeclineReason;
import com.revolutclone.card.domain.LimitPeriod;
import com.revolutclone.card.pan.PanGenerator;
import com.revolutclone.card.testsupport.AuthorizationTestSupport;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * plans/phase-05-cards.md's Tests table: "Two simultaneous auths on one card cannot both pass a limit
 * only one should."
 *
 * <p>This is the test the phase writes its partition-key argument for: "Keyed by {@code cardId}, so two
 * taps on the same card are evaluated in order against the same limits. This is the clearest possible
 * justification for the partition-key rule from Phase 00 - with random partitioning, two simultaneous
 * auths could both pass a limit check that only one should."
 *
 * <p><strong>Why a real race and not a simulated one.</strong> The hazard is an interleaving: thread A
 * reads the rolling total, thread B reads the same total before A has committed its row, and both
 * conclude there is room. Nothing about either evaluation is wrong in isolation, which is exactly why a
 * test that called the evaluator twice in sequence - or stubbed a total - would pass against code that
 * has this bug. So these tests start several threads on one {@link CyclicBarrier} and drive the whole
 * chain through the same consumer entry point the broker would, then assert on the {@code
 * card_authorization} rows and the funds actually held.
 *
 * <p><strong>What is deliberately not asserted: which tap wins.</strong> The guarantee is "not both",
 * not "the first one submitted". Pinning the winner would be asserting a scheduling accident, and the
 * test would then fail for a reason that has nothing to do with limits.
 *
 * <p><strong>What makes a failure here unambiguous.</strong> Every wallet is funded far above the limit
 * under test, so an over-approval cannot be caught by an {@code INSUFFICIENT_FUNDS} decline instead - if
 * the lock were removed, the extra tap would be approved and the assertions would see two holds rather
 * than a second decline with a convenient reason. The fake ledger is synchronised for the same reason:
 * see {@code FakeLedgerReservations}.
 */
class ConcurrentAuthLimitTest extends AuthorizationTestSupport {

    /** 20.00 EUR. The daily limit is set to exactly this, so there is room for one tap and not two. */
    private static final long TAP_MINOR = 2_000L;

    @Test
    void twoSimultaneousTapsOnOneCardCannotBothPassALimitOnlyOneShouldPass() throws Exception {
        setLimit(LimitPeriod.DAILY, TAP_MINOR);

        race(List.of(
                () -> tap("net-race-a", pan, TAP_MINOR),
                () -> tap("net-race-b", pan, TAP_MINOR)));

        List<CardAuthorization> decided = authorizations.findAll();
        assertThat(decided).as("both taps were decided; neither was dropped").hasSize(2);

        assertThat(statesOf(decided))
                .as("one approval and one refusal, in whichever order the threads happened to arrive")
                .containsExactlyInAnyOrder(AuthorizationState.APPROVED, AuthorizationState.DECLINED);

        CardAuthorization refused = onlyOneIn(decided, AuthorizationState.DECLINED);
        assertThat(refused.getDeclineReason())
                .as("refused by the limit, not by anything that merely looks like one")
                .isEqualTo(DeclineReason.LIMIT_EXCEEDED);
        assertThat(refused.getReservationId()).isNull();

        // The money is the real assertion. Two approvals would have held 40.00 against a 20.00 ceiling,
        // and no amount of state bookkeeping would undo that.
        assertThat(ledger.holds()).hasSize(1);
        assertThat(ledger.availableMinor(ledgerAccountId)).isEqualTo(WALLET_BALANCE_MINOR - TAP_MINOR);
        assertThat(ledger.totalMinor(ledgerAccountId))
                .as("a hold moves nothing: total is untouched either way")
                .isEqualTo(WALLET_BALANCE_MINOR);
    }

    @Test
    void aBurstOfTapsApprovesExactlyAsManyAsTheLimitAllowsAndRefusesTheRest() throws Exception {
        // 10.00 each against a 40.00 daily ceiling: four fit, the other four must not. Eight racing
        // threads rather than two because a lost interleaving is a probability, not a certainty - with
        // eight contenders for four places, an evaluator that reads a stale total will overshoot on
        // essentially every run rather than occasionally.
        long tapMinor = 1_000L;
        int allowed = 4;
        int taps = 8;
        setLimit(LimitPeriod.DAILY, allowed * tapMinor);

        List<Runnable> burst = new ArrayList<>();
        for (int i = 0; i < taps; i++) {
            String networkRef = "net-burst-" + i;
            burst.add(() -> tap(networkRef, pan, tapMinor));
        }
        race(burst);

        List<CardAuthorization> decided = authorizations.findAll();
        assertThat(decided).hasSize(taps);
        assertThat(decided.stream().filter(a -> a.getState() == AuthorizationState.APPROVED).toList())
                .as("the ceiling is a ceiling: exactly four of eight, never five")
                .hasSize(allowed);
        assertThat(decided.stream()
                .filter(a -> a.getState() == AuthorizationState.DECLINED)
                .map(CardAuthorization::getDeclineReason)
                .distinct())
                .as("every refusal is a limit refusal - the wallet had 100.00 throughout")
                .containsExactly(DeclineReason.LIMIT_EXCEEDED);

        assertThat(ledger.holds()).hasSize(allowed);
        assertThat(ledger.availableMinor(ledgerAccountId))
                .as("the held total lands exactly on the ceiling, neither over nor under")
                .isEqualTo(WALLET_BALANCE_MINOR - allowed * tapMinor);
    }

    @Test
    void concurrentTapsOnTwoCardsAreEachEvaluatedAgainstTheirOwnWindow() throws Exception {
        // The other half of "per card": serialising the decision must not merge two cards' histories, and
        // striping the lock must not let one card's tap be refused because an unrelated card shared its
        // stripe. Each card has room for exactly one tap, so the correct answer is one approval each -
        // two in total, from four racing taps.
        String otherPan = new PanGenerator().generate();
        Card other = saveCard(otherPan, CardState.ACTIVE, YearMonth.now().plusYears(3));
        setLimit(LimitPeriod.DAILY, TAP_MINOR);
        setLimit(other.getId(), LimitPeriod.DAILY, TAP_MINOR);

        race(List.of(
                () -> tap("net-card1-a", pan, TAP_MINOR),
                () -> tap("net-card1-b", pan, TAP_MINOR),
                () -> tap("net-card2-a", otherPan, TAP_MINOR),
                () -> tap("net-card2-b", otherPan, TAP_MINOR)));

        assertThat(approvalsOn(card.getId())).hasSize(1);
        assertThat(approvalsOn(other.getId()))
                .as("the second card's limit is its own, not a share of the first card's")
                .hasSize(1);
        assertThat(ledger.holds()).hasSize(2);
    }

    /**
     * Runs every tap from one starting line and waits for all of them.
     *
     * <p>A {@link CyclicBarrier} rather than simply submitting the tasks: thread-pool submission is
     * staggered enough that the first tap can finish before the last one starts, which would quietly turn
     * a concurrency test into a sequential one that passes for the wrong reason.
     *
     * <p>Failures are re-thrown, never counted. An exception inside a tap means the decision chain broke -
     * and a test that let it stand in for "the tap was refused" would report the strongest possible green
     * for the weakest possible reason.
     */
    private void race(List<Runnable> taps) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(taps.size());
        List<Future<?>> outcomes = new ArrayList<>();

        // Closing an ExecutorService waits for every task to finish, so the assertions below cannot run
        // against a decision still in flight.
        try (ExecutorService pool = Executors.newFixedThreadPool(taps.size())) {
            for (Runnable tap : taps) {
                Callable<Void> atTheSameInstant = () -> {
                    startLine.await();
                    tap.run();
                    return null;
                };
                outcomes.add(pool.submit(atTheSameInstant));
            }
        }
        for (Future<?> outcome : outcomes) {
            outcome.get();
        }
    }

    private List<AuthorizationState> statesOf(List<CardAuthorization> decided) {
        return decided.stream().map(CardAuthorization::getState).toList();
    }

    private List<CardAuthorization> approvalsOn(UUID cardId) {
        return authorizations.findAll().stream()
                .filter(a -> a.getCardId().equals(cardId))
                .filter(a -> a.getState() == AuthorizationState.APPROVED)
                .toList();
    }

    private CardAuthorization onlyOneIn(List<CardAuthorization> decided, AuthorizationState state) {
        List<CardAuthorization> matching = decided.stream().filter(a -> a.getState() == state).toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }
}
