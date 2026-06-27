package com.terminalone.marketdata;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer coverage of the delayed market-data API (FR-6): JWT gating, JSON
 * shape of quotes/chains, and symbol normalization. The {@link MarketDataProvider}
 * is mocked, so no network/cache is exercised here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
class MarketDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MarketDataProvider provider;

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
}
