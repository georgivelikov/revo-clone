package com.revolutclone.payment;

import com.revolutclone.payment.domain.Payment;
import com.revolutclone.payment.domain.PaymentRepository;
import com.revolutclone.payment.readmodel.AccountView;
import com.revolutclone.payment.readmodel.AccountViewRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Both annotations are required here, not optional: common-messaging's own
 * {@code MessagingAutoConfiguration} declares its own {@code @EntityScan} and
 * {@code @EnableJpaRepositories}, scoped to its own outbox/idempotent packages. Once either
 * annotation appears anywhere in the context, Boot's default auto-detection backs off entirely
 * for the whole context, not just common-messaging's packages - see
 * docs/spring-boot-4-notes.md and the identical situation in identity-service,
 * account-service and ledger-service.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = {Payment.class, AccountView.class})
@EnableJpaRepositories(basePackageClasses = {PaymentRepository.class, AccountViewRepository.class})
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
