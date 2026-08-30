#!/usr/bin/env bash
# ============================================================================
# بناء حزمة وقت التشغيل qalabox-runtime.qbxruntime — آلياً بالكامل
# بدون qemu وبدون chroot: جذر Debian من حل اعتماديات .deb مباشرة
# (راجع resolve_debs.py) + Wine (Kron4ek) + Box86/Box64 (بناء cross) + proot ثابت
#
# الاستخدام:
#   bash build_runtime_package.sh [OUT_DIR]        # البناء الكامل (على CI)
#   DRY=1 bash build_runtime_package.sh [OUT_DIR]  # الجذر فقط — تحقق سريع
#
# متغيرات اختيارية: SUITE WINE_TAG BOX64_TAG BOX86_TAG GITHUB_TOKEN DEB_MIRROR
# ============================================================================
set -euo pipefail

OUT_DIR="${1:-/tmp/qbx-out}"
SUITE="${SUITE:-bookworm}"
DRY="${DRY:-0}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d /tmp/qbx-build.XXXXXX)"
ROOT="$WORK/rootfs"
CACHE="$WORK/debs"
PKG="$WORK/pkg"
mkdir -p "$OUT_DIR" "$ROOT" "$CACHE" "$PKG"

GHAPI="https://api.github.com"
GH_CURL=(curl -sSL --max-time 60 --retry 3)
[ -n "${GITHUB_TOKEN:-}" ] && GH_CURL+=(-H "Authorization: Bearer $GITHUB_TOKEN")

log() { echo -e "\n\033[1;36m==== $* ====\033[0m"; }
die() { echo -e "\033[1;31m❌ $*\033[0m" >&2; exit 1; }

gh_latest() {  # gh_latest owner/repo → tag
  "${GH_CURL[@]}" "$GHAPI/repos/$1/releases/latest" |
    python3 -c 'import json,sys;print(json.load(sys.stdin).get("tag_name") or "")' 2>/dev/null || true
}
gh_asset_url() {  # gh_asset_url owner/repo substring → URL أو فارغ
  "${GH_CURL[@]}" "$GHAPI/repos/$1/releases/latest" | python3 -c '
import json, sys
d = json.load(sys.stdin)
pat = sys.argv[1]
for a in d.get("assets", []):
    if pat in a["name"]:
        print(a["browser_download_url"]); break' "$2" 2>/dev/null || true
}
dl() {  # dl URL DEST
  log "تحميل: $(basename "$2")"
  curl -L --fail --retry 3 --retry-delay 3 --max-time 1800 -o "$2" "$1"
}

echo "مجلد العمل: $WORK  |  OUT: $OUT_DIR  |  DRY=$DRY  |  SUITE=$SUITE"

# ────────────────────────────── 0) الأدوات ──────────────────────────────
if [ "$DRY" != 1 ]; then
  MISSING=()
  command -v cmake >/dev/null || MISSING+=(cmake)
  command -v aarch64-linux-gnu-g++ >/dev/null || MISSING+=(g++-aarch64-linux-gnu)
  command -v arm-linux-gnueabihf-g++ >/dev/null || MISSING+=(g++-arm-linux-gnueabihf)
  command -v unzip >/dev/null || MISSING+=(unzip zip)
  if [ ${#MISSING[@]} -gt 0 ]; then
    if sudo -n true 2>/dev/null; then
      log "تثبيت أدوات البناء: ${MISSING[*]}"
      sudo apt-get update -qq
      sudo apt-get install -y -qq --no-install-recommends "${MISSING[@]}"
    else
      die "أدوات غائبة (${MISSING[*]}) ولا يوجد sudo — ثبّتها يدوياً"
    fi
  fi
fi

# ─────────────────────── 1) الجذر arm64 (الأصلي) ───────────────────────
log "1/8 جذر Debian arm64 (native: Xvfb/PulseAudio/X11/Mesa/خطوط)"
python3 "$HERE/resolve_debs.py" "$SUITE" arm64 "$ROOT" "$CACHE" \
  bash dash coreutils sed grep findutils tar gzip xz-utils util-linux diffutils \
  ca-certificates \
  libx11-6 libxext6 libxfixes3 libxtst6 libxxf86vm1 libxrender1 libxrandr2 \
  libxi6 libxcursor1 libxinerama1 libxcomposite1 \
  xvfb x11-utils xauth xkb-data \
  libgl1 libglx0 libglvnd0 libglx-mesa0 libgl1-mesa-dri \
  pulseaudio pulseaudio-utils libasound2 libpulse0 \
  fonts-dejavu-core fonts-liberation libfontconfig1 libfreetype6 \
  libc6-dev libx11-dev libxext-dev libxfixes-dev libxtst-dev libgnutls30

# ─────────────── 2) مكتبات الضيف amd64 (Wine64 عبر Box64) ───────────────
log "2/8 مكتبات الضيف amd64"
python3 "$HERE/resolve_debs.py" "$SUITE" amd64 "$ROOT" "$CACHE" \
  libc6 libgcc-s1 libstdc++6 \
  libx11-6 libxext6 libxrender1 libxrandr2 libxi6 libxcursor1 libxinerama1 \
  libxcomposite1 libxfixes3 libxxf86vm1 libxcb1 \
  libgnutls30 libasound2 libpulse0 libudev1 libdrm2 \
  libgl1 libglx0 libglvnd0 libfontconfig1 libfreetype6

# ─────────────── 3) مكتبات الضيف i386 (Wine32 عبر Box86) ────────────────
log "3/8 مكتبات الضيف i386"
python3 "$HERE/resolve_debs.py" "$SUITE" i386 "$ROOT" "$CACHE" \
  libc6 libgcc-s1 libstdc++6 \
  libx11-6 libxext6 libxrender1 libxrandr2 libxi6 libxcursor1 libxinerama1 \
  libxcomposite1 libxfixes3 libxxf86vm1 libxcb1 \
  libgnutls30 libasound2 libpulse0 libudev1 libdrm2 \
  libgl1 libglx0 libglvnd0 libfontconfig1 libfreetype6

# ───────────── 4) armhf (تشغيل Box86 نفسه + sysroot بنائه) ──────────────
log "4/8 مكتبات armhf (لـ Box86)"
python3 "$HERE/resolve_debs.py" "$SUITE" armhf "$ROOT" "$CACHE" \
  libc6 libgcc-s1 libstdc++6 libc6-dev

# ─────────────────────────── 5) ضبط /etc ────────────────────────────────
log "5/8 ضبط /etc والمجلدات الأساسية"
mkdir -p "$ROOT"/{root,tmp,qalabox,dev,proc,sys,run,home,media,mnt,opt,srv} \
         "$ROOT"/etc/apt/apt.conf.d "$ROOT"/etc/apt/trusted.gpg.d "$ROOT"/etc/apt/sources.list.d \
         "$ROOT"/etc/ssl/certs "$ROOT"/var/lib/dpkg/updates "$ROOT"/var/log "$ROOT"/var/tmp
chmod 1777 "$ROOT/tmp" "$ROOT/var/tmp"
chmod 700 "$ROOT/root"
[ -e "$ROOT/var/lib/dpkg/status" ] || touch "$ROOT/var/lib/dpkg/status"

cat > "$ROOT/etc/passwd" <<'EOF'
root:x:0:0:root:/root:/bin/bash
android:x:1000:1000:Android User:/root:/bin/bash
EOF
cat > "$ROOT/etc/group" <<'EOF'
root:x:0:
android:x:1000:
EOF
cat > "$ROOT/etc/nsswitch.conf" <<'EOF'
passwd: files
group: files
shadow: files
hosts: files dns
networks: files
protocols: files
services: files
EOF
cat > "$ROOT/etc/hosts" <<'EOF'
127.0.0.1   localhost
::1         localhost ip6-localhost ip6-loopback
EOF
cat > "$ROOT/etc/resolv.conf" <<'EOF'
nameserver 1.1.1.1
nameserver 8.8.8.8
EOF
cat > "$ROOT/etc/environment" <<'EOF'
LANG=C.UTF-8
EOF
cat > "$ROOT/etc/apt/sources.list" <<EOF
deb http://deb.debian.org/debian $SUITE main
EOF
python3 -c 'import secrets; open("'"$ROOT"'/etc/machine-id","w").write(secrets.token_hex(16)+"\n")'
cat "$ROOT"/usr/share/ca-certificates/mozilla/*.crt > "$ROOT/etc/ssl/certs/ca-certificates.crt" 2>/dev/null \
  || log "⚠️ لم أجد شهادات mozilla — راجع حزمة ca-certificates"

# روابط usrmerge الدفاعية (/bin /sbin /lib → usr/*) إن لم تنشأ من base-files
for L in bin:usr/bin sbin:usr/sbin lib:usr/lib; do
  [ -e "$ROOT/${L%%:*}" ] || ln -s "${L##*:}" "$ROOT/${L%%:*}"
done
# محمّلات المعماريات — حرجة: box64 يحمّل ld-linux-x86-64، box86 نفسه armhf
# وwine32 يحتاج i386 — بأهداف مطلقة (تعمل داخل proot وفي usrmerge معاً)
ensure_loader() {  # ensure_loader <مسار الرابط داخل الجذر> <الهدف المطلق>
  local link="$1" target="$2"
  if [ -e "$ROOT/$target" ]; then
    mkdir -p "$(dirname "$ROOT/$link")"
    [ -e "$ROOT/$link" ] || ln -s "/$target" "$ROOT/$link"
  else
    log "⚠️ محمّل غائب: $target (تحقق من حزم libc الضيفة)"
  fi
}
ensure_loader lib64/ld-linux-x86-64.so.2 usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2
ensure_loader lib/ld-linux.so.2          usr/lib/i386-linux-gnu/ld-linux.so.2
ensure_loader lib/ld-linux-armhf.so.3    usr/lib/arm-linux-gnueabihf/ld-linux-armhf.so.3
[ -e "$ROOT/bin/bash" ] || [ -e "$ROOT/usr/bin/bash" ] || die "bash غير موجود في الجذر!"
[ -e "$ROOT/bin/sh" ] || ln -s /bin/dash "$ROOT/bin/sh" 2>/dev/null || true
[ -e "$ROOT/bin/sh" ] || die "/bin/sh غير موجودة!"

# ──────────────────────────── 6) Wine ───────────────────────────────────
WINE_VERSION="—"
if [ "$DRY" != 1 ]; then
  log "6/8 Wine (Kron4ek Wine-Builds)"
  WINE_TAG="${WINE_TAG:-$(gh_latest Kron4ek/Wine-Builds)}"
  [ -z "$WINE_TAG" ] && WINE_TAG="9.0"
  log "إصدار Wine: $WINE_TAG"
  URL64="$(gh_asset_url Kron4ek/Wine-Builds "wine-${WINE_TAG}-amd64.tar.xz")"
  URL32="$(gh_asset_url Kron4ek/Wine-Builds "wine-${WINE_TAG}-x86.tar.xz")"
  [ -z "$URL64" ] && die "لم أجد wine-${WINE_TAG}-amd64.tar.xz — جرّب WINE_TAG آخر"
  [ -z "$URL32" ] && die "لم أجد wine-${WINE_TAG}-x86.tar.xz — جرّب WINE_TAG آخر"
  dl "$URL64" "$WORK/wine64.tar.xz"
  dl "$URL32" "$WORK/wine32.tar.xz"
  mkdir -p "$WORK/w64" "$WORK/w32" "$ROOT/opt/wine64" "$ROOT/opt/wine32"
  tar -xJf "$WORK/wine64.tar.xz" -C "$WORK/w64" --strip-components=1
  tar -xJf "$WORK/wine32.tar.xz" -C "$WORK/w32" --strip-components=1
  cp -a "$WORK/w64/." "$ROOT/opt/wine64/"
  cp -a "$WORK/w32/." "$ROOT/opt/wine32/"
  WINE_VERSION="Wine $WINE_TAG"
  for f in "$ROOT/opt/wine64/bin/wine" "$ROOT/opt/wine64/bin/wineboot" \
           "$ROOT/opt/wine64/bin/wineserver" "$ROOT/opt/wine32/bin/wine" \
           "$ROOT/opt/wine32/bin/wineboot" "$ROOT/opt/wine32/bin/wineserver"; do
    [ -x "$f" ] || die "ثنائي Wine مفقود: $f"
  done

  # ─────────────────────── wrappers wine/wineboot/… ──────────────────────
  W="$ROOT/usr/local/bin"
  mkdir -p "$W"
  gen_wrapper() {  # gen_wrapper <name> <bin> <auto|fixed64>
    local name="$1" bin="$2" mode="$3"
    if [ "$mode" = fixed64 ]; then
      cat > "$W/$name" <<EOF
#!/bin/bash
# قلعة بوكس — مولَّد آلياً
exec /usr/local/bin/box64 /opt/wine64/bin/$bin "\$@"
EOF
    else
      cat > "$W/$name" <<EOF
#!/bin/bash
# قلعة بوكس — مولَّد آلياً
case "\${WINEARCH:-win32}" in
  win64) exec /usr/local/bin/box64 /opt/wine64/bin/$bin "\$@" ;;
  *)     exec /usr/local/bin/box86 /opt/wine32/bin/$bin "\$@" ;;
esac
EOF
    fi
    chmod 755 "$W/$name"
  }
  gen_wrapper wine wine auto
  gen_wrapper wineboot wineboot auto
  gen_wrapper wineserver wineserver auto
  gen_wrapper wine64 wine fixed64
  log "wrappers: wine (box86↔box64 حسب WINEARCH) + wine64"
fi

# ──────────────────────── 7) Box64 / Box86 ──────────────────────────────
if [ "$DRY" != 1 ]; then
  log "7/8 بناء Box64 + Box86 (cross-compile)"
  BOX64_TAG="${BOX64_TAG:-$(gh_latest ptitSeb/box64)}"
  [ -z "$BOX64_TAG" ] && BOX64_TAG="v0.4.4"
  log "Box64: $BOX64_TAG"
  git clone -q --depth 1 --branch "$BOX64_TAG" https://github.com/ptitSeb/box64 "$WORK/box64" \
    || git clone -q --depth 1 https://github.com/ptitSeb/box64 "$WORK/box64"
  cmake -S "$WORK/box64" -B "$WORK/box64/build" \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo -DARM_DYNAREC=ON \
    -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
    -DCMAKE_C_FLAGS="--sysroot=$ROOT -Wno-psabi" \
    -DCMAKE_EXE_LINKER_FLAGS="--sysroot=$ROOT" \
    -DCMAKE_INSTALL_PREFIX=/usr/local
  cmake --build "$WORK/box64/build" -j "$(nproc)"
  make -C "$WORK/box64/build" install DESTDIR="$ROOT" >/dev/null
  [ -x "$ROOT/usr/local/bin/box64" ] || die "box64 لم يُثبَّت"
  BOX64_VERSION="Box64 $BOX64_TAG"

  BOX86_TAG="${BOX86_TAG:-$(gh_latest ptitSeb/box86)}"
  [ -z "$BOX86_TAG" ] && BOX86_TAG="0.3.8"
  log "Box86: $BOX86_TAG"
  git clone -q --depth 1 --branch "$BOX86_TAG" https://github.com/ptitSeb/box86 "$WORK/box86" \
    || git clone -q --depth 1 --branch "v$BOX86_TAG" https://github.com/ptitSeb/box86 "$WORK/box86" \
    || git clone -q --depth 1 https://github.com/ptitSeb/box86 "$WORK/box86"
  cmake -S "$WORK/box86" -B "$WORK/box86/build" \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo -DARM_DYNAREC=ON \
    -DCMAKE_C_COMPILER=arm-linux-gnueabihf-gcc \
    -DCMAKE_C_FLAGS="--sysroot=$ROOT -Wno-psabi" \
    -DCMAKE_EXE_LINKER_FLAGS="--sysroot=$ROOT" \
    -DCMAKE_INSTALL_PREFIX=/usr/local
  cmake --build "$WORK/box86/build" -j "$(nproc)"
  make -C "$WORK/box86/build" install DESTDIR="$ROOT" >/dev/null
  [ -x "$ROOT/usr/local/bin/box86" ] || die "box86 لم يُثبَّت"
  BOX86_VERSION="Box86 $BOX86_TAG"

  # ───────────────────────── qalarender (جسر العرض) ──────────────────────
  log "بناء qalarender للجذر (cross)"
  aarch64-linux-gnu-gcc -O2 -o "$ROOT/qalabox/qalarender" "$HERE/qalarender.c" \
    --sysroot="$ROOT" \
    -I"$ROOT/usr/include" -I"$ROOT/usr/include/aarch64-linux-gnu" \
    -L"$ROOT/usr/lib/aarch64-linux-gnu" -L"$ROOT/lib/aarch64-linux-gnu" \
    -Wl,-rpath-link,"$ROOT/usr/lib/aarch64-linux-gnu" \
    -Wl,-rpath-link,"$ROOT/lib/aarch64-linux-gnu" \
    -lX11 -lXfixes -lXtst
  [ -x "$ROOT/qalabox/qalarender" ] || die "qalarender لم يُبنَ"
fi

# ────────────────────── 8) proot + cnc-ddraw + الحزم ────────────────────
PROOT_VERSION="—"; CNC_VERSION="—"
if [ "$DRY" != 1 ]; then
  log "8/8 proot ثابت + cnc-ddraw + الحزم النهائية"
  PROOT_URL="https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static"
  dl "$PROOT_URL" "$PKG/proot"
  chmod 755 "$PKG/proot"
  [ "$(stat -c%s "$PKG/proot")" -gt 500000 ] || die "proot صغير بشكل مشبوه"
  PROOT_VERSION="proot 5.3.0 (static)"

  CNC_TAG="$(gh_latest FunkyFr3sh/cnc-ddraw)"; [ -z "$CNC_TAG" ] && CNC_TAG="v7.1.0.0"
  CNC_URL="$(gh_asset_url FunkyFr3sh/cnc-ddraw "cnc-ddraw.zip")"
  [ -z "$CNC_URL" ] && CNC_URL="https://github.com/FunkyFr3sh/cnc-ddraw/releases/download/$CNC_TAG/cnc-ddraw.zip"
  dl "$CNC_URL" "$WORK/cnc.zip"
  mkdir -p "$WORK/cnc" "$PKG/dxwrapper/cnc-ddraw"
  unzip -qo "$WORK/cnc.zip" -d "$WORK/cnc"
  [ -f "$WORK/cnc/ddraw.dll" ] || die "ddraw.dll غير موجود في cnc-ddraw.zip"
  cp "$WORK/cnc/ddraw.dll" "$PKG/dxwrapper/cnc-ddraw/ddraw.dll"
  CNC_VERSION="cnc-ddraw $CNC_TAG"

  python3 - <<PYEOF
import json
v = {"version": "1.0.0",
     "notes": "Debian $SUITE • $WINE_VERSION • ${BOX64_VERSION:-Box64} • ${BOX86_VERSION:-Box86} • $PROOT_VERSION • $CNC_VERSION",
     "components": {"wine": "$WINE_TAG", "box64": "$BOX64_TAG", "box86": "$BOX86_TAG",
                    "proot": "5.3.0", "cnc-ddraw": "$CNC_TAG", "debian": "$SUITE"}}
open("$PKG/version.json", "w").write(json.dumps(v, ensure_ascii=False, indent=1))
PYEOF

  cat > "$PKG/LICENSES.txt" <<'EOF'
QalaBox runtime — مكوّنات مفتوحة المصدر بالكامل (لا تُضمَّن أي ألعاب)
- Debian rootfs: https://deb.debian.org (Free Software licenses)
- Wine: LGPL 2.1 — https://winehq.org (بناءات Kron4ek: https://github.com/Kron4ek/Wine-Builds)
- Box64: MIT — https://github.com/ptitSeb/box64
- Box86: MIT — https://github.com/ptitSeb/box86
- proot: GPL 2.0 — https://github.com/proot-me/proot (static build v5.3.0)
- cnc-ddraw: GPL — https://github.com/FunkyFr3sh/cnc-ddraw
- qalarender: جزء من مشروع QalaBox — يُبنى من runtime-src/qalarender.c
الاستخدام الشخصي لمفاتيحك الأصلية فقط.
EOF
fi

log "ترجيع imagefs.tar ($(du -sh "$ROOT" | cut -f1))"
( cd "$ROOT" && tar --format=gnu --numeric-owner --owner=0 --group=0 -cf "$WORK/imagefs.tar" . )
mv "$WORK/imagefs.tar" "$PKG/imagefs.tar"

if [ "$DRY" != 1 ]; then
  log "ضغط الحزمة النهائية"
  ( cd "$PKG" && zip -q -r -X "$OUT_DIR/qalabox-runtime.qbxruntime" \
      imagefs.tar proot version.json dxwrapper LICENSES.txt )
  cp "$PKG/version.json" "$OUT_DIR/"
fi

# ─────────────────────────────── manifest ───────────────────────────────
{
  echo "QalaBox runtime build manifest"
  echo "date: $(date -u +%FT%TZ)"
  echo "suite: $SUITE | wine: $WINE_VERSION | box64: ${BOX64_VERSION:-—} | box86: ${BOX86_VERSION:-—}"
  echo "proot: $PROOT_VERSION | cnc-ddraw: $CNC_VERSION | dry_mode: $DRY"
  echo "imagefs.tar sha256: $(sha256sum "$PKG/imagefs.tar" | cut -d' ' -f1)"
  [ -f "$OUT_DIR/qalabox-runtime.qbxruntime" ] && \
    echo "qalabox-runtime.qbxruntime sha256: $(sha256sum "$OUT_DIR/qalabox-runtime.qbxruntime" | cut -d' ' -f1)" || true
  [ -f "$OUT_DIR/qalabox-runtime.qbxruntime" ] && \
    echo "qalabox-runtime.qbxruntime size: $(stat -c%s "$OUT_DIR/qalabox-runtime.qbxruntime")" || true
} | tee "$OUT_DIR/build-manifest.txt"

log "اكتمل. المخرجات في $OUT_DIR:"
ls -lh "$OUT_DIR" || true

if [ "$DRY" = 1 ] && [ -z "${KEEP:-}" ]; then
  rm -rf "$WORK"
  log "وضع DRY: حُذف مجلد العمل المؤقت"
fi
