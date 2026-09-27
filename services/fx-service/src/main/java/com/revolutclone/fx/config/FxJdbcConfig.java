package com.revolutclone.fx.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code fx_quote} is written through {@link JdbcClient} rather than JPA - see
 * {@code FxQuoteRepository} for why the {@code numeric(20,10)} rate columns force that choice.
 *
 * <p>Declared here rather than relied upon from auto-configuration, for the reason
 * docs/spring-boot-4-notes.md keeps recording: Boot 4.1 splits auto-configuration per concern, and
 * which module carries {@code JdbcClient}'s is not something to assume. Constructing it from the
 * {@link DataSource} is a one-liner with no ambiguity, and {@link ConditionalOnMissingBean} means an
 * auto-configured one still wins if it is present.
 */
@Configuration
public class FxJdbcConfig {

    @Bean
    @ConditionalOnMissingBean
    JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }
}
