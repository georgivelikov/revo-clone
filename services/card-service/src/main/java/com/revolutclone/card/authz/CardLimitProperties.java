package com.revolutclone.card.authz;

import com.revolutclone.card.domain.LimitPeriod;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How wide the two rolling windows are.
 *
 * <p><strong>Rolling, not calendar - written down here as plans/phase-05-cards.md's Risks section
 * demands.</strong> That note says: "'Daily' means a rolling 24 h or a calendar day in the user's
 * timezone - pick one, write it down, and test the boundary. Calendar-day is more intuitive for users;
 * rolling is easier to implement correctly." Rolling is the choice, for the reason the note gives and
 * for one more: a calendar day needs the holder's timezone, which {@code card-service} does not own and
 * would have to project from {@code identity-service} to answer a question with a two-to-five-second
 * network deadline on it. So:
 *
 * <ul>
 *   <li><strong>DAILY</strong> - the 24 hours immediately before the decision.</li>
 *   <li><strong>MONTHLY</strong> - the 30 days immediately before it. Thirty days and not
 *       "the same day-of-month last month", because a rolling window has to be a {@link Duration} to be
 *       a constant width: calendar months are 28 to 31 days long, so a month-shaped window would make
 *       February's limit tighter than January's without anybody having changed a limit.</li>
 * </ul>
 *
 * <p>The consequence a holder can observe, and the one the boundary tests state: spend leaves the window
 * gradually rather than all at once at midnight. A card that hit its daily limit at 14:00 can be used
 * again from 14:00 the next day, not from 00:00.
 *
 * <p>Both are properties rather than constants for the same reason the hold TTL is: task 05.8's
 * controllable clock exists so that windows can be crossed without waiting for them, and a test should
 * be able to state the width it is crossing.
 */
@ConfigurationProperties(prefix = "revolut.card.limits")
public record CardLimitProperties(Duration dailyWindow, Duration monthlyWindow) {

    /**
     * The window to look back over for a period. {@link LimitPeriod#PER_TRANSACTION} has none - it is a
     * ceiling on one amount and reads no history at all - and asking for it is a bug in the caller
     * rather than a zero-width window, because a zero-width window would quietly mean "unlimited".
     */
    public Duration windowFor(LimitPeriod period) {
        return switch (period) {
            case DAILY -> dailyWindow;
            case MONTHLY -> monthlyWindow;
            case PER_TRANSACTION -> throw new IllegalArgumentException(
                    "PER_TRANSACTION is a ceiling on a single amount, not a rolling window");
        };
    }
}
