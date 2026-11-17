package com.ruangpay.service;

import com.ruangpay.model.Transaction;
import com.ruangpay.processor.PaymentProcessor;

public final class PollingService {
    private final TransactionManager transactionManager;
    private int interval = 60;
    private volatile boolean running;

    public PollingService(TransactionManager transactionManager) {
        this.transactionManager = transactionManager;
    }

    public void startPolling(int interval) {
        if (running) return;
        this.interval = Math.max(5, interval);
        running = true;
        Thread.startVirtualThread(() -> {
            while (running) {
                pollPendingTransactions();
                try { Thread.sleep(this.interval * 1000L); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        });
    }

    public void pollPendingTransactions() {
        for (Transaction transaction : transactionManager.getPendingTransactions()) {
            if (transaction.isExpired()) {
                transactionManager.expireTransaction(transaction.getId());
                continue;
            }
            PaymentProcessor.PaymentStatus status =
                    transactionManager.getPaymentProcessor().checkStatus(transaction.getId());
            if (status == PaymentProcessor.PaymentStatus.SUCCESS) {
                transactionManager.updateStatus(transaction.getId(), Transaction.StatusTransaksi.SUCCESS);
                transactionManager.notifyMerchant(transaction);
            }
        }
    }

    public void stopPolling() { running = false; }
}
