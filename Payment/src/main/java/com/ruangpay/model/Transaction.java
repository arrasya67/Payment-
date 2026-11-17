package com.ruangpay.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public final class Transaction {
    public enum StatusTransaksi { PENDING, SUCCESS, EXPIRED, FAILED }

    private final String id = UUID.randomUUID().toString();
    private final Merchant merchant;
    private final Pelanggan pelanggan;
    private final String orderId;
    private final BigDecimal amount;
    private final BigDecimal baseAmount;
    private final BigDecimal taxAmount;
    private final int uniqueCode;
    private final String paymentMethod = "QRIS";
    private volatile StatusTransaksi status = StatusTransaksi.PENDING;
    private String qrCode;
    private String qrString;
    private final boolean sandbox;
    private final String source;
    private final Merchant.QrisMode qrisMode;
    private volatile String lastUpdateSource = "API";
    private final Instant expiredAt;
    private final Instant createdAt = Instant.now();
    private volatile Instant updatedAt = createdAt;

    public Transaction(Merchant merchant, Pelanggan pelanggan, String orderId,
                       BigDecimal amount, boolean sandbox) {
        this(merchant, pelanggan, orderId, amount, amount, BigDecimal.ZERO, 0,
                sandbox, "API", merchant.getQrisMode());
    }

    public Transaction(Merchant merchant, Pelanggan pelanggan, String orderId,
                       BigDecimal totalAmount, BigDecimal baseAmount, BigDecimal taxAmount,
                       int uniqueCode, boolean sandbox, String source, Merchant.QrisMode qrisMode) {
        this.merchant = merchant;
        this.pelanggan = pelanggan;
        this.orderId = orderId;
        this.amount = totalAmount;
        this.baseAmount = baseAmount;
        this.taxAmount = taxAmount;
        this.uniqueCode = uniqueCode;
        this.sandbox = sandbox;
        this.source = source == null || source.isBlank() ? "API" : source.toUpperCase();
        this.qrisMode = qrisMode;
        this.expiredAt = createdAt.plus(15, ChronoUnit.MINUTES);
    }

    public boolean isPending() { return status == StatusTransaksi.PENDING; }
    public boolean isExpired() { return Instant.now().isAfter(expiredAt); }
    public synchronized boolean updateStatus(StatusTransaksi next) {
        if (!isPending()) return false;
        status = next;
        updatedAt = Instant.now();
        return true;
    }
    public synchronized boolean updateStatus(StatusTransaksi next, String updateSource) {
        boolean changed = updateStatus(next);
        if (changed) lastUpdateSource = updateSource == null ? "SYSTEM" : updateSource.toUpperCase();
        return changed;
    }

    public String getId() { return id; }
    public Merchant getMerchant() { return merchant; }
    public Pelanggan getPelanggan() { return pelanggan; }
    public String getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBaseAmount() { return baseAmount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public int getUniqueCode() { return uniqueCode; }
    public String getPaymentMethod() { return paymentMethod; }
    public StatusTransaksi getStatus() { return status; }
    public String getQrCode() { return qrCode; }
    public void setQrCode(String qrCode) { this.qrCode = qrCode; }
    public String getQrString() { return qrString; }
    public void setQrString(String qrString) { this.qrString = qrString; }
    public boolean isSandbox() { return sandbox; }
    public Instant getExpiredAt() { return expiredAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getSource() { return source; }
    public String getLastUpdateSource() { return lastUpdateSource; }
    public Merchant.QrisMode getQrisMode() { return qrisMode; }
}
