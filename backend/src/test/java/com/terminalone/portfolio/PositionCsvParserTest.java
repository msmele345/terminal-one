package com.terminalone.portfolio;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CSV import contract (FR-3): valid rows become positions; malformed rows
 * are reported with their line number, never silently dropped.
 *
 * Template header:
 * kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side
 */
class PositionCsvParserTest {

    private final PositionCsvParser parser = new PositionCsvParser();

    @Test
    void parsesASingleStockRow() {
        String csv = """
                kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side
                STOCK,AAPL,100,150.25,2026-01-15,,,,
                """;

        ImportResult result = parser.parse(csv);

        assertThat(result.errors()).isEmpty();
        assertThat(result.options()).isEmpty();
        assertThat(result.stocks()).hasSize(1);
        StockPosition stock = result.stocks().get(0);
        assertThat(stock.getSymbol()).isEqualTo("AAPL");
        assertThat(stock.getQuantity()).isEqualByComparingTo("100");
        assertThat(stock.getCostBasis()).isEqualByComparingTo("150.25");
        assertThat(stock.getOpenedDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void parsesASingleOptionRow() {
        String csv = """
                kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side
                OPTION,AAPL,2,3.50,2026-01-15,CALL,160,2026-06-19,LONG
                """;

        ImportResult result = parser.parse(csv);

        assertThat(result.errors()).isEmpty();
        assertThat(result.stocks()).isEmpty();
        assertThat(result.options()).hasSize(1);
        OptionPosition opt = result.options().get(0);
        assertThat(opt.getUnderlying()).isEqualTo("AAPL");
        assertThat(opt.getOptionType()).isEqualTo(OptionType.CALL);
        assertThat(opt.getStrike()).isEqualByComparingTo("160");
        assertThat(opt.getExpiry()).isEqualTo(LocalDate.of(2026, 6, 19));
        assertThat(opt.getQuantity()).isEqualByComparingTo("2");
        assertThat(opt.getCostBasis()).isEqualByComparingTo("3.50");
        assertThat(opt.getSide()).isEqualTo(PositionSide.LONG);
        assertThat(opt.getOpenedDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void reportsMalformedRowsWithLineNumbersWhileKeepingValidOnes() {
        String csv = """
                kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side
                STOCK,AAPL,100,150.25,2026-01-15,,,,
                STOCK,MSFT,notanumber,300.00,2026-02-01,,,,
                OPTION,TSLA,1,5.00,2026-03-01,PUT,200,2026-09-19,SHORT
                BANANA,XYZ,1,1.00,2026-01-01,,,,
                """;

        ImportResult result = parser.parse(csv);

        // Two good rows survive
        assertThat(result.stocks()).hasSize(1);
        assertThat(result.options()).hasSize(1);
        // Two bad rows reported, not dropped silently
        assertThat(result.errors()).hasSize(2);
        assertThat(result.errors().get(0).line()).isEqualTo(3); // bad quantity
        assertThat(result.errors().get(1).line()).isEqualTo(5); // unknown kind
        assertThat(result.errors().get(1).message()).contains("BANANA");
    }

    @Test
    void blankLinesAreSkippedNotReportedAsErrors() {
        String csv = """
                kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side
                STOCK,AAPL,100,150.25,2026-01-15,,,,

                STOCK,MSFT,50,300.00,2026-02-01,,,,
                """;

        ImportResult result = parser.parse(csv);

        assertThat(result.errors()).isEmpty();
        assertThat(result.stocks()).hasSize(2);
    }

    @Test
    void wrongColumnCountIsReported() {
        String csv = """
                kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side
                STOCK,AAPL,100
                """;

        ImportResult result = parser.parse(csv);

        assertThat(result.stocks()).isEmpty();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).message()).contains("columns");
    }
}
