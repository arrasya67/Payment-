package com.ruangpay.model;

import java.math.BigDecimal;
import java.time.Instant;

public final class Merchant {
    public enum QrisMode { RUANGPAY, OWN }
    private final int id;
    private final String name;
    private final String email;
    private final String passwordHash;
    private final String apiKey;
    private final String apiKeyLive;
    private final String apiKeySandbox;
    private final String listenerSecret;
    private String callbackUrl;
    private BigDecimal balanceLive = BigDecimal.ZERO;
    private BigDecimal balanceSandbox = BigDecimal.ZERO;
    private QrisMode qrisMode = QrisMode.RUANGPAY;
    private String ownQrisRaw = "";
    private String ownQrisImageData = "";
    private final Instant createdAt = Instant.now();

    public Merchant(int id, String name, String email, String passwordHash,
                    String apiKeyLive, String apiKeySandbox, String listenerSecret, String callbackUrl) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.apiKey = apiKeySandbox;
        this.apiKeyLive = apiKeyLive;
        this.apiKeySandbox = apiKeySandbox;
        this.listenerSecret = listenerSecret;
        this.callbackUrl = callbackUrl == null ? "" : callbackUrl;
    }

    public boolean validateApiKey(String key) {
        return key != null && (apiKeySandbox.equals(key) || apiKeyLive.equals(key));
    }

    public synchronized void creditSandbox(BigDecimal amount) { balanceSandbox = balanceSandbox.add(amount); }
    public synchronized boolean holdSandbox(BigDecimal amount) {
        if (amount.signum() <= 0 || balanceSandbox.compareTo(amount) < 0) return false;
        balanceSandbox = balanceSandbox.subtract(amount);
        return true;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public String getApiKey() { return apiKey; }
    public String getApiKeyLive() { return apiKeyLive; }
    public String getApiKeySandbox() { return apiKeySandbox; }
    public String getListenerSecret() { return listenerSecret; }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }
    public BigDecimal getBalanceLive() { return balanceLive; }
    public synchronized BigDecimal getBalanceSandbox() { return balanceSandbox; }
    public Instant getCreatedAt() { return createdAt; }
    public QrisMode getQrisMode() { return qrisMode; }
    public String getOwnQrisRaw() { return ownQrisRaw; }
    public String getOwnQrisImageData() { return ownQrisImageData; }
    public synchronized void configureQris(QrisMode mode, String raw, String imageData) {
        this.qrisMode = mode;
        this.ownQrisRaw = raw == null ? "" : raw.trim();
        this.ownQrisImageData = imageData == null ? "" : imageData.trim();
    }
}
