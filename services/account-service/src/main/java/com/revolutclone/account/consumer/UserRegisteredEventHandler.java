package com.revolutclone.account.consumer;

import com.revolutclone.account.service.AccountWriter;
import com.revolutclone.contracts.EventEnvelope;
import com.revolutclone.contracts.events.UserRegisteredV1;
import com.revolutclone.messaging.api.EventHandler;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * plans/phase-01-identity-account-gateway.md's account-service design detail: "On
 * UserRegisteredV1, open three WALLET accounts - EUR, GBP, USD - in one transaction". The three
 * {@link AccountWriter#openWallet} calls join this method's own transaction (Spring's default
 * REQUIRED propagation), so either all three wallets and their three outbox rows land, or none
 * do. Redelivery protection lives one layer up, in the {@code IdempotentHandler} this bean is
 * wrapped by (see {@link UserRegisteredKafkaListener}) - this class only ever runs once per
 * distinct {@code eventId}.
 */
@Component
public class UserRegisteredEventHandler implements EventHandler<UserRegisteredV1> {

    private static final List<String> STARTER_CURRENCIES = List.of("EUR", "GBP", "USD");

    private final AccountWriter accountWriter;

    public UserRegisteredEventHandler(AccountWriter accountWriter) {
        this.accountWriter = accountWriter;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope<UserRegisteredV1> envelope) {
        UserRegisteredV1 event = envelope.payload();
        for (String currency : STARTER_CURRENCIES) {
            accountWriter.openWallet(event.userId(), currency);
        }
    }
}
