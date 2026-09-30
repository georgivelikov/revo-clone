package com.revolutclone.simulator.chaos;

/**
 * The {@code POST /simulator/chaos} request body. Every field but {@code lever} is optional -
 * which ones are required depends on the lever, per {@link ChaosController}:
 *
 * <table>
 * <caption>lever -&gt; fields it reads</caption>
 * <tr><td>{@code ledger.delay}</td><td>{@code value} (milliseconds)</td></tr>
 * <tr><td>{@code ledger.fail-rate}</td><td>{@code value} (integer percent, 0-100)</td></tr>
 * <tr><td>{@code ledger.reject-all}</td><td>{@code value} ("true"/"false")</td></tr>
 * <tr><td>{@code ledger.reset}</td><td>(none)</td></tr>
 * <tr><td>{@code outbox.pause} / {@code outbox.resume}</td><td>{@code service}</td></tr>
 * <tr><td>{@code fx.down} / {@code fx.up}</td><td>(none)</td></tr>
 * <tr><td>{@code poison}</td><td>{@code topic}</td></tr>
 * <tr><td>{@code duplicate}</td><td>{@code topic}, {@code count}</td></tr>
 * </table>
 */
public record ChaosCommand(String lever, String service, String topic, String value, Integer count) {
}
