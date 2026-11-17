package com.ruangpay.service;

import com.ruangpay.model.Withdrawal;
import com.ruangpay.util.Json;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TelegramBotService {
    private final String token = System.getenv().getOrDefault("TELEGRAM_BOT_TOKEN", "");
    private final String adminChatId = System.getenv().getOrDefault("TELEGRAM_ADMIN_CHAT_ID", "");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Withdrawal> withdrawals;
    private final BiConsumer<Withdrawal, Withdrawal.Status> statusHandler;
    private volatile long offset;
    private final Pattern withdrawalId = Pattern.compile("Withdrawal #([a-f0-9-]{36})", Pattern.CASE_INSENSITIVE);

    public TelegramBotService(Map<String, Withdrawal> withdrawals,
                              BiConsumer<Withdrawal, Withdrawal.Status> statusHandler) {
        this.withdrawals = withdrawals;
        this.statusHandler = statusHandler;
    }

    public boolean enabled() { return !token.isBlank() && !adminChatId.isBlank(); }

    public void start() {
        if (!enabled()) {
            System.out.println("Telegram disabled: TELEGRAM_BOT_TOKEN/TELEGRAM_ADMIN_CHAT_ID not set");
            return;
        }
        deleteWebhookForPolling();
        System.out.println("Telegram polling enabled for configured admin chat");
        Thread.startVirtualThread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { poll(); } catch (Exception e) {
                    try { Thread.sleep(3000); } catch (InterruptedException x) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        });
    }

    private void deleteWebhookForPolling() {
        try {
            HttpRequest req = HttpRequest.newBuilder(api("deleteWebhook?drop_pending_updates=false"))
                    .timeout(Duration.ofSeconds(8)).GET().build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("Telegram webhook cleanup failed; polling will retry");
        }
    }

    public void notifyNewTransaction(String id, String merchant, String customer,
                                     String amount, String qrisMode, String source) {
        send("🧾 <b>TRANSAKSI BARU</b>\n"
                + "ID: <code>" + esc(id) + "</code>\n"
                + "Merchant: " + esc(merchant) + "\n"
                + "Pelanggan: " + esc(customer) + "\n"
                + "Total: <b>Rp " + esc(amount) + "</b>\n"
                + "QRIS: " + esc(qrisMode) + "\nSumber: " + esc(source));
    }

    public void notifyPaymentSuccess(String id, String merchant, String amount, String source) {
        send("✅ <b>PEMBAYARAN DITERIMA</b>\nID: <code>" + esc(id)
                + "</code>\nMerchant: " + esc(merchant) + "\nTotal: <b>Rp "
                + esc(amount) + "</b>\nDiverifikasi melalui: " + esc(source));
    }

    public void notifyWithdrawal(Withdrawal w) {
        send("🏦 <b>REQUEST WITHDRAWAL</b>\nWithdrawal #" + esc(w.getId())
                + "\nMerchant: " + esc(w.getMerchant().getName())
                + "\nNominal: <b>Rp " + esc(w.getAmount().toPlainString()) + "</b>"
                + "\nBank: " + esc(w.getBankCode()) + " ••••"
                + esc(last4(w.getBankAccountNumber()))
                + "\n\nBalas pesan ini dengan <b>berhasil</b> atau <b>gagal</b>."
                + "\nAlternatif: <code>/wd_success " + esc(w.getId())
                + "</code> atau <code>/wd_failed " + esc(w.getId()) + "</code>");
    }

    public void send(String html) {
        if (!enabled()) return;
        try {
            String body = "chat_id=" + enc(adminChatId) + "&parse_mode=HTML&text=" + enc(html);
            HttpRequest req = HttpRequest.newBuilder(api("sendMessage"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private void poll() throws Exception {
        URI uri = api("getUpdates?timeout=20&offset=" + offset + "&allowed_updates=%5B%22message%22%5D");
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).GET().build();
        HttpResponse<String> response = http.send(req, HttpResponse.BodyHandlers.ofString());
        Map<String,Object> root = Json.parseObject(response.body());
        if (!(root.get("result") instanceof List<?> updates)) return;
        for (Object item : updates) {
            if (!(item instanceof Map<?,?> u)) continue;
            Number updateId = (Number) u.get("update_id");
            if (updateId != null) offset = Math.max(offset, updateId.longValue() + 1);
            if (!(u.get("message") instanceof Map<?,?> message)) continue;
            Map<?,?> chat = message.get("chat") instanceof Map<?,?> c ? c : Map.of();
            if (!adminChatId.equals(String.valueOf(chat.get("id")))) continue;
            String text = String.valueOf(message.containsKey("text") ? message.get("text") : "").trim();
            String id = commandId(text);
            if (id == null && message.get("reply_to_message") instanceof Map<?,?> reply) {
                id = extractId(String.valueOf(reply.containsKey("text") ? reply.get("text") : ""));
            }
            if (id == null) continue;
            Withdrawal w = withdrawals.get(id);
            if (w == null) { send("❌ Withdrawal tidak ditemukan: <code>" + esc(id) + "</code>"); continue; }
            String lower = text.toLowerCase(Locale.ROOT);
            if (lower.equals("berhasil") || lower.startsWith("/wd_success")) {
                statusHandler.accept(w, Withdrawal.Status.PAID);
                send("✅ Withdrawal <code>" + esc(id) + "</code> ditandai <b>PAID</b>.");
            } else if (lower.equals("gagal") || lower.startsWith("/wd_failed")) {
                statusHandler.accept(w, Withdrawal.Status.FAILED);
                send("❌ Withdrawal <code>" + esc(id) + "</code> ditandai <b>FAILED</b>.");
            }
        }
    }

    private String commandId(String text) {
        String[] parts = text.split("\\s+");
        if (parts.length >= 2 && (parts[0].equalsIgnoreCase("/wd_success")
                || parts[0].equalsIgnoreCase("/wd_failed"))) return parts[1];
        return null;
    }
    private String extractId(String text) {
        Matcher m = withdrawalId.matcher(text);
        return m.find() ? m.group(1) : null;
    }
    private URI api(String method) { return URI.create("https://api.telegram.org/bot" + token + "/" + method); }
    private static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String esc(String value) {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }
    private static String last4(String s) { return s.length() <= 4 ? s : s.substring(s.length() - 4); }
}