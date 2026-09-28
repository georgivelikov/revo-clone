package com.revolutclone.card.service;

import com.revolutclone.card.domain.Card;
import com.revolutclone.card.domain.CardLimit;
import com.revolutclone.card.domain.CardLimitRepository;
import com.revolutclone.card.domain.CardRepository;
import com.revolutclone.card.domain.LimitPeriod;
import com.revolutclone.card.readmodel.FundingAccountRepository;
import com.revolutclone.card.web.error.CardNotFoundException;
import com.revolutclone.card.web.error.WalletCurrencyUnknownException;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-05-cards.md task 9's limits endpoints, and {@link CardLimit}'s own invariant made
 * to hold by construction rather than by trusting a caller: "a limit is stored in the wallet's
 * currency, not the merchant's." A request here names only an amount - the currency is always
 * resolved from the card's own funding wallet, the same {@code funding_account} projection the
 * authorization chain reads, so a limit can no more be set in the wrong currency than
 * {@code CardLimitEvaluator} can compare one that already is.
 */
@Component
public class CardLimitService {

    private final CardRepository cards;
    private final CardLimitRepository limits;
    private final FundingAccountRepository fundingAccounts;

    public CardLimitService(CardRepository cards, CardLimitRepository limits,
            FundingAccountRepository fundingAccounts) {
        this.cards = cards;
        this.limits = limits;
        this.fundingAccounts = fundingAccounts;
    }

    @Transactional(readOnly = true)
    public List<CardLimit> list(UUID cardId, UUID userId) {
        ownedCard(cardId, userId);
        return limits.findByCardId(cardId).stream().sorted(Comparator.comparing(CardLimit::getPeriod)).toList();
    }

    @Transactional
    public CardLimit upsert(UUID cardId, UUID userId, LimitPeriod period, long amountMinor) {
        Card card = ownedCard(cardId, userId);
        String currency = fundingAccounts.findById(card.getAccountId())
                .map(account -> account.getCurrency())
                .orElseThrow(() -> new WalletCurrencyUnknownException(card.getAccountId()));

        return limits.save(new CardLimit(cardId, period, amountMinor, currency));
    }

    @Transactional
    public void remove(UUID cardId, UUID userId, LimitPeriod period) {
        ownedCard(cardId, userId);
        limits.deleteById(new CardLimit.Key(cardId, period));
    }

    private Card ownedCard(UUID cardId, UUID userId) {
        return cards.findByIdAndUserId(cardId, userId).orElseThrow(() -> new CardNotFoundException(cardId));
    }
}
