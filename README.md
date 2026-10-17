# Rekam Layar Pro

Perekam layar Android: 30/60/120 fps, 720p/1080p/2K, tiga tingkat kualitas, dan
audio dari suara perangkat, mikrofon, keduanya, atau tanpa audio. Kontrol jeda
dan stop mengambang di atas aplikasi lain dan bisa digeser.

## Cara tercepat: satu perintah di Termux

```bash
pkg install -y unzip
cd ~ && unzip ~/storage/downloads/rekam-layar-pro.zip
cd rekam-layar-pro
bash kirim.sh
```

Skrip itu memasang alatnya, membuat kunci penandatangan, membuat repo GitHub,
menyimpan kuncinya sebagai Secrets, mengunggah, menunggu build selesai, lalu
menaruh APK bertanda tangan di folder Download HP kamu. Jalankan lagi perintah
yang sama setiap kali ada perubahan.

## Cara build jadi APK lewat GitHub

1. Buat repository baru di GitHub, lalu unggah seluruh isi folder ini
   (bisa lewat tombol **Add file › Upload files**, atau `git push` biasa).
2. Buka tab **Actions**. Workflow `Build APK` jalan otomatis setelah push
   pertama. Kalau belum jalan, pilih workflow itu lalu **Run workflow**.
3. Tunggu sekitar 3–5 menit. Setelah hijau, buka run-nya dan unduh artifact
   **rekam-layar-pro-debug**. Di dalamnya ada `app-debug.apk`.
4. Pindahkan APK ke HP, izinkan pemasangan dari sumber tidak dikenal, lalu pasang.

APK ini bertanda tangan debug. Cukup untuk dipakai sendiri, tapi tidak bisa
diunggah ke Play Store tanpa keystore rilis sendiri.

## Build lewat Android Studio

Buka foldernya sebagai project. Android Studio akan membuat Gradle wrapper
sendiri, lalu **Run** seperti biasa. Butuh JDK 17.

## Izin yang diminta

| Izin | Kapan diminta | Kalau ditolak |
|---|---|---|
| Tangkap layar | Setiap kali mulai merekam | Rekaman tidak jalan (aturan Android, tidak bisa dilewati) |
| Mikrofon | Hanya jika sumber audio memakai mikrofon | Rekaman jalan tanpa suara mikrofon |
| Notifikasi | Sekali, di Android 13+ | Rekaman jalan, tapi tidak ada tombol di notifikasi |
| Tampilkan di atas aplikasi lain | Sekali, sebelum rekaman pertama | Rekaman jalan, kontrol hanya dari notifikasi |

## Batas yang nyata, bukan bug

- **120 fps sering tidak tersedia.** Aplikasi menanyakan langsung ke `MediaCodec`
  berapa fps maksimum yang sanggup di-encode pada ukuran yang dipilih. Banyak HP
  membatasi tangkap layar di 60 fps walaupun layarnya 120 Hz. Kalau begitu,
  pilihan 120 fps dimatikan dan menjelaskan alasannya saat dipencet.
- **2K butuh layar fisik minimal 1440 piksel di sisi pendek.** Kalau kurang,
  pilihan itu dimatikan karena hasilnya hanya pembesaran.
- **Suara internal butuh Android 10 ke atas**, dan hanya menangkap aplikasi yang
  mengizinkan perekaman. Aplikasi streaming berbayar umumnya memblokirnya —
  hasilnya senyap, bukan error.
- **Merekam sambil main game menurunkan fps game itu sendiri**, karena virtual
  display kedua ikut memakai bandwidth GPU.

## Kenapa aplikasi ini ringan

`VirtualDisplay` menggambar langsung ke input `Surface` milik `MediaCodec`, jadi
piksel tidak pernah masuk ke CPU atau RAM aplikasi. Encoding dikerjakan chip
encoder bawaan HP. Yang tetap terasa adalah panas dan baterai saat merekam lama
dengan bitrate tinggi, dan kecepatan tulis penyimpanan.

## Peta file

```
app/src/main/java/com/rekamlayar/pro/
  MainActivity.kt      layar utama, lembar pengaturan, riwayat
  RecorderService.kt   layanan latar depan, pemegang MediaProjection
  RecorderEngine.kt    inti rekaman: MediaCodec + AudioRecord + MediaMuxer
  OverlayControl.kt    pil mengambang yang bisa digeser
  Caps.kt              pembacaan batas asli perangkat
  Profil.kt            pengaturan dan penyimpanannya
  Riwayat.kt           daftar rekaman
```

Hasil rekaman disimpan di `Movies/RekamLayarPro` dan muncul di galeri.
