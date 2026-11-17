package com.ruangpay.service;

import com.ruangpay.model.*;
import com.ruangpay.processor.PaymentProcessor;
import com.ruangpay.util.QrisUtil;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class TransactionManager {
    private final PaymentProcessor paymentProcessor;
    private final Map<String, Transaction> transactionRepository = new ConcurrentHashMap<>();
    private final List<WebhookLog> webhookLogRepository = new CopyOnWriteArrayList<>();
    private final Object databaseConnection = new Object();
    private final List<LedgerEntry> ledger = new CopyOnWriteArrayList<>();
    private final BlockingQueue<Runnable> eventQueue = new LinkedBlockingQueue<>();
    private final RecentTransactionList recent = new RecentTransactionList(100);
    private final java.util.Set<String> processedExternalEvents = ConcurrentHashMap.newKeySet();
    private final AtomicInteger customerIds = new AtomicInteger();
    private final Map<String, Pelanggan> customersByEmail = new ConcurrentHashMap<>();
    private volatile java.util.function.Consumer<Transaction> statusChangeListener;

    /** Dipanggil saat status menjadi final — dipakai untuk callback L6. */
    public void setStatusChangeListener(java.util.function.Consumer<Transaction> listener) {
        this.statusChangeListener = listener;
    }

    public TransactionManager(PaymentProcessor paymentProcessor) {
        this.paymentProcessor = paymentProcessor;
        Thread.startVirtualThread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { eventQueue.take().run(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException ignored) {}
            }
        });
    }

    public Transaction createTransaction(Merchant merchant, Pelanggan pelanggan,
                                         String orderId, BigDecimal amount) {
        return createTransaction(merchant, pelanggan, orderId, amount, true);
    }

    public Transaction createTransaction(Merchant merchant, Pelanggan pelanggan,
                                         String orderId, BigDecimal amount, boolean sandbox) {
        return createTransaction(merchant, pelanggan, orderId, amount, amount,
                BigDecimal.ZERO, 0, sandbox, "API");
    }

    public Transaction createTransaction(Merchant merchant, Pelanggan pelanggan,
                                         String orderId, BigDecimal totalAmount,
                                         BigDecimal baseAmount, BigDecimal taxAmount,
                                         int uniqueCode, boolean sandbox, String source) {
        if (totalAmount == null || totalAmount.signum() <= 0) throw new IllegalArgumentException("Nominal harus lebih dari 0");
        boolean duplicate = transactionRepository.values().stream().anyMatch(t ->
                t.getMerchant().getId() == merchant.getId() && t.getOrderId().equals(orderId)
                        && t.isSandbox() == sandbox);
        if (duplicate) throw new IllegalStateException("orderId sudah digunakan");
        Transaction transaction = new Transaction(merchant, pelanggan, orderId, totalAmount,
                baseAmount, taxAmount, uniqueCode, sandbox, source, merchant.getQrisMode());
        if (merchant.getQrisMode() == Merchant.QrisMode.OWN) {
            if (!merchant.getOwnQrisRaw().isBlank())
                transaction.setQrString(QrisUtil.makeDynamic(merchant.getOwnQrisRaw(), totalAmount));
            transaction.setQrCode(merchant.getOwnQrisImageData());
            if ((transaction.getQrString() == null || transaction.getQrString().isBlank())
                    && (transaction.getQrCode() == null || transaction.getQrCode().isBlank()))
                throw new IllegalStateException("QRIS milik merchant belum diunggah");
        } else {
            PaymentProcessor.QRResponse qr = paymentProcessor.generateQR(
                    transaction.getId(), totalAmount, merchant.getId());
            transaction.setQrCode(qr.qrCode());
            transaction.setQrString(qr.qrString());
        }
        transactionRepository.put(transaction.getId(), transaction);
        recent.addFirst(transaction);
        return transaction;
    }

    public boolean updateStatus(String transactionId, Transaction.StatusTransaksi status) {
        Transaction transaction = transactionRepository.get(transactionId);
        if (transaction == null) return false;
        synchronized (databaseConnection) {
            if (!transaction.updateStatus(status)) return false;
            if (status == Transaction.StatusTransaksi.SUCCESS
                    && transaction.getQrisMode() == Merchant.QrisMode.RUANGPAY) {
                merchantCreditExactlyOnce(transaction);
            }
            java.util.function.Consumer<Transaction> l = statusChangeListener;
            if (l != null && transaction.getStatus() != Transaction.StatusTransaksi.PENDING) l.accept(transaction);
            return true;
        }
    }

    private void merchantCreditExactlyOnce(Transaction transaction) {
        boolean exists = ledger.stream().anyMatch(e -> e.referenceType().equals("TRANSACTION")
                && e.referenceId().equals(transaction.getId()) && e.entryType().equals("CREDIT"));
        if (!exists) {
            ledger.add(new LedgerEntry(transaction.getMerchant().getId(), "TRANSACTION",
                    transaction.getId(), "CREDIT", transaction.getAmount()));
            transaction.getMerchant().creditSandbox(transaction.getAmount());
        }
    }

    public List<Transaction> getPendingTransactions() {
        return transactionRepository.values().stream().filter(Transaction::isPending).toList();
    }

    public boolean handleWebhook(String payload) { return payload != null && !payload.isBlank(); }
    public boolean expireTransaction(String transactionId) {
        return updateStatus(transactionId, Transaction.StatusTransaksi.EXPIRED);
    }
    public boolean notifyMerchant(Transaction transaction) { return transaction != null; }
    public PaymentProcessor getPaymentProcessor() { return paymentProcessor; }

    public Pelanggan newCustomer(String name, String email, String phone) {
        // Laporan §3.10.1: email adalah candidate key — pelanggan yang sama
        // (email sama) tidak dibuat baru, tapi dipakai lagi.
        String key = email == null ? "" : email.trim().toLowerCase();
        if (!key.isBlank()) {
            Pelanggan existing = customersByEmail.get(key);
            if (existing != null) return existing;
            Pelanggan p = new Pelanggan(customerIds.incrementAndGet(), name, email, phone);
            customersByEmail.put(key, p);
            return p;
        }
        return new Pelanggan(customerIds.incrementAndGet(), name, email, phone);
    }

    /** Daftar pelanggan unik milik merchant ini (dedupe by email). */
    public List<Pelanggan> listPelanggan(int merchantId) {
        Map<String, Pelanggan> seen = new java.util.LinkedHashMap<>();
        for (Transaction t : forMerchant(merchantId)) {
            Pelanggan p = t.getPelanggan();
            String key = p.getEmail() == null || p.getEmail().isBlank()
                    ? "id:" + p.getId() : p.getEmail().toLowerCase();
            seen.putIfAbsent(key, p);
        }
        return List.copyOf(seen.values());
    }

    /** L4: catat webhook yang masuk ke webhook_log (selalu, walau duplikat). */
    public WebhookLog recordWebhookLog(Transaction transaction, String payload, boolean processed) {
        WebhookLog log = new WebhookLog(transaction, payload);
        if (processed) log.markProcessed();
        webhookLogRepository.add(log);
        return log;
    }

    public List<WebhookLog> listWebhookLogs(int merchantId) {
        return webhookLogRepository.stream()
                .filter(w -> w.getTransaction().getMerchant().getId() == merchantId)
                .sorted((a, b) -> b.getReceivedAt().compareTo(a.getReceivedAt()))
                .collect(Collectors.toList());
    }
    public Transaction find(String id) { return transactionRepository.get(id); }
    public Transaction findByOrderId(int merchantId, String orderId) {
        return transactionRepository.values().stream()
                .filter(t -> t.getMerchant().getId() == merchantId && t.getOrderId().equals(orderId))
                .findFirst().orElse(null);
    }
    public boolean claimExternalEvent(String eventId) { return processedExternalEvents.add(eventId); }
    public List<Transaction> allRecent() { return recent.snapshot(); }
    public List<Transaction> forMerchant(int merchantId) {
        return recent.snapshot().stream().filter(t -> t.getMerchant().getId() == merchantId)
                .collect(Collectors.toList());
    }
    public List<LedgerEntry> ledgerFor(int merchantId) {
        return ledger.stream().filter(e -> e.merchantId() == merchantId).toList();
    }
    public void enqueue(Runnable event) { eventQueue.offer(event); }
}
