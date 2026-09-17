package com.revolutclone.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/**
 * Mirrors plans/phase-01-identity-account-gateway.md's user_profile table exactly.
 *
 * <p>Implements {@link Persistable} and always reports {@code isNew() == true}, same reasoning
 * as {@code common-messaging}'s {@code ProcessedEvent} and account-service's {@code Account}
 * (found live, task 01.5): with a manually-assigned (non-generated) {@code id}, Spring Data's
 * default heuristic treats a populated id as "existing" and routes the initial {@code save()}
 * through JPA {@code merge()} instead of {@code persist()}, so {@code @PrePersist} fires on an
 * internal copy rather than the instance the caller is still holding - {@code createdAt} and
 * {@code updatedAt} came back {@code null} in the register response even though both columns
 * were correctly populated in the database. Safe for the {@code PATCH /me} update path too:
 * that path never calls {@code save()} at all, it mutates an already-managed entity inside a
 * {@code @Transactional} method and relies on Hibernate's dirty checking, which is independent
 * of {@link Persistable}.
 */
@Entity
@Table(name = "user_profile")
public class UserProfile implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 2, columnDefinition = "char(2)")
    private String country;

    private String phone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfile() {
        // JPA
    }

    public UserProfile(UUID id, String email, String firstName, String lastName, String country,
                        String phone, UserStatus status) {
        this.id = id;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.country = country;
        this.phone = phone;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    @Override
    @Transient
    public UUID getId() {
        return id;
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    public String getEmail() {
        return email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getCountry() {
        return country;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
