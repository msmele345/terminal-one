package com.terminalone.portfolio;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural coverage of the V1 PositionSource adapter (FR-4): CRUD for stock and
 * option positions plus CSV import, exercised against a real (H2) persistence layer.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ManualPositionSourceTest {

    @Autowired
    private StockPositionRepository stockRepo;

    @Autowired
    private OptionPositionRepository optionRepo;

    private PositionSource source;

    @BeforeEach
    void setUp() {
        source = new ManualPositionSource(stockRepo, optionRepo, new PositionCsvParser());
    }

    private static StockPosition aapl() {
        return new StockPosition("AAPL", new BigDecimal("100"), new BigDecimal("150.25"),
                LocalDate.of(2026, 1, 15));
    }

    private static OptionPosition aaplCall() {
        return new OptionPosition("AAPL", OptionType.CALL, new BigDecimal("160"),
                LocalDate.of(2026, 6, 19), new BigDecimal("2"), new BigDecimal("3.50"),
                PositionSide.LONG, LocalDate.of(2026, 1, 15));
    }

    @Test
    void addStockThenListReturnsIt() {
        StockPosition saved = source.addStock(aapl());

        assertThat(saved.getId()).isNotNull();
        assertThat(source.listStocks()).extracting(StockPosition::getSymbol).containsExactly("AAPL");
    }

    @Test
    void addOptionThenListReturnsIt() {
        OptionPosition saved = source.addOption(aaplCall());

        assertThat(saved.getId()).isNotNull();
        assertThat(source.listOptions()).hasSize(1);
        assertThat(source.listOptions().get(0).getOptionType()).isEqualTo(OptionType.CALL);
    }

    @Test
    void updateStockMutatesPersistedFields() {
        StockPosition saved = source.addStock(aapl());

        StockPosition patch = new StockPosition("AAPL", new BigDecimal("150"),
                new BigDecimal("151.00"), LocalDate.of(2026, 1, 20));
        StockPosition updated = source.updateStock(saved.getId(), patch);

        assertThat(updated.getQuantity()).isEqualByComparingTo("150");
        assertThat(updated.getCostBasis()).isEqualByComparingTo("151.00");
        assertThat(source.listStocks()).hasSize(1);
        assertThat(source.listStocks().get(0).getQuantity()).isEqualByComparingTo("150");
    }

    @Test
    void updateMissingStockThrowsNotFound() {
        assertThatThrownBy(() -> source.updateStock(999L, aapl()))
                .isInstanceOf(PositionNotFoundException.class);
    }

    @Test
    void deleteStockRemovesIt() {
        StockPosition saved = source.addStock(aapl());

        source.deleteStock(saved.getId());

        assertThat(source.listStocks()).isEmpty();
    }

    @Test
    void deleteMissingOptionThrowsNotFound() {
        assertThatThrownBy(() -> source.deleteOption(999L))
                .isInstanceOf(PositionNotFoundException.class);
    }

    @Test
    void importPersistsValidRowsAndReportsErrors() {
        String csv = """
                kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side
                STOCK,AAPL,100,150.25,2026-01-15,,,,
                STOCK,MSFT,bad,300.00,2026-02-01,,,,
                OPTION,TSLA,1,5.00,2026-03-01,PUT,200,2026-09-19,SHORT
                """;

        ImportResult result = source.importCsv(csv);

        assertThat(result.errors()).hasSize(1);
        assertThat(source.listStocks()).hasSize(1);
        assertThat(source.listOptions()).hasSize(1);
    }
}
