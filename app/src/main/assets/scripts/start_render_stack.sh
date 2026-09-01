#!/bin/bash
# start_render_stack.sh — حزمة العرض داخل الجذر (الخيار الافتراضي):
#   Xvfb (خادم X افتراضي) + qalarender (التقاط الإطارات والربط بالتطبيق)
# يتم استدعاؤه من التطبيق عبر proot قبل إقلاع Wine.
# v1.1: لا يفشل إذا غاب xdpyinfo — يتحقق من مقبس X مباشرة
# v1.4: سجلات فشل صريحة (سبب موت Xvfb/qalarender ورقم الخروج) — كانت
#       الإخفاقات تمر بصمت فتبقى شاشة «جارٍ تشغيل البيئة» معلقة إلى الأبد
set -u
W="${QB_SCREEN_W:-1280}"
H="${QB_SCREEN_H:-720}"
FPS="${QB_FPS:-60}"
export DISPLAY="${DISPLAY:-:0}"

log() { echo "[render] $*"; }
err() { echo "[render][خطأ] $*"; }

xready() {
    if command -v xdpyinfo >/dev/null 2>&1; then
        xdpyinfo >/dev/null 2>&1
    else
        [ -e "/tmp/.X11-unix/X0" ]
    fi
}

log "تشغيل Xvfb بدقة ${W}x${H}…"
if ! xready; then
    if ! command -v Xvfb >/dev/null 2>&1; then
        err "Xvfb غير موجود في نظام الجذر! حزمة وقت التشغيل ناقصة (حزمة xvfb)"
        err "أعد تثبيت حزمة .qbxruntime كاملة من docs/RUNTIME_BINARIES.md"
        sleep infinity
    fi
    Xvfb :0 -screen 0 "${W}x${H}x24" -nolisten tcp +extension GLX +render &
    XVFB_PID=$!
    # انتظار جاهزية الخادم (حتى 6 ثوان)
    for i in $(seq 1 60); do
        if xready; then break; fi
        # إن مات Xvfb مبكراً فلا معنى للانتظار — سجل السبب وتوقف
        if ! kill -0 "$XVFB_PID" 2>/dev/null; then
            err "مات Xvfb فور إطلاقه (خروج) — راجع سجلات kernel/الذاكرة"
            err "ملاحظة: بعض الأجهزة تمنع MIT-SHM — جرّب LIBGL_ALWAYS_SOFTWARE=1"
            sleep infinity
        fi
        sleep 0.1
    done
    if ! xready; then
        err "انتهت مهلة جاهزية Xvfb (6 ثوان) — الخادم حي لكنه لا يستجيب"
        sleep infinity
    fi
fi
log "خادم X جاهز"

# مقبس الجسر داخل مجلد ملزم ليصل للتطبيق
SOCKET="/tmp/.X11-unix/.xbridge.sock"
if [ -x /qalabox/qalarender ]; then
    log "تشغيل qalarender (fps=$FPS)…"
    exec /qalabox/qalarender --display :0 --socket "$SOCKET" --fps "$FPS"
elif [ -f /qalabox/qalarender ]; then
    err "/qalabox/qalarender موجود لكنه غير قابل للتنفيذ — بت التنفيذ فُقد أثناء التثبيت"
    chmod +x /qalabox/qalarender 2>/dev/null && log "أُعيد ضبط بت التنفيذ — إعادة المحاولة" \
        && exec /qalabox/qalarender --display :0 --socket "$SOCKET" --fps "$FPS"
    err "تعذر تنفيذ /qalabox/qalarender حتى بعد إصلاح البت — أعد تثبيت الحزمة"
    sleep infinity
else
    err "/qalabox/qalarender غير موجود في نظام الجذر!"
    err "حزمة وقت التشغيل المثبتة قديمة/ناقصة — هذه الحزمة يجب أن تحتوي qalarender داخل الجذر"
    err "ابنِ حزمة جديدة من runtime-src/build_runtime_package.sh وأعد تثبيتها"
    # إبقاء الجلسة حية حتى لو غاب الجسر (لتشخيص أسهل من السجلات)
    sleep infinity
fi
