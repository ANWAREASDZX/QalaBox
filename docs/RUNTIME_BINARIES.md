# بناء حزمة وقت التشغيل (.qbxruntime)

قلعة بوكس مفصولة عمداً عن الثنائيات الكبيرة (Wine/Box64/…) لأسباب قانونية وحجمية.
هذا الدليل يبني الحزمة من **مصادر مفتوحة 100%** على جهاز لينكس (أو WSL2).

## ⚡ الطريقة الآلية (موصى بها) — سكربت واحد

الطريقة اليدوية أدناه صالحة، لكن يوجد الآن سكربت يبني كل شيء **آلياً بدون qemu
ولا chroot** — يبني جذر Debian بحل اعتماديات `.deb` مباشرة:

```bash
bash runtime-src/build_runtime_package.sh out/
# الناتج: out/qalabox-runtime.qbxruntime + build-manifest.txt + version.json
```

- يتضمن: جذر Debian arm64 (Xvfb/PulseAudio/X11/Mesa/خطوط) + مكتبات الضيف
  amd64/i386 + **Wine** (بناءات Kron4ek) + **Box64/Box86** (بناء cross بـ dynarec)
  + **qalarender** مُجمّع مسبقاً + **proot** ثابت + **cnc-ddraw** + التغليف النهائي
- وضع تحقق سريع من الجذر فقط: `DRY=1 bash runtime-src/build_runtime_package.sh out/`
- نفس السكربت يعمل على CircleCI (منظمة QalaBox — تعريف `qalabox-runtime`) ويضع
  الحزمة artifact جاهزة للتنزيل. المتغيرات: `SUITE WINE_TAG BOX64_TAG BOX86_TAG`

## المحتويات (ZIP واحد)

```
qalabox-runtime.zip  (غيّر الامتداد إلى .qbxruntime)
├── imagefs.tar              # نظام جذر Debian arm64 (الجزء الأكبر)
├── proot                    # ثنائي proot ثابت arm64
├── xserver/qalax11          # (اختياري) خادم X مخصص لأندرويد
├── dxwrapper/cnc-ddraw/ddraw.dll
├── dxwrapper/dxvk/*.dll     # (اختياري)
└── version.json             # {"version":"1.0.0","notes":"..."}
```

## 1) نظام الجذر imagefs (Debian arm64)

```bash
# عبر qemu-user-static + debootstrap
sudo apt install debootstrap qemu-user-static
mkdir rootfs && cd rootfs
sudo debootstrap --arch=arm64 --foreign bookworm . http://deb.debian.org/debian
sudo chroot . /debootstrap/debootstrap --second-stage

# أدخل الجذر عبر qemu
sudo chroot . qemu-aarch64-static /bin/bash
# ثم داخل الجذر:
dpkg --configure -a
apt update
apt install -y locales ca-certificates libc6 libgcc-s1 \
    libx11-6 libxext6 libxfixes3 libxtst6 libxxf86vm1 libgl1 mesa-utils \
    xvfb x11-utils pulseaudio pulseaudio-utils proot \
    libpulse0 libasound2
# ضبط اللغة
locale-gen en_US.UTF-8
```

### Wine + Box86/Box64 (قلب المحاكاة)

الطريقة الموصى بها: خذ بناءات **Box64/Box86** الرسمية (ptitSeb — رخصة MIT)
وبناءات **Wine-x86** الشهيرة للتشغيل تحت المترجم، وثبّتها بالهيكل التالي
بحيث تكون أوامر `wine/wineboot/wineserver` **ملفات وسيطة (wrappers)** تستدعي
box86/box64 تلقائياً:

```bash
# مثال مبدئي للهيكل (داخل imagefs):
apt install -y box86 box64        # أو ثبّت من مصدر ptitSeb
# ضع wine (x86) في /opt/wine واصنع wrappers:
cat > /usr/local/bin/wine <<'EOF'
#!/bin/bash
exec box86 /opt/wine/bin/wine "$@"
EOF
chmod +x /usr/local/bin/wine
```

> الوعد الواجهي لقلعة بوكس بسيط: **أوامر wine/wineboot/wineserver تعمل
> مباشرة داخل الجذر** (مهما كانت طريقة التغليف). كل السكربتات تعتمد هذا فقط.
> يمكنك أيضاً الاسترشاد بهيكل imagefs لمشاريع مفتوحة مشابهة (Winlator وغيرها)
> والرجوع لمستندات Box64.

### إضافات داخل الجذر لقلعة بوكس

```bash
# مجلد قلعة بوكس داخل الجذر (سكربتات التطبيق تُنسخ إليه تلقائياً عند التشغيل)
mkdir -p /qalabox
# qalarender (خادم العرض) — انسخ مصدره وابنِه:
apt install -y gcc libx11-dev libxfixes-dev libxtst-dev
# انسخ runtime-src/qalarender.c إلى الجذر ثم:
gcc -O2 -o /qalabox/qalarender qalarender.c -lX11 -lXfixes -lXtst
```

## 2) proot ثابت

```bash
# من مشروع proot رسمي (GPL 2.0) — خذ بناء arm64 الثابت أو رقّقه:
# https://proot-me.github.io/  أو مستودعات Termux
cp proot /path/to/package/proot
chmod +x /path/to/package/proot
```

## 3) ddraw.dll (cnc-ddraw)

```bash
# من إصدارات cnc-ddraw الرسمية (GPL) — رخصة تسمح بإعادة التوزيع بشروطها:
# https://github.com/FunkyFr3sh/cnc-ddraw/releases
cp ddraw.dll /path/to/package/dxwrapper/cnc-ddraw/ddraw.dll
```

## 4) version.json

```json
{"version": "1.0.0", "notes": "بناء أول — Wine 9 + Box64 0.3.x + Xvfb"}
```

## 5) تجميع الحزمة

```bash
cd /path/to/package
zip -r ../qalabox-runtime.qbxruntime imagefs.tar proot version.json \
    dxwrapper xserver 2>/dev/null || \
zip -r ../qalabox-runtime.qbxruntime imagefs.tar proot version.json dxwrapper
```

ثم انقل الملف للهاتف وثبّته من: **الإعدادات ← تثبيت وقت التشغيل**.

## الاختيار المتقدم: خادم X مخصص (أداء أعلى)

المسار الافتراضي يستخدم Xvfb داخل الجذر (توافق شامل). إن أردت أداء أعلى
بُنِ خادم X مخصص لأندرويد (bionic) يلتزم بالعقد:

```
qalax11 :0 -socketdir <مجلد المقبس> -screen WxHx32 -noreset
```

ويضع مقبس العرض في `<socketdir>/X0`. ضع الثنائي في `xserver/qalax11`
وسيستخدمه التطبيق تلقائياً قبل اللجوء لـ Xvfb.

## حل المشاكل

| العرض | السبب المحتمل |
|---|---|
| «حزمة غير صالحة» | ناقص imagefs.tar أو proot أو version.json |
| شاشة سوداء بعد التثبيت | qalarender غير مبني داخل الجذر — راجع سجل الجلسة |
| لا صوت | pulseaudio غير مثبت داخل الجذر أو منفذ 4712 محجوب |
| wine: command not found | wrappers غير موجودة في PATH داخل الجذر |

كل أخطاء التشغيل تظهر في **سجل الجلسة** داخل التطبيق (زر «السجل»
في شاشة المحاكاة، أو تصديره من الإعدادات).
