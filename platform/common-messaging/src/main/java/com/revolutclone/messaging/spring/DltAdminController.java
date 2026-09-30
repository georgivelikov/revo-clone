package com.revolutclone.messaging.spring;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-07-resilience-and-scale.md §DLT tooling: "A DLT no one looks at is a silent
 * data-loss channel." One controller, component-scanned into every service on this module's
 * classpath (see {@link com.revolutclone.messaging.MessagingAutoConfiguration}) rather than
 * duplicated per service. {@code {topic}} is always the *main* topic name (e.g.
 * {@code payment.event.v1}), never the {@code .DLT}-suffixed one - {@link DltAdminService}
 * appends the suffix itself.
 *
 * <p>{@code replay/{offset}} takes an explicit {@code partition} query parameter (default 0)
 * rather than folding it into the path: retry/DLT topics have 3 partitions, not 12, per
 * plans/phase-00-foundations.md, so an offset alone is genuinely ambiguous - two different
 * partitions can each have a record at offset 4. {@link #browse} reports both fields for exactly
 * this reason, so an operator reading its output always has what {@code replay} needs.
 *
 * <p>Guarded by {@link ConditionalOnClass} rather than assumed present: this module has no other
 * reason to depend on a Servlet stack, so a future consumer of {@code common-messaging} without
 * {@code spring-boot-starter-web} on its classpath (there is none today - every current consumer
 * has it; only the WebFlux-only {@code api-gateway} excludes it, and that service does not
 * depend on this module) simply never registers this bean instead of failing to start. See
 * docs/spring-boot-4-notes.md's {@code excludeName} note for why an unconditional reference to a
 * Servlet-only annotation type is the wrong way to make that safe.
 *
 * <p>Not routed through {@code api-gateway}: like every other {@code /internal/**} or
 * {@code /admin/**} endpoint in this project (see e.g. {@code ledger-service}'s
 * {@code AdminController}), this is hit directly against a service's own host and port, and each
 * service's own resource-server config still requires an authenticated JWT for it.
 */
@RestController
@RequestMapping("/admin/dlt")
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
public class DltAdminController {

    private static final int DEFAULT_BROWSE_LIMIT = 100;

    private final DltAdminService dltAdminService;

    public DltAdminController(DltAdminService dltAdminService) {
        this.dltAdminService = dltAdminService;
    }

    @GetMapping("/{topic}")
    public ResponseEntity<List<DeadLetterRecord>> browse(@PathVariable String topic,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_BROWSE_LIMIT) int limit) {
        return ResponseEntity.ok(dltAdminService.browse(topic, limit));
    }

    @PostMapping("/{topic}/replay/{offset}")
    public ResponseEntity<ReplayResult> replayOne(@PathVariable String topic, @PathVariable long offset,
            @RequestParam(name = "partition", defaultValue = "0") int partition) {
        return ResponseEntity.ok(dltAdminService.replayOne(topic, partition, offset));
    }

    @PostMapping("/{topic}/replay-all")
    public ResponseEntity<ReplayAllSummary> replayAll(@PathVariable String topic,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun) {
        return ResponseEntity.ok(dltAdminService.replayAll(topic, dryRun));
    }
}
