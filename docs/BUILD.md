# بناء قلعة بوكس — خطوة بخطوة

## المتطلبات

| المكوّن | الإصدار |
|---|---|
| Android Studio | Hedgehog (2023.1) أو أحدث |
| JDK | 17 (مدمج مع Android Studio) |
| Android SDK | API 34 (compileSdk) |
| Android NDK | r26+ (يُثبّت من SDK Manager) |
| CMake | 3.22.1 (من SDK Manager) |
| جهاز اختبار | أندرويد 10+ بمعالج ARM64 |

## الخطوات

### 1) فتح المشروع
- فك ضغط `QalaBox-v1.0-source.zip`
- Android Studio ← `File > Open` ← مجلد `QalaBox`
- انتظر مزامنة Gradle (سيُنزّل الاعتمادات تلقائياً)

### 2) تثبيت NDK وCMake
`Tools > SDK Manager > SDK Tools`:
- [x] NDK (Side by side) — أي إصدار r26 أو أحدث
- [x] CMake — 3.22.1

### 3) البناء
- `Build > Build Bundle(s) / APK(s) > Build APK(s)`
- الناتج: `app/build/outputs/apk/debug/app-debug.apk`
- للإصدار النهائي: أنشئ مفتاح توقيع من `Build > Generate Signed Bundle/APK`

> **ملاحظة**: أول بناء يترجم `xbridge.c` تلقائياً عبر CMake — لا تحتاج أي إعداد.

### 4) التثبيت على الجهاز
```bash
adb install app-debug.apk
```
أو انسخ APK وثبّته يدوياً (فعّل «مصادر غير معروفة»).

## ماذا بعد التثبيت؟

التطبيق يعمل بكل واجهاته لكنه يحتاج **حزمة وقت التشغيل** (النواة الثنائية).
هذا مقصود ولأسباب قانونية/حجمية — راجع `RUNTIME_BINARIES.md` لبناء الحزمة
ثم ثبّتها من: الإعدادات ← تثبيت وقت التشغيل.

## حل مشاكل البناء الشائعة

| المشكلة | الحل |
|---|---|
| `Failed to resolve: material` | تأكد من اتصال الإنترنت عند أول مزامنة |
| `CMake not found` | ثبّت CMake 3.22.1 من SDK Manager |
| `NDK not configured` | SDK Manager ← NDK، ثم أعد المزامنة |
| `Unsupported class file major version` | استخدم JDK 17 (Settings > Build > Gradle > Gradle JDK) |
| بناء الإصدار Release يفشل في proguard | القواعد جاهزة في `proguard-rules.pro` — تأكد أنها لم تُحذف |

## تخصيص سريع

- تغيير الاسم/الأيقونة: `res/values/strings.xml` و`drawable/ic_launcher_foreground.xml`
- إضافة بروفايل لعبة: ملف JSON جديد في `assets/profiles/` — الدليل في `PROFILES.md`
- تغيير الألوان: `res/values/colors.xml`
