package com.revolutclone.card.service;

import com.revolutclone.card.crypto.CardPinHasher;
import com.revolutclone.card.crypto.PanCipher;
import com.revolutclone.card.crypto.PanHasher;
import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.domain.CardState;
import com.revolutclone.card.pan.PanGenerator;
import com.revolutclone.contracts.events.CardIssuedV1;
import com.revolutclone.messaging.api.OutboxAppender;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-05-cards.md task 2: "Issuance endpoint + CardIssuedV1". One transaction creates the
 * card row and appends the outbox event, same shape as every other write in this project
 * (CLAUDE.md hard rule 6) - never a template send straight to the broker.
 *
 * <p>{@code card.authorization.v1} is the only card topic plans/phase-00-foundations.md's
 * catalogue declares; it carries {@code CardIssuedV1} here for the same reason
 * {@code ledger.entry.v1} carries several distinct ledger event types - one topic per domain,
 * not one topic per event type. See {@link CardIssuedV1}'s own javadoc.
 */
@Component
public class CardIssuanceService {

    private static final String CARD_TOPIC = "card.authorization.v1";
    private static final int VALIDITY_YEARS = 3;

    private final CardRepository cards;
    private final PanGenerator panGenerator;
    private final PanHasher panHasher;
    private final PanCipher panCipher;
    private final CardPinHasher pinHasher;
    private final OutboxAppender outbox;

    public CardIssuanceService(
            CardRepository cards,
            PanGenerator panGenerator,
            PanHasher panHasher,
            PanCipher panCipher,
            CardPinHasher pinHasher,
            OutboxAppender outbox) {
        this.cards = cards;
        this.panGenerator = panGenerator;
        this.panHasher = panHasher;
        this.panCipher = panCipher;
        this.pinHasher = pinHasher;
        this.outbox = outbox;
    }

    @Transactional
    public Card issue(IssueCardCommand command) {
        String pan = panGenerator.generate();
        String cvv = panGenerator.generateCvv();
        YearMonth expiry = YearMonth.now().plusYears(VALIDITY_YEARS);
        String panLast4 = pan.substring(pan.length() - 4);

        Card card = new Card(
                UUID.randomUUID(),
                command.userId(),
                command.accountId(),
                panHasher.hash(pan),
                panLast4,
                panCipher.encrypt(pan),
                expiry.getMonthValue(),
                expiry.getYear(),
                panCipher.encrypt(cvv),
                pinHasher.hash(command.pin()),
                command.type(),
                CardState.ACTIVE,
                command.label());

        // Flush so the pan_hash unique index is checked here, in a state PanGenerator's own
        // caller can react to, rather than surfacing at commit after the outbox row was added.
        cards.saveAndFlush(card);

        outbox.append(CARD_TOPIC, card.getId().toString(), new CardIssuedV1(
                card.getId(),
                card.getUserId(),
                card.getAccountId(),
                card.getType().name(),
                card.getPanLast4(),
                card.getExpiryMonth(),
                card.getExpiryYear()));

        return card;
    }
}
