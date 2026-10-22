#!/usr/bin/env bash
# Word lists of the PocketDS keyboard (suggestions): the most frequent words of the AOSP
# LatinIME dictionaries (Apache-2.0, packages/inputmethods/LatinIME of the source tree).
#   build.sh [SRC_DIR]   -> device/ayaneo/pocket_ds/parts/assets/words_{fr,en}.txt
# One "word frequency" per line, lower case, letters only (accents kept), sorted by word,
# the 25 000 most frequent words.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="${1:-$HERE/../src}"
exec python3 -I - "$SRC/packages/inputmethods/LatinIME/dictionaries" \
    "$HERE/device/ayaneo/pocket_ds/parts/assets" <<'PY'
import gzip, re, sys, os
dictionaries, out = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)
line = re.compile(r"^ word=([^,]+),f=(\d+),")
for lang, name in (("fr", "fr"), ("en", "en_US")):
    best = {}
    with gzip.open(f"{dictionaries}/{name}_wordlist.combined.gz", "rt", encoding="utf-8") as f:
        for text in f:
            m = line.match(text)
            if not m or not m.group(1).isalpha():
                continue
            word, freq = m.group(1).lower(), int(m.group(2))
            if freq > best.get(word, -1):
                best[word] = freq
    top = sorted(best.items(), key=lambda kv: -kv[1])[:25000]
    top.sort()
    with open(f"{out}/words_{lang}.txt", "w", encoding="utf-8") as f:
        for word, freq in top:
            f.write(f"{word} {freq}\n")
    print(f"{lang}: {len(top)} words")
PY
