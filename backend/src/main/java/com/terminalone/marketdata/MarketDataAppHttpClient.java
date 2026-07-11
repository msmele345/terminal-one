package com.terminalone.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Real transport for the MarketData.app v1 API (D3). Authenticates with a Bearer
 * token (kept out of the URL and the cache key), and returns the raw
 * {@code (status, body)} for <em>any</em> HTTP status — 200/203/204 and 4xx/5xx
 * alike — so the caller (and the caching layer) decide what to do. Network/IO
 * failures degrade to a non-cacheable 503 rather than propagating an exception.
 */
public class MarketDataAppHttpClient implements MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataAppHttpClient.class);
    private static final int SERVICE_UNAVAILABLE = 503;

    private final RestClient restClient;
    private final String token;

    /**
     * @param restClient a RestClient pre-configured with the v1 base URL
     *                   (trailing slash); paths passed to {@link #get} are relative
     * @param token      the MarketData.app API token (from env)
     */
    public MarketDataAppHttpClient(RestClient restClient, String token) {
        this.restClient = restClient;
        this.token = token;
    }

    @Override
    public Response get(String path) {
        try {
            ResponseEntity<String> entity = restClient.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(status -> true, (request, response) -> {
                        // Never throw: we inspect the status ourselves (203 delayed,
                        // 204 no_data, and 4xx/5xx are all meaningful to the caller).
                    })
                    .toEntity(String.class);
            String body = entity.getBody();
            return new Response(entity.getStatusCode().value(), body == null ? "" : body);
        } catch (RestClientException e) {
            log.warn("MarketData.app request failed for '{}': {}", path, e.getMessage());
            return new Response(SERVICE_UNAVAILABLE, "");
        }
    }
}
