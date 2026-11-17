package com.ruangpay.processor;

import java.math.BigDecimal;

public interface PaymentProcessor {
    QRResponse generateQR(String transactionId, BigDecimal amount, int merchantId);
    PaymentStatus checkStatus(String transactionId);
    boolean processRefund(String transactionId);

    record QRResponse(String qrCode, String qrString) {}
    enum PaymentStatus { PENDING, SUCCESS, EXPIRED, FAILED }
}
