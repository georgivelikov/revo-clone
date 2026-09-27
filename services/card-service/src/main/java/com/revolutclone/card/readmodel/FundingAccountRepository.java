package com.revolutclone.card.readmodel;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FundingAccountRepository extends JpaRepository<FundingAccount, UUID> {
}
