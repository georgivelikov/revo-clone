package com.revolutclone.identity.web;

import com.revolutclone.identity.domain.UserProfile;
import com.revolutclone.identity.domain.UserProfileRepository;
import com.revolutclone.identity.service.RegistrationService;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/identity")
public class IdentityController {

    private final RegistrationService registrationService;
    private final UserProfileRepository userProfileRepository;

    public IdentityController(RegistrationService registrationService,
                               UserProfileRepository userProfileRepository) {
        this.registrationService = registrationService;
        this.userProfileRepository = userProfileRepository;
    }

    /** The one unauthenticated route - see common-security's resource-server config. */
    @PostMapping("/register")
    public ResponseEntity<UserProfileResponse> register(@Valid @RequestBody RegisterRequest request) {
        UserProfile profile = registrationService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(UserProfileResponse.from(profile));
    }

    @GetMapping("/me")
    public UserProfileResponse me(@CurrentUser UserPrincipal principal) {
        return UserProfileResponse.from(findByPrincipal(principal));
    }

    @PatchMapping("/me")
    @Transactional
    public UserProfileResponse patchMe(@CurrentUser UserPrincipal principal,
                                        @RequestBody PatchProfileRequest request) {
        UserProfile profile = findByPrincipal(principal);
        if (request.firstName() != null) {
            profile.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            profile.setLastName(request.lastName());
        }
        if (request.phone() != null) {
            profile.setPhone(request.phone());
        }
        return UserProfileResponse.from(profile);
    }

    private UserProfile findByPrincipal(UserPrincipal principal) {
        return userProfileRepository.findById(UUID.fromString(principal.userId()))
                .orElseThrow(() -> new NoSuchElementException("no profile for the current user"));
    }
}
