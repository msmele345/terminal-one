package com.terminalone.portfolio;

import java.util.List;

/**
 * The portfolio-ingestion seam (D2, FR-4). V1 wires exactly one implementation,
 * {@link ManualPositionSource}; a {@code BrokeragePositionSource} arrives in V2
 * behind this same interface. All position reads/writes flow through here.
 */
public interface PositionSource {

    List<StockPosition> listStocks();

    List<OptionPosition> listOptions();

    StockPosition addStock(StockPosition position);

    OptionPosition addOption(OptionPosition position);

    /** Applies the mutable fields of {@code patch} onto the stored stock position. */
    StockPosition updateStock(long id, StockPosition patch);

    /** Applies the mutable fields of {@code patch} onto the stored option position. */
    OptionPosition updateOption(long id, OptionPosition patch);

    void deleteStock(long id);

    void deleteOption(long id);

    /** Parses + persists the valid rows of a positions CSV, returning per-row errors. */
    ImportResult importCsv(String csv);
}
