package com.revolutclone.notification.readmodel;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentPartiesRepository extends JpaRepository<PaymentParties, UUID> {
}
