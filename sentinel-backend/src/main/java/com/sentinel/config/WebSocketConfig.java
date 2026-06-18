package com.sentinel.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // setSessionCookieNeeded(false): the wildcard origin pattern above is
        // incompatible with cookie-bearing cross-origin requests (browsers
        // refuse to send cookies to "*"), so SockJS handshakes from the React
        // dev server and the Flutter web app would otherwise fail. We don't
        // rely on JSESSIONID for routing — there's a single instance — so
        // making the transport cookieless is safe.
        registry.addEndpoint("/ws/events")
                .setAllowedOriginPatterns("*")
                .withSockJS()
                .setSessionCookieNeeded(false);
        // FR-3: agent streams step-by-step progress on this endpoint; same broker, separate URL
        registry.addEndpoint("/ws/agent")
                .setAllowedOriginPatterns("*")
                .withSockJS()
                .setSessionCookieNeeded(false);
    }
}
