package com.sentinel.ingestion;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.sentinel.config.SentinelProperties;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.enums.Severity;
import com.sentinel.model.enums.SignalType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

class ElasticsearchConnectorTest {

    private WireMockServer wireMock;
    private ElasticsearchConnector connector;

    @BeforeEach
    void setup() {
        wireMock = new WireMockServer(9201);
        wireMock.start();
        WireMock.configureFor("localhost", 9201);

        SentinelProperties props = new SentinelProperties();
        props.getElasticsearch().setUrl("http://localhost:9201");
        props.getElasticsearch().setIndexPattern("logs-*");
        props.getElasticsearch().setPollIntervalSeconds(30);

        WebClient webClient = WebClient.builder()
                .baseUrl("http://localhost:9201")
                .build();

        connector = new ElasticsearchConnector(webClient, props);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void fetchRecentErrorLogs_returnsNormalizedEvents() {
        String esResponse = """
                {
                  "hits": {
                    "total": {"value": 1},
                    "hits": [
                      {
                        "_id": "abc123",
                        "_index": "logs-2024.03.01",
                        "_source": {
                          "@timestamp": "2024-03-01T10:00:00Z",
                          "service": {"name": "payment-service"},
                          "log": {"level": "ERROR"},
                          "message": "Connection refused to database",
                          "error": {
                            "type": "java.net.ConnectException",
                            "message": "Connection refused"
                          }
                        }
                      }
                    ]
                  }
                }
                """;

        stubFor(post(urlPathMatching("/logs-\\*/_search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(esResponse)));

        List<NormalizedEvent> events = connector.fetchRecentErrorLogs();

        assertThat(events).hasSize(1);
        NormalizedEvent event = events.get(0);
        assertThat(event.serviceName()).isEqualTo("payment-service");
        assertThat(event.signalType()).isEqualTo(SignalType.LOG);
        assertThat(event.severity()).isEqualTo(Severity.P2_HIGH);
        assertThat(event.attributes()).containsKey("db_connection_error");
        assertThat(event.attributes().get("db_connection_error")).isEqualTo(true);
    }

    @Test
    void fetchRecentErrorLogs_returnsEmpty_onConnectorError() {
        stubFor(post(anyUrl())
                .willReturn(aResponse().withStatus(500)));

        List<NormalizedEvent> events = connector.fetchRecentErrorLogs();
        assertThat(events).isEmpty();
    }

    @Test
    void fetchRecentErrorLogs_detectsOomPattern() {
        String esResponse = """
                {
                  "hits": {
                    "hits": [
                      {
                        "_id": "oom1",
                        "_index": "logs-2024",
                        "_source": {
                          "service": {"name": "order-service"},
                          "log": {"level": "ERROR"},
                          "message": "java.lang.OutOfMemoryError: Java heap space",
                          "error": {"type": "OutOfMemoryError", "message": "heap"}
                        }
                      }
                    ]
                  }
                }
                """;

        stubFor(post(anyUrl())
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(esResponse)));

        List<NormalizedEvent> events = connector.fetchRecentErrorLogs();

        assertThat(events).hasSize(1);
        assertThat(events.get(0).attributes().get("oom_detected")).isEqualTo(true);
    }
}
