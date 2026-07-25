package com.terminalone.engine;

import com.terminalone.engine.config.EngineConfigSeeder;
import com.terminalone.ledger.PaperTrade;
import com.terminalone.ledger.PaperTradeRepository;
import com.terminalone.ledger.TakenPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 8 AC3 (FR-18, D14): "mark as taken" promotes a recommendation to
 * {@code taken_positions} with a manually entered fill price, flips its status
 * to TAKEN (reflected in the ledger), and rejects re-takes / unknown ids.
 * Lives in the engine package because {@link Recommendation} construction is
 * deliberately package-private (same precedent as LedgerControllerTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@Transactional
class RecommendationControllerTest {

    private static final Instant OPENED_AT = Instant.parse("2026-06-15T20:30:00Z");
    private static final LocalDate EXPIRY = LocalDate.of(2026, 8, 21);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EngineConfigSeeder seeder;

    @Autowired
    private RecommendationRepository recommendations;

    @Autowired
    private PaperTradeRepository paperTrades;

    @Autowired
    private TakenPositionRepository takenPositions;

    @BeforeEach
    void seedConfig() {
        seeder.run();
    }

    @Test
    void marksARecommendationAsTakenWithAManualFillPriceAndReflectsItAsARealPosition() throws Exception {
        Recommendation rec = recommendations.save(recommendation("AAPL", 3.50));

        mockMvc.perform(post("/api/recommendations/{id}/take", rec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fillPrice\":3.65}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendationId").value(rec.getId()))
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.strategy").value("BULL_CALL_DEBIT_SPREAD"))
                .andExpect(jsonPath("$.direction").value("BULLISH"))
                .andExpect(jsonPath("$.configVersion").value(1))
                .andExpect(jsonPath("$.contracts").value(2))
                .andExpect(jsonPath("$.fillPrice").value(3.65))
                .andExpect(jsonPath("$.takenAt").isNotEmpty());

        // Reflected as a real taken position, and the recommendation flipped to TAKEN.
        assertThat(takenPositions.findByRecommendation_Id(rec.getId())).isPresent();
        assertThat(recommendations.findById(rec.getId()).orElseThrow().getStatus())
                .isEqualTo(RecommendationStatus.TAKEN);
    }

    @Test
    void takenStatusIsReflectedInTheLedger() throws Exception {
        Recommendation rec = recommendations.save(recommendation("MSFT", 1.20));
        paperTrades.save(PaperTrade.fromRecommendation(rec, OPENED_AT));

        mockMvc.perform(post("/api/recommendations/{id}/take", rec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fillPrice\":1.25}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/ledger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].symbol").value("MSFT"))
                .andExpect(jsonPath("$.entries[0].recommendationStatus").value("TAKEN"));

        mockMvc.perform(get("/api/recommendations/taken"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("MSFT"))
                .andExpect(jsonPath("$[0].fillPrice").value(1.25));
    }

    @Test
    void rejectsRetakingAnAlreadyTakenRecommendationWith409() throws Exception {
        Recommendation rec = recommendations.save(recommendation("AAPL", 3.50));

        mockMvc.perform(post("/api/recommendations/{id}/take", rec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fillPrice\":3.65}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/recommendations/{id}/take", rec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fillPrice\":3.70}"))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectsAnUnknownRecommendationWith404() throws Exception {
        mockMvc.perform(post("/api/recommendations/{id}/take", 999_999)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fillPrice\":1.00}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAMissingFillPriceWith400() throws Exception {
        Recommendation rec = recommendations.save(recommendation("AAPL", 3.50));

        mockMvc.perform(post("/api/recommendations/{id}/take", rec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsAnonymousCallers() throws Exception {
        mockMvc.perform(post("/api/recommendations/{id}/take", 1)
                        .with(anonymous())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fillPrice\":1.00}"))
                .andExpect(status().isUnauthorized());
    }

    private Recommendation recommendation(String symbol, double entryDebit) {
        return new Recommendation(
                symbol, StrategyType.BULL_CALL_DEBIT_SPREAD,
                Direction.BULLISH, VolatilityRegime.NORMAL, 60,
                RecommendationStatus.PAPER, 1, EXPIRY,
                symbol + "-100C", 100.0, symbol + "-110C", 110.0,
                entryDebit, 0.72, 650.0, 350.0, 1.86, 85.0,
                2, "{}", OPENED_AT.minusSeconds(3600));
    }
}
