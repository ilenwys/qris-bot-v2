# ⚡ QRIS Bot - Android Automation

Otomasi GoPay Merchant QRIS menggunakan Android Accessibility Service + OCR.

---

## 📱 Cara Build APK via GitHub Actions (Tanpa PC)

### Step 1 — Buat Repository GitHub

1. Buka [github.com](https://github.com) → Login
2. Klik tombol **"+"** → **"New repository"**
3. Nama repo: `qris-bot`
4. Pilih **Public** (gratis)
5. Klik **"Create repository"**

---

### Step 2 — Upload Semua File Ini

Di halaman repository yang baru dibuat, klik **"uploading an existing file"**

Upload **seluruh isi folder ini** dengan struktur:
```
.github/
  workflows/
    build.yml
android-app/
  app/
    build.gradle
    src/
      main/
        AndroidManifest.xml
        assets/          ← taruh template images di sini!
        kotlin/...
        res/...
  build.gradle
  settings.gradle
  gradle.properties
  gradlew
  gradle/
    wrapper/
      gradle-wrapper.properties
```

---

### Step 3 — Tambahkan Template Images

**WAJIB** sebelum build! Upload 3 file PNG ke folder `android-app/app/src/main/assets/`:

| Nama File | Gambar |
|-----------|--------|
| `tpl_qr_button.png` | Crop tombol QR hijau di navbar bawah GoPay |
| `tpl_tambah_nominal.png` | Crop tombol "Tambah Nominal" |
| `tpl_terapkan.png` | Crop tombol "Terapkan" hijau |

Cara crop: Screenshot GoPay → crop ketat bagian tombolnya → save PNG.

---

### Step 4 — Jalankan Build

1. Di repository GitHub, klik tab **"Actions"**
2. Klik workflow **"Build QRIS Bot APK"**
3. Klik tombol **"Run workflow"** → **"Run workflow"**
4. Tunggu ~5-10 menit

---

### Step 5 — Download APK

1. Setelah build ✅ selesai, klik workflow run tersebut
2. Scroll ke bawah ke bagian **"Artifacts"**
3. Klik **"qris-bot-apk"** → download ZIP
4. Extract ZIP → dapat file `app-debug.apk`
5. Transfer ke HP → install (aktifkan "Install dari sumber tidak dikenal")

---

## 🔧 Setup APK di HP

Setelah install:

1. Buka app **QRIS Bot**
2. Masukkan URL server Python: `ws://IP_SERVER:8765`
3. Tap **"Izinkan Screenshot"** → grant permission
4. Tap **"Buka Pengaturan Accessibility"**
   - Cari **"QRIS Bot Service"** → aktifkan
5. Tap **CONNECT**
6. Status harus berubah jadi ✅

---

## 🐍 Jalankan Python Server

```bash
cd python-server
pip install -r requirements.txt
python server.py
```

Kirim perintah QRIS:
```bash
curl -X POST http://SERVER_IP:8080/api/send-qris \
  -H "Content-Type: application/json" \
  -d '{"amount": 10000}'
```

---

## ❓ Build Gagal?

Cek tab **Actions** → klik run yang gagal → lihat log error.

Error umum:
- `assets not found` → belum upload file template PNG
- `SDK not found` → GitHub Actions harusnya handle otomatis, coba re-run
- `Gradle error` → lihat log lengkap untuk detail
