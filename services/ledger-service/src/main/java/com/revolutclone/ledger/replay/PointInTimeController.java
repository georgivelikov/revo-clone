package com.revolutclone.ledger.replay;

import java.time.Clock;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-07-resilience-and-scale.md's ### Replay: "the thing a mutable-balance design
 * simply cannot do, and demonstrating it is the clearest justification for the whole
 * event-sourced approach." Same path prefix and auth posture as {@code AdminController}
 * ({@code /internal/ledger/admin/**} requires an authenticated JWT under
 * {@code SecurityConfig}'s default {@code anyRequest().authenticated()} - no
 * {@code /admin/chaos}-style {@code permitAll}, since this is operator tooling invoked directly,
 * not something an unauthenticated {@code simulator-service} calls). Wrapped by
 * {@code tasks.ps1 point-in-time <timestamp>}.
 */
@RestController
@RequestMapping("/internal/ledger/admin/point-in-time")
public class PointInTimeController {

    private final PointInTimeService pointInTimeService;
    private final Clock clock;

    public PointInTimeController(PointInTimeService pointInTimeService, Clock clock) {
        this.pointInTimeService = pointInTimeService;
        this.clock = clock;
    }

    @GetMapping
    public ResponseEntity<PointInTimeQueryResponse> query(@RequestParam Instant asOf) {
        return ResponseEntity.ok(PointInTimeQueryResponse.from(asOf, clock.instant(), pointInTimeService.query(asOf)));
    }
}
