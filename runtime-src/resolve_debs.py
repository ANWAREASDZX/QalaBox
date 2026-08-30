#!/usr/bin/env python3
"""
حلّال اعتماديات Debian — يبني نظام جذر بدون qemu أو chroot.

الطريقة: تُقرأ فهارس Packages.gz للمعمارية المطلوبة، تُحلّ الاعتماديات
(Depends/Pre-Depends مع البدائل والـ Provides)، ثم تُحمَّل حزم .deb وتُفكّ
بـ dpkg-deb -x داخل مجلد الجذر مباشرة (لا تُنفَّذ أي ثنائي خارجي).

الاستخدام:
  resolve_debs.py SUITE ARCH ROOT CACHE PKG [PKG...]

مثال:
  resolve_debs.py bookworm arm64 /tmp/rootfs /tmp/cache bash xvfb

بيئة:
  DEB_MIRROR   مرآة Debian (افتراضي https://deb.debian.org/debian)
"""
import gzip
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

BASE = os.environ.get("DEB_MIRROR", "https://deb.debian.org/debian").rstrip("/")

# أسماء افتراضية (virtual) لا معنى لتثبيتها — الـ postinsts لا تعمل أصلاً
IGNORABLE = {
    "debconf-2.0", "debconf", "install-info", "dpkg",
    "default-dbus-session-bus", "dbus-session-bus", "dbus",
    "default-mta", "mail-transport-agent", "mail-reader", "www-browser",
    "awk", "posix-shell", "c-compiler", "libc-dev", "libglvnd-vendor",
    "alsa-ucm-conf",
}


def log(msg: str) -> None:
    print(f"[resolve] {msg}", flush=True)


def fetch(url: str, dest: str, retries: int = 3) -> None:
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "qalabox-builder/1.0"})
            with urllib.request.urlopen(req, timeout=120) as r, open(dest + ".part", "wb") as f:
                while True:
                    chunk = r.read(1 << 18)
                    if not chunk:
                        break
                    f.write(chunk)
            os.replace(dest + ".part", dest)
            return
        except Exception as e:  # noqa: BLE001
            last = e
            log(f"إعادة محاولة {i + 1}/{retries} لـ {url}: {e}")
            time.sleep(2 * (i + 1))
    raise RuntimeError(f"فشل التحميل: {url}: {last}")


def load_index(suite: str, arch: str, cache: str) -> dict:
    """تحميل وفهرسة Packages.gz — dict: package → حقوله"""
    os.makedirs(cache, exist_ok=True)
    gz_path = os.path.join(cache, f"Packages-{suite}-{arch}.gz")
    fetch(f"{BASE}/dists/{suite}/main/binary-{arch}/Packages.gz", gz_path)
    pkgs: dict = {}
    cur: dict = {}
    with gzip.open(gz_path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip():
                if cur.get("Package"):
                    pkgs.setdefault(cur["Package"], cur)
                cur = {}
                continue
            if line.startswith((" ", "\t")) or ":" not in line:
                continue
            key, val = line.split(":", 1)
            cur[key.strip()] = val.strip()
    if cur.get("Package"):
        pkgs.setdefault(cur["Package"], cur)
    log(f"فهرس {suite}/{arch}: {len(pkgs)} حزمة")
    return pkgs


def clean_token(tok: str) -> str:
    tok = re.sub(r"\[[^\]]*\]", " ", tok)        # [arch lists]
    tok = re.sub(r"<[^>]*>", " ", tok)           # build profiles <>
    tok = re.sub(r"\([^)]*\)", " ", tok)         # (>= 2.34)
    return tok.strip()


def dep_groups(field: str):
    """'a (>=1) | b, c' → [['a','b'], ['c']]"""
    out = []
    for group in field.split(","):
        alts = [clean_token(t) for t in group.split("|")]
        alts = [re.sub(r":(any|native|arm64|amd64|i386|armhf|all)$", "", a).strip() for a in alts]
        alts = [a for a in alts if a]
        if alts:
            out.append(alts)
    return out


def provides_map(pkgs: dict) -> dict:
    m: dict = {}
    for name, fields in pkgs.items():
        prov = fields.get("Provides", "")
        for p in prov.split(","):
            p = clean_token(p)
            if p:
                m.setdefault(p, []).append(name)
    return m


def resolve(seeds: list, pkgs: dict, prov: dict) -> set:
    chosen: set = set()
    work = list(dict.fromkeys(seeds))
    missing: list = []
    while work:
        name = work.pop(0)
        if name in chosen:
            continue
        if name in pkgs:
            chosen.add(name)
            f = pkgs[name]
        elif name in prov:
            pick = prov[name][0]
            log(f"«{name}» افتراضية → {pick}")
            chosen.add(name)
            if pick in chosen:
                continue
            chosen.add(pick)
            f = pkgs.get(pick, {})
        elif name in IGNORABLE:
            continue
        else:
            missing.append(name)
            continue
        for field in ("Depends", "Pre-Depends"):
            raw = f.get(field)
            if not raw:
                continue
            for alts in dep_groups(raw):
                picked = None
                for alt in alts:
                    if alt in pkgs or alt in prov or alt in IGNORABLE:
                        picked = alt
                        break
                if picked is None:
                    log(f"⚠️ لا بديل لـ {alts} (مطلوب من {name}) — تجاهل")
                    continue
                if picked not in chosen:
                    work.append(picked)
    if missing:
        log(f"⚠️ غير موجودة في الفهرس: {sorted(set(missing))}")
    return chosen


def main() -> int:
    if len(sys.argv) < 6:
        print(__doc__)
        return 2
    suite, arch, root, cache = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    seeds = sys.argv[5:]
    os.makedirs(cache, exist_ok=True)
    os.makedirs(root, exist_ok=True)

    pkgs = load_index(suite, arch, cache)
    prov = provides_map(pkgs)
    chosen = resolve(seeds, pkgs, prov)
    real = sorted(n for n in chosen if n in pkgs)
    log(f"النتيجة: {len(real)} حزمة حقيقية للمعمارية {arch}")

    state_path = os.path.join(cache, f"state-{suite}-{arch}.json")
    done: dict = {}
    if os.path.exists(state_path):
        done = json.load(open(state_path))

    total = 0
    for i, name in enumerate(real, 1):
        fields = pkgs[name]
        fname = fields["Filename"]
        url = f"{BASE}/{fname}"
        deb = os.path.join(cache, os.path.basename(fname))
        fetch(url, deb)
        size = os.path.getsize(deb)
        total += size
        subprocess.run(["dpkg-deb", "-x", deb, root], check=True)
        done[name] = fields.get("Version", "?")
        if i % 20 == 0:
            log(f"  فُكّ {i}/{len(real)} ({total / 1e6:.0f} MB)…")

    json.dump(done, open(state_path, "w"))
    log(f"اكتمل {arch}: {len(real)} حزمة، {total / 1e6:.0f} MB مُفكّكة في {root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
