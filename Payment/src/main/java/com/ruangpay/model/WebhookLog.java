package com.ruangpay.model;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

public final class WebhookLog {
    private static final AtomicInteger IDS = new AtomicInteger();
    private final int id = IDS.incrementAndGet();
    private final Transaction transaction;
    private final String payload;
    private final Instant receivedAt = Instant.now();
    private boolean processed;

    public WebhookLog(Transaction transaction, String payload) {
        this.transaction = transaction;
        this.payload = payload;
    }

    public void markProcessed() { processed = true; }
    public String getPayload() { return payload; }
    public int getId() { return id; }
    public Transaction getTransaction() { return transaction; }
    public Instant getReceivedAt() { return receivedAt; }
    public boolean isProcessed() { return processed; }
}
