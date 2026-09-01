#!/bin/bash
# env_check.sh — فحص ذاتي لمكونات وقت التشغيل داخل الجذر (v1.4)
# كل سطر: OK: مكوّن — تفاصيل  |  MISSING: مكوّن (أثر فوري لسبب أي علوق)
# يُستدعى من الإعدادات عبر RuntimeManager.environmentCheck
check_bin() {  # check_bin <name> <hint>
    local name="$1" hint="${2:-}"
    local p
    p="$(command -v "$name" 2>/dev/null)" || { echo "MISSING: $name${hint:+ — $hint}"; return; }
    echo "OK: $name — $p"
}
ver_of() {  # ver_of <name> <args...>
    local name="$1"; shift
    local v
    v="$("$name" "$@" 2>/dev/null | head -1)" && [ -n "$v" ] \
        && echo "OK: $name — $v" || echo "MISSING: $name (لا يُنفَّذ أو لا يجيب)"
}

echo "── الأساس ──"
[ -x /bin/bash ] && echo "OK: bash — /bin/bash" || echo "MISSING: bash"
check_bin Xvfb "حزمة xvfb — بدونه لا يوجد عرض إطلاقاً"
check_bin xdpyinfo "حزمة x11-utils (اختياري)"
check_bin qalarender "جسر العرض — بدون الإطارات لا تصل للتطبيق"
[ -x /qalabox/qalarender ] && echo "OK: /qalabox/qalarender قابل للتنفيذ" \
    || echo "MISSING: /qalabox/qalarender غير قابل للتنفيذ"

echo "── Wine ──"
check_bin wine
check_bin wine64 "للألعاب 64-بت (اختياري)"
check_bin wineboot
check_bin wineserver
check_bin box64 "مترجم x86_64 — بدونه لن يعمل wine64"
check_bin box86 "مترجم x86 (32-بت) — بدونه لن تعمل الألعاب 32-بت مثل سترونغهولد"

echo "── الإصدارات ──"
command -v wine >/dev/null 2>&1 && ver_of wine --version
command -v wine64 >/dev/null 2>&1 && ver_of wine64 --version
command -v box64 >/dev/null 2>&1 && { v="$(box64 --version 2>/dev/null | head -1)"; [ -n "$v" ] && echo "OK: box64 — $v"; }
command -v box86 >/dev/null 2>&1 && { v="$(box86 --version 2>/dev/null | head -1)"; [ -n "$v" ] && echo "OK: box86 — $v"; }

echo "── الصوت ──"
check_bin pulseaudio "الصوت (اختياري — لعبة بلا صوت تعمل)"
check_bin pactl

echo "── مكتبات Wine الحرجة (32-بت) ──"
for lib in libX11.so.6 libXext.so.6 libXrender.so.1 libgnutls.so.30 libasound.so.2; do
    found=""
    for d in /usr/lib/i386-linux-gnu /lib/i386-linux-gnu /usr/lib32; do
        [ -e "$d/$lib" ] && { found="$d/$lib"; break; }
    done
    [ -n "$found" ] && echo "OK: i386/$lib" || echo "MISSING: i386/$lib — الألعاب 32-بت ستفشل دونها"
done

echo "── المحمّلات ──"
[ -e /lib/ld-linux.so.2 ] && echo "OK: /lib/ld-linux.so.2 (محمل 32-بت)" \
    || echo "MISSING: /lib/ld-linux.so.2 — wine32 لن يشتغل بدونه"
[ -e /lib64/ld-linux-x86-64.so.2 ] && echo "OK: /lib64/ld-linux-x86-64.so.2 (محمل 64-بت)" \
    || echo "MISSING: /lib64/ld-linux-x86-64.so.2 — wine64 لن يشتغل بدونه"
echo "── نهاية الفحص ──"
