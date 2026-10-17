#!/data/data/com.termux/files/usr/bin/bash
#
# Satu perintah untuk Termux: siapkan kunci, unggah ke GitHub, bangun APK,
# lalu unduh hasilnya ke folder Download.
#
#   bash kirim.sh              -> repo bernama rekam-layar-pro
#   bash kirim.sh nama-repo    -> repo dengan nama lain
#
set -e

REPO="${1:-rekam-layar-pro}"
JKS="$HOME/rekam-layar.jks"
SANDI="$HOME/.rekam-layar-pass"
ALIAS="rekamlayar"

pesan() { printf '\n\033[1;33m==> %s\033[0m\n' "$1"; }

# ---------------------------------------------------------------- 1. periksa
if [ ! -f settings.gradle.kts ]; then
  echo "Jalankan skrip ini dari dalam folder proyek (yang berisi settings.gradle.kts)."
  exit 1
fi

# ------------------------------------------------------------- 2. alat kerja
pesan "Memasang alat yang dibutuhkan"
pkg install -y git gh openjdk-17 >/dev/null

# --------------------------------------------------------------- 3. keystore
if [ ! -f "$JKS" ]; then
  pesan "Membuat kunci penandatangan (sekali seumur proyek)"
  echo "Kunci ini yang menandai aplikasi sebagai milikmu."
  echo "Kalau hilang, pembaruan aplikasi tidak bisa dipasang menimpa versi lama."
  echo
  read -r -s -p "Buat kata sandi keystore: " KSPASS; echo
  read -r -s -p "Ulangi kata sandi        : " KSPASS2; echo
  if [ "$KSPASS" != "$KSPASS2" ]; then echo "Kata sandi tidak sama."; exit 1; fi
  if [ ${#KSPASS} -lt 6 ]; then echo "Kata sandi minimal 6 karakter."; exit 1; fi

  keytool -genkeypair -v -keystore "$JKS" -alias "$ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KSPASS" -keypass "$KSPASS" \
    -dname "CN=Rekam Layar Pro, OU=Pribadi, O=Pribadi, L=Lampung, S=Lampung, C=ID" >/dev/null 2>&1

  printf '%s' "$KSPASS" > "$SANDI"
  chmod 600 "$SANDI"
  echo "Kunci tersimpan di $JKS"
  echo "Kata sandinya disimpan di $SANDI supaya skrip ini tidak bertanya lagi."
  echo "Hapus file itu kalau kamu lebih suka mengetik sandinya setiap kali."
else
  if [ -f "$SANDI" ]; then
    KSPASS=$(cat "$SANDI")
  else
    read -r -s -p "Kata sandi keystore: " KSPASS; echo
  fi
fi

# -------------------------------------------------------------------- 4. git
if [ ! -d .git ]; then
  pesan "Menyiapkan git"
  git init -b main >/dev/null
  if [ -z "$(git config --global user.email || true)" ]; then
    read -r -p "Email GitHub kamu : " EMAIL
    read -r -p "Nama kamu         : " NAMA
    git config --global user.email "$EMAIL"
    git config --global user.name "$NAMA"
  fi
fi

pesan "Mencatat perubahan"
git add -A
git commit -m "Rekam Layar Pro $(date '+%Y-%m-%d %H:%M')" >/dev/null 2>&1 || echo "Tidak ada perubahan baru."

# --------------------------------------------------------------- 5. login gh
if ! gh auth status >/dev/null 2>&1; then
  pesan "Login GitHub"
  echo "Pilih: GitHub.com -> HTTPS -> Y -> Login with a web browser."
  echo "Salin kode yang muncul, buka github.com/login/device di browser, tempel di sana."
  gh auth login
fi

# ----------------------------------------------------- 6. repo dan rahasianya
if git remote get-url origin >/dev/null 2>&1; then
  BARU=tidak
else
  pesan "Membuat repository $REPO"
  gh repo create "$REPO" --private --source=. --remote=origin >/dev/null
  BARU=ya
fi

pesan "Menyimpan kunci ke GitHub Secrets"
base64 -w0 "$JKS" | gh secret set KEYSTORE_BASE64 >/dev/null
printf '%s' "$KSPASS"  | gh secret set KEYSTORE_PASSWORD >/dev/null
printf '%s' "$ALIAS"   | gh secret set KEY_ALIAS >/dev/null
printf '%s' "$KSPASS"  | gh secret set KEY_PASSWORD >/dev/null
echo "Empat rahasia tersimpan. Isinya tidak bisa dibaca lagi dari web, termasuk olehmu."

# ---------------------------------------------------------------- 7. unggah
pesan "Mengunggah"
if [ "$BARU" = "ya" ]; then
  git push -u origin main
else
  git push
fi

# ------------------------------------------------------------- 8. tunggu build
pesan "Menunggu GitHub membangun APK (sekitar 3-6 menit)"
sleep 20
RUN=$(gh run list --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
if [ -n "$RUN" ]; then
  gh run watch "$RUN" --exit-status || true
fi

# ---------------------------------------------------------------- 9. unduh
TUJUAN="$HOME/storage/downloads/RekamLayarPro"
mkdir -p "$TUJUAN"
pesan "Mengunduh APK"
if gh run download "$RUN" -n rekam-layar-pro-release -D "$TUJUAN" 2>/dev/null; then
  echo "APK rilis bertanda tangan tersimpan di: Download/RekamLayarPro"
elif gh run download "$RUN" -n rekam-layar-pro-debug -D "$TUJUAN" 2>/dev/null; then
  echo "APK debug tersimpan di: Download/RekamLayarPro"
else
  echo "Build belum menghasilkan APK. Lihat lognya dengan: gh run view $RUN --log-failed"
  exit 1
fi

pesan "Selesai"
echo "Buka aplikasi Files, masuk ke Download/RekamLayarPro, lalu pasang APK-nya."
echo
echo "Untuk perubahan berikutnya, cukup jalankan lagi: bash kirim.sh"
