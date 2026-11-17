package com.ruangpay.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerEntry(
        String id, int merchantId, String referenceType, String referenceId,
        String entryType, BigDecimal amount, Instant createdAt
) {
    public LedgerEntry(int merchantId, String referenceType, String referenceId,
                       String entryType, BigDecimal amount) {
        this(UUID.randomUUID().toString(), merchantId, referenceType, referenceId,
                entryType, amount, Instant.now());
    }
}
