#!/usr/bin/env bash
# Regenerate every KhataGo launcher / splash asset from the master artwork.
#
#   ./assets_src/brand/regenerate_icons.sh
#
# Inputs (in this directory):
#   flat_square.png    - square master mark with transparent background (the source of truth)
# Outputs:
#   app/src/main/res/mipmap-*{,,-round}/ic_launcher[_round].png  (legacy launcher icons)
#   app/src/main/res/drawable-nodpi/ic_launcher_foreground.png   (adaptive foreground)
#   app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png   (themed-icon silhouette)
#   app/src/main/res/drawable-nodpi/ic_khatago_splash.png        (splash + brand moments)
#   app/src/main/res/drawable-nodpi/logo_wordmark.png            (logo + wordmark)
#
# To swap in official brand artwork later: replace flat_square.png with a square,
# transparent-background PNG of the mark (>= 512px) and re-run this script.
# Requires ImageMagick 6/7 (`convert`, `identify`).
set -euo pipefail

BRAND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RES_DIR="$(cd "$BRAND_DIR/../.." && pwd)/app/src/main/res"
SRC="$BRAND_DIR/flat_square.png"
BG="#e9faf1"          # brand mint backdrop used behind the mark
SPARKLE=70            # % of the legacy icon canvas occupied by the mark

[ -f "$SRC" ] || { echo "missing master artwork: $SRC" >&2; exit 1; }

mkdir -p "$RES_DIR/drawable-nodpi"
for d in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  name="${d%%:*}"; size="${d##*:}"
  mkdir -p "$RES_DIR/mipmap-$name"
  convert "$SRC" -gravity center -background "$BG" -extent ${size}x${size} \
      "PNG32:$RES_DIR/mipmap-$name/ic_launcher.png"
  # round legacy variant: clip to a circle via an explicit mask (no alpha on the flattened base)
  convert -size ${size}x${size} xc:white -draw "fill black circle $((size/2)),$((size/2)) $((size/2)),0" \
      -negate "$BRAND_DIR/.circle_mask.png"
  convert "$SRC" -gravity center -background "$BG" -extent ${size}x${size} -alpha set \
      "$BRAND_DIR/.circle_mask.png" -compose CopyOpacity -composite \
      "PNG32:$RES_DIR/mipmap-$name/ic_launcher_round.png"
  rm -f "$BRAND_DIR/.circle_mask.png"
done

# Adaptive icon foreground: 108dp design grid, art inside the 66dp safe zone -> 432px canvas.
convert "$SRC" -resize 264x264 -gravity center -background none -extent 432x432 \
    "PNG32:$RES_DIR/drawable-nodpi/ic_launcher_foreground.png"

# Monochrome / themed icon layer: solid silhouette of the artwork.
convert "$SRC" -resize 108x108 -background white -flatten -alpha off \
    \( -clone 0 -alpha extract -threshold 15% \) -compose CopyOpacity -composite \
    "PNG32:$RES_DIR/drawable-nodpi/ic_launcher_monochrome.png"

# Splash / brand-moment artwork.
convert "$SRC" -resize 288x288 "PNG32:$RES_DIR/drawable-nodpi/ic_khatago_splash.png"

# Logo + wordmark lockup (font on CI machines may differ; DejaVu Sans Bold is the default pick).
FONT=""
for candidate in \
    /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf \
    /usr/share/fonts/dejavu/DejaVuSans-Bold.ttf \
    /System/Library/Fonts/Supplemental/Arial Bold.ttf; do
  if [ -f "$candidate" ]; then FONT="$candidate"; break; fi
done
if [ -z "$FONT" ] && command -v fc-match >/dev/null 2>&1; then
  FONT="$(fc-match -f '%{file}' 'DejaVu Sans:style=Bold')"
fi
convert "$SRC" -resize 96x96 "$BRAND_DIR/.logo96.png"
if [ -n "$FONT" ]; then
  convert -size 700x160 xc:none -gravity West -font "$FONT" -pointsize 96 \
      -fill "#0B5F45" -annotate +0+0 "KhataGo" "$BRAND_DIR/.wordmark.png"
else
  convert -size 700x160 xc:none -gravity West -pointsize 96 -fill "#0B5F45" \
      -annotate +0+0 "KhataGo" "$BRAND_DIR/.wordmark.png"
fi
convert "$BRAND_DIR/.wordmark.png" -trim +repage "$BRAND_DIR/.wordmark_t.png"
convert "$BRAND_DIR/.logo96.png" "$BRAND_DIR/.wordmark_t.png" \
    -gravity center -background none -splice 24x0 +append -trim +repage \
    -bordercolor none -border 8 -resize 800x \
    "PNG32:$RES_DIR/drawable-nodpi/logo_wordmark.png"
rm -f "$BRAND_DIR/.logo96.png" "$BRAND_DIR/.wordmark.png" "$BRAND_DIR/.wordmark_t.png"

echo "KhataGo icon set regenerated in $RES_DIR"
