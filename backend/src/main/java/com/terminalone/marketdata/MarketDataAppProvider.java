package com.terminalone.marketdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * MarketData.app adapter (D3). Parses the vendor's columnar JSON (parallel
 * arrays keyed by field) into the domain model. Greeks/IV are NOT taken from the
 * vendor — they are derived in-house by {@link OptionAnalytics} (D22).
 *
 * <p>HTTP 200 = live, 203 = cached/delayed (success), 204 = no_data.
 */
public class MarketDataAppProvider implements MarketDataProvider {

    private static final int HTTP_DELAYED = 203;
    private static final int DEFAULT_DTE = 45;
    private static final int DEFAULT_STRIKE_LIMIT = 30;

    private final MarketDataClient client;
    private final ObjectMapper mapper;
    private final int chainDte;
    private final int chainStrikeLimit;

    public MarketDataAppProvider(MarketDataClient client, ObjectMapper mapper) {
        this(client, mapper, DEFAULT_DTE, DEFAULT_STRIKE_LIMIT);
    }

    public MarketDataAppProvider(MarketDataClient client, ObjectMapper mapper,
                                 int chainDte, int chainStrikeLimit) {
        this.client = client;
        this.mapper = mapper;
        this.chainDte = chainDte;
        this.chainStrikeLimit = chainStrikeLimit;
    }

    @Override
    public OptionChain getChain(String symbol) {
        // Bound the request to one near-money expiry window so it stays cheap.
        String path = "options/chain/" + symbol + "/?dte=" + chainDte + "&strikeLimit=" + chainStrikeLimit;
        MarketDataClient.Response res = client.get(path);
        boolean delayed = res.status() == HTTP_DELAYED;
        JsonNode root = readTree(res.body());

        JsonNode optionSymbol = root.path("optionSymbol");
        JsonNode side = root.path("side");
        JsonNode strike = root.path("strike");
        JsonNode expiration = root.path("expiration");
        JsonNode bid = root.path("bid");
        JsonNode ask = root.path("ask");
        JsonNode openInterest = root.path("openInterest");

        List<OptionContract> contracts = new ArrayList<>(optionSymbol.size());
        for (int i = 0; i < optionSymbol.size(); i++) {
            contracts.add(new OptionContract(
                    optionSymbol.get(i).asText(),
                    "call".equalsIgnoreCase(side.get(i).asText()) ? CallPut.CALL : CallPut.PUT,
                    strike.get(i).asDouble(),
                    epochToDate(expiration.get(i).asLong()),
                    bid.get(i).asDouble(),
                    ask.get(i).asDouble(),
                    openInterest.get(i).asInt()));
        }

        double underlyingPrice = root.path("underlyingPrice").path(0).asDouble();
        Instant asOf = Instant.ofEpochSecond(root.path("updated").path(0).asLong());
        return new OptionChain(symbol, underlyingPrice, asOf, delayed, contracts);
    }

    @Override
    public StockQuote getQuote(String symbol) {
        MarketDataClient.Response res = client.get("stocks/quotes/" + symbol + "/");
        boolean delayed = res.status() == HTTP_DELAYED;
        JsonNode root = readTree(res.body());
        return new StockQuote(
                symbol,
                root.path("bid").path(0).asDouble(),
                root.path("ask").path(0).asDouble(),
                root.path("last").path(0).asDouble(),
                Instant.ofEpochSecond(root.path("updated").path(0).asLong()),
                delayed);
    }

    /**
     * Vendor expirations are unix-epoch seconds (typically 16:00 ET = same UTC
     * date). UTC keeps the date correct for both 16:00-ET and 00:00-UTC tickers.
     */
    private static LocalDate epochToDate(long epochSeconds) {
        return LocalDate.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    private JsonNode readTree(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unparseable MarketData.app response", e);
        }
    }
}
