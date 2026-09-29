package com.revolutclone.analytics.web;

import com.revolutclone.analytics.budget.BudgetService;
import com.revolutclone.security.api.CurrentUser;
import com.revolutclone.security.api.UserPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 06.5's point 1: "A way to set/create a budget... a plain endpoint is fine, mirroring 06.2's
 * override endpoint; no UI exists yet and no test pins its exact shape." Mirrors
 * {@code CategorisationOverrideController} deliberately: a {@code PUT} that upserts on the table's
 * own unique constraint, one endpoint rather than separate create/update verbs.
 */
@RestController
@RequestMapping("/api/analytics/budgets")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    @PutMapping
    public ResponseEntity<BudgetResponse> upsert(@CurrentUser UserPrincipal principal,
            @Valid @RequestBody UpsertBudgetRequest request) {
        var budget = budgetService.upsert(UUID.fromString(principal.userId()), request.category(), request.month(),
                request.limitMinor(), request.currency());
        return ResponseEntity.ok(BudgetResponse.from(budget));
    }

    @GetMapping
    public List<BudgetResponse> list(@CurrentUser UserPrincipal principal) {
        return budgetService.list(UUID.fromString(principal.userId())).stream().map(BudgetResponse::from).toList();
    }
}
