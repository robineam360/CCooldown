#!/usr/bin/env bash
#
# Rebuild the PDF user guide, the brochure, and the "New in vX.Y" one-pager PNG
# from the HTML sources in src/.
#
#   ./release/docs/build.sh
#
# Outputs, all in release/docs/:
#   CCooldown-User-Guide-v<version>.pdf   (version read from app/build.gradle.kts)
#   CCooldown-Brochure.pdf
#   CCooldown-whats-new-v<version>.png    (guide page 3, for Slack/email)
#
# The one-pager is *sliced out of* guide.html rather than authored separately, so
# it can never drift from the guide. Nothing to keep in sync.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$REPO/release/docs/src"
OUT="$REPO/release/docs"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

[ -x "$CHROME" ] || { echo "Google Chrome not found at: $CHROME" >&2; exit 1; }

VERSION="$(sed -n 's/.*versionName *= *"\([^"]*\)".*/\1/p' "$REPO/app/build.gradle.kts" | head -1)"
[ -n "$VERSION" ] || { echo "couldn't read versionName from app/build.gradle.kts" >&2; exit 1; }
echo "building docs for v$VERSION"

# Chrome needs a file:// URL, and the repo path contains spaces and a comma that
# are painful to escape — so render from a clean temp copy instead.
WORK="$(mktemp -d /tmp/ccd-docs.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
cp -R "$SRC/." "$WORK/"

render() {  # render <html> <pdf>
  "$CHROME" --headless --disable-gpu --no-pdf-header-footer \
            --virtual-time-budget=30000 \
            --print-to-pdf="$WORK/$2" "file://$WORK/$1" >/dev/null 2>&1
}

render guide.html guide.pdf
render brochure.html brochure.pdf

# The one-pager: slice the "New in v…" page (and the stylesheet) out of the guide.
python3 - "$WORK" <<'PY'
import sys, re
work = sys.argv[1]
src = open(f"{work}/guide.html", encoding="utf-8").read()
style = src[src.index("<style>"): src.index("</style>") + len("</style>")]
m = re.search(r'<h2>New in v[\d.]+</h2>', src)
if not m:
    raise SystemExit('no "New in vX.Y" page found in guide.html — skipping one-pager')
start = src.rfind('<div class="page">', 0, m.start())
foot = src.index('<span>3</span></div>', m.start())
end = src.index('</div>', foot + len('<span>3</span></div>')) + len('</div>')
page = src[start:end]
assert "New in v" in page and "What it is" not in page, "slice grabbed the wrong page"
open(f"{work}/whatsnew.html", "w", encoding="utf-8").write(
    '<!DOCTYPE html><html><head><meta charset="utf-8">' + style +
    '<style>body{background:#FAF9F5;} .page{page-break-after:auto;}</style>'
    '</head><body>' + page + '</body></html>')
PY

# A4 at 96dpi is 794x1121 CSS px; 2x for a crisp retina/Slack image.
"$CHROME" --headless --disable-gpu --hide-scrollbars \
          --force-device-scale-factor=2 --window-size=794,1121 \
          --screenshot="$WORK/whatsnew.png" "file://$WORK/whatsnew.html" >/dev/null 2>&1

cp "$WORK/guide.pdf"     "$OUT/CCooldown-User-Guide-v$VERSION.pdf"
cp "$WORK/brochure.pdf"  "$OUT/CCooldown-Brochure.pdf"
cp "$WORK/whatsnew.png"  "$OUT/CCooldown-whats-new-v$VERSION.png"

echo
echo "wrote:"
ls -1sh "$OUT/CCooldown-User-Guide-v$VERSION.pdf" \
        "$OUT/CCooldown-Brochure.pdf" \
        "$OUT/CCooldown-whats-new-v$VERSION.png"
echo
echo "NOW READ EVERY PAGE YOU CHANGED. .page is a fixed A4 box with"
echo "overflow:hidden — too much content is silently clipped, not reflowed."
