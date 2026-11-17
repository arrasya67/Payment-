# RuangPay — GUI Dashboard (versi laporan)

Folder kerja dari **ZIP upstream** (kode lama, 2026-09-29), diperbaiki agar sesuai
Laporan Basis Data Kelompok 01 rev2. **GUI dulu, database nanti** — saat ini
backend masih in-memory (belum ada MySQL); lapisan database ditambahkan
belakangan tanpa mengubah GUI.

## Menjalankan (lokal)

```bash
./build.sh
PORT=8081 java -jar build/ruangpay.jar
# buka http://127.0.0.1:8081/
```

Port 8080 dipakai portfolio di VM ini, jadi pakai 8081. Akun demo:
register dulu, atau pakai tombol demo di halaman login
(`demo@ruangpay.id` / `demo1234`).

## GUI — 1 dashboard, 9 menu

| Menu | Layanan laporan | Isi |
|---|---|---|
| Dashboard | L1–L7 ringkasan | Saldo Sandbox/Live, total, sukses, PENDING; transaksi terbaru; tombol WhatsApp Super Admin di transaksi bermasalah; polling 5 dtk |
| Buat Transaksi | L3 | Form order + nominal + pelanggan; pilih Sandbox/Live; QR + hitung mundur 15 mnt; simulasi Success/Failed/Expired |
| Transaksi | L3/L5 | Pencarian + filter status; klik baris → modal detail (QR, countdown, simulasi, WhatsApp) |
| Merchant | L1 | Merchant saya + ubah callback URL (L6); daftar semua merchant; API key |
| Pelanggan | L2 | Daftar pelanggan unik (email = candidate key) |
| Webhook Log | L4/L7 | Jejak audit webhook: payload, processed, filter |
| Withdrawals | Tambahan | Ajukan penarikan + riwayat |
| QRIS Setup | Tambahan | Mode RUANGPAY / OWN + upload QRIS statis |
| Developers | Tambahan | API key Sandbox/Live, contoh curl, webhook generik, listener ShopeePay |

Interaktif: polling status tiap 5 detik (indikator "Live"), hitung mundur
expired tiap 1 detik, pencarian + filter status tanpa reload, modal detail,
tombol salin, toast, tombol WhatsApp.

## Yang disepakati dan sudah diterapkan

- **Tanpa tombol refund** — refund hanya interface di laporan, belum
  diimplementasi. Modal transaksi menuliskan hal ini.
- **Tanpa halaman admin** — Super Admin/CS diarahkan ke WhatsApp lewat
  tombol `wa.me` dengan pesan otomatis (Order ID + status + nominal).
  Ini link manual, bukan bot (tidak butuh Hermes WA yang sedang logout).
- **FAILED hanya di Sandbox** — di Live, tombol FAILED disembunyikan dan
  endpoint simulasi menolaknya (400). Live tetap PENDING/SUCCESS/EXPIRED.
- **KosHub dihapus total** — tidak ada legacy pricing, kode unik, atau pajak
  dari sumber lama; `X-Source: KOSHUB` diperlakukan sebagai API biasa.
- **Pelanggan unik by email** — `newCustomer()` memakai lagi pelanggan
  dengan email yang sama (laporan §3.10.1 candidate key).
- **Webhook generik** — `POST /api/webhooks` selalu mencatat ke
  `webhook_log` (walau duplikat) lalu update status bila masih PENDING.
  Listener ShopeePay juga mencatat ke `webhook_log`.
- **Callback L6 nyata** — `TransactionManager` memanggil listener saat
  status menjadi final; server POST JSON ke `callback_url` merchant
  (best-effort, dicatat di log). Di VM ini Java tidak boleh TCP keluar,
  jadi callback tercatat GAGAL di log lokal — verifikasi kirimnya
  tertunda sampai uji di server partisi.

## API baru (in-memory, tahap GUI)

- `GET /api/merchants` — daftar merchant (tanpa secret merchant lain)
- `POST /api/me/callback` — ubah callback URL merchant sendiri
- `GET /api/pelanggan` — daftar pelanggan unik milik merchant
- `POST /api/webhooks` — webhook generik `{transactionId|orderId, status}`
- `GET /api/webhook-logs[?transaction_id=]` — daftar webhook log
- `POST /v1/transactions` — tambah field `environment: SANDBOX|LIVE`

## Jujur — batas tahap ini

- Data masih in-memory, hilang saat server restart (database MySQL
  `payment_gateway` menyusul di tahap berikutnya).
- QR string mode RUANGPAY adalah simulator (`RUANGPAY|SIMULATOR|...`),
  bukan QRIS resmi; mode OWN memakai QRIS statis merchant (EMVCo + CRC16).
- `QRISProcessor.checkStatus()` masih selalu PENDING — SUCCESS hanya
  lewat simulasi, webhook, atau listener ShopeePay.
- String QR Live tetap memakai QR simulator yang sama seperti Sandbox;
  yang membedakan adalah environment dan aturan status FAILED.
