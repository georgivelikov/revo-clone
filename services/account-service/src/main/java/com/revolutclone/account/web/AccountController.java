package com.revolutclone.account.web;

import com.revolutclone.account.domain.Account;
import com.revolutclone.account.domain.AccountRepository;
import com.revolutclone.account.error.AccountNotFoundException;
import com.revolutclone.account.service.AccountWriter;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountRepository repository;
    private final AccountWriter accountWriter;

    public AccountController(AccountRepository repository, AccountWriter accountWriter) {
        this.repository = repository;
        this.accountWriter = accountWriter;
    }

    @GetMapping("/me")
    public List<AccountResponse> me(@CurrentUser UserPrincipal principal) {
        return repository.findByUserId(UUID.fromString(principal.userId())).stream()
                .map(AccountResponse::from)
                .toList();
    }

    /**
     * A 404, never a 403, for another user's account - see {@link AccountNotFoundException} -
     * so the response never confirms the id exists at all.
     */
    @GetMapping("/{id}")
    public AccountResponse byId(@CurrentUser UserPrincipal principal, @PathVariable UUID id) {
        Account account = repository.findByIdAndUserId(id, UUID.fromString(principal.userId()))
                .orElseThrow(() -> new AccountNotFoundException(id));
        return AccountResponse.from(account);
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@CurrentUser UserPrincipal principal,
                                                 @Valid @RequestBody CreateAccountRequest request) {
        Account account = accountWriter.openWallet(
                UUID.fromString(principal.userId()), request.currency().toUpperCase());
        return ResponseEntity.status(HttpStatus.CREATED).body(AccountResponse.from(account));
    }
}
