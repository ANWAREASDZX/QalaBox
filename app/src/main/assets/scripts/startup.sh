#!/bin/bash
# startup.sh — إقلاع اللعبة داخل نظام الجذر (يستدعيه Launcher.launchGame)
# v1.1: انتظار جاهزية العرض + اختيار wine64 للألعاب 64-بت + تنظيف متغيرات
# v1.4: فحوص إقلاع صريحة (wine/الملف التنفيذي) + سطح مكتب Wine افتراضي
#       (QB_DESKTOP) + سجلات تشخيص غابت عن ExaGear كلياً — كل فشل يترك أثراً
set -u
export WINEPREFIX="${QB_WINEPREFIX:-/container/prefix}"
export WINEDEBUG="${WINEDEBUG:--all}"
export DISPLAY="${DISPLAY:-:0}"
# WINEDLLOVERRIDES يصل مباشرة عبر بيئة الجلسة (يضبطه DxWrapperManager)

log() { echo "[startup] $*"; }
err() { echo "[startup][خطأ] $*"; }

# ── 0) انتظار خادم العرض (كفالة: wine لن يبدأ قبل أن يكون :0 حياً) ──
X_READY=0
for i in $(seq 1 50); do
    if [ -e "/tmp/.X11-unix/X0" ]; then X_READY=1; break; fi
    sleep 0.1
done
if [ "$X_READY" = "1" ]; then
    log "العرض :0 جاهز"
else
    err "انتهت مهلة انتظار مقبس العرض :0 — حزمة العرض لم تنجح، الإطارات لن تصل أبداً"
fi

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

# ── 4-ب) فحوص إقلاع صريحة — كانت الإخفاقات تمر بصمت (v1.4) ──
if ! command -v "$WINE_BIN" >/dev/null 2>&1; then
    err "الثنائي «$WINE_BIN» غير موجود في نظام الجذر! حزمة وقت التشغيل ناقصة أو معطوبة"
    err "المطلوب: wine (وbox86/box64) داخل الجذر — راجع docs/RUNTIME_BINARIES.md"
    exit 127
fi
EXE_NAME="${QB_EXE_WIN##*\\}"
GAME_FILE="/container/prefix/drive_c/${QB_EXE_REL_DIR:-}/${EXE_NAME}"
if [ -n "${QB_EXE_WIN:-}" ] && [ ! -f "$GAME_FILE" ]; then
    err "ملف اللعبة غير موجود: $GAME_FILE"
    err "تحقق من وضع ملفات اللعبة داخل مجلد الحاوية (drive_c) ومن اسم الملف التنفيذي"
    exit 2
fi
log "المعمارية=${QB_ARCH:-x86} | WINEARCH=${WINEARCH:-win32} | الواجهة=$WINE_BIN"
log "تشغيل: ${QB_EXE_WIN:-} ${QB_ARGS:-}"

# ── 5) سطح مكتب Wine افتراضي (v1.4) ──
# مستوى ثابت من الاستقرار للألعاب الكلاسيكية: يمنع فشل تغيير الدقة/الوضع
# الحصري على Xvfb (سبب شاشة سوداء/خروج صامت)، وبنفس نمط ExaGear.
# يُعطَّل لكل بروفايل عبر env: QB_DESKTOP=0
if [ "${QB_DESKTOP:-1}" = "1" ]; then
    DW="${QB_SCREEN_W:-1280}"
    DH="${QB_SCREEN_H:-720}"
    log "سطح مكتب افتراضي ${DW}x${DH} (QB_DESKTOP=1 — أوقفه من بروفايل اللعبة عند الحاجة)"
    exec "$WINE_BIN" explorer "/desktop=QalaBox,${DW}x${DH}" "${QB_EXE_WIN}" ${QB_ARGS:-}
fi

exec "$WINE_BIN" "${QB_EXE_WIN}" ${QB_ARGS:-}
