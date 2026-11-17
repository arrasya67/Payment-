/* RuangPay — GUI web dashboard (Java backend + HTML/CSS/JS)
 * Sesuai Laporan Basis Data rev2 (L1–L7, Tabel 1.1) + fitur tambahan
 * yang disimpan: login, saldo, withdrawal, ShopeePay, mode OWN, Sandbox.
 * Tanpa tombol refund (hanya interface), tanpa halaman admin —
 * Super Admin/CS diarahkan ke WhatsApp. KosHub dihapus total.
 * Polling status tiap 5 detik, hitung mundur expired tiap 1 detik. */
const $ = s => document.querySelector(s), $$ = s => [...document.querySelectorAll(s)];
let token = localStorage.getItem('rp_token'), merchant = null, authMode = 'login', current = 'overview';
let txCache = [], lastRefresh = null, pollTimer = null, countdownTimer = null;
let txFilter = 'ALL', txSearch = '', wlFilter = '';
/* Nomor WhatsApp Super Admin/CS (Agis) — link wa.me, bukan bot. */
const WA_ADMIN = '6285141310949';

const money = n => new Intl.NumberFormat('id-ID', { style: 'currency', currency: 'IDR', maximumFractionDigits: 0 }).format(Number(n || 0));
const date = s => new Intl.DateTimeFormat('id-ID', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(s));
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const waLink = t => 'https://wa.me/' + WA_ADMIN + '?text=' + encodeURIComponent(
  'Halo Super Admin RuangPay, saya butuh bantuan untuk transaksi bermasalah:\n' +
  'Order ID: ' + t.orderId + '\nStatus: ' + t.status + '\nNominal: ' + money(t.amount) + '\nID: ' + t.id);

function toast(message) { const el = $('#toast'); el.textContent = message; el.classList.add('show'); setTimeout(() => el.classList.remove('show'), 3000); }
async function api(path, options = {}) {
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) };
  if (token) headers.Authorization = 'Bearer ' + token;
  const r = await fetch(path, { ...options, headers });
  const data = await r.json().catch(() => ({ message: 'Respons tidak valid' }));
  if (!r.ok) throw new Error(data.message || 'Request gagal');
  return data;
}
function copyText(v) { navigator.clipboard.writeText(v); toast('Disalin ke clipboard'); }
function statusBadge(s) { return '<span class="status ' + s.toLowerCase() + '">' + s + '</span>'; }
function envBadge(t) { return '<span class="env-mini ' + (t.isSandbox ? 'sandbox' : 'live') + '">' + (t.isSandbox ? 'SANDBOX' : 'LIVE') + '</span>'; }
function isProblem(t) {
  if (t.status === 'EXPIRED' || t.status === 'FAILED') return true;
  if (t.status === 'PENDING') { const left = new Date(t.expiredAt) - Date.now(); return left > 0 && left < 120000; }
  return false;
}
function countdownText(t) {
  if (t.status !== 'PENDING') return '—';
  const left = Math.max(0, Math.floor((new Date(t.expiredAt) - Date.now()) / 1000));
  const m = Math.floor(left / 60), s = left % 60;
  return m + ':' + String(s).padStart(2, '0');
}

/* ---------- AUTH ---------- */
function setAuthMode(mode) {
  authMode = mode; const reg = mode === 'register';
  $('#nameField').classList.toggle('hidden', !reg);
  $('#authTitle').textContent = reg ? 'Mulai menerima pembayaran' : 'Selamat datang kembali';
  $('#authSubtitle').textContent = reg ? 'Buat akun Sandbox dalam hitungan detik.' : 'Masuk untuk melanjutkan ke dashboard.';
  $('#authAction').textContent = reg ? 'Buat akun' : 'Masuk';
  $('#switchText').textContent = reg ? 'Sudah punya akun?' : 'Belum punya akun?';
  $('#switchAuth').textContent = reg ? 'Masuk' : 'Daftar gratis';
  $('input[name=password]').autocomplete = reg ? 'new-password' : 'current-password';
}
$('#switchAuth').onclick = () => setAuthMode(authMode === 'login' ? 'register' : 'login');
$('#fillDemo').onclick = () => {
  $('input[name=email]').value = 'demo@ruangpay.id';
  $('input[name=password]').value = 'demo1234';
  toast('Akun demo diisi — tekan Masuk');
};
$('#authForm').onsubmit = async e => {
  e.preventDefault(); const f = new FormData(e.target);
  try {
    const data = await api('/api/auth/' + authMode, { method: 'POST', body: JSON.stringify(Object.fromEntries(f)) });
    token = data.token; localStorage.setItem('rp_token', token); merchant = data.merchant; showApp();
  } catch (err) { toast(err.message); }
};
$('#logout').onclick = () => { stopTimers(); localStorage.removeItem('rp_token'); token = null; merchant = null; $('#app').classList.add('hidden'); $('#auth').classList.remove('hidden'); };
$('#menu').onclick = () => $('.sidebar').classList.toggle('open');
$$('.nav-item[data-view]').forEach(b => b.onclick = () => navigate(b.dataset.view));

function navigate(view) {
  current = view;
  $$('.nav-item[data-view]').forEach(b => b.classList.toggle('active', b.dataset.view === view));
  $('.sidebar').classList.remove('open');
  const names = { overview: 'Dashboard', create: 'Buat Transaksi', transactions: 'Transaksi', merchants: 'Merchant', pelanggan: 'Pelanggan', 'webhook-logs': 'Webhook Log', withdrawals: 'Withdrawals', qris: 'QRIS Setup', developers: 'Developers' };
  $('#pageTitle').textContent = names[view] || view;
  render();
}
async function init() {
  if (!token) return;
  try { merchant = await api('/api/me'); showApp(); }
  catch { localStorage.removeItem('rp_token'); token = null; }
}
function showApp() {
  $('#auth').classList.add('hidden'); $('#app').classList.remove('hidden');
  $('#avatar').textContent = merchant.name.split(/\s+/).map(x => x[0]).join('').slice(0, 2).toUpperCase();
  startTimers(); render();
}
function startTimers() {
  stopTimers();
  pollTimer = setInterval(() => { if (!document.hidden) render(true); }, 5000);
  countdownTimer = setInterval(() => {
    $$('[data-countdown]').forEach(el => {
      const t = txCache.find(x => x.id === el.dataset.countdown);
      if (t) el.textContent = countdownText(t);
    });
    const stale = txCache.some(t => t.status === 'PENDING' && new Date(t.expiredAt) <= Date.now());
    if (stale) render(true);
  }, 1000);
}
function stopTimers() { if (pollTimer) clearInterval(pollTimer); if (countdownTimer) clearInterval(countdownTimer); pollTimer = countdownTimer = null; }

async function render(silent) {
  try {
    // Jangan timpa tampilan saat pengguna sedang mengetik di form/pencarian.
    const ae = document.activeElement;
    if (silent && ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA' || ae.tagName === 'SELECT') && $('#content').contains(ae)) return;
    if (current === 'overview') await overview();
    else if (current === 'create') await createView();
    else if (current === 'transactions') await transactionsView();
    else if (current === 'merchants') await merchantsView();
    else if (current === 'pelanggan') await pelangganView();
    else if (current === 'webhook-logs') await webhookLogsView();
    else if (current === 'withdrawals') await withdrawalView();
    else if (current === 'qris') await qrisSettings();
    else if (current === 'developers') developers();
    lastRefresh = new Date();
    $('#liveLabel').textContent = 'Live · ' + lastRefresh.toLocaleTimeString('id-ID');
  } catch (e) { if (!silent) toast(e.message); }
}
async function fetchTx() { const d = await api('/v1/transactions'); txCache = d.data || []; return txCache; }

/* ---------- DASHBOARD (L1–L7 ringkasan) ---------- */
async function overview() {
  const d = await api('/api/dashboard'); merchant = d.merchant;
  const m = d.metrics, tx = d.transactions || []; txCache = tx;
  const pending = tx.filter(t => t.status === 'PENDING').length;
  const problems = tx.filter(isProblem);
  $('#content').innerHTML =
    '<div class="page-head"><div><h1>Halo, ' + esc(merchant.name) + '</h1><p>Ringkasan aktivitas pembayaran Anda — diperbarui otomatis tiap 5 detik.</p></div>' +
    '<button class="btn primary" onclick="navigate(\'create\')">+ Buat transaksi</button></div>' +
    '<section class="metrics">' +
    metric('Saldo Sandbox', money(m.balanceSandbox), 'Dana tercatat (RUANGPAY)') +
    metric('Saldo Live', money(m.balanceLive), 'Dana tercatat (LIVE)') +
    metric('Total transaksi', m.transactions, pending + ' masih PENDING') +
    metric('Pembayaran sukses', m.success, 'Tercatat tepat sekali') +
    '</section>' +
    (problems.length ? '<div class="alert-card"><strong>⚠ ' + problems.length + ' transaksi butuh perhatian</strong><span> ' + problems.map(t => esc(t.orderId)).join(', ') + ' — hubungi Super Admin via WhatsApp dari tabel di bawah.</span></div>' : '') +
    '<section class="grid-2"><div class="panel"><div class="panel-head"><h3>Transaksi terbaru</h3><button class="link-btn" onclick="navigate(\'transactions\')">Lihat semua</button></div>' + txTable(tx) + '</div>' +
    '<div class="panel"><div class="panel-head"><h3>Mulai cepat</h3></div><div class="panel-body quick-grid">' +
    '<button class="quick-action" onclick="navigate(\'create\')"><span class="quick-icon">＋</span><div><strong>Buat transaksi</strong><small>Generate QRIS (Sandbox / Live)</small></div></button>' +
    '<button class="quick-action" onclick="navigate(\'merchants\')"><span class="quick-icon">▣</span><div><strong>Kelola Merchant</strong><small>Callback URL &amp; API key</small></div></button>' +
    '<button class="quick-action" onclick="navigate(\'webhook-logs\')"><span class="quick-icon">≣</span><div><strong>Webhook Log</strong><small>Jejak audit setiap webhook</small></div></button>' +
    '<button class="quick-action" onclick="navigate(\'withdrawals\')"><span class="quick-icon">⇣</span><div><strong>Tarik saldo</strong><small>Simulasi payout ke bank</small></div></button>' +
    '</div></div></section>';
}
function metric(label, value, foot) {
  return '<div class="metric-card"><div class="metric-label">' + label + '</div><div class="metric-value">' + value + '</div><div class="metric-foot">' + foot + '</div></div>';
}
function txTable(rows) {
  if (!rows.length) return '<div class="empty"><strong>Belum ada transaksi</strong>Buat transaksi pertama Anda.</div>';
  return '<div class="table-wrap"><table><thead><tr><th>Order</th><th>Pelanggan</th><th>Jumlah</th><th>Status</th><th>Berakhir</th><th></th></tr></thead><tbody>' +
    rows.map(t => '<tr class="row-click" onclick="openTxModal(\'' + t.id + '\')"><td><strong>' + esc(t.orderId) + '</strong><br><span class="mono">' + esc(t.id.slice(0, 8)) + '</span> ' + envBadge(t) + '</td>' +
      '<td>' + esc((t.customer || '').split(' · ')[0]) + '</td>' +
      '<td class="amount">' + money(t.amount) + '</td>' +
      '<td>' + statusBadge(t.status) + '</td>' +
      '<td><span class="mono" data-countdown="' + t.id + '">' + countdownText(t) + '</span></td>' +
      '<td>' + (isProblem(t) ? '<a class="btn wa small" target="_blank" rel="noopener" href="' + waLink(t) + '" onclick="event.stopPropagation()">💬 Super Admin</a>' : '') + '</td></tr>').join('') +
    '</tbody></table></div>';
}

/* ---------- BUAT TRANSAKSI (L3) ---------- */
async function createView() {
  await fetchTx();
  $('#content').innerHTML =
    '<div class="sandbox-banner"><div><h3>Buat transaksi QRIS</h3><p>QR dinamis dibuat dari QRIS (simulator EMVCo + CRC16). Status awal PENDING, berakhir 15 menit.</p></div><span class="env-badge">L3 · QRIS</span></div>' +
    '<div class="grid-2"><div class="panel"><div class="panel-head"><h3>Form transaksi</h3></div>' +
    '<form id="paymentForm" class="panel-body form-grid">' +
    '<div class="field full-span"><label>Environment</label><div class="seg" id="envSeg">' +
    '<button type="button" class="seg-btn active" data-env="SANDBOX">Sandbox</button>' +
    '<button type="button" class="seg-btn" data-env="LIVE">Live</button></div>' +
    '<small class="muted">Sandbox: simulasi boleh FAILED. Live: hanya PENDING / SUCCESS / EXPIRED (sesuai laporan).</small></div>' +
    '<div class="field"><label>Order ID</label><input name="orderId" value="ORD-' + Date.now().toString().slice(-6) + '" required></div>' +
    '<div class="field"><label>Nominal (Rp)</label><input name="amount" type="number" min="1000" value="150000" required></div>' +
    '<div class="field"><label>Nama pelanggan</label><input name="customerName" value="Budi Santoso" required></div>' +
    '<div class="field"><label>Email pelanggan</label><input name="email" type="email" value="budi@example.com"></div>' +
    '<div class="field full-span"><label>Nomor telepon</label><input name="phoneNumber" value="081234567890"></div>' +
    '<div class="field full-span"><button class="btn primary" type="submit">Generate transaksi →</button></div>' +
    '</form></div>' +
    '<div class="panel"><div class="panel-head"><h3>QR &amp; simulasi</h3></div><div class="panel-body"><div id="simulation">' +
    (txCache[0] ? simCard(txCache[0]) : '<div class="empty"><strong>Belum ada transaksi aktif</strong>Form di samping akan membuat satu transaksi.</div>') +
    '</div></div></div></div>' +
    '<div class="panel section-space"><div class="panel-head"><h3>Transaksi terbaru</h3></div>' + txTable(txCache.slice(0, 6)) + '</div>';
  let env = 'SANDBOX';
  $$('#envSeg .seg-btn').forEach(b => b.onclick = () => { env = b.dataset.env; $$('#envSeg .seg-btn').forEach(x => x.classList.toggle('active', x === b)); });
  $('#paymentForm').onsubmit = async e => {
    e.preventDefault(); const f = Object.fromEntries(new FormData(e.target));
    try {
      const t = await api('/v1/transactions', { method: 'POST', headers: { 'X-API-Key': merchant.apiKeySandbox }, body: JSON.stringify({ orderId: f.orderId, amount: Number(f.amount), environment: env, customer: { name: f.customerName, email: f.email, phoneNumber: f.phoneNumber } }) });
      txCache.unshift(t); toast('Transaksi ' + env + ' dibuat'); $('#simulation').innerHTML = simCard(t);
    } catch (err) { toast(err.message); }
  };
}
function simCard(t) {
  const live = !t.isSandbox;
  return '<div><p class="kicker">TRANSACTION READY · ' + (live ? 'LIVE' : 'SANDBOX') + '</p>' +
    '<h2 class="amount">' + money(t.amount) + '</h2><p class="mono">' + esc(t.id) + '</p>' +
    '<p class="muted">Berakhir dalam <strong class="mono" data-countdown="' + t.id + '">' + countdownText(t) + '</strong></p>' +
    '<div class="code-box">' + esc(t.qrString || t.qrCode || '(QR tidak tersedia)') + '<button class="copy-btn" onclick="copyText(\'' + esc(t.qrString || '') + '\')">Copy</button></div>' +
    '<div style="display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:14px">' +
    '<button class="btn primary" onclick="simulate(\'' + t.id + '\',\'SUCCESS\')">Success</button>' +
    (live ? '' : '<button class="btn secondary" onclick="simulate(\'' + t.id + '\',\'FAILED\')">Failed</button>') +
    '<button class="btn secondary" onclick="simulate(\'' + t.id + '\',\'EXPIRED\')">Expired</button></div>' +
    (live ? '<p class="muted" style="margin-top:10px">Live: tombol FAILED disembunyikan — sesuai status laporan.</p>' : '') +
    '<a class="btn wa" target="_blank" rel="noopener" href="' + waLink(t) + '" style="margin-top:12px">💬 Ada masalah? Hubungi Super Admin (WhatsApp)</a></div>';
}
async function simulate(id, status) {
  try {
    const d = await api('/v1/sandbox/transactions/' + id + '/simulate', { method: 'POST', body: JSON.stringify({ status }) });
    toast(d.changed ? 'Status menjadi ' + status : 'Status final tidak diubah lagi');
    await fetchTx(); render();
  } catch (e) { toast(e.message); }
}

/* ---------- TRANSAKSI (L3/L5) ---------- */
async function transactionsView() {
  await fetchTx();
  const filtered = txCache.filter(t =>
    (txFilter === 'ALL' || t.status === txFilter) &&
    (!txSearch || (t.orderId + ' ' + (t.customer || '')).toLowerCase().includes(txSearch.toLowerCase())));
  $('#content').innerHTML =
    '<div class="page-head"><div><h1>Transaksi</h1><p>Klik baris untuk detail. Status diperbarui otomatis tiap 5 detik (L5).</p></div>' +
    '<button class="btn primary" onclick="navigate(\'create\')">+ Buat transaksi</button></div>' +
    '<div class="filter-bar"><input id="txSearch" placeholder="🔍 Cari order / pelanggan…" value="' + esc(txSearch) + '">' +
    ['ALL', 'PENDING', 'SUCCESS', 'EXPIRED', 'FAILED'].map(s => '<button class="chip' + (txFilter === s ? ' active' : '') + '" data-f="' + s + '">' + (s === 'ALL' ? 'Semua' : s) + '</button>').join('') + '</div>' +
    '<div class="panel" id="txListWrap">' + txTable(filtered) + '</div>';
  $('#txSearch').oninput = e => {
    txSearch = e.target.value;
    const f = txCache.filter(t => (txFilter === 'ALL' || t.status === txFilter) &&
      (!txSearch || (t.orderId + ' ' + (t.customer || '')).toLowerCase().includes(txSearch.toLowerCase())));
    $('#txListWrap').innerHTML = txTable(f);
  };
  $$('.chip').forEach(c => c.onclick = () => { txFilter = c.dataset.f; transactionsView(); });
}

/* ---------- MERCHANT (L1) ---------- */
async function merchantsView() {
  const d = await api('/api/merchants'); const list = d.data || [];
  const me = list.find(m => m.id === merchant.id) || merchant;
  $('#content').innerHTML =
    '<div class="page-head"><div><h1>Merchant</h1><p>Manajemen merchant (L1). Merchant baru dibuat lewat Register.</p></div></div>' +
    '<div class="grid-2"><div class="panel"><div class="panel-head"><h3>Merchant saya</h3>' + statusBadge('SUCCESS') + '</div><div class="panel-body">' +
    '<p><strong>' + esc(me.name) + '</strong><br><span class="muted">' + esc(me.email) + ' · sejak ' + date(me.createdAt) + '</span></p>' +
    '<p class="muted">Saldo Sandbox <strong>' + money(me.balanceSandbox) + '</strong> · Saldo Live <strong>' + money(me.balanceLive) + '</strong> · Mode QRIS <strong>' + me.qrisMode + '</strong></p>' +
    '<form id="cbForm" class="form-grid"><div class="field full-span"><label>Callback URL (L6 — menerima status transaksi)</label>' +
    '<input name="callbackUrl" value="' + esc(me.callbackUrl || '') + '" placeholder="https://toko-saya.id/payment/callback"></div>' +
    '<div class="field full-span"><button class="btn primary" type="submit">Simpan callback URL</button></div></form></div></div>' +
    '<div class="panel"><div class="panel-head"><h3>API key saya</h3></div><div class="panel-body">' +
    '<p class="muted">Sandbox (aktif untuk pengujian)</p><div class="code-box">' + esc(me.apiKeySandbox || '') + '<button class="copy-btn" onclick="copyText(\'' + esc(me.apiKeySandbox || '') + '\')">Copy</button></div>' +
    '<p class="muted" style="margin-top:14px">Live (terkunci — butuh provider QRIS &amp; aktivasi)</p><div class="code-box">' + esc((me.apiKeyLive || '').slice(0, 14)) + '••••••••••••</div>' +
    '</div></div></div>' +
    '<div class="panel section-space"><div class="panel-head"><h3>Semua merchant (' + list.length + ')</h3></div>' +
    '<div class="table-wrap"><table><thead><tr><th>ID</th><th>Nama</th><th>Email</th><th>Callback URL</th><th>Terdaftar</th></tr></thead><tbody>' +
    list.map(m => '<tr><td>' + m.id + '</td><td><strong>' + esc(m.name) + '</strong></td><td>' + esc(m.email) + '</td><td class="mono">' + esc(m.callbackUrl || '—') + '</td><td>' + date(m.createdAt) + '</td></tr>').join('') +
    '</tbody></table></div></div>';
  $('#cbForm').onsubmit = async e => {
    e.preventDefault(); const f = Object.fromEntries(new FormData(e.target));
    try { await api('/api/me/callback', { method: 'POST', body: JSON.stringify({ callbackUrl: f.callbackUrl }) }); toast('Callback URL disimpan'); }
    catch (err) { toast(err.message); }
  };
}

/* ---------- PELANGGAN (L2) ---------- */
async function pelangganView() {
  const d = await api('/api/pelanggan'); const list = d.data || [];
  $('#content').innerHTML =
    '<div class="page-head"><div><h1>Pelanggan</h1><p>Pencatatan pelanggan (L2). Email unik — pelanggan yang sama tidak dibuat dua kali.</p></div></div>' +
    '<div class="panel"><div class="panel-head"><h3>' + list.length + ' pelanggan</h3><span class="muted">Data dari transaksi Anda</span></div>' +
    (list.length ? '<div class="table-wrap"><table><thead><tr><th>ID</th><th>Nama</th><th>Email</th><th>Telepon</th><th>Terdaftar</th></tr></thead><tbody>' +
      list.map(p => '<tr><td>' + p.id + '</td><td><strong>' + esc(p.name) + '</strong></td><td>' + esc(p.email) + '</td><td>' + esc(p.phoneNumber || '—') + '</td><td>' + date(p.createdAt) + '</td></tr>').join('') +
      '</tbody></table></div>' : '<div class="empty"><strong>Belum ada pelanggan</strong>Pelanggan tercatat otomatis saat transaksi dibuat.</div>') + '</div>';
}

/* ---------- WEBHOOK LOG (L4/L7) ---------- */
async function webhookLogsView() {
  const d = await api('/api/webhook-logs'); const list = d.data || [];
  const filtered = wlFilter ? list.filter(w => w.transactionId === wlFilter || w.orderId.includes(wlFilter)) : list;
  $('#content').innerHTML =
    '<div class="page-head"><div><h1>Webhook Log</h1><p>Jejak audit setiap webhook yang diterima (L4 + L7). Webhook duplikat tetap tercatat.</p></div></div>' +
    '<div class="filter-bar"><input id="wlSearch" placeholder="🔍 Filter order ID / transaction ID…" value="' + esc(wlFilter) + '"></div>' +
    '<div class="panel" id="wlListWrap"><div class="panel-head"><h3>' + filtered.length + ' webhook</h3></div>' +
    (filtered.length ? '<div class="table-wrap"><table><thead><tr><th>ID</th><th>Order</th><th>Processed</th><th>Diterima</th><th>Payload</th></tr></thead><tbody>' +
      filtered.map(w => '<tr><td>' + w.id + '</td><td><strong>' + esc(w.orderId) + '</strong><br><span class="mono">' + esc(w.transactionId.slice(0, 8)) + '</span></td>' +
        '<td>' + (w.processed ? '<span class="status success">YA</span>' : '<span class="status pending">BELUM</span>') + '</td>' +
        '<td>' + date(w.receivedAt) + '</td><td><details><summary class="mono">lihat payload</summary><pre class="payload">' + esc(w.payload) + '</pre></details></td></tr>').join('') +
      '</tbody></table></div>' : '<div class="empty"><strong>Belum ada webhook</strong>Kirim webhook ke POST /api/webhooks atau lewat listener ShopeePay — akan tercatat di sini.</div>') + '</div>';
  $('#wlSearch').oninput = e => {
    wlFilter = e.target.value;
    const f = list.filter(w => !wlFilter || w.transactionId === wlFilter || w.orderId.includes(wlFilter));
    $('#wlListWrap').innerHTML = '<div class="panel-head"><h3>' + f.length + ' webhook</h3></div>' +
      (f.length ? '<div class="table-wrap"><table><thead><tr><th>ID</th><th>Order</th><th>Processed</th><th>Diterima</th><th>Payload</th></tr></thead><tbody>' +
        f.map(w => '<tr><td>' + w.id + '</td><td><strong>' + esc(w.orderId) + '</strong></td><td>' + (w.processed ? '<span class="status success">YA</span>' : '<span class="status pending">BELUM</span>') + '</td><td>' + date(w.receivedAt) + '</td><td><details><summary class="mono">lihat payload</summary><pre class="payload">' + esc(w.payload) + '</pre></details></td></tr>').join('') + '</tbody></table></div>' : '<div class="empty"><strong>Tidak ada webhook yang cocok</strong></div>');
  };
}

/* ---------- WITHDRAWALS ---------- */
async function withdrawalView() {
  const d = await api('/v1/withdrawals');
  $('#content').innerHTML =
    '<div class="page-head"><div><h1>Withdrawals</h1><p>Simulasikan pencairan saldo Sandbox ke rekening bank.</p></div></div>' +
    '<div class="grid-2"><div class="panel"><div class="panel-head"><h3>Request withdrawal</h3><span class="amount">' + money(merchant.balanceSandbox) + '</span></div>' +
    '<form id="withdrawForm" class="panel-body form-grid">' +
    '<div class="field"><label>Nominal</label><input name="amount" type="number" min="1000" required></div>' +
    '<div class="field"><label>Bank</label><select name="bankCode"><option>BCA</option><option>BRI</option><option>BNI</option><option>MANDIRI</option></select></div>' +
    '<div class="field"><label>Nomor rekening</label><input name="bankAccountNumber" required></div>' +
    '<div class="field"><label>Nama pemilik</label><input name="bankAccountName" required></div>' +
    '<div class="field full-span"><button class="btn primary" type="submit">Ajukan withdrawal</button></div></form></div>' +
    '<div class="panel"><div class="panel-head"><h3>Alur dana</h3></div><div class="panel-body quick-grid">' +
    '<div class="quick-action"><span class="quick-icon">1</span><div><strong>Available → Held</strong><small>Saldo dikunci secara atomik</small></div></div>' +
    '<div class="quick-action"><span class="quick-icon">2</span><div><strong>Admin review</strong><small>Status awal PENDING</small></div></div>' +
    '<div class="quick-action"><span class="quick-icon">3</span><div><strong>Payout</strong><small>Disbursement adapter pada produksi</small></div></div></div></div></div>' +
    '<div class="panel section-space"><div class="panel-head"><h3>Withdrawal history</h3></div>' + wdTable(d.data) + '</div>';
  $('#withdrawForm').onsubmit = async e => {
    e.preventDefault(); const b = Object.fromEntries(new FormData(e.target));
    try { await api('/v1/withdrawals', { method: 'POST', body: JSON.stringify({ ...b, amount: Number(b.amount) }) }); toast('Withdrawal diajukan'); merchant = await api('/api/me'); withdrawalView(); }
    catch (err) { toast(err.message); }
  };
}
function wdTable(rows) {
  if (!rows.length) return '<div class="empty"><strong>Belum ada withdrawal</strong>Saldo dari transaksi sukses dapat ditarik di sini.</div>';
  return '<div class="table-wrap"><table><thead><tr><th>ID</th><th>Bank</th><th>Rekening</th><th>Jumlah</th><th>Status</th><th>Waktu</th></tr></thead><tbody>' +
    rows.map(w => '<tr><td class="mono">' + esc(w.id.slice(0, 8)) + '</td><td>' + esc(w.bankCode) + '</td><td>' + esc(w.bankAccountNumber) + '</td><td class="amount">' + money(w.amount) + '</td><td>' + statusBadge(w.status) + '</td><td>' + date(w.createdAt) + '</td></tr>').join('') +
    '</tbody></table></div>';
}

/* ---------- QRIS SETUP ---------- */
async function qrisSettings() {
  merchant = await api('/api/me'); const own = merchant.qrisMode === 'OWN';
  $('#content').innerHTML =
    '<div class="page-head"><div><h1>QRIS Setup</h1><p>Pilih sumber QRIS untuk setiap transaksi merchant Anda.</p></div></div>' +
    '<div class="panel"><div class="panel-head"><h3>Payment routing</h3><span class="status ' + (own ? 'pending' : 'success') + '">' + merchant.qrisMode + '</span></div>' +
    '<form id="qrisForm" class="panel-body"><div class="mode-choice">' +
    '<label class="mode-option"><input type="radio" name="mode" value="RUANGPAY" ' + (own ? '' : 'checked') + '><span class="mode-card"><strong>RuangPay QRIS</strong><small>Dana tercatat sebagai saldo RuangPay dan dapat diajukan untuk withdrawal.</small></span></label>' +
    '<label class="mode-option"><input type="radio" name="mode" value="OWN" ' + (own ? 'checked' : '') + '><span class="mode-card"><strong>QRIS milik sendiri</strong><small>Pembayaran langsung ke QRIS Anda. Tidak masuk saldo dan tidak membutuhkan withdrawal RuangPay.</small></span></label></div>' +
    '<div id="ownQrisFields" class="section-space ' + (own ? '' : 'hidden') + '"><div class="grid-2">' +
    '<div class="upload-zone"><strong>Upload gambar QRIS</strong><p class="muted">PNG/JPG/WebP, maksimal sekitar 1 MB.</p><input id="qrisImage" type="file" accept="image/png,image/jpeg,image/webp"><img id="qrisPreview" class="preview-qr hidden" alt="Preview QRIS"></div>' +
    '<div class="field"><label>QRIS raw string (opsional)</label><input id="qrisRaw" placeholder="000201010211..." autocomplete="off"><p class="muted">Jika diisi, Java membuat nominal dinamis dan menghitung CRC16. Jika hanya gambar, QR tetap statis.</p></div>' +
    '</div></div><div style="margin-top:18px"><button class="btn primary" type="submit">Simpan konfigurasi</button></div></form></div>' +
    '<div class="panel section-space"><div class="panel-head"><h3>Perilaku sistem</h3></div><div class="panel-body quick-grid">' +
    '<div class="quick-action"><span class="quick-icon">R</span><div><strong>RuangPay</strong><small>Ledger, saldo, withdrawal, dan kontrol Telegram aktif.</small></div></div>' +
    '<div class="quick-action"><span class="quick-icon">O</span><div><strong>Own QRIS</strong><small>Transaksi tetap tercatat dan webhook tetap bekerja, tetapi dana tidak ditahan RuangPay.</small></div></div></div></div>';
  $$('input[name=mode]').forEach(r => r.onchange = () => { $('#ownQrisFields').classList.toggle('hidden', document.querySelector('input[name=mode]:checked').value === 'RUANGPAY'); });
  $('#qrisImage').onchange = e => {
    const f = e.target.files[0]; if (!f) return;
    const reader = new FileReader();
    reader.onload = () => { const img = $('#qrisPreview'); img.src = reader.result; img.dataset.value = reader.result; img.classList.remove('hidden'); };
    reader.readAsDataURL(f);
  };
  $('#qrisForm').onsubmit = async e => {
    e.preventDefault(); const mode = new FormData(e.target).get('mode'); const image = $('#qrisPreview').dataset.value || '';
    try { await api('/api/settings/qris', { method: 'POST', body: JSON.stringify({ mode, qrisRaw: $('#qrisRaw').value, qrisImageData: image, preserveExisting: true }) }); merchant = await api('/api/me'); toast('Konfigurasi QRIS disimpan'); qrisSettings(); }
    catch (err) { toast(err.message); }
  };
}

/* ---------- DEVELOPERS ---------- */
function developers() {
  $('#content').innerHTML =
    '<div class="page-head"><div><h1>Developers</h1><p>Kredensial dan contoh integrasi REST API.</p></div></div>' +
    '<div class="grid-2"><div class="panel"><div class="panel-head"><h3>Sandbox API key</h3><span class="status success">ACTIVE</span></div><div class="panel-body"><p class="muted">Gunakan hanya di server. Jangan masukkan secret ke frontend produksi.</p><div class="code-box">' + esc(merchant.apiKeySandbox) + '<button class="copy-btn" onclick="copyText(\'' + esc(merchant.apiKeySandbox) + '\')">Copy</button></div></div></div>' +
    '<div class="panel"><div class="panel-head"><h3>Listener signing secret</h3><span class="status success">HMAC SHA-256</span></div><div class="panel-body"><p class="muted">Gunakan pada notification listener untuk menandatangani raw JSON.</p><div class="code-box">' + esc(merchant.listenerSecret) + '<button class="copy-btn" onclick="copyText(\'' + esc(merchant.listenerSecret) + '\')">Copy</button></div></div></div></div>' +
    '<div class="panel section-space"><div class="panel-head"><h3>Create transaction</h3><span class="mono">POST /v1/transactions</span></div><div class="panel-body"><div class="code-box">curl -X POST \'' + location.origin + '/v1/transactions\' \\\n  -H \'Content-Type: application/json\' \\\n  -H \'X-API-Key: ' + esc(merchant.apiKeySandbox) + '\' \\\n  -d \'{\n    "orderId": "ORD-2026-0001",\n    "amount": 150000,\n    "environment": "SANDBOX",\n    "customer": {"name":"Budi","email":"budi@example.com","phoneNumber":"081234567890"}\n  }\'</div></div></div>' +
    '<div class="panel section-space"><div class="panel-head"><h3>Webhook generik (L4)</h3><span class="mono">POST /api/webhooks</span></div><div class="panel-body"><p class="muted">Setiap webhook yang masuk dicatat ke webhook_log, lalu status transaksi diperbarui bila masih PENDING.</p><div class="code-box">curl -X POST \'' + location.origin + '/api/webhooks\' \\\n  -H \'Content-Type: application/json\' \\\n  -H \'X-API-Key: ' + esc(merchant.apiKeySandbox) + '\' \\\n  -d \'{"orderId":"ORD-2026-0001","status":"SUCCESS"}\'</div></div></div>' +
    '<div class="panel section-space"><div class="panel-head"><h3>ShopeePay notification listener</h3><span class="mono">POST /v1/listener/shopeepay/webhook</span></div><div class="panel-body"><p class="muted">Kirim event ID unik, order ID, nominal, dan status. Header <span class="mono">X-Listener-Signature</span> adalah hex HMAC-SHA256 dari raw JSON. Event juga tercatat di Webhook Log.</p><div class="code-box">{\n  "eventId": "android-notif-170000001",\n  "orderId": "ORD-2026-0001",\n  "amount": 150000,\n  "status": "SUCCESS"\n}</div></div></div>';
}

/* ---------- MODAL DETAIL TRANSAKSI ---------- */
async function openTxModal(id) {
  try {
    const t = await api('/v1/transactions/' + id);
    $('#modalCard').innerHTML =
      '<div class="modal-head"><div><h3>' + esc(t.orderId) + '</h3><p class="muted mono">' + esc(t.id) + '</p></div>' +
      '<button class="icon-btn" style="display:grid" onclick="closeModal()">✕</button></div>' +
      '<div class="modal-body"><div class="kv"><span>Status</span>' + statusBadge(t.status) + '</div>' +
      '<div class="kv"><span>Environment</span>' + envBadge(t) + '</div>' +
      '<div class="kv"><span>Nominal</span><strong>' + money(t.amount) + '</strong></div>' +
      '<div class="kv"><span>Pelanggan</span>' + esc(t.customer || '—') + '</div>' +
      '<div class="kv"><span>Metode</span>' + esc(t.paymentMethod) + ' · ' + esc(t.qrisMode) + '</div>' +
      '<div class="kv"><span>Dibuat</span>' + date(t.createdAt) + '</div>' +
      '<div class="kv"><span>Berakhir</span><span class="mono" data-countdown="' + t.id + '">' + countdownText(t) + '</span></div>' +
      '<div class="kv"><span>Sumber update</span>' + esc(t.lastUpdateSource || 'API') + '</div>' +
      '<p class="muted">QR string</p><div class="code-box">' + esc(t.qrString || t.qrCode || '(tidak tersedia)') + '</div>' +
      '<div style="display:flex;gap:10px;margin-top:16px;flex-wrap:wrap">' +
      (t.status === 'PENDING' ? '<button class="btn primary" onclick="simulate(\'' + t.id + '\',\'SUCCESS\');closeModal()">Simulasi Success</button>' + (t.isSandbox ? '<button class="btn secondary" onclick="simulate(\'' + t.id + '\',\'FAILED\');closeModal()">Simulasi Failed</button>' : '') + '<button class="btn secondary" onclick="simulate(\'' + t.id + '\',\'EXPIRED\');closeModal()">Simulasi Expired</button>' : '') +
      '<a class="btn wa" target="_blank" rel="noopener" href="' + waLink(t) + '">💬 Hubungi Super Admin (WhatsApp)</a>' +
      '</div><p class="muted" style="margin-top:12px">Tidak ada tombol refund di sini — refund hanya interface di laporan dan belum diimplementasi.</p></div>';
    $('#modal').classList.remove('hidden');
  } catch (e) { toast(e.message); }
}
function closeModal() { $('#modal').classList.add('hidden'); }
$('#modalBackdrop').onclick = closeModal;
document.addEventListener('keydown', e => { if (e.key === 'Escape') closeModal(); });

/* ---------- EFEK POINTER ---------- */
window.addEventListener('pointermove', e => {
  const g = $('.cursor-glow'); if (!g) return;
  g.style.left = e.clientX + 'px'; g.style.top = e.clientY + 'px';
  document.body.classList.add('pointer-active');
}, { passive: true });
document.documentElement.addEventListener('mouseleave', () => document.body.classList.remove('pointer-active'));
init();
