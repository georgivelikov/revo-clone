package com.revolutclone.account.web;

import com.revolutclone.account.domain.Account;
import com.revolutclone.account.service.AccountWriter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 06.6: analytics-service owns the {@code vault} table (plans/phase-06-vaults-analytics.md's
 * "Vaults" section) but not the account behind it or the money behind that - "analytics-service
 * must never call ledger-service directly or post anything itself". This is the one call it is
 * allowed to make: open the vault's backing {@code VAULT}-type account here, synchronously, and
 * hand back its id.
 *
 * <p><strong>Deliberately not behind a JWT, unlike every other route this service exposes.</strong>
 * The caller is another backend service acting on a user's behalf, not a live user request - there
 * is no JWT to check, and this project has no service-to-service client-credentials identity yet
 * (a Phase 07 concern, the same gap card-service's {@code NetworkPanController} javadoc names for
 * the identical reason). Not routed by {@code api-gateway} - service-to-service only, over the
 * internal Docker network.
 */
@RestController
@RequestMapping("/internal/accounts")
public class InternalVaultAccountController {

    private final AccountWriter accountWriter;

    public InternalVaultAccountController(AccountWriter accountWriter) {
        this.accountWriter = accountWriter;
    }

    @PostMapping("/vaults")
    public ResponseEntity<AccountResponse> openVault(@Valid @RequestBody OpenVaultAccountRequest request) {
        Account account = accountWriter.openVault(
                request.userId(), request.currency().toUpperCase(), request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }
}
