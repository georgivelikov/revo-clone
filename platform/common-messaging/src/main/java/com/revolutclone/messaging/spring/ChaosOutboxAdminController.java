package com.revolutclone.messaging.spring;

import com.revolutclone.messaging.outbox.ChaosOutboxControl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-07-resilience-and-scale.md's Chaos control plane, {@code outbox.pause} lever.
 * Component-scanned into every service on this module's classpath, same as
 * {@link DltAdminController} - see that class's javadoc for the {@link ConditionalOnClass} guard
 * and the "hit directly, not through api-gateway" precedent this follows.
 *
 * <p>{@code @Profile("chaos")}, unlike {@code DltAdminController}: a DLT browser is safe to leave
 * reachable always, but a lever that can silently stop every event this service publishes must be
 * off unless the chaos profile is explicitly active - plans/phase-07's own "a chaos endpoint
 * reachable in a demo build is a genuinely bad idea."
 */
@RestController
@RequestMapping("/admin/chaos/outbox")
@Profile("chaos")
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
public class ChaosOutboxAdminController {

    private final ChaosOutboxControl control;

    public ChaosOutboxAdminController(ChaosOutboxControl control) {
        this.control = control;
    }

    @GetMapping
    public ResponseEntity<ChaosOutboxStatus> status() {
        return ResponseEntity.ok(new ChaosOutboxStatus(control.isPaused()));
    }

    @PostMapping("/pause")
    public ResponseEntity<ChaosOutboxStatus> pause() {
        control.pause();
        return ResponseEntity.ok(new ChaosOutboxStatus(control.isPaused()));
    }

    @PostMapping("/resume")
    public ResponseEntity<ChaosOutboxStatus> resume() {
        control.resume();
        return ResponseEntity.ok(new ChaosOutboxStatus(control.isPaused()));
    }

    public record ChaosOutboxStatus(boolean paused) {
    }
}
