package com.watchdog.aihub;

import com.watchdog.config.WatchdogProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Configuration
public class AihubConfig {

    @Bean
    public WebClient llmWebClient(WatchdogProperties props) {
        String baseUrl = props.getAihub().getBaseUrl();
        WebClient.Builder builder = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024));
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    /**
     * Provider switch:
     *   watchdog.aihub.provider=anthropic (default) → AnthropicLlmClient
     *   watchdog.aihub.provider=ollama              → OllamaLlmClient (native, no proxy)
     */
    @Bean
    @ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
    public LlmClient llmClient(WebClient llmWebClient, WatchdogProperties props) {
        String provider = props.getAihub().getProvider();
        if ("ollama".equalsIgnoreCase(provider)) {
            log.info("LLM provider: ollama (native) — model={}, baseUrl={}",
                    props.getAihub().getModel(), props.getAihub().getBaseUrl());
            return new OllamaLlmClient(llmWebClient, props);
        }
        log.info("LLM provider: anthropic — model={}, baseUrl={}",
                props.getAihub().getModel(), props.getAihub().getBaseUrl());
        return new AnthropicLlmClient(llmWebClient, props);
    }
}
