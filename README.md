# EDU Timur Kasuari CBT — Android App

Aplikasi CBT Android dengan **FLAG_SECURE** (screenshot diblokir di level OS).

---

## Cara Build APK via GitHub (TANPA Android Studio)

### Step 1 — Upload ke GitHub
Buka Claude Code di desktop dan jalankan perintah ini satu per satu:

```bash
# Masuk ke folder project
cd cbt-android

# Init git
git init
git add .
git commit -m "Initial: EDU Timur Kasuari CBT Android App"

# Buat repo di GitHub dulu (github.com → New Repository → nama: cbt-android → Public)
# Lalu jalankan:
git remote add origin https://github.com/USERNAME_KAMU/cbt-android.git
git branch -M main
git push -u origin main
```

### Step 2 — GitHub Actions Build Otomatis
Setelah push, GitHub Actions langsung build APK.
- Buka: `github.com/USERNAME_KAMU/cbt-android/actions`
- Tunggu ~3-5 menit hingga ada centang hijau ✅
- Klik workflow → **Artifacts** → Download **CBT-Debug-APK**

### Step 3 — Install di HP Siswa
1. Copy file `.apk` ke HP
2. Aktifkan "Install dari sumber tidak dikenal" di Settings HP
3. Install APK
4. Buka app → langsung masuk ke halaman CBT

---

## Fitur Keamanan

| Fitur | Status |
|-------|--------|
| **FLAG_SECURE** (screenshot hitam) | ✅ Aktif |
| Fullscreen kiosk mode | ✅ Aktif |
| Tombol Back tidak keluar app | ✅ Aktif |
| Hanya akses edu.timurkasuari.com | ✅ Aktif |
| Tidak ada izin kamera/mic/GPS | ✅ |
| HTTPS only | ✅ Aktif |
| Layar tidak mati saat ujian | ✅ Aktif |

---

## Upload ke Play Store (Opsional)

Untuk Play Store perlu **Release APK** yang ditandatangani dengan keystore.

### Buat Keystore (jalankan di terminal):
```bash
keytool -genkey -v -keystore cbt-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -alias cbt-key
```

### Tambahkan ke GitHub Secrets:
Di GitHub repo → Settings → Secrets → Actions → New secret:

```
KEYSTORE_BASE64  = (base64 dari file .jks: base64 -i cbt-release.jks)
KEYSTORE_PASSWORD = password keystore
KEY_ALIAS        = cbt-key
KEY_PASSWORD     = password key
```

Setelah itu push lagi → GitHub Actions akan build Release APK.

---

## Troubleshooting

**Build gagal "SDK not found"**
→ GitHub Actions sudah handle otomatis, tidak perlu setup manual

**APK terinstall tapi tidak bisa buka**
→ Pastikan HP Android minimal versi 5.0 (Lollipop)

**Screenshot masih bisa di HP tertentu**
→ FLAG_SECURE efektif di Android 5+. HP dengan custom ROM mungkin bypass.
