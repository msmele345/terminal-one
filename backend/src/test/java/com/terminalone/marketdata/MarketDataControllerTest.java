package com.terminalone.marketdata;

import com.terminalone.marketdata.AtmIvRecorder.RecordResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer coverage of the delayed market-data API (FR-6): JWT gating, JSON
 * shape of quotes/chains, symbol normalization, and the ATM IV series endpoints
 * (AC5). The {@link MarketDataProvider} + {@link AtmIvRecorder} are mocked, so no
 * network/cache is exercised; the real {@link IvHistoryRepository} (H2) backs the
 * series read, with rollback per test via {@link Transactional}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@Transactional
class MarketDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IvHistoryRepository ivHistory;

    @MockitoBean
    private MarketDataProvider provider;

    @MockitoBean
    private AtmIvRecorder ivRecorder;

    @Test
    void quoteRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/marketdata/quote/AAPL").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsAQuote() throws Exception {
        when(provider.getQuote("AAPL")).thenReturn(
                new StockQuote("AAPL", 192.4, 192.6, 192.5, Instant.parse("2026-06-26T20:00:00Z"), true));

        mockMvc.perform(get("/api/marketdata/quote/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.bid").value(192.4))
                .andExpect(jsonPath("$.ask").value(192.6))
                .andExpect(jsonPath("$.delayed").value(true));
    }

    @Test
    void returnsAChainWithContracts() throws Exception {
        OptionContract call = new OptionContract(
                "AAPL260116C00190000", CallPut.CALL, 190.0, LocalDate.of(2026, 1, 16), 8.1, 8.4, 12000);
        when(provider.getChain("AAPL")).thenReturn(
                new OptionChain("AAPL", 192.5, Instant.parse("2026-06-26T20:00:00Z"), true, List.of(call)));

        mockMvc.perform(get("/api/marketdata/chain/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.underlying").value("AAPL"))
                .andExpect(jsonPath("$.delayed").value(true))
                .andExpect(jsonPath("$.contracts[0].optionSymbol").value("AAPL260116C00190000"))
                .andExpect(jsonPath("$.contracts[0].callPut").value("CALL"))
                .andExpect(jsonPath("$.contracts[0].strike").value(190.0))
                .andExpect(jsonPath("$.contracts[0].expiration").value("2026-01-16"))
                .andExpect(jsonPath("$.contracts[0].openInterest").value(12000));
    }

    @Test
    void normalizesSymbolToUppercase() throws Exception {
        when(provider.getQuote("AAPL")).thenReturn(
                new StockQuote("AAPL", 1.0, 1.0, 1.0, Instant.parse("2026-06-26T20:00:00Z"), false));

        mockMvc.perform(get("/api/marketdata/quote/aapl"))
                .andExpect(status().isOk());

        verify(provider).getQuote("AAPL");
    }

    @Test
    void historyRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/marketdata/history/AAPL").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsDailyHistory() throws Exception {
        PriceBar bar = new PriceBar(LocalDate.of(2026, 1, 13), 190.0, 192.0, 189.0, 191.5, 50_000_000L);
        when(provider.getDailyBars("AAPL")).thenReturn(
                new PriceHistory("AAPL", Instant.parse("2026-06-26T20:00:00Z"), true, List.of(bar)));

        mockMvc.perform(get("/api/marketdata/history/AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.delayed").value(true))
                .andExpect(jsonPath("$.bars[0].date").value("2026-01-13"))
                .andExpect(jsonPath("$.bars[0].open").value(190.0))
                .andExpect(jsonPath("$.bars[0].high").value(192.0))
                .andExpect(jsonPath("$.bars[0].low").value(189.0))
                .andExpect(jsonPath("$.bars[0].close").value(191.5))
                .andExpect(jsonPath("$.bars[0].volume").value(50_000_000L));
    }

    @Test
    void normalizesHistorySymbolToUppercase() throws Exception {
        when(provider.getDailyBars("AAPL")).thenReturn(
                new PriceHistory("AAPL", Instant.parse("2026-06-26T20:00:00Z"), false, List.of()));

        mockMvc.perform(get("/api/marketdata/history/aapl"))
                .andExpect(status().isOk());

        verify(provider).getDailyBars("AAPL");
    }

    @Test
    void ivHistoryRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/marketdata/iv-history/AAPL").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsIvHistorySeriesOldestFirst() throws Exception {
        Instant at = Instant.parse("2026-06-26T20:00:00Z");
        ivHistory.save(new IvHistory("AAPL", LocalDate.of(2026, 6, 30), 0.31, 100.0, 100.0, LocalDate.of(2026, 8, 21), at));
        ivHistory.save(new IvHistory("AAPL", LocalDate.of(2026, 6, 29), 0.28, 99.0, 100.0, LocalDate.of(2026, 8, 21), at));

        mockMvc.perform(get("/api/marketdata/iv-history/aapl"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].asOfDate").value("2026-06-29"))
                .andExpect(jsonPath("$[0].atmIv").value(0.28))
                .andExpect(jsonPath("$[1].asOfDate").value("2026-06-30"))
                .andExpect(jsonPath("$[1].atmIv").value(0.31));
    }

    @Test
    void runTriggersAccumulationAndReturnsTheSummary() throws Exception {
        when(ivRecorder.recordDailyAtmIv())
                .thenReturn(new RecordResult(LocalDate.of(2026, 7, 1), 2, List.of("ZZZZ")));

        mockMvc.perform(post("/api/marketdata/iv-history/run"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-07-01"))
                .andExpect(jsonPath("$.recorded").value(2))
                .andExpect(jsonPath("$.skipped[0]").value("ZZZZ"));

        verify(ivRecorder).recordDailyAtmIv();
    }
}
