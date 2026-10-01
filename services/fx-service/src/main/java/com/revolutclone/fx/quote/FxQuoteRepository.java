package com.revolutclone.fx.quote;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code fx_quote}, by hand over {@link JdbcClient} rather than through JPA.
 *
 * <p>The reason is the two {@code numeric(20,10)} rate columns. Mapping them through an entity means
 * a Java decimal type on the field or in an {@code AttributeConverter}, and CLAUDE.md's hard rule 1
 * confines that type to {@code FxMath}/{@code Money.toMajor} - enforced by path in the project's
 * tripwire, so this is a build failure rather than a style preference. Casting at the SQL boundary
 * keeps rates as the exact decimal text they already are everywhere else in this service:
 * {@code cast(:rate as numeric)} on the way in, {@code ::text} on the way out. The round trip is
 * therefore Postgres's own, with no client-side rounding step for Hibernate to silently truncate -
 * the phase's first listed gotcha.
 *
 * <p>It also buys the conditional state updates tasks 04.5 and 04.6 need ("mark {@code CONSUMED}
 * only if still {@code ACTIVE}"), which is a single statement here and an optimistic-locking dance
 * in JPA.
 */
@Repository
public class FxQuoteRepository {

    private static final String COLUMNS = """
            id, idempotency_key, user_id, sell_currency, buy_currency, sell_minor, buy_minor,
            mid_rate::text as mid_rate, client_rate::text as client_rate, spread_bps,
            reservation_id, state, expires_at, created_at
            """;

    private static final String INSERT = """
            insert into fx_quote (id, idempotency_key, user_id, sell_currency, buy_currency,
                                  sell_minor, buy_minor, mid_rate, client_rate, spread_bps,
                                  reservation_id, state, expires_at, created_at)
            values (:id, :idempotencyKey, :userId, :sellCurrency, :buyCurrency,
                    :sellMinor, :buyMinor, cast(:midRate as numeric), cast(:clientRate as numeric),
                    :spreadBps, :reservationId, :state, :expiresAt, :createdAt)
            """;

    private final JdbcClient jdbcClient;

    public FxQuoteRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * @throws org.springframework.dao.DuplicateKeyException if this {@code idempotencyKey} was
     *         already used - the unique constraint, not a prior read, is what makes the quoting path
     *         idempotent under a genuine race
     */
    public FxQuote insert(FxQuote quote) {
        jdbcClient.sql(INSERT)
                .param("id", quote.id())
                .param("idempotencyKey", quote.idempotencyKey())
                .param("userId", quote.userId())
                .param("sellCurrency", quote.sellCurrency())
                .param("buyCurrency", quote.buyCurrency())
                .param("sellMinor", quote.sellMinor())
                .param("buyMinor", quote.buyMinor())
                .param("midRate", quote.midRate())
                .param("clientRate", quote.clientRate())
                .param("spreadBps", quote.spreadBps())
                .param("reservationId", quote.reservationId())
                .param("state", quote.state().name())
                .param("expiresAt", atUtc(quote.expiresAt()))
                .param("createdAt", atUtc(quote.createdAt()))
                .update();
        return quote;
    }

    public Optional<FxQuote> findById(UUID id) {
        return jdbcClient.sql("select " + COLUMNS + " from fx_quote where id = :id")
                .param("id", id)
                .query(FxQuoteRepository::mapRow)
                .optional();
    }

    /**
     * {@code ACTIVE -> CONSUMED}, but only if the quote is still {@code ACTIVE} and still unexpired
     * <em>at the database</em>. The whole execution decision is this one statement: two concurrent
     * executes of one quote both reach it, the second blocks on the row lock, re-evaluates the
     * {@code where} clause after the first commits and matches nothing. Exactly one caller gets
     * {@code 1} back, and only that caller appends the outbox event - so "same quote executed twice
     * concurrently produces one entry" is decided here rather than by the ledger's idempotency key,
     * which is the second line of defence rather than the first.
     *
     * <p>{@code expires_at > :now} is in the statement and not only in the service's own check for
     * the same reason: expiry is a server-clock fact, and putting it in the transition means an
     * execute racing the expiry sweep cannot slip between the check and the update.
     *
     * @return 1 if this call performed the transition, 0 if it did not
     */
    public int markConsumed(UUID id, Instant asOf) {
        return jdbcClient.sql("""
                        update fx_quote set state = 'CONSUMED'
                        where id = :id and state = 'ACTIVE' and expires_at > :now
                        """)
                .param("id", id)
                .param("now", atUtc(asOf))
                .update();
    }

    /**
     * {@code CONSUMED -> CANCELLED}, the compensation transition: the ledger rejected the capture of
     * a quote this service had already accepted. Conditional on {@code CONSUMED} so a replayed
     * rejection cannot resurrect a quote that has since been dealt with some other way.
     *
     * @return 1 if this call performed the transition, 0 if it did not
     */
    public int markCancelled(UUID id) {
        return jdbcClient.sql("update fx_quote set state = 'CANCELLED' where id = :id and state = 'CONSUMED'")
                .param("id", id)
                .update();
    }

    /**
     * The sweep's candidate set: still {@code ACTIVE}, and due. Selected before the per-row
     * conditional update rather than updated in bulk because releasing a hold is a synchronous
     * call to another service, done one reservation at a time outside any transaction - see
     * {@link QuoteExpirySweep}.
     */
    public List<FxQuote> findActiveExpiredAsOf(Instant asOf) {
        return jdbcClient.sql("select " + COLUMNS + " from fx_quote where state = 'ACTIVE' and expires_at <= :now")
                .param("now", atUtc(asOf))
                .query(FxQuoteRepository::mapRow)
                .list();
    }

    /**
     * {@code ACTIVE -> EXPIRED}, conditional on both facts still holding at the database - mirrors
     * {@link #markConsumed}'s own contract and for the identical reason: a quote executed in the
     * gap between {@link #findActiveExpiredAsOf} reading the row and this statement reaching it
     * must be left alone, and the caller finds out by getting {@code 0} back rather than by racing
     * a plain read-then-write.
     *
     * @return 1 if this call performed the transition, 0 if it did not
     */
    public int markExpired(UUID id, Instant asOf) {
        return jdbcClient.sql("""
                        update fx_quote set state = 'EXPIRED'
                        where id = :id and state = 'ACTIVE' and expires_at <= :now
                        """)
                .param("id", id)
                .param("now", atUtc(asOf))
                .update();
    }

    public Optional<FxQuote> findByIdempotencyKey(String idempotencyKey) {
        return jdbcClient.sql("select " + COLUMNS + " from fx_quote where idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(FxQuoteRepository::mapRow)
                .optional();
    }

    /** Backs the {@code fx.quote.active.count} gauge (plans/phase-08-load-and-observability.md task 3). */
    public long countActive() {
        return jdbcClient.sql("select count(*) from fx_quote where state = 'ACTIVE'")
                .query(Long.class)
                .single();
    }

    private static FxQuote mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new FxQuote(
                rs.getObject("id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getObject("user_id", UUID.class),
                rs.getString("sell_currency"),
                rs.getString("buy_currency"),
                rs.getLong("sell_minor"),
                rs.getLong("buy_minor"),
                rs.getString("mid_rate"),
                rs.getString("client_rate"),
                rs.getInt("spread_bps"),
                rs.getObject("reservation_id", UUID.class),
                FxQuoteState.valueOf(rs.getString("state")),
                instantOf(rs, "expires_at"),
                instantOf(rs, "created_at"));
    }

    /**
     * {@code timestamptz} through {@link OffsetDateTime} rather than {@code Instant} directly: the
     * offset-aware type is the one the JDBC specification requires a driver to support for this
     * column type, so the conversion is explicit here rather than dependent on a driver extension.
     */
    private static OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instantOf(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
