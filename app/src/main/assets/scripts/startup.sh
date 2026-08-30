#!/bin/bash
# startup.sh — إقلاع اللعبة داخل نظام الجذر (يستدعيه Launcher.launchGame)
# v1.1: انتظار جاهزية العرض + اختيار wine64 للألعاب 64-بت + تنظيف متغيرات
set -u
export WINEPREFIX="${QB_WINEPREFIX:-/container/prefix}"
export WINEDEBUG="${WINEDEBUG:--all}"
export DISPLAY="${DISPLAY:-:0}"
# WINEDLLOVERRIDES يصل مباشرة عبر بيئة الجلسة (يضبطه DxWrapperManager)

log() { echo "[startup] $*"; }

# ── 0) انتظار خادم العرض (كفالة: wine لن يبدأ قبل أن يكون :0 حياً) ──
for i in $(seq 1 50); do
    if [ -e "/tmp/.X11-unix/X0" ]; then break; fi
    sleep 0.1
done
log "العرض :0 جاهز"

# ── 1) الصوت: PulseAudio + بروتوكول Simple-TCP على المنفذ 4712 ──
# (علاج تقطيع الصوت: تدفق PCM خام يستقبله التطبيق ويضبط سماكته)
if command -v pulseaudio >/dev/null 2>&1; then
    pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true
    if command -v pactl >/dev/null 2>&1; then
        # فشل التحميل هنا عادة يعني أنه محمّل مسبقاً — لا مشكلة
        pactl load-module module-simple-protocol-tcp port=4712 rate=48000 format=s16le channels=2 >/dev/null 2>&1 || true
        log "PulseAudio جاهز على منفذ 4712"
    fi
fi

# ── 2) تطبيق مفاتيح التسجيل الخاصة بالبروفايل (كتبها ProfileEngine) ──
if [ -f /container/apply_registry.sh ]; then
    log "تطبيق مفاتيح التسجيل…"
    bash /container/apply_registry.sh || true
    rm -f /container/apply_registry.sh
fi

# ── 3) المجلد الحالي = مجلد اللعبة (ألعاب كلاسيكية كثيرة تقرأ CWD) ──
if [ -n "${QB_EXE_REL_DIR:-}" ]; then
    cd "/container/prefix/drive_c/${QB_EXE_REL_DIR}" 2>/dev/null || true
fi

# ── 4) الإقلاع: اختر wine64 لملفات PE ذات 64-بت إن توفرت ──
WINE_BIN="wine"
if [ "${QB_ARCH:-x86}" = "x64" ] && command -v wine64 >/dev/null 2>&1; then
    WINE_BIN="wine64"
fi
log "تشغيل: ${WINE_BIN} \"${QB_EXE_WIN:-}\" ${QB_ARGS:-}"
exec "${WINE_BIN}" "${QB_EXE_WIN}" ${QB_ARGS:-}
