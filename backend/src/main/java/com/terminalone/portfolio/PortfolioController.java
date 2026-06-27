package com.terminalone.portfolio;

import com.terminalone.portfolio.dto.PortfolioSummaryResponses.PortfolioSummary;
import com.terminalone.portfolio.dto.PositionRequest;
import com.terminalone.portfolio.dto.PositionResponses.ImportResponse;
import com.terminalone.portfolio.dto.PositionResponses.OptionPositionResponse;
import com.terminalone.portfolio.dto.PositionResponses.PositionsResponse;
import com.terminalone.portfolio.dto.PositionResponses.StockPositionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Manual portfolio API (FR-1–FR-5). All reads/writes go through {@link PositionSource}
 * (D2) — the manual adapter is the only implementation wired in V1. The unified
 * {@code positions} route carries a {@code kind} discriminator (STOCK | OPTION).
 */
@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PositionSource positions;
    private final PortfolioSummaryService summaryService;

    public PortfolioController(PositionSource positions, PortfolioSummaryService summaryService) {
        this.positions = positions;
        this.summaryService = summaryService;
    }

    @GetMapping("/positions")
    public PositionsResponse list() {
        List<StockPositionResponse> stocks = positions.listStocks().stream()
                .map(StockPositionResponse::from).toList();
        List<OptionPositionResponse> options = positions.listOptions().stream()
                .map(OptionPositionResponse::from).toList();
        return new PositionsResponse(stocks, options);
    }

    /** Positions with delayed market value + unrealized P&amp;L ($/%) and portfolio totals (FR-5). */
    @GetMapping("/summary")
    public PortfolioSummary summary() {
        return summaryService.summarize();
    }

    @PostMapping("/positions")
    public ResponseEntity<?> create(@RequestBody PositionRequest request) {
        Kind kind = Kind.from(request.kind());
        if (kind == Kind.STOCK) {
            StockPosition saved = positions.addStock(PositionMapper.toStock(request));
            return ResponseEntity.status(HttpStatus.CREATED).body(StockPositionResponse.from(saved));
        }
        OptionPosition saved = positions.addOption(PositionMapper.toOption(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(OptionPositionResponse.from(saved));
    }

    @PutMapping("/positions/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody PositionRequest request) {
        Kind kind = Kind.from(request.kind());
        if (kind == Kind.STOCK) {
            StockPosition saved = positions.updateStock(id, PositionMapper.toStock(request));
            return ResponseEntity.ok(StockPositionResponse.from(saved));
        }
        OptionPosition saved = positions.updateOption(id, PositionMapper.toOption(request));
        return ResponseEntity.ok(OptionPositionResponse.from(saved));
    }

    @DeleteMapping("/positions/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, @RequestParam String kind) {
        if (Kind.from(kind) == Kind.STOCK) {
            positions.deleteStock(id);
        } else {
            positions.deleteOption(id);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/import")
    public ImportResponse importCsv(@RequestBody String csv) {
        ImportResult result = positions.importCsv(csv);
        List<ImportResponse.ImportError> errors = result.errors().stream()
                .map(e -> new ImportResponse.ImportError(e.line(), e.message()))
                .toList();
        return new ImportResponse(result.stocks().size(), result.options().size(), errors);
    }

    private enum Kind {
        STOCK, OPTION;

        static Kind from(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("kind is required (STOCK or OPTION)");
            }
            try {
                return Kind.valueOf(raw.strip().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("kind must be STOCK or OPTION, was '" + raw + "'");
            }
        }
    }
}
