package com.revolutclone.notification.readmodel;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerAccountRefRepository extends JpaRepository<LedgerAccountRef, UUID> {
}
