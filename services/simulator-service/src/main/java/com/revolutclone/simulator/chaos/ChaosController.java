package com.revolutclone.simulator.chaos;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-07-resilience-and-scale.md's Chaos control plane: one endpoint, a small honest set
 * of levers (see {@link ChaosCommand}'s javadoc for the full table). {@code @Profile("chaos")} -
 * "a chaos endpoint reachable in a demo build is a genuinely bad idea" - and unauthenticated, like
 * every other endpoint in this database-less service (see {@code SimulatorServiceApplication}).
 *
 * <p>{@code poison} and {@code duplicate} act directly on Kafka from here, since publishing (and,
 * for duplicate, tailing) a topic is exactly what this service already does with no database in
 * the way. Every other lever mutates a flag that lives in a different service's JVM and so must be
 * forwarded over HTTP - see {@link ChaosControlPlaneClient}.
 */
@RestController
@RequestMapping("/simulator/chaos")
@Profile("chaos")
public class ChaosController {

    private final ChaosControlPlaneClient controlPlane;
    private final ChaosPoisonPublisher poisonPublisher;
    private final ChaosDuplicator duplicator;

    public ChaosController(ChaosControlPlaneClient controlPlane, ChaosPoisonPublisher poisonPublisher,
            ChaosDuplicator duplicator) {
        this.controlPlane = controlPlane;
        this.poisonPublisher = poisonPublisher;
        this.duplicator = duplicator;
    }

    @PostMapping
    public ChaosResult apply(@RequestBody ChaosCommand command) {
        String lever = command.lever() == null ? "" : command.lever();
        return switch (lever) {
            case "ledger.delay" -> {
                long delayMs = requireLong(command);
                controlPlane.setLedgerDelay(delayMs);
                yield result(lever, "ledger-service delayMs=" + delayMs);
            }
            case "ledger.fail-rate" -> {
                int failRatePercent = requirePercent(command);
                controlPlane.setLedgerFailRatePercent(failRatePercent);
                yield result(lever, "ledger-service failRatePercent=" + failRatePercent);
            }
            case "ledger.reject-all" -> {
                boolean rejectAll = requireBoolean(command);
                controlPlane.setLedgerRejectAll(rejectAll);
                yield result(lever, "ledger-service rejectAll=" + rejectAll);
            }
            case "ledger.reset" -> {
                controlPlane.resetLedger();
                yield result(lever, "ledger-service chaos state reset");
            }
            case "outbox.pause" -> {
                String service = requireService(command);
                controlPlane.pauseOutbox(service);
                yield result(lever, service + " outbox paused");
            }
            case "outbox.resume" -> {
                String service = requireService(command);
                controlPlane.resumeOutbox(service);
                yield result(lever, service + " outbox resumed");
            }
            case "fx.down" -> {
                controlPlane.setFxDown(true);
                yield result(lever, "fx-service now returning 503 for every quote");
            }
            case "fx.up" -> {
                controlPlane.setFxDown(false);
                yield result(lever, "fx-service quoting normally again");
            }
            case "poison" -> {
                String topic = requireTopic(command);
                poisonPublisher.poison(topic);
                yield result(lever, "published one unparseable message to " + topic);
            }
            case "duplicate" -> {
                String topic = requireTopic(command);
                int count = requireCount(command);
                int republished = duplicator.duplicateLast(topic, count);
                yield result(lever, "re-published " + republished + " of the last " + count + " messages on "
                        + topic);
            }
            default -> throw new InvalidChaosCommandException("unknown lever \"" + command.lever() + "\"");
        };
    }

    private static ChaosResult result(String lever, String detail) {
        return new ChaosResult(lever, detail);
    }

    private static long requireLong(ChaosCommand command) {
        try {
            return Long.parseLong(requireValue(command));
        } catch (NumberFormatException e) {
            throw new InvalidChaosCommandException(
                    "\"" + command.lever() + "\" needs an integer \"value\", got \"" + command.value() + "\"");
        }
    }

    /**
     * An integer percentage 0-100, not a fraction: this project bans {@code double}/{@code float}
     * outright (CLAUDE.md hard rule 1, enforced literally by agent/tripwire.ps1 with no carve-out
     * for a non-money probability), so {@code ledger.fail-rate}'s {@code value} is "30" for 30%,
     * not "0.3".
     */
    private static int requirePercent(ChaosCommand command) {
        int percent;
        try {
            percent = Integer.parseInt(requireValue(command));
        } catch (NumberFormatException e) {
            throw new InvalidChaosCommandException(
                    "\"" + command.lever() + "\" needs an integer 0-100 \"value\", got \"" + command.value() + "\"");
        }
        if (percent < 0 || percent > 100) {
            throw new InvalidChaosCommandException(
                    "\"" + command.lever() + "\" needs a \"value\" between 0 and 100, got " + percent);
        }
        return percent;
    }

    private static boolean requireBoolean(ChaosCommand command) {
        return Boolean.parseBoolean(requireValue(command));
    }

    private static String requireValue(ChaosCommand command) {
        if (command.value() == null || command.value().isBlank()) {
            throw new InvalidChaosCommandException("\"" + command.lever() + "\" requires a \"value\" field");
        }
        return command.value();
    }

    private static String requireService(ChaosCommand command) {
        if (command.service() == null || command.service().isBlank()) {
            throw new InvalidChaosCommandException("\"" + command.lever() + "\" requires a \"service\" field");
        }
        return command.service();
    }

    private static String requireTopic(ChaosCommand command) {
        if (command.topic() == null || command.topic().isBlank()) {
            throw new InvalidChaosCommandException("\"" + command.lever() + "\" requires a \"topic\" field");
        }
        return command.topic();
    }

    private static int requireCount(ChaosCommand command) {
        if (command.count() == null || command.count() <= 0) {
            throw new InvalidChaosCommandException("\"duplicate\" requires a positive \"count\" field");
        }
        return command.count();
    }
}
