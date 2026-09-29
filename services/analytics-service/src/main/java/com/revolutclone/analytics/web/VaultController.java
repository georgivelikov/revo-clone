package com.revolutclone.analytics.web;

import com.revolutclone.analytics.vault.Vault;
import com.revolutclone.analytics.vault.VaultService;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Vault CRUD (task 06.6) - see {@link VaultService}. Move-in/move-out lives in {@link VaultTransferController}. */
@RestController
@RequestMapping("/api/analytics/vaults")
public class VaultController {

    private final VaultService vaultService;

    public VaultController(VaultService vaultService) {
        this.vaultService = vaultService;
    }

    @PostMapping
    public ResponseEntity<VaultResponse> create(@CurrentUser UserPrincipal principal,
            @Valid @RequestBody CreateVaultRequest request) {
        Vault vault = vaultService.create(UUID.fromString(principal.userId()), request.name(),
                request.currency().toUpperCase(), request.goalMinor(), request.roundupEnabled(),
                request.roundupSourceCardId());
        return ResponseEntity.status(HttpStatus.CREATED).body(VaultResponse.from(vault));
    }

    @GetMapping
    public List<VaultResponse> list(@CurrentUser UserPrincipal principal) {
        return vaultService.list(UUID.fromString(principal.userId())).stream().map(VaultResponse::from).toList();
    }

    @GetMapping("/{id}")
    public VaultResponse get(@CurrentUser UserPrincipal principal, @PathVariable UUID id) {
        return VaultResponse.from(vaultService.get(id, UUID.fromString(principal.userId())));
    }

    @PutMapping("/{id}")
    public VaultResponse update(@CurrentUser UserPrincipal principal, @PathVariable UUID id,
            @Valid @RequestBody UpdateVaultRequest request) {
        Vault vault = vaultService.update(id, UUID.fromString(principal.userId()), request.goalMinor(),
                request.roundupEnabled(), request.roundupSourceCardId());
        return VaultResponse.from(vault);
    }
}
