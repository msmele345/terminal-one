package com.terminalone.portfolio;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the documented positions CSV template into draft positions (FR-3).
 *
 * Pure (no persistence): produces unsaved entities plus a list of per-line errors.
 * Template header (order-sensitive):
 * {@code kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side}
 */
@Component
public class PositionCsvParser {

    private static final int COLUMNS = 9;

    public ImportResult parse(String csv) {
        List<StockPosition> stocks = new ArrayList<>();
        List<OptionPosition> options = new ArrayList<>();
        List<ImportResult.RowError> errors = new ArrayList<>();

        String[] lines = csv.split("\n", -1);
        for (int i = 1; i < lines.length; i++) { // line 0 is the header
            String raw = lines[i].strip();
            if (raw.isEmpty()) {
                continue;
            }
            int lineNumber = i + 1; // 1-based, header is line 1
            String[] cells = raw.split(",", -1);
            try {
                parseRow(cells, stocks, options);
            } catch (RuntimeException e) {
                errors.add(new ImportResult.RowError(lineNumber, e.getMessage()));
            }
        }
        return new ImportResult(stocks, options, errors);
    }

    private void parseRow(String[] cells, List<StockPosition> stocks, List<OptionPosition> options) {
        if (cells.length != COLUMNS) {
            throw new IllegalArgumentException(
                    "expected " + COLUMNS + " columns but found " + cells.length);
        }
        String kind = cells[0].strip().toUpperCase();
        switch (kind) {
            case "STOCK" -> stocks.add(parseStock(cells));
            case "OPTION" -> options.add(parseOption(cells));
            default -> throw new IllegalArgumentException("unknown kind '" + cells[0].strip() + "'");
        }
    }

    private StockPosition parseStock(String[] c) {
        String symbol = c[1].strip().toUpperCase();
        BigDecimal quantity = new BigDecimal(c[2].strip());
        BigDecimal costBasis = new BigDecimal(c[3].strip());
        LocalDate openedDate = LocalDate.parse(c[4].strip());
        return new StockPosition(symbol, quantity, costBasis, openedDate);
    }

    private OptionPosition parseOption(String[] c) {
        String underlying = c[1].strip().toUpperCase();
        BigDecimal quantity = new BigDecimal(c[2].strip());
        BigDecimal costBasis = new BigDecimal(c[3].strip());
        LocalDate openedDate = c[4].strip().isEmpty() ? null : LocalDate.parse(c[4].strip());
        OptionType optionType = OptionType.valueOf(c[5].strip().toUpperCase());
        BigDecimal strike = new BigDecimal(c[6].strip());
        LocalDate expiry = LocalDate.parse(c[7].strip());
        PositionSide side = PositionSide.valueOf(c[8].strip().toUpperCase());
        return new OptionPosition(underlying, optionType, strike, expiry, quantity, costBasis, side, openedDate);
    }
}
