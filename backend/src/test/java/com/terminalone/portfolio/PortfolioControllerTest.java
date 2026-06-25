package com.terminalone.portfolio;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP-layer coverage of the manual-portfolio API (FR-1–FR-5): stock + option
 * CRUD and CSV import, all behind JWT (here satisfied with @WithMockUser).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@Transactional
class PortfolioControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void positionsRouteRequiresAuth() throws Exception {
        // The class-level @WithMockUser is ignored when we explicitly run anonymously.
        mockMvc.perform(get("/api/portfolio/positions").with(anonymous()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addStockThenListReturnsIt() throws Exception {
        mockMvc.perform(post("/api/portfolio/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"STOCK","symbol":"AAPL","quantity":100,
                                 "costBasis":150.25,"openedDate":"2026-01-15"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.symbol").value("AAPL"));

        mockMvc.perform(get("/api/portfolio/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stocks[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$.stocks[0].kind").value("STOCK"));
    }

    @Test
    void addEditAndDeleteAnOptionPosition() throws Exception {
        String id = mockMvc.perform(post("/api/portfolio/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"OPTION","symbol":"TSLA","optionType":"PUT","strike":200,
                                 "expiry":"2026-09-19","quantity":1,"costBasis":5.00,"side":"SHORT"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.optionType").value("PUT"))
                .andExpect(jsonPath("$.side").value("SHORT"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1");

        // Edit: bump the quantity
        mockMvc.perform(put("/api/portfolio/positions/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"OPTION","symbol":"TSLA","optionType":"PUT","strike":200,
                                 "expiry":"2026-09-19","quantity":3,"costBasis":5.00,"side":"SHORT"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(3));

        // Delete
        mockMvc.perform(delete("/api/portfolio/positions/" + id).param("kind", "OPTION"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/portfolio/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options").isEmpty());
    }

    @Test
    void editingAStockUpdatesItInPlace() throws Exception {
        String id = mockMvc.perform(post("/api/portfolio/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"STOCK","symbol":"NVDA","quantity":10,
                                 "costBasis":100,"openedDate":"2026-01-01"}"""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1");

        mockMvc.perform(put("/api/portfolio/positions/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"STOCK","symbol":"NVDA","quantity":25,
                                 "costBasis":105.50,"openedDate":"2026-01-01"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(25))
                .andExpect(jsonPath("$.costBasis").value(105.50));
    }

    @Test
    void missingRequiredFieldIs400() throws Exception {
        mockMvc.perform(post("/api/portfolio/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"STOCK","symbol":"AAPL","quantity":100}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void unknownKindIs400() throws Exception {
        mockMvc.perform(post("/api/portfolio/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"kind":"CRYPTO","symbol":"BTC","quantity":1,
                                 "costBasis":50000,"openedDate":"2026-01-01"}"""))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingAMissingPositionIs404() throws Exception {
        mockMvc.perform(delete("/api/portfolio/positions/99999").param("kind", "STOCK"))
                .andExpect(status().isNotFound());
    }

    @Test
    void csvImportPersistsValidRowsAndReportsErrors() throws Exception {
        String csv = """
                kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side
                STOCK,IBM,100,140.00,2026-01-10,,,,
                STOCK,GE,bad,12.00,2026-01-10,,,,
                OPTION,IBM,1,2.00,2026-01-10,CALL,150,2026-12-18,LONG
                """;

        mockMvc.perform(post("/api/portfolio/import")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(csv))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedStocks").value(1))
                .andExpect(jsonPath("$.importedOptions").value(1))
                .andExpect(jsonPath("$.errors[0].line").value(3));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor anonymous() {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous();
    }
}
