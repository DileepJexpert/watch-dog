package com.watchdog.aihub;

import com.watchdog.config.WatchdogProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

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

    @Bean
    @ConditionalOnProperty(prefix = "watchdog.agent", name = "enabled", havingValue = "true")
    public LlmClient llmClient(WebClient llmWebClient, WatchdogProperties props) {
        return new AnthropicLlmClient(llmWebClient, props);
    }
}
