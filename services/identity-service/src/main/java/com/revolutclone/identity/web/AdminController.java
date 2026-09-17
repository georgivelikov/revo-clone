package com.revolutclone.identity.web;

import com.revolutclone.identity.service.ReconciliationService;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * plans/phase-01-identity-account-gateway.md's reconciliation endpoint: the dual-write problem
 * the outbox cannot solve, because step 2 (provisioning through
 * {@link com.revolutclone.security.api.IdentityProviderPort}) is an external call outside any
 * local transaction.
 */
@RestController
@RequestMapping("/api/identity/admin")
public class AdminController {

    private final ReconciliationService reconciliationService;

    public AdminController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping("/reconcile")
    public List<UserProfileResponse> reconcile() {
        return reconciliationService.reconcileOrphanedProviderUsers().stream()
                .map(UserProfileResponse::from)
                .toList();
    }
}
