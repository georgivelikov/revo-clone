package com.revolutclone.simulator.card;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * plans/phase-05-cards.md task 8's spend endpoints, exercised through the real Spring context with
 * {@link FakeCardNetworkPanClient} standing in for card-service - same testing style as every other
 * MockMvc test in this project (see {@code FakeCardNetworkPanClientTestConfig}'s javadoc). The
 * orchestration itself (merchant defaults, event shape, registry bookkeeping) is
 * {@code CardSpendServiceTest}'s job; this class is only about the HTTP surface: validation,
 * status codes, and problem-detail mapping.
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(FakeCardNetworkPanClientTestConfig.class)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "revolut.simulator.card.base-url=http://card.invalid"
})
class CardSpendControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeCardNetworkPanClient panClient;

    private UUID cardId;

    @BeforeEach
    void aCardTheNetworkKnowsAbout() {
        cardId = UUID.randomUUID();
        panClient.register(cardId, "4000000000001091");
    }

    @Test
    void aMissingCardIdIsRejectedAsBadRequest() throws Exception {
        mockMvc.perform(post("/simulator/cards/spend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.properties.fieldErrors.cardId").exists());
    }

    @Test
    void aFullySpecifiedSpendIsAcceptedAndEchoesEveryField() throws Exception {
        mockMvc.perform(post("/simulator/cards/spend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cardId":"%s","merchant":"Tesco","amountMinor":4250,"currency":"EUR"}
                                """.formatted(cardId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.networkRef").isString())
                .andExpect(jsonPath("$.cardId").value(cardId.toString()))
                .andExpect(jsonPath("$.merchantName").value("Tesco"))
                .andExpect(jsonPath("$.merchantMcc").value("5411"))
                .andExpect(jsonPath("$.amountMinor").value(4250))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.settleOnDay").value(1));
    }

    @Test
    void spendingOnAnUnknownCardIsNotFound() throws Exception {
        mockMvc.perform(post("/simulator/cards/spend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cardId":"%s","merchant":"Tesco","amountMinor":4250,"currency":"EUR"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://revolutclone.com/problems/card-not-found"));
    }

    @Test
    void aSpendBatchAcceptsAndReturnsTheRequestedCount() throws Exception {
        mockMvc.perform(post("/simulator/cards/spend/batch")
                        .queryParam("count", "5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cardId":"%s"}
                                """.formatted(cardId)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void reversingAnUnknownNetworkRefIsNotFound() throws Exception {
        mockMvc.perform(post("/simulator/cards/reverse/sim-" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(
                        "https://revolutclone.com/problems/unknown-network-ref"));
    }
}
