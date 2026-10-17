#!/data/data/com.termux/files/usr/bin/bash
#
# Satu perintah untuk memperbarui aplikasi:
# membongkar zip terbaru dari Download, mengunggah ke GitHub,
# menunggu build, lalu menaruh APK di Download/RekamLayarPro.
#
#   bash ~/pasang.sh
#
set -e

PROYEK="$HOME/rekam-layar-pro"
UNDUHAN="$HOME/storage/downloads"
TUJUAN="$HOME/storage/downloads/RekamLayarPro"

pesan() { printf '\n\033[1;33m==> %s\033[0m\n' "$1"; }

# ---------------------------------------------- 1. cari zip terbaru
pesan "Mencari zip terbaru di Download"
ZIP=$(ls -t "$UNDUHAN"/rekam-layar-pro*.zip 2>/dev/null | head -n 1 || true)
if [ -z "$ZIP" ]; then
  echo "Tidak ada file rekam-layar-pro*.zip di folder Download."
  echo "Unduh dulu zip-nya, lalu jalankan skrip ini lagi."
  exit 1
fi
echo "Pakai: $(basename "$ZIP")"

# ---------------------------------------------- 2. bongkar menimpa
pesan "Membongkar ke $PROYEK"
mkdir -p "$PROYEK"
unzip -oq "$ZIP" -d "$HOME/.rl-tmp"
cp -rf "$HOME/.rl-tmp/rekam-layar-pro/." "$PROYEK/"
rm -rf "$HOME/.rl-tmp"
cd "$PROYEK"

# simpan salinan skrip ini supaya versi berikutnya tetap ada di home
cp -f pasang.sh "$HOME/pasang.sh" 2>/dev/null || true

# ---------------------------------------------- 3. periksa kesiapan
if [ ! -d .git ]; then
  echo "Folder ini belum terhubung ke GitHub. Jalankan dulu: bash kirim.sh"
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "Belum login GitHub. Jalankan dulu: gh auth login"
  exit 1
fi

# ---------------------------------------------- 4. unggah
pesan "Mengunggah perubahan"
git add -A
if git diff --cached --quiet; then
  echo "Tidak ada perubahan. Memakai build terakhir yang ada."
else
  git commit -m "Pembaruan $(date '+%Y-%m-%d %H:%M')" >/dev/null
  git push
fi

# ---------------------------------------------- 5. tunggu build
pesan "Menunggu GitHub membangun APK"
sleep 20
RUN=$(gh run list --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
if [ -n "$RUN" ]; then
  gh run watch "$RUN" --exit-status || true
fi

# ---------------------------------------------- 6. unduh APK
pesan "Mengunduh APK"
mkdir -p "$TUJUAN"
rm -f "$TUJUAN"/*.apk
if gh run download "$RUN" -n rekam-layar-pro-release -D "$TUJUAN" 2>/dev/null; then
  echo "APK rilis bertanda tangan siap."
elif gh run download "$RUN" -n rekam-layar-pro-debug -D "$TUJUAN" 2>/dev/null; then
  echo "APK debug siap."
else
  echo "Build gagal. Lihat sebabnya dengan:"
  echo "  gh run view $RUN --log-failed"
  exit 1
fi

pesan "Selesai"
echo "Buka Files > Download > RekamLayarPro, lalu pasang APK-nya."
