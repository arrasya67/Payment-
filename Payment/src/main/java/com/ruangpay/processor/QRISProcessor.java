package com.ruangpay.processor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class QRISProcessor implements PaymentProcessor {
    private final Object apiClient;
    private final Object qrGenerator;
    private final String baseUrl;

    public QRISProcessor(Object apiClient, Object qrGenerator, String baseUrl) {
        this.apiClient = apiClient;
        this.qrGenerator = qrGenerator;
        this.baseUrl = baseUrl;
    }

    @Override
    public QRResponse generateQR(String transactionId, BigDecimal amount, int merchantId) {
        String qr = generateQRString(merchantId, amount, transactionId);
        return new QRResponse("data:text/plain;base64," +
                Base64.getEncoder().encodeToString(qr.getBytes(StandardCharsets.UTF_8)), qr);
    }

    @Override public PaymentStatus checkStatus(String transactionId) { return PaymentStatus.PENDING; }
    @Override public boolean processRefund(String transactionId) { return true; }

    private String generateQRString(int merchantId, BigDecimal amount, String transactionId) {
        // Simulator: string ini BUKAN QRIS resmi — label SIMULATOR, bukan SANDBOX,
        // supaya tidak salah dibaca sebagai mode transaksi (Live juga pakai string ini).
        return "RUANGPAY|SIMULATOR|MID=" + merchantId + "|AMOUNT=" + amount.toPlainString()
                + "|TRX=" + transactionId + "|BASE=" + baseUrl;
    }
}
