package com.ruangpay.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class Withdrawal {
    public enum Status { PENDING, APPROVED, PROCESSING, PAID, REJECTED, FAILED, CANCELLED }
    private final String id = UUID.randomUUID().toString();
    private final Merchant merchant;
    private final BigDecimal amount;
    private final String bankCode;
    private final String bankAccountNumber;
    private final String bankAccountName;
    private volatile Status status = Status.PENDING;
    private final Instant createdAt = Instant.now();

    public Withdrawal(Merchant merchant, BigDecimal amount, String bankCode,
                      String bankAccountNumber, String bankAccountName) {
        this.merchant = merchant;
        this.amount = amount;
        this.bankCode = bankCode;
        this.bankAccountNumber = bankAccountNumber;
        this.bankAccountName = bankAccountName;
    }

    public String getId() { return id; }
    public Merchant getMerchant() { return merchant; }
    public BigDecimal getAmount() { return amount; }
    public String getBankCode() { return bankCode; }
    public String getBankAccountNumber() { return bankAccountNumber; }
    public String getBankAccountName() { return bankAccountName; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public synchronized boolean resolve(Status next) {
        if (status != Status.PENDING && status != Status.PROCESSING && status != Status.APPROVED)
            return false;
        if (next != Status.PAID && next != Status.FAILED && next != Status.REJECTED)
            return false;
        status = next;
        return true;
    }
    public Instant getCreatedAt() { return createdAt; }
}
