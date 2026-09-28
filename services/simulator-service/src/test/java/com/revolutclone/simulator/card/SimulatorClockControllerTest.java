package com.revolutclone.simulator.card;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * plans/phase-05-cards.md's "controllable clock" endpoint. Needs no reachable card-service at all -
 * advancing the day with nothing pending never calls it - which is exactly why this is its own test
 * class rather than folded into {@code CardSpendControllerTest}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=127.0.0.1:9092",
        "revolut.simulator.card.base-url=http://card.invalid"
})
class SimulatorClockControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void advancingWithNothingPendingMovesTheDayAndSettlesNothing() throws Exception {
        mockMvc.perform(post("/simulator/clock/advance-day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day").value(1))
                .andExpect(jsonPath("$.settledCount").value(0));

        mockMvc.perform(post("/simulator/clock/advance-day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day").value(2))
                .andExpect(jsonPath("$.settledCount").value(0));
    }
}
