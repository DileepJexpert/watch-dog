package com.sentinel.api;

import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.entity.ServiceHealthEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.repository.IncidentRepository;
import com.sentinel.repository.ServiceHealthRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Dashboard API endpoints for the single-pane-of-glass view.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DashboardController {

    private final ServiceHealthRepository serviceHealthRepository;
    private final IncidentRepository incidentRepository;

    /** Service health map - all services with status */
    @GetMapping("/summary")
    public ResponseEntity<List<ServiceHealthEntity>> getSummary() {
        return ResponseEntity.ok(serviceHealthRepository.findAll());
    }

    /** Active incidents for the incident panel */
    @GetMapping("/incidents/active")
    public ResponseEntity<List<IncidentEntity>> getActiveIncidents() {
        return ResponseEntity.ok(
                incidentRepository.findByStatusOrderByDetectedAtDesc(IncidentStatus.OPEN));
    }

    /** Recent deployments correlated with incident activity */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Instant last24h = Instant.now().minus(24, ChronoUnit.HOURS);
        Instant last7d = Instant.now().minus(7, ChronoUnit.DAYS);

        long openIncidents = incidentRepository.findByStatusOrderByDetectedAtDesc(IncidentStatus.OPEN).size();
        long last24hIncidents = incidentRepository.findRecentIncidents(last24h).size();
        long last7dIncidents = incidentRepository.findRecentIncidents(last7d).size();

        Map<String, Object> stats = Map.of(
                "openIncidents", openIncidents,
                "incidentsLast24h", last24hIncidents,
                "incidentsLast7d", last7dIncidents,
                "serviceCount", serviceHealthRepository.count()
        );
        return ResponseEntity.ok(stats);
    }

    /** Top incident-prone services */
    @GetMapping("/top-services")
    public ResponseEntity<List<Object[]>> getTopServices() {
        Instant since = Instant.now().minus(7, ChronoUnit.DAYS);
        return ResponseEntity.ok(incidentRepository.countByServiceGrouped(since));
    }
}
