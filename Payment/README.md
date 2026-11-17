# RuangPay

MVP payment gateway QRIS multi-tenant dengan Java sebagai core engine dan dashboard web modern.

## Fitur yang sudah berjalan

- Register/login merchant
- API key Sandbox dan Live
- Pembuatan transaksi QRIS Sandbox
- Simulasi status `SUCCESS`, `FAILED`, dan `EXPIRED`
- Saldo Sandbox berbasis immutable ledger
- Pengajuan withdrawal Sandbox
- Webhook notification listener ShopeePay dengan HMAC SHA-256 dan idempotensi event
- Dua mode QRIS: QRIS RuangPay atau QRIS milik merchant
- Integrasi Koshub melalui API key, `X-Source: KOSHUB`, dan legacy pricing opsional
- Telegram withdrawal command center (`berhasil`/`gagal` atau command ID)
- Dashboard responsif
- HashMap session/API-key cache, Queue event, dan Double Linked List riwayat terbaru

## Menjalankan

```bash
./run.sh
```

Buka `http://localhost:8080`.

## Build

```bash
./build.sh
java -jar build/ruangpay.jar
```

## Deploy Oracle VPS (Ubuntu)

Pastikan ingress TCP port `80` dibuka pada Oracle Cloud Security List/NSG.

```bash
chmod +x deploy/oracle-vps.sh
sudo bash deploy/oracle-vps.sh
```

Health check:

```bash
curl http://PUBLIC_IP/api/health
```

### Mengaktifkan Telegram

Rotasi token yang pernah terekspos. Simpan token baru langsung di VPS, bukan di
source code atau chat:

```bash
sudo nano /opt/ruangpay.env
```

Isi:

```dotenv
TELEGRAM_BOT_TOKEN=token_baru_dari_BotFather
TELEGRAM_ADMIN_CHAT_ID=id_chat_admin
```

Deploy ulang. Bot menggunakan long polling sehingga belum membutuhkan domain
HTTPS. Saat withdrawal dibuat, bot mengirim detail. Admin dapat membalas pesan
dengan `berhasil` atau `gagal`, atau menggunakan:

```text
/wd_success <withdrawal-id>
/wd_failed <withdrawal-id>
```

Mode QRIS milik merchant tidak mengisi saldo RuangPay dan tidak menyediakan
withdrawal. Transaksi tetap tercatat serta dapat diperbarui melalui webhook.

## Catatan produksi

Versi ini adalah sandbox MVP yang menggunakan penyimpanan in-memory. Sebelum Live:

1. Ganti repository dengan PostgreSQL dan migration.
2. Ganti queue in-memory dengan queue durable.
3. Hubungkan `PaymentProcessor` ke provider QRIS berizin.
4. Hubungkan withdrawal ke partner disbursement.
5. Aktifkan TLS, secrets manager, KYC/KYB, audit dan reconciliation.

## Callback notification listener

```text
POST /v1/listener/shopeepay/webhook
X-API-Key: sk_sandbox_...
X-Listener-Signature: <hex HMAC-SHA256 raw body>
```

```json
{
  "eventId": "android-notif-170000001",
  "orderId": "ORD-2026-0001",
  "amount": 150000,
  "status": "SUCCESS",
  "rawNotification": "Pembayaran diterima..."
}
```

Notification listener bukan sumber settlement yang setara dengan API resmi
provider. Gunakan sebagai prototipe, verifikasi nominal/order, amankan perangkat,
dan lakukan rekonsiliasi sebelum dianggap layak untuk uang nyata.
