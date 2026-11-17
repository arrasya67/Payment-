# Cara pakai kode ini (siap paste)

Kode lengkap payment gateway RuangPay — versi laporan Basis Data rev2.
Java 21, tanpa framework (HttpServer bawaan JDK), frontend HTML/CSS/JS.

## 1. Paste / salin folder ini utuh

Salin seluruh isi folder `qris-payment-gateway-laporan/` ke project kamu.
Jangan ubah nama folder `src/`.

Struktur:
```
qris-payment-gateway-laporan/
├── build.sh                  # compile -> build/ruangpay.jar
├── run.sh                    # build + jalankan
├── GUI.md                    # penjelasan GUI + API + batas jujur
├── CARA-PASTE.md             # file ini
├── Dockerfile / render.yaml  # deploy (bawaan ZIP)
├── deploy/oracle-vps.sh      # deploy VPS (bawaan ZIP)
├── .env.example
└── src/
    ├── main/java/com/ruangpay/
    │   ├── RuangPayApplication.java   # server + semua endpoint API
    │   ├── model/                     # Merchant, Pelanggan, Transaction, WebhookLog, Withdrawal, LedgerEntry
    │   ├── processor/                 # PaymentProcessor (interface), QRISProcessor (simulator)
    │   ├── service/                   # TransactionManager, PollingService, TelegramBotService, EventJournal
    │   └── util/                      # Json, QrisUtil (EMVCo + CRC16), Security (PBKDF2, HMAC)
    └── main/resources/public/
        ├── index.html        # 1 dashboard, 9 menu
        ├── app.js            # polling 5 dtk, countdown 1 dtk, semua tab
        └── styles.css        # tampilan dashboard
```

## 2. Jalankan (lokal)

```bash
./build.sh
PORT=8081 java -jar build/ruangpay.jar
# buka http://127.0.0.1:8081/
```

Butuh JDK 21. Port 8081 (8080 biasanya sudah dipakai aplikasi lain).

## 3. Akun demo

Di halaman login klik "demo@ruangpay.id / demo1234" — tombol itu mengisi
email + password otomatis, lalu tekan Masuk. Atau Register akun baru.

## 4. Yang sudah sesuai ketentuan laporan + fitur

- L1 Merchant, L2 Pelanggan, L3 Buat Transaksi + QR, L4 Webhook,
  L5 Polling, L6 Callback ke merchant, L7 Webhook Log (audit)
- Tanpa tombol refund, tanpa halaman admin — Super Admin via WhatsApp
- FAILED hanya di Sandbox; Live hanya PENDING/SUCCESS/EXPIRED
- KosHub dihapus total; pelanggan unik by email; saldo, withdrawal,
  ShopeePay listener, mode QRIS OWN disimpan
- Database: BELUM ada di kode ini (tahap GUI dulu, in-memory).
  Tahap berikutnya: MySQL `payment_gateway` (4 tabel laporan + 3 tambahan).
