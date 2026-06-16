package com.sentinel.aihub;

import com.sentinel.config.SentinelProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Configuration
public class AihubConfig {

    @Bean
    public WebClient llmWebClient(SentinelProperties props) {
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
     *   sentinel.aihub.provider=anthropic (default) → AnthropicLlmClient
     *   sentinel.aihub.provider=ollama              → OllamaLlmClient (native, no proxy)
     */
    @Bean
    @ConditionalOnProperty(prefix = "sentinel.agent", name = "enabled", havingValue = "true")
    public LlmClient llmClient(WebClient llmWebClient, SentinelProperties props) {
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
