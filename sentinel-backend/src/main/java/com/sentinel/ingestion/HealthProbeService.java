package com.sentinel.ingestion;

import com.sentinel.config.SentinelProperties;
import com.sentinel.model.NormalizedEvent;
import com.sentinel.model.enums.Severity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Active health probe service. Sends HTTP requests to monitored API endpoints
 * and records up/down status, response times, and TLS certificate expiry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HealthProbeService {

    private final SentinelProperties properties;

    /**
     * Probes all configured targets and returns normalized events.
     */
    public List<NormalizedEvent> probeAll() {
        List<SentinelProperties.HealthProbeConfig.ProbeTarget> targets =
                properties.getHealthProbe().getTargets();

        List<CompletableFuture<NormalizedEvent>> futures = targets.stream()
                .map(target -> CompletableFuture.supplyAsync(() -> probe(target)))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    private NormalizedEvent probe(SentinelProperties.HealthProbeConfig.ProbeTarget target) {
        int timeoutSeconds = properties.getHealthProbe().getTimeoutSeconds();
        Instant start = Instant.now();

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(target.getUrl()))
                    .GET()
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long responseTimeMs = Duration.between(start, Instant.now()).toMillis();

            int statusCode = response.statusCode();
            boolean isUp = statusCode >= 200 && statusCode < 400;

            Map<String, Object> attrs = new HashMap<>();
            attrs.put("url", target.getUrl());
            attrs.put("status_code", statusCode);
            attrs.put("response_time_ms", responseTimeMs);
            attrs.put("probe_type", target.getType());

            // Check TLS certificate expiry
            Long certExpiryDays = checkTlsCertExpiry(target.getUrl());
            if (certExpiryDays != null) {
                attrs.put("tls_cert_expiry_days", certExpiryDays);
                attrs.put("tls_cert_expiring_soon", certExpiryDays < 7);
            }

            if (!isUp) {
                attrs.put("5xx_error", statusCode >= 500);
                return NormalizedEvent.ofProbe(target.getName(),
                        statusCode >= 500 ? Severity.P1_CRITICAL : Severity.P2_HIGH,
                        String.format("Health probe FAILED for %s: HTTP %d (%dms)",
                                target.getName(), statusCode, responseTimeMs),
                        attrs);
            }

            // Slow response warning
            if (responseTimeMs > 5000) {
                return NormalizedEvent.ofProbe(target.getName(), Severity.P3_MEDIUM,
                        String.format("Health probe SLOW for %s: HTTP %d (%dms)",
                                target.getName(), statusCode, responseTimeMs),
                        attrs);
            }

            return NormalizedEvent.ofProbe(target.getName(), Severity.P4_INFO,
                    String.format("Health probe OK for %s: HTTP %d (%dms)",
                            target.getName(), statusCode, responseTimeMs),
                    attrs);

        } catch (Exception e) {
            long responseTimeMs = Duration.between(start, Instant.now()).toMillis();
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("url", target.getUrl());
            attrs.put("error", e.getMessage());
            attrs.put("response_time_ms", responseTimeMs);
            attrs.put("probe_type", target.getType());
            attrs.put("service_down", true);

            return NormalizedEvent.ofProbe(target.getName(), Severity.P1_CRITICAL,
                    String.format("Health probe UNREACHABLE for %s: %s", target.getName(), e.getMessage()),
                    attrs);
        }
    }

    private Long checkTlsCertExpiry(String url) {
        if (!url.startsWith("https://")) return null;
        try {
            URI uri = URI.create(url);
            SSLContext ctx = SSLContext.getInstance("TLS");
            final long[] expiryMs = {Long.MAX_VALUE};

            ctx.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        public void checkServerTrusted(X509Certificate[] chain, String authType) {
                            if (chain != null && chain.length > 0) {
                                expiryMs[0] = chain[0].getNotAfter().getTime();
                            }
                        }
                    }
            }, null);

            HttpClient client = HttpClient.newBuilder()
                    .sslContext(ctx)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(5))
                    .build();

            client.send(request, HttpResponse.BodyHandlers.discarding());

            if (expiryMs[0] != Long.MAX_VALUE) {
                long daysLeft = Duration.between(Instant.now(),
                        Instant.ofEpochMilli(expiryMs[0])).toDays();
                return daysLeft;
            }
        } catch (Exception e) {
            log.debug("Could not check TLS cert for {}: {}", url, e.getMessage());
        }
        return null;
    }
}
