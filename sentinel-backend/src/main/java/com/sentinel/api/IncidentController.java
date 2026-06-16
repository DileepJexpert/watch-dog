package com.sentinel.api;

import com.sentinel.alerting.AlertingService;
import com.sentinel.model.entity.IncidentEntity;
import com.sentinel.model.enums.IncidentStatus;
import com.sentinel.repository.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class IncidentController {

    private final IncidentRepository incidentRepository;
    private final AlertingService alertingService;

    @GetMapping
    public ResponseEntity<Page<IncidentEntity>> listIncidents(
            @RequestParam(defaultValue = "OPEN,INVESTIGATING") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        List<IncidentStatus> statuses = List.of(status.split(",")).stream()
                .map(s -> IncidentStatus.valueOf(s.trim()))
                .toList();

        PageRequest pageable = PageRequest.of(page, size, Sort.by("detectedAt").descending());
        return ResponseEntity.ok(incidentRepository.findByStatusIn(statuses, pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IncidentEntity> getIncident(@PathVariable UUID id) {
        return incidentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<IncidentEntity> resolveIncident(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "manual") String resolvedBy) {

        return incidentRepository.findById(id).map(incident -> {
            incident.setStatus(IncidentStatus.RESOLVED);
            incident.setResolvedAt(Instant.now());
            incident.setResolvedBy(resolvedBy);
            IncidentEntity saved = incidentRepository.save(incident);
            alertingService.alertResolved(saved);
            return ResponseEntity.ok(saved);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/service/{serviceName}")
    public ResponseEntity<List<IncidentEntity>> getByService(@PathVariable String serviceName) {
        return ResponseEntity.ok(incidentRepository.findByServiceNameOrderByDetectedAtDesc(serviceName));
    }
}
