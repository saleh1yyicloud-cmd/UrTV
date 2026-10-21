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
import io
import json
import re
import urllib.request
from pathlib import Path

from PIL import Image, ImageStat

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "jtb"

FONTS = {
    "__HUWIYA_REG__": "huwiya-reg.woff",
    "__HUWIYA_SB__": "huwiya-sb.woff",
    "__ZUNBURK__": "zunburk-bold.woff",
    "__ORNAMENT__": "ornament-mask.png",
    "__BUILDING__": "building.jpg",   # the new building's render, for the project chapter
}
PHOTOS = [
    "halaqah-circle.jpg", "mushaf-rahl.jpg", "mosque-door.jpg",
    "students-entering.jpg", "students-sunset.jpg", "reading-carpet.jpg",
]
PHOTO_WIDTH = 1920
PHOTO_QUALITY = 78

# the association's news, as its website shows them (tqb.org.sa reads the same API)
NEWS_API = "https://api.tcamol.com/api/website/home"
NEWS_CACHE = SRC / "news.json"   # the last list fetched, so a build without internet keeps the news
STATS_CACHE = SRC / "stats.json"   # likewise for the association's figures
NEWS_SHOW = 4
NEWS_WIDTH = 1600


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


def http_get(url):
    req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "jtb-screen-build"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


_home = None
def site_home():
    """The website's home data (news, statistics), fetched once per build; None when unreachable."""
    global _home
    if _home is None:
        try:
            _home = json.loads(http_get(NEWS_API))
        except Exception as e:
            print(f"website unreachable ({e})")
            _home = {}
    return _home or None


def cached(cache, fresh):
    """fresh() from the website, saved to cache; the cache's last good copy when the site can't be reached."""
    home = site_home()
    if home is not None:
        try:
            items = fresh(home)
            cache.write_text(json.dumps(items, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
            print(f"{cache.stem}: {len(items)} from the website")
            return items
        except Exception as e:   # the API changed shape: keep the last good copy
            print(f"{cache.stem}: unreadable ({e})")
    items = json.loads(cache.read_text(encoding="utf-8")) if cache.exists() else []
    print(f"{cache.stem}: {len(items)} from {cache.name}")
    return items


def news_list():
    """The latest published news, newest first."""
    def fresh(home):
        items = [{k: n.get(k) for k in ("id", "title", "excerpt", "image", "published_at", "slug")}
                 for n in home.get("news") or []
                 if n.get("is_published", True) and not n.get("is_unlisted") and n.get("title")]
        items.sort(key=lambda n: n.get("published_at") or "", reverse=True)
        return items
    return cached(NEWS_CACHE, fresh)[:NEWS_SHOW]


def site_stats():
    """The association's figures as the website's home page shows them, in its order, cut into chapters of
    three or four. Values like "5,000,000 وجه" keep only the number; the label already names the unit."""
    def fresh(home):
        items = sorted((s for s in home.get("statistics") or [] if s.get("is_active", True)),
                       key=lambda s: s.get("sort_order") or 0)
        return [{"label": s["label"], "value": s["value"], "updated": (s.get("updated_at") or "")[:10]}
                for s in items if s.get("label") and s.get("value")]
    items = cached(STATS_CACHE, fresh)
    tiles = []
    for s in items:
        m = re.search(r"\d[\d,.]*", s["value"])
        if m:
            tiles.append({"n": m.group(0).replace(",", ""), "l": s["label"]})
    if not tiles:
        return []
    year = max((s["updated"][:4] for s in items if s.get("updated")), default="")
    year = year.translate(str.maketrans("0123456789", "٠١٢٣٤٥٦٧٨٩"))
    title = "أرقام وإحصائيات الجمعية" + (f" لعام {year}م" if year else "")
    count = -(-len(tiles) // 4)                      # chapters needed at four a chapter, shared out evenly
    groups, start = [], 0
    for k in range(count):
        size = len(tiles) // count + (1 if k < len(tiles) % count else 0)
        groups.append({"title": title, "tiles": tiles[start:start + size]})
        start += size
    return groups


def news_image(url):
    if not url:
        return None
    try:
        im = Image.open(io.BytesIO(http_get(url))).convert("RGB")
    except Exception as e:
        print(f"news: image skipped ({e})")
        return None
    if im.width > NEWS_WIDTH:
        im = im.resize((NEWS_WIDTH, round(im.height * NEWS_WIDTH / im.width)), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=80, optimize=True, progressive=True)
    return "data:image/jpeg;base64," + base64.b64encode(buf.getvalue()).decode("ascii")


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

    news = [{"t": n["title"], "b": n.get("excerpt") or "", "d": (n.get("published_at") or "")[:10],
             "img": news_image(n.get("image"))} for n in news_list()]
    assert page.count("news: [],") == 1, "template.html no longer has the news placeholder"
    page = page.replace("news: [],", "news: " + json.dumps(news, ensure_ascii=False) + ",")

    assert page.count("siteStats: [],") == 1, "template.html no longer has the site statistics placeholder"
    page = page.replace("siteStats: [],", "siteStats: " + json.dumps(site_stats(), ensure_ascii=False) + ",")

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
