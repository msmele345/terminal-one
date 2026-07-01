package com.terminalone.portfolio;

import com.terminalone.marketdata.CallPut;
import com.terminalone.marketdata.MarketDataProvider;
import com.terminalone.marketdata.OptionChain;
import com.terminalone.marketdata.OptionContract;
import com.terminalone.marketdata.StockQuote;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer coverage of {@code GET /api/portfolio/summary} (FR-5): JWT gating,
 * delayed market value + unrealized P&amp;L JSON shape, portfolio totals, and the
 * unpriced-row fallback. Positions are persisted through the real stack; the
 * {@link MarketDataProvider} is mocked so no network/cache is touched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@Transactional
class PortfolioSummaryControllerTest {

    private static final Instant AS_OF = Instant.parse("2026-06-26T20:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MarketDataProvider provider;

    @Test
    void summaryRouteRequiresAuth() throws Exception {
        mockMvc.perform(get("/api/portfolio/summary").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pricesAStockPositionWithPnlAndTotals() throws Exception {
        addStock("AAPL", 100, "150.00");
        when(provider.getQuote("AAPL")).thenReturn(
                new StockQuote("AAPL", 164.9, 165.1, 165.0, AS_OF, true));

        mockMvc.perform(get("/api/portfolio/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.stocks[0].priced").value(true))
                .andExpect(jsonPath("$.stocks[0].markPrice").value(165.0))
                .andExpect(jsonPath("$.stocks[0].marketValue").value(16500.0))
                .andExpect(jsonPath("$.stocks[0].unrealizedPnl").value(1500.0))
                .andExpect(jsonPath("$.stocks[0].unrealizedPnlPct").value(10.0))
                .andExpect(jsonPath("$.totals.marketValue").value(16500.0))
                .andExpect(jsonPath("$.totals.unrealizedPnl").value(1500.0))
                .andExpect(jsonPath("$.totals.unrealizedPnlPct").value(10.0))
                .andExpect(jsonPath("$.delayed").value(true))
                .andExpect(jsonPath("$.unpriced").value(0));
    }

    @Test
    void pricesAnOptionLegFromTheChain() throws Exception {
        addOption("TSLA", "CALL", "200", "2026-09-18", 2, "5.00", "LONG");
        when(provider.getChain("TSLA")).thenReturn(new OptionChain(
                "TSLA", 205.0, AS_OF, false, List.of(
                new OptionContract("TSLA260918C00200000", CallPut.CALL, 200.0,
                        LocalDate.of(2026, 9, 18), 6.0, 7.0, 500))));

        mockMvc.perform(get("/api/portfolio/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].underlying").value("TSLA"))
                .andExpect(jsonPath("$.options[0].priced").value(true))
                .andExpect(jsonPath("$.options[0].markPrice").value(6.5))
                .andExpect(jsonPath("$.options[0].marketValue").value(1300.0))
                .andExpect(jsonPath("$.options[0].unrealizedPnl").value(300.0))
                .andExpect(jsonPath("$.totals.unrealizedPnl").value(300.0));
    }

    @Test
    void unpriceableSymbolRendersAsUnpricedRow() throws Exception {
        addStock("ZZZZ", 10, "20.00");
        when(provider.getQuote(anyString())).thenReturn(null);

        mockMvc.perform(get("/api/portfolio/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].priced").value(false))
                .andExpect(jsonPath("$.stocks[0].marketValue").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.unpriced").value(1))
                .andExpect(jsonPath("$.totals.marketValue").value(0.0));
    }

    private void addStock(String symbol, int qty, String cost) throws Exception {
        mockMvc.perform(post("/api/portfolio/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"STOCK","symbol":"%s","quantity":%d,
                                 "costBasis":%s,"openedDate":"2026-01-15"}""".formatted(symbol, qty, cost)))
                .andExpect(status().isCreated());
    }

    private void addOption(String underlying, String type, String strike, String expiry,
                           int qty, String premium, String side) throws Exception {
        mockMvc.perform(post("/api/portfolio/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"OPTION","symbol":"%s","optionType":"%s","strike":%s,
                                 "expiry":"%s","quantity":%d,"costBasis":%s,"side":"%s"}"""
                                .formatted(underlying, type, strike, expiry, qty, premium, side)))
                .andExpect(status().isCreated());
    }
}
