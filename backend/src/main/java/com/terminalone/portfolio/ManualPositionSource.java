package com.terminalone.portfolio;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * V1 PositionSource: manual / CSV entry backed by Postgres (D2, FR-4). The CSV
 * import delegates parsing to {@link PositionCsvParser} and persists only the
 * valid rows, returning the parser's per-row errors unchanged.
 */
@Service
@Transactional
public class ManualPositionSource implements PositionSource {

    private final StockPositionRepository stockRepo;
    private final OptionPositionRepository optionRepo;
    private final PositionCsvParser csvParser;

    public ManualPositionSource(StockPositionRepository stockRepo,
                                OptionPositionRepository optionRepo,
                                PositionCsvParser csvParser) {
        this.stockRepo = stockRepo;
        this.optionRepo = optionRepo;
        this.csvParser = csvParser;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockPosition> listStocks() {
        return stockRepo.findAllByOrderBySymbolAsc();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OptionPosition> listOptions() {
        return optionRepo.findAllByOrderByUnderlyingAscExpiryAsc();
    }

    @Override
    public StockPosition addStock(StockPosition position) {
        return stockRepo.save(position);
    }

    @Override
    public OptionPosition addOption(OptionPosition position) {
        return optionRepo.save(position);
    }

    @Override
    public StockPosition updateStock(long id, StockPosition patch) {
        StockPosition existing = stockRepo.findById(id)
                .orElseThrow(() -> new PositionNotFoundException("stock", id));
        existing.setSymbol(patch.getSymbol());
        existing.setQuantity(patch.getQuantity());
        existing.setCostBasis(patch.getCostBasis());
        existing.setOpenedDate(patch.getOpenedDate());
        return stockRepo.save(existing);
    }

    @Override
    public OptionPosition updateOption(long id, OptionPosition patch) {
        OptionPosition existing = optionRepo.findById(id)
                .orElseThrow(() -> new PositionNotFoundException("option", id));
        existing.setUnderlying(patch.getUnderlying());
        existing.setOptionType(patch.getOptionType());
        existing.setStrike(patch.getStrike());
        existing.setExpiry(patch.getExpiry());
        existing.setQuantity(patch.getQuantity());
        existing.setCostBasis(patch.getCostBasis());
        existing.setSide(patch.getSide());
        existing.setOpenedDate(patch.getOpenedDate());
        return optionRepo.save(existing);
    }

    @Override
    public void deleteStock(long id) {
        if (!stockRepo.existsById(id)) {
            throw new PositionNotFoundException("stock", id);
        }
        stockRepo.deleteById(id);
    }

    @Override
    public void deleteOption(long id) {
        if (!optionRepo.existsById(id)) {
            throw new PositionNotFoundException("option", id);
        }
        optionRepo.deleteById(id);
    }

    @Override
    public ImportResult importCsv(String csv) {
        ImportResult parsed = csvParser.parse(csv);
        stockRepo.saveAll(parsed.stocks());
        optionRepo.saveAll(parsed.options());
        return parsed;
    }
}
