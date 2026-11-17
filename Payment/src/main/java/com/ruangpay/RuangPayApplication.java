package com.ruangpay;

import com.ruangpay.model.*;
import com.ruangpay.processor.QRISProcessor;
import com.ruangpay.service.*;
import com.ruangpay.util.*;
import com.sun.net.httpserver.*;

import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class RuangPayApplication {
    private final Map<Integer, Merchant> merchants = new ConcurrentHashMap<>();
    private final Map<String, Merchant> byEmail = new ConcurrentHashMap<>();
    private final Map<String, Merchant> apiKeyCache = new ConcurrentHashMap<>(); // HashMap concept
    private final Map<String, Merchant> sessions = new ConcurrentHashMap<>();
    private final Map<String, Withdrawal> withdrawals = new ConcurrentHashMap<>();
    private final AtomicInteger merchantIds = new AtomicInteger();
    private final TransactionManager transactionManager =
            new TransactionManager(new QRISProcessor(new Object(), new Object(), "sandbox"));
    private final PollingService pollingService = new PollingService(transactionManager);
    private final TelegramBotService telegram = new TelegramBotService(withdrawals, this::resolveWithdrawal);
    private final EventJournal journal = new EventJournal();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** L6 — callback ke merchant saat status menjadi final (best-effort). */
    private void notifyCallback(Transaction t) {
        String url = t.getMerchant().getCallbackUrl();
        if (url == null || url.isBlank()) return;
        try {
            String body = Json.stringify(Map.of(
                    "transactionId", t.getId(), "orderId", t.getOrderId(),
                    "amount", t.getAmount(), "status", t.getStatus().name(),
                    "merchantId", t.getMerchant().getId()));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            System.out.println("[callback] " + t.getId() + " -> " + url + " : HTTP " + res.statusCode());
        } catch (Exception e) {
            System.out.println("[callback] GAGAL " + t.getId() + " -> " + url + " : " + e.getMessage());
        }
    }

    public static void main(String[] args) throws Exception { new RuangPayApplication().start(); }

    private void start() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api", this::handleApi);
        server.createContext("/v1", this::handleApi);
        server.createContext("/", this::serveStatic);
        transactionManager.setStatusChangeListener(this::notifyCallback);
        pollingService.startPolling(15);
        telegram.start();
        server.start();
        System.out.println("RuangPay running on http://localhost:" + port);
    }

    private void handleApi(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/api/health")) { json(ex, 200, Map.of("status","ok","time",Instant.now().toString())); return; }
            if (path.equals("/api/auth/register") && method.equals("POST")) { register(ex); return; }
            if (path.equals("/api/auth/login") && method.equals("POST")) { login(ex); return; }
            if (path.equals("/api/me") && method.equals("GET")) { me(ex, sessionMerchant(ex)); return; }
            if (path.equals("/api/me/callback") && method.equals("POST")) { updateCallback(ex, sessionMerchant(ex)); return; }
            if (path.equals("/api/merchants") && method.equals("GET")) { listMerchants(ex, sessionMerchant(ex)); return; }
            if (path.equals("/api/pelanggan") && method.equals("GET")) { listPelanggan(ex, sessionMerchant(ex)); return; }
            if (path.equals("/api/webhooks") && method.equals("POST")) { handleWebhook(ex, keyOrSessionMerchant(ex)); return; }
            if (path.equals("/api/webhook-logs") && method.equals("GET")) { listWebhookLogs(ex, sessionMerchant(ex)); return; }
            if (path.equals("/api/dashboard") && method.equals("GET")) { dashboard(ex, sessionMerchant(ex)); return; }
            if (path.equals("/api/settings/qris") && method.equals("POST")) { updateQrisSettings(ex, sessionMerchant(ex)); return; }
            if (path.equals("/v1/transactions") && method.equals("POST")) { createTransaction(ex, keyMerchant(ex)); return; }
            if (path.equals("/v1/transactions") && method.equals("GET")) { listTransactions(ex, keyOrSessionMerchant(ex)); return; }
            if (path.matches("/v1/transactions/[^/]+") && method.equals("GET")) {
                getTransaction(ex, keyOrSessionMerchant(ex), path.substring(path.lastIndexOf('/') + 1)); return;
            }
            if (path.equals("/legacy/check_status.php") && method.equals("GET")) { legacyStatus(ex); return; }
            if (path.equals("/v1/listener/shopeepay/webhook") && method.equals("POST")) {
                handleShopeePayListener(ex, keyMerchant(ex)); return;
            }
            if (path.matches("/v1/sandbox/transactions/[^/]+/simulate") && method.equals("POST")) {
                simulate(ex, keyOrSessionMerchant(ex), path.split("/")[4]); return;
            }
            if (path.equals("/v1/withdrawals") && method.equals("POST")) { createWithdrawal(ex, keyOrSessionMerchant(ex)); return; }
            if (path.equals("/v1/withdrawals") && method.equals("GET")) { listWithdrawals(ex, keyOrSessionMerchant(ex)); return; }
            json(ex, 404, error("NOT_FOUND", "Endpoint tidak ditemukan"));
        } catch (AuthException e) {
            json(ex, 401, error("UNAUTHORIZED", e.getMessage()));
        } catch (IllegalStateException e) {
            json(ex, 409, error("CONFLICT", e.getMessage()));
        } catch (IllegalArgumentException e) {
            json(ex, 400, error("BAD_REQUEST", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            json(ex, 500, error("INTERNAL_ERROR", "Terjadi kesalahan pada server"));
        }
    }

    private void register(HttpExchange ex) throws IOException {
        Map<String,Object> b = body(ex);
        String name = required(b, "name"), email = required(b, "email").toLowerCase();
        String password = required(b, "password");
        if (!email.contains("@")) throw new IllegalArgumentException("Email tidak valid");
        if (password.length() < 8) throw new IllegalArgumentException("Password minimal 8 karakter");
        if (byEmail.containsKey(email)) throw new IllegalStateException("Email sudah terdaftar");
        Merchant m = new Merchant(merchantIds.incrementAndGet(), name, email,
                Security.hashPassword(password), Security.token("sk_live_"),
                Security.token("sk_sandbox_"), Security.token("whsec_listener_"), "");
        merchants.put(m.getId(), m); byEmail.put(email, m);
        apiKeyCache.put(m.getApiKeyLive(), m); apiKeyCache.put(m.getApiKeySandbox(), m);
        String token = Security.token("sess_"); sessions.put(token, m);
        json(ex, 201, Map.of("token",token,"merchant",merchantMap(m,true)));
    }

    private void login(HttpExchange ex) throws IOException {
        Map<String,Object> b = body(ex);
        Merchant m = byEmail.get(required(b,"email").toLowerCase());
        if (m == null || !Security.verifyPassword(required(b,"password"), m.getPasswordHash()))
            throw new AuthException("Email atau password salah");
        String token = Security.token("sess_"); sessions.put(token, m);
        json(ex, 200, Map.of("token",token,"merchant",merchantMap(m,true)));
    }

    private void me(HttpExchange ex, Merchant m) throws IOException { json(ex, 200, merchantMap(m,true)); }

    // ---------- L1: daftar merchant + ubah callback URL ----------
    private void listMerchants(HttpExchange ex, Merchant me) throws IOException {
        List<Map<String,Object>> data = merchants.values().stream()
                .sorted(Comparator.comparingInt(Merchant::getId))
                .map(m -> {
                    Map<String,Object> o = merchantMap(m, m.getId() == me.getId());
                    o.remove("listenerSecret");
                    return o;
                }).toList();
        json(ex, 200, Map.of("data", data));
    }

    private void updateCallback(HttpExchange ex, Merchant m) throws IOException {
        String url = required(body(ex), "callbackUrl");
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            throw new IllegalArgumentException("callback_url harus diawali http:// atau https://");
        m.setCallbackUrl(url);
        json(ex, 200, merchantMap(m, true));
    }

    // ---------- L2: daftar pelanggan unik ----------
    private void listPelanggan(HttpExchange ex, Merchant m) throws IOException {
        List<Map<String,Object>> data = transactionManager.listPelanggan(m.getId()).stream()
                .map(p -> Map.<String,Object>of("id", p.getId(), "name", p.getName(),
                        "email", p.getEmail(), "phoneNumber", p.getPhoneNumber(),
                        "createdAt", p.getCreatedAt().toString())).toList();
        json(ex, 200, Map.of("data", data));
    }

    // ---------- L4: webhook generik (selalu dicatat ke webhook_log) ----------
    private void handleWebhook(HttpExchange ex, Merchant m) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (raw.isBlank()) throw new IllegalArgumentException("Body webhook kosong");
        Map<String,Object> payload = Json.parseObject(raw);
        String txId = String.valueOf(payload.getOrDefault("transactionId", ""));
        String orderId = String.valueOf(payload.getOrDefault("orderId", ""));
        String status = String.valueOf(payload.getOrDefault("status", ""));
        Transaction t = !txId.isBlank() ? transactionManager.find(txId)
                : transactionManager.findByOrderId(m.getId(), orderId);
        if (t == null || t.getMerchant().getId() != m.getId())
            throw new IllegalArgumentException("Transaksi tidak ditemukan");
        if (status.isBlank()) throw new IllegalArgumentException("status wajib diisi (SUCCESS atau EXPIRED)");
        Transaction.StatusTransaksi next;
        try { next = Transaction.StatusTransaksi.valueOf(status.toUpperCase()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("status tidak valid: " + status); }
        if (next == Transaction.StatusTransaksi.FAILED && !t.isSandbox())
            throw new IllegalArgumentException("FAILED hanya berlaku di Sandbox");
        boolean changed = transactionManager.updateStatus(t.getId(), next);
        WebhookLog log = transactionManager.recordWebhookLog(t, raw, true);
        json(ex, 200, Map.of("accepted", true, "webhookLogId", log.getId(),
                "transactionId", t.getId(), "status", t.getStatus().name(), "changed", changed));
    }

    private void listWebhookLogs(HttpExchange ex, Merchant m) throws IOException {
        String q = ex.getRequestURI().getRawQuery();
        String filter = null;
        if (q != null) for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals("transaction_id"))
                filter = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        final String f = filter;
        List<Map<String,Object>> data = transactionManager.listWebhookLogs(m.getId()).stream()
                .filter(w -> f == null || f.isBlank() || w.getTransaction().getId().equals(f))
                .map(w -> Map.<String,Object>of("id", w.getId(),
                        "transactionId", w.getTransaction().getId(),
                        "orderId", w.getTransaction().getOrderId(),
                        "payload", w.getPayload(), "processed", w.isProcessed(),
                        "receivedAt", w.getReceivedAt().toString())).toList();
        json(ex, 200, Map.of("data", data));
    }

    private void dashboard(HttpExchange ex, Merchant m) throws IOException {
        List<Transaction> txs = transactionManager.forMerchant(m.getId());
        long success = txs.stream().filter(t -> t.getStatus() == Transaction.StatusTransaksi.SUCCESS).count();
        json(ex, 200, Map.of(
                "merchant", merchantMap(m,true),
                "metrics", Map.of("balanceSandbox",m.getBalanceSandbox(),"balanceLive",m.getBalanceLive(),
                        "transactions",txs.size(),"success",success),
                "transactions", txs.stream().limit(8).map(this::transactionMap).toList()
        ));
    }

    private void createTransaction(HttpExchange ex, Merchant m) throws IOException {
        Map<String,Object> b = body(ex);
        String orderId = required(b,"orderId");
        BigDecimal baseAmount = new BigDecimal(String.valueOf(b.get("amount")));
        if (baseAmount.compareTo(new BigDecimal("1000")) < 0)
            throw new IllegalArgumentException("Minimal transaksi Rp 1.000");
        // KosHub DIHAPUS total (keputusan Agis 2026-11-16/17): tidak ada lagi
        // legacy pricing, kode unik, atau pajak dari sumber lama.
        String source = String.valueOf(b.getOrDefault("source", "API")).toUpperCase();
        if (source.equals("KOSHUB")) source = "API";
        // environment: SANDBOX (default) atau LIVE — status FAILED hanya di Sandbox.
        String env = String.valueOf(b.getOrDefault("environment", "SANDBOX")).toUpperCase();
        boolean sandbox = !env.equals("LIVE");
        BigDecimal total = baseAmount;
        @SuppressWarnings("unchecked") Map<String,Object> customer =
                b.get("customer") instanceof Map<?,?> map ? (Map<String,Object>) map : Map.of();
        Pelanggan p = transactionManager.newCustomer(
                String.valueOf(customer.getOrDefault("name","Pelanggan")),
                String.valueOf(customer.getOrDefault("email","")),
                String.valueOf(customer.getOrDefault("phoneNumber","")));
        Transaction t = transactionManager.createTransaction(m,p,orderId,total,baseAmount,
                BigDecimal.ZERO, 0, sandbox, source);
        journal.record("transaction.created", Map.of(
                "transactionId",t.getId(),"merchantId",m.getId(),"orderId",t.getOrderId(),
                "amount",t.getAmount(),"source",t.getSource(),"qrisMode",t.getQrisMode().name()));
        telegram.notifyNewTransaction(t.getId(), m.getName(), p.getName(),
                t.getAmount().toPlainString(), t.getQrisMode().name(), t.getSource());
        json(ex, 201, transactionMap(t));
    }

    private void updateQrisSettings(HttpExchange ex, Merchant m) throws IOException {
        Map<String,Object> b = body(ex);
        Merchant.QrisMode mode = Merchant.QrisMode.valueOf(required(b, "mode").toUpperCase());
        String raw = String.valueOf(b.getOrDefault("qrisRaw", ""));
        String image = String.valueOf(b.getOrDefault("qrisImageData", ""));
        boolean preserve = Boolean.parseBoolean(String.valueOf(b.getOrDefault("preserveExisting", false)));
        if (preserve && raw.isBlank()) raw = m.getOwnQrisRaw();
        if (preserve && image.isBlank()) image = m.getOwnQrisImageData();
        if (raw.length() > 5000) throw new IllegalArgumentException("QRIS raw terlalu panjang");
        if (image.length() > 1_500_000) throw new IllegalArgumentException("Gambar QRIS maksimal sekitar 1 MB");
        if (mode == Merchant.QrisMode.OWN && raw.isBlank() && image.isBlank())
            throw new IllegalArgumentException("Unggah gambar QRIS atau isi QRIS raw");
        m.configureQris(mode, raw, image);
        json(ex, 200, Map.of("mode",m.getQrisMode().name(),"hasRaw",!raw.isBlank(),"hasImage",!image.isBlank()));
    }

    private void getTransaction(HttpExchange ex, Merchant m, String id) throws IOException {
        Transaction t = transactionManager.find(id);
        if (t == null || t.getMerchant().getId() != m.getId())
            throw new IllegalArgumentException("Transaksi tidak ditemukan");
        json(ex, 200, transactionMap(t));
    }

    private void legacyStatus(HttpExchange ex) throws IOException {
        String query = Optional.ofNullable(ex.getRequestURI().getRawQuery()).orElse("");
        String id = Arrays.stream(query.split("&")).map(x -> x.split("=",2))
                .filter(x -> x.length == 2 && x[0].equals("id")).map(x -> x[1]).findFirst().orElse("");
        Transaction t = transactionManager.find(id);
        json(ex, 200, Map.of("status", t == null ? "NOT_FOUND" : t.getStatus().name()));
    }

    private void listTransactions(HttpExchange ex, Merchant m) throws IOException {
        json(ex,200,Map.of("data",transactionManager.forMerchant(m.getId()).stream()
                .map(this::transactionMap).toList()));
    }

    private void simulate(HttpExchange ex, Merchant m, String id) throws IOException {
        Transaction t = transactionManager.find(id);
        if (t == null || t.getMerchant().getId() != m.getId()) throw new IllegalArgumentException("Transaksi tidak ditemukan");
        String status = required(body(ex),"status").toUpperCase();
        Transaction.StatusTransaksi next = Transaction.StatusTransaksi.valueOf(status);
        if (next == Transaction.StatusTransaksi.PENDING) throw new IllegalArgumentException("Status simulasi tidak valid");
        if (next == Transaction.StatusTransaksi.FAILED && !t.isSandbox())
            throw new IllegalArgumentException("FAILED hanya berlaku di Sandbox — Live hanya PENDING/SUCCESS/EXPIRED");
        boolean changed = transactionManager.updateStatus(id,next);
        json(ex,200,Map.of("changed",changed,"transaction",transactionMap(t)));
    }

    private void handleShopeePayListener(HttpExchange ex, Merchant m) throws IOException {
        String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String signature = ex.getRequestHeaders().getFirst("X-Listener-Signature");
        String expected = Security.hmacSha256(m.getListenerSecret(), raw);
        if (!Security.secureEquals(expected, signature))
            throw new AuthException("Signature notification listener tidak valid");
        Map<String,Object> payload = Json.parseObject(raw);
        String eventId = required(payload, "eventId");
        if (!transactionManager.claimExternalEvent(eventId)) {
            json(ex, 200, Map.of("accepted",true,"duplicate",true)); return;
        }
        String orderId = required(payload, "orderId");
        BigDecimal amount = new BigDecimal(String.valueOf(payload.get("amount")));
        Transaction t = transactionManager.findByOrderId(m.getId(), orderId);
        if (t == null || t.getAmount().compareTo(amount) != 0)
            throw new IllegalArgumentException("Order atau nominal tidak cocok");
        if (!required(payload, "status").equalsIgnoreCase("SUCCESS"))
            throw new IllegalArgumentException("Listener hanya menerima konfirmasi SUCCESS");
        boolean changed = transactionManager.updateStatus(t.getId(), Transaction.StatusTransaksi.SUCCESS);
        transactionManager.recordWebhookLog(t, raw, changed);
        if (changed) {
            journal.record("transaction.success", Map.of(
                    "transactionId",t.getId(),"merchantId",m.getId(),"amount",t.getAmount(),
                    "source","SHOPEEPAY_LISTENER","qrisMode",t.getQrisMode().name()));
            telegram.notifyPaymentSuccess(t.getId(),m.getName(),
                    t.getAmount().toPlainString(),"SHOPEEPAY_LISTENER");
        }
        json(ex, 200, Map.of("accepted",true,"duplicate",!changed,"transactionId",t.getId()));
    }

    private void createWithdrawal(HttpExchange ex, Merchant m) throws IOException {
        if (m.getQrisMode() == Merchant.QrisMode.OWN)
            throw new IllegalStateException("Withdrawal tidak berlaku untuk QRIS milik sendiri");
        Map<String,Object> b = body(ex);
        BigDecimal amount = new BigDecimal(String.valueOf(b.get("amount")));
        if (!m.holdSandbox(amount)) throw new IllegalStateException("Saldo Sandbox tidak mencukupi");
        Withdrawal w = new Withdrawal(m,amount,required(b,"bankCode"),
                required(b,"bankAccountNumber"),required(b,"bankAccountName"));
        withdrawals.put(w.getId(),w);
        journal.record("withdrawal.requested", Map.of(
                "withdrawalId",w.getId(),"merchantId",m.getId(),"amount",w.getAmount(),
                "bankCode",w.getBankCode(),"accountLast4",last4(w.getBankAccountNumber())));
        telegram.notifyWithdrawal(w);
        json(ex,201,withdrawalMap(w));
    }

    private void resolveWithdrawal(Withdrawal w, Withdrawal.Status next) {
        if (!w.resolve(next)) return;
        if (next == Withdrawal.Status.FAILED || next == Withdrawal.Status.REJECTED)
            w.getMerchant().creditSandbox(w.getAmount());
        journal.record("withdrawal.resolved", Map.of(
                "withdrawalId",w.getId(),"merchantId",w.getMerchant().getId(),
                "amount",w.getAmount(),"status",next.name(),"source","TELEGRAM"));
    }

    private void listWithdrawals(HttpExchange ex, Merchant m) throws IOException {
        List<Map<String,Object>> data = withdrawals.values().stream()
                .filter(w -> w.getMerchant().getId() == m.getId())
                .sorted(Comparator.comparing(Withdrawal::getCreatedAt).reversed())
                .map(this::withdrawalMap).toList();
        json(ex,200,Map.of("data",data));
    }

    private Merchant sessionMerchant(HttpExchange ex) {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) throw new AuthException("Silakan login");
        Merchant m = sessions.get(auth.substring(7));
        if (m == null) throw new AuthException("Sesi tidak valid");
        return m;
    }

    private Merchant keyMerchant(HttpExchange ex) {
        String key = ex.getRequestHeaders().getFirst("X-API-Key");
        Merchant m = apiKeyCache.get(key);
        if (m == null || !m.validateApiKey(key)) throw new AuthException("API key tidak valid");
        return m;
    }

    private Merchant keyOrSessionMerchant(HttpExchange ex) {
        return ex.getRequestHeaders().getFirst("X-API-Key") != null ? keyMerchant(ex) : sessionMerchant(ex);
    }

    private Map<String,Object> merchantMap(Merchant m, boolean includeKeys) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id",m.getId()); out.put("name",m.getName()); out.put("email",m.getEmail());
        out.put("callbackUrl",m.getCallbackUrl()); out.put("balanceSandbox",m.getBalanceSandbox());
        out.put("balanceLive",m.getBalanceLive()); out.put("createdAt",m.getCreatedAt().toString());
        out.put("qrisMode",m.getQrisMode().name());
        out.put("hasOwnQrisRaw",!m.getOwnQrisRaw().isBlank());
        out.put("hasOwnQrisImage",!m.getOwnQrisImageData().isBlank());
        if (includeKeys) {
            out.put("apiKeySandbox",m.getApiKeySandbox());
            out.put("apiKeyLive",m.getApiKeyLive());
            out.put("listenerSecret",m.getListenerSecret());
        }
        return out;
    }

    private Map<String,Object> transactionMap(Transaction t) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("id",t.getId()); out.put("orderId",t.getOrderId()); out.put("amount",t.getAmount());
        out.put("status",t.getStatus().name()); out.put("environment",t.isSandbox()?"SANDBOX":"LIVE");
        out.put("paymentMethod",t.getPaymentMethod()); out.put("qrString",t.getQrString());
        out.put("qrCode",t.getQrCode()); out.put("customer",t.getPelanggan().getInfo());
        out.put("baseAmount",t.getBaseAmount()); out.put("taxAmount",t.getTaxAmount());
        out.put("uniqueCode",t.getUniqueCode()); out.put("source",t.getSource());
        out.put("lastUpdateSource",t.getLastUpdateSource()); out.put("qrisMode",t.getQrisMode().name());
        out.put("createdAt",t.getCreatedAt().toString()); out.put("expiredAt",t.getExpiredAt().toString());
        return out;
    }

    private Map<String,Object> withdrawalMap(Withdrawal w) {
        return Map.of("id",w.getId(),"amount",w.getAmount(),"bankCode",w.getBankCode(),
                "bankAccountNumber","••••"+last4(w.getBankAccountNumber()),
                "bankAccountName",w.getBankAccountName(),"status",w.getStatus().name(),
                "createdAt",w.getCreatedAt().toString());
    }

    private static String last4(String s) { return s.length() <= 4 ? s : s.substring(s.length()-4); }
    private static Map<String,Object> body(HttpExchange ex) throws IOException {
        return Json.parseObject(new String(ex.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
    }
    private static String required(Map<String,Object> map, String key) {
        Object v = map.get(key);
        if (v == null || String.valueOf(v).isBlank()) throw new IllegalArgumentException(key+" wajib diisi");
        return String.valueOf(v);
    }
    private static Map<String,Object> error(String code,String message) {
        return Map.of("code",code,"message",message,"requestId",UUID.randomUUID().toString());
    }
    private static void json(HttpExchange ex,int status,Object value) throws IOException {
        byte[] bytes = Json.stringify(value).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control","no-store");
        ex.sendResponseHeaders(status,bytes.length);
        try(OutputStream out=ex.getResponseBody()){out.write(bytes);}
    }

    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path="/index.html";
        if (path.contains("..")) { ex.sendResponseHeaders(400,-1); return; }
        try(InputStream in=getClass().getResourceAsStream("/public"+path)) {
            if(in==null) { ex.sendResponseHeaders(404,-1); return; }
            byte[] bytes=in.readAllBytes();
            String type=path.endsWith(".css")?"text/css":path.endsWith(".js")?"application/javascript":"text/html";
            ex.getResponseHeaders().set("Content-Type",type+"; charset=utf-8");
            ex.sendResponseHeaders(200,bytes.length);
            try(OutputStream out=ex.getResponseBody()){out.write(bytes);}
        }
    }

    private static final class AuthException extends RuntimeException {
        AuthException(String message){super(message);}
    }
}
