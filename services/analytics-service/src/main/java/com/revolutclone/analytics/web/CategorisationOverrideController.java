package com.revolutclone.analytics.web;

import com.revolutclone.analytics.categorisation.MerchantCategoryOverrideService;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 06.2's storage-and-a-way-to-set-one for user overrides, the categorisation resolver's
 * highest-priority layer. Setting an override here only changes <em>future</em> categorisation -
 * see {@link MerchantCategoryOverrideService}'s javadoc for why retro-applying it to past
 * transactions is out of this task's scope (task 06.4). No UI calls this yet and no test pins
 * its shape, per the task's own scope note.
 */
@RestController
@RequestMapping("/api/analytics/categories/overrides")
public class CategorisationOverrideController {

    private final MerchantCategoryOverrideService overrideService;

    public CategorisationOverrideController(MerchantCategoryOverrideService overrideService) {
        this.overrideService = overrideService;
    }

    @PutMapping
    public ResponseEntity<MerchantCategoryOverrideResponse> setOverride(
            @CurrentUser UserPrincipal principal, @Valid @RequestBody SetMerchantCategoryOverrideRequest request) {

        var override = overrideService.setOverride(UUID.fromString(principal.userId()), request.merchantName(),
                request.category());
        return ResponseEntity.ok(MerchantCategoryOverrideResponse.from(override));
    }
}
