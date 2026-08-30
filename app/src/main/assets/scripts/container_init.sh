#!/bin/bash
# container_init.sh — تهيئة Wine prefix لحاوية جديدة أو مُعادة الإنشاء
# v1.1: يشغّل Xvfb مؤقتاً إذا لم يوجد خادم عرض — wineboot يحتاج DISPLAY
set -u
export WINEPREFIX="/container/prefix"
export WINEARCH="${1:-win32}"
export WINEDEBUG=-all
export DISPLAY="${DISPLAY:-:0}"

XVFB_PID=0
cleanup() {
    if [ "$XVFB_PID" != "0" ]; then
        kill "$XVFB_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

# wineboot يتطلب عرض X — أطلق Xvfb مؤقتاً إن غاب الخادم
if ! [ -e "/tmp/.X11-unix/X0" ]; then
    if command -v Xvfb >/dev/null 2>&1; then
        echo "[init] لا يوجد خادم عرض — تشغيل Xvfb مؤقت…"
        Xvfb :0 -screen 0 "1024x768x24" -nolisten tcp &
        XVFB_PID=$!
        # انتظار ظهور المقبس
        for i in $(seq 1 50); do
            [ -e "/tmp/.X11-unix/X0" ] && break
            sleep 0.1
        done
    else
        echo "[init] تنبيه: Xvfb غير موجود — قد تفشل التهيئة الرسومية"
    fi
fi

echo "[init] تهيئة prefix (${WINEARCH})…"
wineboot -u || true
wineserver -w || true
echo "[init] انتهت التهيئة"
