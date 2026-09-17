package com.revolutclone.identity.web;

import com.revolutclone.identity.domain.UserProfile;
import java.time.Instant;
import java.util.UUID;

public record UserProfileResponse(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String country,
        String phone,
        String status,
        Instant createdAt,
        Instant updatedAt) {

    public static UserProfileResponse from(UserProfile profile) {
        return new UserProfileResponse(
                profile.getId(),
                profile.getEmail(),
                profile.getFirstName(),
                profile.getLastName(),
                profile.getCountry(),
                profile.getPhone(),
                profile.getStatus().name(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
