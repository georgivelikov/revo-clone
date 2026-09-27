package com.revolutclone.card.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.revolutclone.card.crypto.PanCipher;
import com.revolutclone.card.crypto.PanHasher;
import com.revolutclone.card.pan.PanGenerator;
import com.revolutclone.card.testsupport.NoOpJwtDecoderTestConfig;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * plans/phase-05-cards.md's Tests table: "Auth resolves the right card by PAN hash; two cards
 * sharing last4 and expiry both issue and both resolve correctly." {@code pan_hash}, not
 * {@code pan_last4}, is the authorization flow's only lookup key - see Card's javadoc and the
 * migration's own comment - precisely because last4 (10,000 possible values, global across
 * every user) is nowhere near unique enough on its own.
 */
@Testcontainers
@SpringBootTest
@Import(NoOpJwtDecoderTestConfig.class)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://example.invalid/realms/test",
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        // No real broker in this test - the consumer would otherwise spend the whole test
        // retrying a connection nothing is listening on.
        "spring.kafka.listener.auto-startup=false",
        "revolut.messaging.outbox.poll-delay-ms=300000"
})
class CardLookupByPanHashTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void cryptoKeys(DynamicPropertyRegistry registry) {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        registry.add("revolut.card.crypto.encryption-key", () -> key);
        registry.add("revolut.card.crypto.pan-hash-key", () -> key);
    }

    @Autowired
    private CardRepository cardRepository;

    @Autowired
    private PanHasher panHasher;

    @Autowired
    private PanCipher panCipher;

    @Test
    void resolvesTheRightCardByPanHashEvenWhenTwoCardsShareLast4AndExpiry() {
        String last4 = "4242";
        String panA = panWithLast4(last4);
        String panB = panWithLast4(last4);
        assertThat(panA).isNotEqualTo(panB);
        assertThat(PanGenerator.isValidLuhn(panA)).isTrue();
        assertThat(PanGenerator.isValidLuhn(panB)).isTrue();

        Card cardA = newCard(panA, last4, 12, 2030);
        Card cardB = newCard(panB, last4, 12, 2030);
        cardRepository.saveAndFlush(cardA);
        cardRepository.saveAndFlush(cardB);

        Optional<Card> foundA = cardRepository.findByPanHash(panHasher.hash(panA));
        Optional<Card> foundB = cardRepository.findByPanHash(panHasher.hash(panB));

        assertThat(foundA).isPresent();
        assertThat(foundB).isPresent();
        assertThat(foundA.get().getId()).isEqualTo(cardA.getId());
        assertThat(foundB.get().getId()).isEqualTo(cardB.getId());
        assertThat(foundA.get().getId()).isNotEqualTo(foundB.get().getId());
        assertThat(foundA.get().getPanLast4()).isEqualTo(last4);
        assertThat(foundB.get().getPanLast4()).isEqualTo(last4);
    }

    @Test
    void panHashForAnUnissuedCardResolvesToNothing() {
        String neverIssued = new PanGenerator().generate();

        assertThat(cardRepository.findByPanHash(panHasher.hash(neverIssued))).isEmpty();
    }

    private Card newCard(String pan, String last4, int expiryMonth, int expiryYear) {
        return new Card(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                panHasher.hash(pan),
                last4,
                panCipher.encrypt(pan),
                expiryMonth,
                expiryYear,
                panCipher.encrypt("123"),
                CardType.VIRTUAL,
                CardState.ACTIVE,
                null);
    }

    /**
     * Builds a second, genuinely different, Luhn-valid PAN under {@link PanGenerator#TEST_BIN}
     * that happens to end in the given last4 - the fixture two real cards issued independently
     * could, in principle, collide on (last4, expiry) the same way. Everything but one digit is
     * random; that one digit (the position immediately left of last4, which the standard Luhn
     * weighting never doubles at this PAN length) is solved for so the whole number still
     * checksums.
     */
    private static String panWithLast4(String last4) {
        SecureRandom random = new SecureRandom();
        int length = 16;
        int[] digits = new int[length];
        String bin = PanGenerator.TEST_BIN;
        for (int i = 0; i < bin.length(); i++) {
            digits[i] = bin.charAt(i) - '0';
        }
        int adjustableIndex = length - 5;
        for (int i = bin.length(); i < adjustableIndex; i++) {
            digits[i] = random.nextInt(10);
        }
        for (int i = 0; i < 4; i++) {
            digits[length - 4 + i] = last4.charAt(i) - '0';
        }

        int sum = 0;
        for (int i = 0; i < length; i++) {
            if (i == adjustableIndex) {
                continue;
            }
            sum += isDoubledAt(i, length) ? doubleDigit(digits[i]) : digits[i];
        }
        // The digit immediately left of last4 sits at an odd position from the right (5th) for a
        // 16-digit PAN, so Luhn never doubles it - its contribution to the checksum is its raw
        // value, which means any residue 0-9 is directly reachable.
        digits[adjustableIndex] = (10 - (sum % 10)) % 10;

        StringBuilder sb = new StringBuilder(length);
        for (int digit : digits) {
            sb.append((char) ('0' + digit));
        }
        return sb.toString();
    }

    private static boolean isDoubledAt(int index, int length) {
        int positionFromRight = length - index;
        return positionFromRight % 2 == 0;
    }

    private static int doubleDigit(int digit) {
        int doubled = digit * 2;
        return doubled > 9 ? doubled - 9 : doubled;
    }
}
