package com.terminalone.marketdata;

import com.terminalone.marketdata.MarketDataClient.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * The real MarketData.app transport (Phase 3 AC #1). Driven against a mocked
 * HTTP server (no network): Bearer auth, raw status/body pass-through for every
 * status, and graceful degradation on transport failure.
 */
class MarketDataAppHttpClientTest {

    private static final String BASE = "https://api.marketdata.app/v1/";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private MarketDataClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MarketDataAppHttpClient(builder.build(), "test-token");
    }

    @Test
    void getReturnsStatusAndBodyWithBearerAuth() {
        server.expect(requestTo(BASE + "options/chain/AAPL/"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withStatus(HttpStatus.NON_AUTHORITATIVE_INFORMATION).body("{\"s\":\"ok\"}"));

        Response res = client.get("options/chain/AAPL/");

        assertThat(res.status()).isEqualTo(203); // delayed
        assertThat(res.body()).isEqualTo("{\"s\":\"ok\"}");
        server.verify();
    }

    @Test
    void returnsServerErrorsWithoutThrowing() {
        server.expect(requestTo(BASE + "options/chain/AAPL/"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

        Response res = client.get("options/chain/AAPL/");

        assertThat(res.status()).isEqualTo(500);
        assertThat(res.body()).isEqualTo("boom");
    }

    @Test
    void returnsNoDataAsAnEmptyBody() {
        server.expect(requestTo(BASE + "options/chain/ZZZZ/"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        Response res = client.get("options/chain/ZZZZ/");

        assertThat(res.status()).isEqualTo(204);
        assertThat(res.body()).isEmpty();
    }

    @Test
    void degradesToServiceUnavailableOnTransportFailure() {
        server.expect(requestTo(BASE + "options/chain/AAPL/"))
                .andRespond(request -> {
                    throw new IOException("connection refused");
                });

        Response res = client.get("options/chain/AAPL/");

        assertThat(res.status()).isEqualTo(503);
    }
}
