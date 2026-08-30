/*
 * qalarender.c — خادم عرض قلعة بوكس (يعمل داخل نظام الجذر glibc)
 * ------------------------------------------------------------------
 * عميل X11 يتصل بالعرض :0 ويعمل كجسر بين Wine والهاتف:
 *   → يلتقط إطار الجذر (XGetImage) ويرسله للتطبيق عبر مقبس يونكس
 *   → يرسل صورة مؤشر الماوس (XFixes) لعرضها بلا تأخير
 *   → يستقبل حزم الإدخال (حركة/أزرار/تمرير/مفاتيح) وينفذها عبر XTest
 *
 * البناء (داخل الجذر أو عبر بادئة arm64 متبادلة):
 *   gcc -O2 -o qalarender qalarender.c -lX11 -lXfixes -lXtst
 *
 * التشغيل:
 *   qalarender --display :0 --socket /tmp/.xbridge.sock --fps 60
 *
 * v1.1 إصلاحات:
 *   - نسخ الإطار باحترام bytes_per_line (حماية من حشو صفوف X11)
 *   - كل قراءة/كتابة لـ g_client تحت قفل (منع تسابق البيانات)
 *   - حلقة القبول تتنفس عند الخطأ بدل الدوران الحارق للمعالج
 *   - معالجة SIGTERM/SIGINT لخروج نظيف
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <pthread.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <X11/Xatom.h>
#include <X11/extensions/Xfixes.h>
#include <X11/extensions/XTest.h>

#define MAGIC_FRAME  0x51423146u /* QB1F */
#define MAGIC_CURSOR 0x51424355u /* QBCU */
#define MAGIC_INPUT  0x5142494Eu /* QBIN */

#define IN_MOVE_REL 1
#define IN_MOVE_ABS 2
#define IN_BUTTON   3
#define IN_SCROLL   4
#define IN_KEY      5

typedef struct {
    uint32_t magic, payloadSize;
} PacketHeader;

typedef struct {
    uint32_t magic, type;
    int32_t a, b;
    uint32_t keysym, down;
} InputPacket;

static Display *g_dpy = NULL;
static Window g_root = 0;
static int g_scrW = 800, g_scrH = 600;
static int g_client = -1;
static int g_listen = -1;
static volatile sig_atomic_t g_running = 1;
static int g_fps = 60;
static pthread_mutex_t g_clientMutex = PTHREAD_MUTEX_INITIALIZER;

/* قراءة واصف العميل الحالي بأمان */
static int getClientFd(void) {
    pthread_mutex_lock(&g_clientMutex);
    int fd = g_client;
    pthread_mutex_unlock(&g_clientMutex);
    return fd;
}

/* إغلاق العميل الحالي وتصفيره بأمان — يعيد 1 إذا كان هناك عميل */
static int closeClientLocked(void) {
    if (g_client >= 0) {
        close(g_client);
        g_client = -1;
        return 1;
    }
    return 0;
}

static int writeFull(int fd, const void *buf, size_t n) {
    size_t sent = 0;
    const uint8_t *p = (const uint8_t *) buf;
    while (sent < n) {
        ssize_t w = write(fd, p + sent, n - sent);
        if (w <= 0) return -1;
        sent += w;
    }
    return 0;
}

static int readFull(int fd, void *buf, size_t n) {
    size_t got = 0;
    uint8_t *p = (uint8_t *) buf;
    while (got < n) {
        ssize_t r = read(fd, p + got, n - got);
        if (r <= 0) return -1;
        got += r;
    }
    return 0;
}

static void onSignal(int sig) {
    (void) sig;
    g_running = 0;
}

static void queryRootSize(void) {
    XWindowAttributes attr;
    if (XGetWindowAttributes(g_dpy, g_root, &attr)) {
        if (attr.width > 0 && attr.height > 0) {
            g_scrW = attr.width;
            g_scrH = attr.height;
        }
    }
}

static void sendFrame(uint32_t idx) {
    if (getClientFd() < 0) return;
    XImage *img = XGetImage(g_dpy, g_root, 0, 0, g_scrW, g_scrH, AllPlanes, ZPixmap);
    if (!img) return;
    if (img->width != g_scrW || img->height != g_scrH || !img->data) {
        XDestroyImage(img);
        return;
    }

    size_t rowBytes = (size_t) g_scrW * 4;
    size_t dataLen = rowBytes * (size_t) g_scrH;
    PacketHeader hdr;
    hdr.magic = MAGIC_FRAME;
    hdr.payloadSize = (uint32_t) (12 + dataLen);

    uint8_t *packet = (uint8_t *) malloc(sizeof(hdr) + hdr.payloadSize);
    if (!packet) { XDestroyImage(img); return; }

    uint32_t meta[3] = { (uint32_t) g_scrW, (uint32_t) g_scrH, idx };
    memcpy(packet, &hdr, sizeof(hdr));
    memcpy(packet + sizeof(hdr), meta, 12);

    /* نسخ صف-بصف باحترام bytes_per_line (قد يحشو X11 الصفوف للمحاذاة) */
    int bppOk = (img->bits_per_pixel == 32 || img->bits_per_pixel == 24);
    if (bppOk && (size_t) img->bytes_per_line >= rowBytes) {
        for (int y = 0; y < g_scrH; y++) {
            memcpy(packet + sizeof(hdr) + 12 + (size_t) y * rowBytes,
                   img->data + (size_t) y * img->bytes_per_line, rowBytes);
        }
    } else {
        /* احتياط نادر: تنسيق غير متوقع — أرسل سطوراً كما هي (سيظهر تحريف محتمل) */
        memcpy(packet + sizeof(hdr) + 12, img->data,
               dataLen < (size_t) img->bytes_per_line * g_scrH
                   ? dataLen : (size_t) img->bytes_per_line * g_scrH);
    }
    XDestroyImage(img);

    pthread_mutex_lock(&g_clientMutex);
    if (g_client >= 0 && writeFull(g_client, packet, sizeof(hdr) + hdr.payloadSize) < 0) {
        closeClientLocked();
    }
    pthread_mutex_unlock(&g_clientMutex);
    free(packet);
}

static void sendCursor(void) {
    if (getClientFd() < 0) return;
    XFixesCursorImage *ci = XFixesGetCursorImage(g_dpy);
    if (!ci) return;
    if (ci->width == 0 || ci->height == 0 || !ci->pixels) { XFree(ci); return; }

    size_t nPix = (size_t) ci->width * ci->height;
    size_t payload = 24 + nPix * 4;
    PacketHeader hdr;
    hdr.magic = MAGIC_CURSOR;
    hdr.payloadSize = (uint32_t) payload;

    uint8_t *packet = (uint8_t *) malloc(sizeof(hdr) + payload);
    if (!packet) { XFree(ci); return; }

    int32_t info[4] = { ci->x, ci->y, (int32_t) ci->xhot, (int32_t) ci->yhot };
    uint32_t dims[2] = { ci->width, ci->height };
    uint8_t *pix = packet + sizeof(hdr) + 24;
    for (size_t i = 0; i < nPix; i++) {
        /* XFixes يعيد ARGB داخل unsigned long (8 بايت على معمارية 64-bit) */
        uint32_t argb = (uint32_t) (ci->pixels[i] & 0xFFFFFFFFu);
        memcpy(pix + i * 4, &argb, 4);
    }

    memcpy(packet, &hdr, sizeof(hdr));
    memcpy(packet + sizeof(hdr), info, 16);
    memcpy(packet + sizeof(hdr) + 16, dims, 8);

    pthread_mutex_lock(&g_clientMutex);
    if (g_client >= 0 && writeFull(g_client, packet, sizeof(hdr) + payload) < 0) {
        closeClientLocked();
    }
    pthread_mutex_unlock(&g_clientMutex);
    free(packet);
    XFree(ci);
}

static void *captureLoop(void *arg) {
    (void) arg;
    uint32_t idx = 0;
    long usec = 1000000 / (g_fps > 0 ? g_fps : 60);
    while (g_running) {
        if (getClientFd() < 0) { usleep(100000); continue; }
        /* إعادة فحص الدقة دورياً (لتتبع تغيير الدقة الافتراضية في Wine) */
        if (idx % 30 == 0) queryRootSize();
        sendFrame(idx);
        if (idx % 5 == 0) sendCursor();
        idx++;
        usleep(usec);
    }
    return NULL;
}

static void clampToScreen(int *x, int *y) {
    if (*x < 0) *x = 0;
    if (*y < 0) *y = 0;
    if (*x > g_scrW - 1) *x = g_scrW - 1;
    if (*y > g_scrH - 1) *y = g_scrH - 1;
}

static void handleInput(const InputPacket *p) {
    if (p->magic != MAGIC_INPUT) return;
    switch (p->type) {
        case IN_MOVE_REL: {
            Window rw, cw;
            int rx, ry, wx, wy;
            unsigned int mask;
            if (XQueryPointer(g_dpy, g_root, &rw, &cw, &rx, &ry, &wx, &wy, &mask)) {
                int nx = rx + p->a, ny = ry + p->b;
                clampToScreen(&nx, &ny);
                XTestFakeMotionEvent(g_dpy, -1, nx, ny, CurrentTime);
            }
            break;
        }
        case IN_MOVE_ABS: {
            int nx = p->a, ny = p->b;
            clampToScreen(&nx, &ny);
            XTestFakeMotionEvent(g_dpy, -1, nx, ny, CurrentTime);
            break;
        }
        case IN_BUTTON:
            if (p->a >= 1 && p->a <= 7)
                XTestFakeButtonEvent(g_dpy, (unsigned int) p->a, p->b ? True : False, CurrentTime);
            break;
        case IN_SCROLL: {
            /* عجلة عمودية: زر 4 (أعلى) / 5 (أسفل)، أفقية: 6/7 */
            int btn;
            if (p->a < 0) btn = 6;
            else if (p->a > 0) btn = 7;
            else if (p->b < 0) btn = 4;
            else btn = 5;
            XTestFakeButtonEvent(g_dpy, (unsigned int) btn, True, CurrentTime);
            XTestFakeButtonEvent(g_dpy, (unsigned int) btn, False, CurrentTime);
            break;
        }
        case IN_KEY: {
            KeyCode kc = XKeysymToKeycode(g_dpy, (KeySym) p->keysym);
            if (kc) XTestFakeKeyEvent(g_dpy, kc, p->down ? True : False, CurrentTime);
            break;
        }
        default:
            break;
    }
    XFlush(g_dpy);
}

static void *inputLoop(void *arg) {
    (void) arg;
    while (g_running) {
        int fd = getClientFd();
        if (fd < 0) { usleep(50000); continue; }
        InputPacket pkt;
        if (readFull(fd, &pkt, sizeof(pkt)) < 0) {
            /* الواصف قد يكون أُغلق من خيط آخر — أغلق فقط إن كان نفس الواصف */
            pthread_mutex_lock(&g_clientMutex);
            if (g_client == fd) closeClientLocked();
            pthread_mutex_unlock(&g_clientMutex);
            continue;
        }
        if (fd == getClientFd()) {
            handleInput(&pkt);
        }
    }
    return NULL;
}

static int createListenSocket(const char *path) {
    unlink(path);
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
    if (bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) { close(fd); return -1; }
    chmod(path, 0777); /* إتاحة الوصول للتطبيق عبر ربط المجلد */
    if (listen(fd, 2) < 0) { close(fd); return -1; }
    return fd;
}

int main(int argc, char **argv) {
    const char *display = ":0";
    const char *socketPath = "/tmp/.xbridge.sock";
    g_fps = 60;

    for (int i = 1; i < argc - 1; i++) {
        if (!strcmp(argv[i], "--display")) display = argv[i + 1];
        else if (!strcmp(argv[i], "--socket")) socketPath = argv[i + 1];
        else if (!strcmp(argv[i], "--fps")) g_fps = atoi(argv[i + 1]);
    }
    signal(SIGPIPE, SIG_IGN);
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = onSignal;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);

    XInitThreads();
    g_dpy = XOpenDisplay(display);
    if (!g_dpy) {
        fprintf(stderr, "[qalarender] فشل فتح العرض %s\n", display);
        return 1;
    }
    g_root = DefaultRootWindow(g_dpy);
    queryRootSize();
    fprintf(stdout, "[qalarender] العرض %s بدقة %dx%d\n", display, g_scrW, g_scrH);
    fflush(stdout);

    g_listen = createListenSocket(socketPath);
    if (g_listen < 0) {
        fprintf(stderr, "[qalarender] فشل إنشاء المقبس %s\n", socketPath);
        return 2;
    }

    pthread_t capT, inpT;
    pthread_create(&capT, NULL, captureLoop, NULL);
    pthread_create(&inpT, NULL, inputLoop, NULL);

    /* حلقة القبول: عميل واحد في كل مرة (إعادة اتصال تلقائية) */
    while (g_running) {
        int c = accept(g_listen, NULL, NULL);
        if (c < 0) {
            if (!g_running) break;
            usleep(20000); /* منع الدوران الحارق عند أخطاء متتالية */
            continue;
        }
        pthread_mutex_lock(&g_clientMutex);
        closeClientLocked(); /* عميل واحد فقط — استبدل القديم */
        g_client = c;
        pthread_mutex_unlock(&g_clientMutex);
        fprintf(stdout, "[qalarender] عميل متصل\n");
        fflush(stdout);
    }

    pthread_mutex_lock(&g_clientMutex);
    closeClientLocked();
    pthread_mutex_unlock(&g_clientMutex);
    close(g_listen);
    XCloseDisplay(g_dpy);
    return 0;
}
