#!/bin/bash
# start_render_stack.sh — حزمة العرض داخل الجذر (الخيار الافتراضي):
#   Xvfb (خادم X افتراضي) + qalarender (التقاط الإطارات والربط بالتطبيق)
# يتم استدعاؤه من التطبيق عبر proot قبل إقلاع Wine.
# v1.1: لا يفشل إذا غاب xdpyinfo — يتحقق من مقبس X مباشرة
set -u
W="${QB_SCREEN_W:-1280}"
H="${QB_SCREEN_H:-720}"
FPS="${QB_FPS:-60}"
export DISPLAY="${DISPLAY:-:0}"

xready() {
    if command -v xdpyinfo >/dev/null 2>&1; then
        xdpyinfo >/dev/null 2>&1
    else
        [ -e "/tmp/.X11-unix/X0" ]
    fi
}

echo "[render] تشغيل Xvfb بدقة ${W}x${H}…"
if ! xready; then
    Xvfb :0 -screen 0 "${W}x${H}x24" -nolisten tcp +extension GLX +render &
    # انتظار جاهزية الخادم (حتى 6 ثوان)
    for i in $(seq 1 60); do
        if xready; then break; fi
        sleep 0.1
    done
fi
echo "[render] خادم X جاهز"

# مقبس الجسر داخل مجلد ملزم ليصل للتطبيق
SOCKET="/tmp/.X11-unix/.xbridge.sock"
if [ -x /qalabox/qalarender ]; then
    echo "[render] تشغيل qalarender (fps=$FPS)…"
    exec /qalabox/qalarender --display :0 --socket "$SOCKET" --fps "$FPS"
else
    echo "[render] تنبيه: /qalabox/qalarender غير موجود — ابنه من runtime-src/"
    # إبقاء الجلسة حية حتى لو غاب الخادم (لتشخيص أسهل)
    sleep infinity
fi
