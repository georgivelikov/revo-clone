package com.revolutclone.fx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * No {@code @EntityScan}/{@code @EnableJpaRepositories} of its own yet - this service owns no
 * entity beyond common-messaging's shared outbox/idempotent tables (see
 * docs/spring-boot-4-notes.md for why every *other* service in this codebase needs that pair:
 * common-messaging's own annotations only back off Boot's default scanning for packages nobody
 * else registers). {@code FxRateCache} is a plain in-memory {@code @Component}, not JPA. Task
 * 04.4 adds the first fx-service-owned entity ({@code FxQuote}) and must add both annotations
 * here at that point, following identity-service/account-service/ledger-service/payment-service's
 * precedent.
 */
@SpringBootApplication
public class FxServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FxServiceApplication.class, args);
    }
}
