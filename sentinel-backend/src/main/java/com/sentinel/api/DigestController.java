package com.sentinel.api;

import com.sentinel.scheduler.DailyAiHealthReportScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Endpoints for triggering and previewing the daily AI health digest.
 *
 * Only registered when {@link DailyAiHealthReportScheduler} is in the context
 * (i.e. {@code sentinel.digest.daily.enabled=true}). Without it these routes
 * 404 cleanly.
 */
@RestController
@RequestMapping("/api/digest")
@CrossOrigin(origins = "*")
@ConditionalOnBean(DailyAiHealthReportScheduler.class)
public class DigestController {

    private final DailyAiHealthReportScheduler scheduler;

    @Autowired
    public DigestController(DailyAiHealthReportScheduler scheduler) {
        this.scheduler = scheduler;
    }

    /** Generate the daily digest now and ship it to Slack + Email. */
    @PostMapping("/daily/trigger")
    public ResponseEntity<Map<String, Object>> trigger() {
        String body = scheduler.runAndSend();
        return ResponseEntity.ok(Map.of("status", "sent", "preview", body));
    }

    /**
     * Generate the digest body without sending it. Useful for ad-hoc previews
     * from the dashboard. Optional {@code send=true} flips it to a live send.
     */
    @GetMapping("/daily/preview")
    public ResponseEntity<Map<String, Object>> preview(@RequestParam(defaultValue = "false") boolean send) {
        String body = send ? scheduler.runAndSend() : scheduler.generateReport();
        return ResponseEntity.ok(Map.of(
                "sent", send,
                "body", body
        ));
    }
}
