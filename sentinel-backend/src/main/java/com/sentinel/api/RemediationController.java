package com.sentinel.api;

import com.sentinel.model.entity.RemediationLogEntity;
import com.sentinel.remediation.AutoRemediationEngine;
import com.sentinel.repository.IncidentRepository;
import com.sentinel.repository.RemediationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/remediation")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RemediationController {

    private final RemediationRepository remediationRepository;
    private final IncidentRepository incidentRepository;
    private final AutoRemediationEngine autoRemediationEngine;

    /** Full remediation audit log */
    @GetMapping("/log")
    public ResponseEntity<Page<RemediationLogEntity>> getLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("executedAt").descending());
        return ResponseEntity.ok(remediationRepository.findAllByOrderByExecutedAtDesc(pageable));
    }

    /** Remediation log for a specific incident */
    @GetMapping("/incident/{incidentId}")
    public ResponseEntity<List<RemediationLogEntity>> getByIncident(@PathVariable UUID incidentId) {
        return ResponseEntity.ok(remediationRepository.findByIncidentIdOrderByExecutedAtDesc(incidentId));
    }

    /** Manually trigger remediation for an incident */
    @PostMapping("/{incidentId}/trigger")
    public ResponseEntity<String> triggerRemediation(@PathVariable UUID incidentId) {
        return incidentRepository.findById(incidentId).map(incident -> {
            autoRemediationEngine.remediate(incident);
            return ResponseEntity.ok("Remediation triggered for incident " + incidentId);
        }).orElse(ResponseEntity.notFound().build());
    }
}
