package com.terminalone.marketdata;

/**
 * Transport seam for the MarketData.app HTTP API. Isolating the raw fetch keeps
 * the adapter's columnar-JSON parsing + status handling pure and unit-testable
 * (fixtures in, no network). The production impl is an HTTP client; tests inject
 * a fake that returns recorded responses.
 */
public interface MarketDataClient {

    /**
     * GET a vendor API path (relative to the v1 base), e.g.
     * {@code options/chain/AAPL/?dte=45}.
     *
     * @return the HTTP status and raw response body
     */
    Response get(String path);

    /**
     * A raw vendor response. MarketData.app uses HTTP 200 = live, 203 =
     * cached/delayed (success), 204 = no_data.
     */
    record Response(int status, String body) {
    }
}
