#!/usr/bin/env python3
"""jtb/template.html -> the two pages the screensaver app expects in dist/.

Replaces UrTV's own build. Both pages are the same screen, built self-contained: the
association's fonts, logo, ornament and photographs are inlined, so the TV needs no
network and the app never has to serve a font or a photo from its assets.

  dist/tv.html     what the APK ships and what the app re-downloads from this repo
                   every 6 hours (Updater.java, PhotoCache.REPO)
  dist/urtv.html   the same page, for opening from a computer or a USB stick

Run:  python3 src/build.py
"""
import base64
import json
from pathlib import Path

from PIL import Image, ImageStat

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "jtb"

FONTS = {
    "__HUWIYA_REG__": "huwiya-reg.woff",
    "__HUWIYA_SB__": "huwiya-sb.woff",
    "__ZUNBURK__": "zunburk-bold.woff",
    "__ORNAMENT__": "ornament-mask.png",
    "__BUILDING_LINES__": "building-lines.png",   # its line drawing (white = line), a CSS mask
    "__BUILDING__": "building.jpg",   # the new building's render, for the project chapter
}
PHOTOS = [
    "halaqah-circle.jpg", "mushaf-rahl.jpg", "mosque-door.jpg",
    "students-entering.jpg", "students-sunset.jpg", "reading-carpet.jpg",
]
PHOTO_WIDTH = 1920
PHOTO_QUALITY = 78


def b64(path):
    return base64.b64encode(Path(path).read_bytes()).decode("ascii")


def photo_dim(name):
    """Each photograph carries its own darkening factor: bright stone and sunlight
    swallow the cream text otherwise. Aimed at a mean near 72/255, never brightened."""
    im = Image.open(SRC / "photos" / name).convert("L")
    im.thumbnail((400, 400))
    mean = ImageStat.Stat(im).mean[0]
    return 1.0 if mean <= 0 else max(.42, min(1.0, round(72 / mean, 2)))


def photo_data(name):
    im = Image.open(SRC / "photos" / name).convert("RGB")
    if im.width > PHOTO_WIDTH:
        im = im.resize((PHOTO_WIDTH, round(im.height * PHOTO_WIDTH / im.width)), Image.LANCZOS)
    tmp = ROOT / "dist" / ("." + name)
    tmp.parent.mkdir(exist_ok=True)
    im.save(tmp, "JPEG", quality=PHOTO_QUALITY, optimize=True, progressive=True)
    data = "data:image/jpeg;base64," + b64(tmp)
    tmp.unlink()
    return data


def main():
    page = (SRC / "template.html").read_text(encoding="utf-8")

    for token, name in FONTS.items():
        page = page.replace(token, b64(SRC / "assets" / name))

    logo = (SRC / "assets/logo-cream.svg").read_text(encoding="utf-8").split("?>", 1)[1].strip()
    logo = logo.replace(
        'width="1080" height="1080" viewBox="0 0 1080 1080"',
        'viewBox="288 289 504 454" role="img" '
        'aria-label="شعار جمعية تحفيظ القرآن الكريم ببريدة"')
    page = page.replace("__LOGO__", logo)

    photos = [{"id": photo_data(p), "dim": photo_dim(p)} for p in PHOTOS]
    assert page.count("photos: [],") == 1, "template.html no longer has the photos placeholder"
    page = page.replace("photos: [],", "photos: " + json.dumps(photos, ensure_ascii=False) + ",")

    # no shared store and no sign-in on the TV: the page is the whole screen
    page = page.replace("  if (!window.claude || !window.claude.use) return;",
                        "  if (true) return;   // TV build: no shared store, no sign-in")

    head, rest = page.split('<div class="shift"', 1)
    html = ('<!doctype html>\n<html lang="ar" dir="rtl">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n'
            '<style>html,body{margin:0}img{max-width:100%}[hidden]{display:none!important}</style>\n'
            + head + '</head>\n<body>\n<div class="shift"' + rest + '\n</body>\n</html>\n')

    out = ROOT / "dist"
    out.mkdir(exist_ok=True)
    for name in ("tv.html", "urtv.html"):
        (out / name).write_text(html, encoding="utf-8")
        print(f"wrote dist/{name} ({(out / name).stat().st_size / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
