package com.revolutclone.ledger.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, UUID> {
}
