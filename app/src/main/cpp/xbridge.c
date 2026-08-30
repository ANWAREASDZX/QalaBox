/*
 * xbridge.c — الجسر الأصلي لقلعة بوكس (جانب أندرويد/bionic)
 * ------------------------------------------------------------
 * يتصل بخادم العرض qalarender (يعمل داخل نظام الجذر glibc) عبر مقبس يونكس:
 *   ← إطارات BGRA → تُحوَّل وتُحجَّم (letterbox) وتُعرض على Surface عبر ANativeWindow
 *   ← حزم مؤشر الماوس (XFixes) → تُرسل للواجهة عبر JNI
 *   → حزم إدخال (حركة/أزرار/تمرير/مفاتيح) → XTest داخل الجذر
 *
 * البروتوكول (little-endian) — كل حزمة من الخادم:
 *   u32 magic ; u32 payloadSize ; ثم الحمولة:
 *     إطار : magic 'QB1F' | payload = u32 w, u32 h, u32 idx + BGRA(w*h*4)
 *     مؤشر : magic 'QBCU' | payload = i32 x,y,hotx,hoty, u32 w,h + ARGB
 * حزمة الإدخال من التطبيق (24 بايت):
 *   u32 'QBIN' u32 type i32 a i32 b u32 keysym u32 down
 *
 * v1.1 إصلاحات:
 *   - استهلاك الحمولات غير المعروفة دائماً (منع فقدان تزامن التدفق)
 *   - shutdown() قبل close() لفك حجب read() من خيط آخر
 *   - تحجيم الإطار letterbox بدل القصّ (شاشات بنسب مختلفة)
 *   - تقييد الإطارات بـ g_maxFps (توفير طاقة/حرارة)
 *   - مقارنات size_t آمنة وحماية GetStringUTFChars
 */
#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <string.h>
#include <stdlib.h>
#include <time.h>

#define LOG_TAG "QalaXBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define MAGIC_FRAME  0x51423146u  /* QB1F */
#define MAGIC_CURSOR 0x51424355u  /* QBCU */
#define MAGIC_INPUT  0x5142494Eu  /* QBIN */

#define IN_MOVE_REL 1
#define IN_MOVE_ABS 2
#define IN_BUTTON   3
#define IN_SCROLL   4
#define IN_KEY      5

#define MAX_PAYLOAD (64u * 1024u * 1024u) /* سقف أمان 64MB للحزمة الواحدة */

typedef struct {
    uint32_t magic, payloadSize;
} PacketHeader;

typedef struct {
    uint32_t magic, type;
    int32_t a, b;
    uint32_t keysym, down;
} InputPacket;

static JavaVM *g_vm = NULL;
static jobject g_thiz = NULL;
static jmethodID g_mFps = NULL;
static jmethodID g_mCursor = NULL;

static ANativeWindow *g_window = NULL;
static int g_sock = -1;
static pthread_t g_rxThread;
static pthread_mutex_t g_writeMutex = PTHREAD_MUTEX_INITIALIZER;
static volatile int g_running = 0;
static volatile int g_maxFps = 60;
static char g_socketPath[256] = {0};
static uint8_t *g_frameBuf = NULL;
static size_t g_frameBufSize = 0;
static volatile int g_lastW = 0;
static volatile int g_lastH = 0;

/* ═══════════════ JNI بيئة للخيوط الأصلية ═══════════════ */
static JNIEnv *attachJvm(void) {
    JNIEnv *env = NULL;
    if (g_vm && (*g_vm)->AttachCurrentThread(g_vm, &env, NULL) == JNI_OK) return env;
    return NULL;
}

static void detachJvm(void) {
    if (g_vm) (*g_vm)->DetachCurrentThread(g_vm);
}

/* ═══════════════ مقبس يونكس ═══════════════ */
static int connectUnix(const char *path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (connect(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int readFull(int fd, void *buf, size_t n) {
    size_t got = 0;
    uint8_t *p = (uint8_t *) buf;
    while (got < n) {
        ssize_t r = read(fd, p + got, n - got);
        if (r <= 0) return -1;
        got += (size_t) r;
    }
    return 0;
}

/* استهلاك بايتات من التدفق دون تخزينها (للحمولات المهملة) */
static int skipFull(int fd, size_t n) {
    uint8_t tmp[8192];
    while (n > 0) {
        size_t want = n < sizeof(tmp) ? n : sizeof(tmp);
        if (readFull(fd, tmp, want) < 0) return -1;
        n -= want;
    }
    return 0;
}

static int writeFull(int fd, const void *buf, size_t n) {
    size_t sent = 0;
    const uint8_t *p = (const uint8_t *) buf;
    while (sent < n) {
        ssize_t w = write(fd, p + sent, n - sent);
        if (w <= 0) return -1;
        sent += (size_t) w;
    }
    return 0;
}

/* إغلاق آمن: shutdown يفك حجب read() في الخيوط الأخرى ثم close */
static void closeSocket(int *fd) {
    int v = *fd;
    *fd = -1;
    if (v >= 0) {
        shutdown(v, SHUT_RDWR);
        close(v);
    }
}

static int sendInput(uint32_t type, int32_t a, int32_t b, uint32_t keysym, uint32_t down) {
    int sock;
    InputPacket pkt;
    pkt.magic = MAGIC_INPUT;
    pkt.type = type;
    pkt.a = a;
    pkt.b = b;
    pkt.keysym = keysym;
    pkt.down = down;
    pthread_mutex_lock(&g_writeMutex);
    sock = g_sock;
    if (sock >= 0) {
        /* writeFull تحت نفس القفل يضمن عدم تداخل الإرسال مع الإغلاق */
        writeFull(sock, &pkt, sizeof(pkt));
    }
    pthread_mutex_unlock(&g_writeMutex);
    return sock >= 0 ? 0 : -1;
}

/* ═══════════════ تحويل BGRA→RGBA مع letterbox وعرض الإطار ═══════════════ */
static void blitFrame(const uint8_t *src, int w, int h) {
    ANativeWindow_Buffer buf;
    if (w <= 0 || h <= 0) return;
    if (ANativeWindow_lock(g_window, &buf, NULL) != 0) return;
    if (buf.width <= 0 || buf.height <= 0 || buf.stride <= 0) {
        ANativeWindow_unlockAndPost(g_window);
        return;
    }

    int dstW = buf.width;
    int dstH = buf.height;

    /* حساب مستطيل الملاءمة (aspect-fit) — أبعاد الضيف متمركزة */
    int rectW = dstW, rectH = dstH;
    int offX = 0, offY = 0;
    if (w > 0 && h > 0 && dstW > 0 && dstH > 0) {
        /* قارن النسب دون أرقام عشرية */
        long long lhs = (long long) dstW * h;
        long long rhs = (long long) dstH * w;
        if (lhs > rhs) {            /* الشاشة أعرض — احصر بالارتفاع */
            rectH = dstH;
            rectW = (int) (((long long) dstH * w) / h);
            offX = (dstW - rectW) / 2;
        } else {                    /* الشاشة أطول — احصر بالعرض */
            rectW = dstW;
            rectH = (int) (((long long) dstW * h) / w);
            offY = (dstH - rectH) / 2;
        }
    }

    for (int y = 0; y < dstH; y++) {
        uint8_t *drow = (uint8_t *) buf.bits + (size_t) y * buf.stride * 4;
        int insideY = (y >= offY && y < offY + rectH);
        int sy = insideY ? (int) (((long long) (y - offY) * h) / rectH) : -1;
        const uint8_t *srow = (insideY && sy >= 0 && sy < h) ? src + (size_t) sy * w * 4 : NULL;
        for (int x = 0; x < dstW; x++) {
            if (srow && x >= offX && x < offX + rectW) {
                int sx = (int) (((long long) (x - offX) * w) / rectW);
                if (sx >= w) sx = w - 1;
                const uint8_t *s = srow + (size_t) sx * 4;
                /* BGRA → RGBA */
                drow[x * 4 + 0] = s[2];
                drow[x * 4 + 1] = s[1];
                drow[x * 4 + 2] = s[0];
                drow[x * 4 + 3] = s[3];
            } else {
                /* حواف letterbox سوداء */
                drow[x * 4 + 0] = 0;
                drow[x * 4 + 1] = 0;
                drow[x * 4 + 2] = 0;
                drow[x * 4 + 3] = 0xFF;
            }
        }
    }
    ANativeWindow_unlockAndPost(g_window);
}

/* ═══════════════ خيط الاستقبال ═══════════════ */
static void *rxLoop(void *arg) {
    (void) arg;
    JNIEnv *env = attachJvm();
    if (env == NULL) return NULL;

    /* اتصال مع إعادة محاولة (خادم العرض قد يتأخر في الإقلاع) */
    for (int i = 0; i < 40 && g_running; i++) {
        int fd = connectUnix(g_socketPath);
        if (fd >= 0) {
            pthread_mutex_lock(&g_writeMutex);
            g_sock = fd;
            pthread_mutex_unlock(&g_writeMutex);
            break;
        }
        struct timespec ts = {0, 500 * 1000 * 1000};
        nanosleep(&ts, NULL);
    }
    pthread_mutex_lock(&g_writeMutex);
    int sock = g_sock;
    pthread_mutex_unlock(&g_writeMutex);
    if (sock < 0) {
        LOGE("تعذر الاتصال بخادم العرض: %s", g_socketPath);
        detachJvm();
        return NULL;
    }
    LOGI("متصل بخادم العرض");

    int curW = 0, curH = 0;
    long frames = 0, skipped = 0;
    struct timespec fpsStart, now, lastBlit;
    clock_gettime(CLOCK_MONOTONIC, &fpsStart);
    lastBlit = fpsStart;
    long minFrameNs = g_maxFps > 0 ? (1000000000L / g_maxFps) : 0;

    PacketHeader ph;
    while (g_running) {
        pthread_mutex_lock(&g_writeMutex);
        sock = g_sock;
        pthread_mutex_unlock(&g_writeMutex);
        if (sock < 0) break;
        if (readFull(sock, &ph, sizeof(ph)) < 0) break;
        if (ph.payloadSize > MAX_PAYLOAD) break; /* حزمة تالفة — اقطع */

        if (ph.magic == MAGIC_CURSOR) {
            /* الحمولة: x,y,hotx,hoty (i32) + w,h (u32) + بكسلات ARGB u32 */
            if (ph.payloadSize < 24) { if (skipFull(sock, ph.payloadSize) < 0) break; continue; }
            int32_t info[4];
            uint32_t dims[2];
            if (readFull(sock, info, 16) < 0) break;
            if (readFull(sock, dims, 8) < 0) break;
            size_t nPix = (ph.payloadSize - 24) / 4;
            uint32_t *px = (uint32_t *) malloc(nPix * 4);
            if (!px) { if (skipFull(sock, nPix * 4) < 0) break; continue; }
            if (readFull(sock, px, nPix * 4) < 0) { free(px); break; }
            if (g_thiz && g_mCursor) {
                jintArray arr = (*env)->NewIntArray(env, (jsize) nPix);
                if (arr) {
                    (*env)->SetIntArrayRegion(env, arr, 0, (jsize) nPix, (const jint *) px);
                    (*env)->CallVoidMethod(env, g_thiz, g_mCursor,
                                           (jint) info[0], (jint) info[1],
                                           (jint) info[2], (jint) info[3],
                                           (jint) dims[0], (jint) dims[1], arr);
                    (*env)->DeleteLocalRef(env, arr);
                }
            }
            free(px);
            continue;
        }

        if (ph.magic != MAGIC_FRAME) {
            /* حزمة غير معروفة — استهلك حمولتها للحفاظ على التزامن */
            if (skipFull(sock, ph.payloadSize) < 0) break;
            continue;
        }

        /* الحمولة: w,h,idx ثم بيانات BGRA */
        if (ph.payloadSize < 12) { if (skipFull(sock, ph.payloadSize) < 0) break; continue; }
        uint32_t meta[3];
        if (readFull(sock, meta, 12) < 0) break;
        size_t dataLen = (size_t) ph.payloadSize - 12;
        if (dataLen > g_frameBufSize) {
            uint8_t *nb = (uint8_t *) realloc(g_frameBuf, dataLen);
            if (!nb) { if (skipFull(sock, dataLen) < 0) break; continue; }
            g_frameBuf = nb;
            g_frameBufSize = dataLen;
        }
        if (readFull(sock, g_frameBuf, dataLen) < 0) break;

        int w = (int) meta[0], h = (int) meta[1];
        if (w <= 0 || h <= 0) continue;
        /* تحقق أن البيانات تكمل الأبعاد المعلنة (w*h*4) */
        if ((size_t) w * h * 4 > dataLen) continue;

        if (w != curW || h != curH) {
            curW = w; curH = h;
            g_lastW = w; g_lastH = h;
            LOGI("دقة الجلسة: %dx%d", curW, curH);
        }

        /* تقييد الإطارات: اقرأ دائماً لكن تجاهل العرض المبكر */
        frames++;
        clock_gettime(CLOCK_MONOTONIC, &now);
        long sinceNs = (now.tv_sec - lastBlit.tv_sec) * 1000000000L +
                       (now.tv_nsec - lastBlit.tv_nsec);
        if (minFrameNs > 0 && sinceNs < minFrameNs) {
            skipped++;
            continue;
        }
        lastBlit = now;
        if (g_window) blitFrame(g_frameBuf, curW, curH);

        /* عداد الإطارات كل ثانية */
        double el = (now.tv_sec - fpsStart.tv_sec) +
                    (now.tv_nsec - fpsStart.tv_nsec) / 1e9;
        if (el >= 1.0) {
            if (g_thiz && g_mFps) {
                (*env)->CallVoidMethod(env, g_thiz, g_mFps, (jint) (frames / el));
            }
            frames = 0;
            skipped = 0;
            fpsStart = now;
        }
    }

    closeSocket(&g_sock);
    detachJvm();
    LOGI("انتهى خيط الاستقبال");
    return NULL;
}

/* ═══════════════ واجهة JNI ═══════════════ */
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_qalabox_emu_EmulatorActivity_nativeAttach(JNIEnv *env, jobject thiz,
                                                   jobject surface, jstring socketPath,
                                                   jint maxFps) {
    if (g_running) return JNI_TRUE; /* مرتبط مسبقاً */
    const char *path = (*env)->GetStringUTFChars(env, socketPath, NULL);
    if (!path) return JNI_FALSE;
    strncpy(g_socketPath, path, sizeof(g_socketPath) - 1);
    g_socketPath[sizeof(g_socketPath) - 1] = '\0';
    (*env)->ReleaseStringUTFChars(env, socketPath, path);

    g_window = ANativeWindow_fromSurface(env, surface);
    if (!g_window) return JNI_FALSE;

    g_maxFps = maxFps > 0 ? maxFps : 60;
    g_thiz = (*env)->NewGlobalRef(env, thiz);
    jclass cls = (*env)->GetObjectClass(env, thiz);
    g_mFps = (*env)->GetMethodID(env, cls, "onFps", "(I)V");
    g_mCursor = (*env)->GetMethodID(env, cls, "onCursorData", "(IIIIII[I)V");
    (*env)->DeleteLocalRef(env, cls);

    g_running = 1;
    if (pthread_create(&g_rxThread, NULL, rxLoop, NULL) != 0) {
        g_running = 0;
        ANativeWindow_release(g_window);
        g_window = NULL;
        if (g_thiz) {
            (*env)->DeleteGlobalRef(env, g_thiz);
            g_thiz = NULL;
        }
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_qalabox_emu_EmulatorActivity_nativeDetach(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    if (!g_running && g_sock < 0 && !g_window) return; /* لا شيء للتحرير */
    g_running = 0;
    /* shutdown + close يفكان حجب read() في خيط الاستقبال فوراً */
    closeSocket(&g_sock);
    pthread_join(g_rxThread, NULL);
    if (g_window) {
        ANativeWindow_release(g_window);
        g_window = NULL;
    }
    if (g_thiz) {
        JNIEnv *e = NULL;
        if (g_vm && (*g_vm)->GetEnv(g_vm, (void **) &e, JNI_VERSION_1_6) == JNI_OK && e) {
            (*e)->DeleteGlobalRef(e, g_thiz);
        }
        g_thiz = NULL;
    }
    free(g_frameBuf);
    g_frameBuf = NULL;
    g_frameBufSize = 0;
    g_lastW = 0;
    g_lastH = 0;
}

JNIEXPORT void JNICALL
Java_com_qalabox_emu_EmulatorActivity_nativeMoveRelative(JNIEnv *env, jobject thiz,
                                                         jint dx, jint dy) {
    (void) env; (void) thiz;
    sendInput(IN_MOVE_REL, dx, dy, 0, 0);
}

JNIEXPORT void JNICALL
Java_com_qalabox_emu_EmulatorActivity_nativeMoveAbsolute(JNIEnv *env, jobject thiz,
                                                         jint x, jint y) {
    (void) env; (void) thiz;
    sendInput(IN_MOVE_ABS, x, y, 0, 0);
}

JNIEXPORT void JNICALL
Java_com_qalabox_emu_EmulatorActivity_nativeButton(JNIEnv *env, jobject thiz,
                                                   jint button, jboolean down) {
    (void) env; (void) thiz;
    sendInput(IN_BUTTON, button, down ? 1 : 0, 0, 0);
}

JNIEXPORT void JNICALL
Java_com_qalabox_emu_EmulatorActivity_nativeScroll(JNIEnv *env, jobject thiz,
                                                   jint dx, jint dy) {
    (void) env; (void) thiz;
    sendInput(IN_SCROLL, dx, dy, 0, 0);
}

JNIEXPORT void JNICALL
Java_com_qalabox_emu_EmulatorActivity_nativeKey(JNIEnv *env, jobject thiz,
                                                jlong keysym, jboolean down) {
    (void) env; (void) thiz;
    sendInput(IN_KEY, 0, 0, (uint32_t) keysym, down ? 1 : 0);
}

JNIEXPORT jint JNICALL
Java_com_qalabox_emu_EmulatorActivity_nativeScreenWidth(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return g_lastW;
}

JNIEXPORT jint JNICALL
Java_com_qalabox_emu_EmulatorActivity_nativeScreenHeight(JNIEnv *env, jobject thiz) {
    (void) env; (void) thiz;
    return g_lastH;
}
