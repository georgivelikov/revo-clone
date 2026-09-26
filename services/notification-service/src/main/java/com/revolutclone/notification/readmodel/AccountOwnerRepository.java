package com.revolutclone.notification.readmodel;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountOwnerRepository extends JpaRepository<AccountOwner, UUID> {

    List<AccountOwner> findByUserId(UUID userId);
}
