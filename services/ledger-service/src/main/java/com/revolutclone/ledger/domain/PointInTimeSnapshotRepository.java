package com.revolutclone.ledger.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointInTimeSnapshotRepository extends JpaRepository<PointInTimeSnapshot, UUID> {
}
