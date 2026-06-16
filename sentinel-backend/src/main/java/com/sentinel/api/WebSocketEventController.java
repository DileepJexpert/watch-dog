package com.sentinel.api;

import com.sentinel.model.entity.IncidentEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

/**
 * WebSocket controller for real-time event streaming to the dashboard.
 * Clients subscribe to /topic/incidents for live incident updates.
 */
@Controller
@RequiredArgsConstructor
public class WebSocketEventController {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Broadcast an incident update to all connected dashboard clients.
     * Called by CorrelationEngine when a new incident is created.
     */
    public void broadcastIncident(IncidentEntity incident) {
        messagingTemplate.convertAndSend("/topic/incidents", incident);
    }

    /**
     * Broadcast a service health update.
     */
    public void broadcastHealthUpdate(Object healthUpdate) {
        messagingTemplate.convertAndSend("/topic/health", healthUpdate);
    }

    /**
     * Client-to-server ping (subscription confirmation).
     */
    @MessageMapping("/ping")
    @SendTo("/topic/pong")
    public String ping(String message) {
        return "pong: " + message;
    }
}
