#!/bin/bash
# build_qalarender.sh — بناء خادم العرض qalarender لمعمارية arm64 (glibc)
# يُبنى داخل نظام الجذر نفسه (عبر proot) أو عبر بادئة مترجمة متبادلة.
#
# الاستخدام داخل الجذر:
#   bash /qalabox/build_qalarender.sh
# المتطلبات: gcc + libx11-dev + libxfixes-dev + libxtst-dev
set -e
cd "$(dirname "$0")"

if ! command -v gcc >/dev/null 2>&1; then
    echo "[!] gcc غير موجود — ثبّته: apt install gcc"
    exit 1
fi

for pkg in libx11-dev libxfixes-dev libxtst-dev; do
    if ! dpkg -s "$pkg" >/dev/null 2>&1; then
        echo "[!] حزمة التطوير $pkg غير موجودة — تُثبّت الآن…"
        apt-get update -qq && apt-get install -y -qq "$pkg"
    fi
done

echo "[+] بناء qalarender…"
gcc -O2 -o qalarender qalarender.c -lX11 -lXfixes -lXtst
echo "[+] تم — الملف: $(pwd)/qalarender"
echo "    انسخه إلى حزمة وقت التشغيل: runtime/imagefs/qalabox/qalarender"
