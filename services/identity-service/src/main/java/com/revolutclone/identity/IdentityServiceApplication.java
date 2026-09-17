package com.revolutclone.identity;

import com.revolutclone.identity.domain.UserProfile;
import com.revolutclone.identity.domain.UserProfileRepository;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Both annotations are required here, not optional: common-messaging's own
 * {@code MessagingAutoConfiguration} declares its own {@code @EntityScan} and
 * {@code @EnableJpaRepositories}, each scoped to its own outbox/idempotent packages. Once
 * either annotation appears anywhere in the context, Boot's corresponding default
 * auto-detection (entity scanning from the {@code @SpringBootApplication} package down, or
 * repository scanning) backs off entirely for the WHOLE context, not just common-messaging's
 * packages. Without this service declaring its own scans, {@link UserProfile} is "not a
 * managed type" and {@link UserProfileRepository} silently never becomes a bean. See
 * docs/spring-boot-4-notes.md.
 */
@SpringBootApplication
@EntityScan(basePackageClasses = UserProfile.class)
@EnableJpaRepositories(basePackageClasses = UserProfileRepository.class)
public class IdentityServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
