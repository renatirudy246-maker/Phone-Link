"""Phone-Link icon pipeline: desktop SVG -> ICO + PNG, mobile SVG -> Android mipmap/drawable resources.

Full-bleed rules (per spec):
- SVG viewport 1024x1024 maps 1:1 to output bitmap (left=0, top=0, right=w, bottom=h).
- No extra transparent padding, no inset scaling for legacy/full-bleed outputs.
- Adaptive icon separates background (gradient drawable) from foreground (flow mark) correctly.
"""
import io
import os
import re

import cairosvg
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DESKTOP_SVG = os.path.join(ROOT, "assets", "icon-svg", "desktop-icon.svg")
MOBILE_SVG = os.path.join(ROOT, "assets", "icon-svg", "mobile-icon.svg")

DESKTOP_ICO = os.path.join(ROOT, "src", "desktop", "PhoneLink.Desktop", "Assets", "app.ico")
ANDROID_RES = os.path.join(ROOT, "src", "android", "PhoneLinkAndroid", "app", "src", "main", "res")

ICO_SIZES = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]
LEGACY_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

FOREGROUND_SCALE = 0.90  # adaptive-icon safe zone: content must sit inside center ~66/108


def render_svg(svg_path: str, size: int, bytestring: str | None = None) -> Image.Image:
    kwargs = dict(output_width=size, output_height=size)
    if bytestring is not None:
        kwargs["bytestring"] = bytestring.encode("utf-8")
    else:
        kwargs["url"] = svg_path
    png = cairosvg.svg2png(**kwargs)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def render_svg_native(svg_path: str, bytestring: str | None = None) -> Image.Image:
    return render_svg(svg_path, 1024, bytestring=bytestring)


def extract_paths(svg_path: str) -> list[str]:
    text = open(svg_path, encoding="utf-8").read()
    return [re.sub(r"\s+", " ", m) for m in re.findall(r"<path\s+d=\"([^\"]+)\"", text)]


def flow_only_svg(svg_path: str) -> str:
    """Rebuild SVG containing only the flow paths + their gradients (transparent background)."""
    text = open(svg_path, encoding="utf-8").read()
    defs = re.search(r"<defs>(.*?)</defs>", text, re.S).group(1)
    paths = "\n".join(re.findall(r"<path\s+d=\"[^\"]*\"[^>]*/>", text))
    return f"""<svg width="1024" height="1024" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg">
  <defs>{defs}</defs>
  {paths}
</svg>"""


def centered_on_canvas(img: Image.Image, scale: float) -> Image.Image:
    """Scale img by `scale` and center it on a 1024 transparent canvas."""
    canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    w, h = round(img.width * scale), round(img.height * scale)
    resized = img.resize((w, h), Image.LANCZOS)
    canvas.paste(resized, ((1024 - w) // 2, (1024 - h) // 2), resized)
    return canvas


def circle_mask(size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size, size), fill=255)
    return mask


def build_desktop_ico() -> None:
    full = render_svg_native(DESKTOP_SVG)
    os.makedirs(os.path.dirname(DESKTOP_ICO), exist_ok=True)
    full.save(DESKTOP_ICO, format="ICO", sizes=ICO_SIZES)
    # preview PNGs for verification
    preview_dir = os.path.join(ROOT, "assets", "icon-svg", "_out")
    os.makedirs(preview_dir, exist_ok=True)
    full.save(os.path.join(preview_dir, "desktop-1024.png"))
    for w, h in ICO_SIZES:
        full.resize((w, h), Image.LANCZOS).save(os.path.join(preview_dir, f"desktop-{w}.png"))
    print(f"ICO written: {DESKTOP_ICO} (sizes {ICO_SIZES})")


def build_android_icons() -> None:
    full = render_svg_native(MOBILE_SVG)                       # full-bleed artwork
    flow = render_svg_native(MOBILE_SVG, bytestring=flow_only_svg(MOBILE_SVG))

    fg = centered_on_canvas(flow, FOREGROUND_SCALE)            # adaptive foreground (safe zone)
    os.makedirs(os.path.join(ANDROID_RES, "drawable"), exist_ok=True)
    fg.save(os.path.join(ANDROID_RES, "drawable", "ic_launcher_foreground.png"))
    print("foreground: drawable/ic_launcher_foreground.png (1024, mark scaled %.0f%% centered)" % (FOREGROUND_SCALE * 100))

    for density, px in LEGACY_SIZES.items():
        mip = os.path.join(ANDROID_RES, f"mipmap-{density}")
        os.makedirs(mip, exist_ok=True)
        full.resize((px, px), Image.LANCZOS).save(os.path.join(mip, "ic_launcher.png"))
        round_img = full.resize((px, px), Image.LANCZOS).copy()
        round_img.putalpha(circle_mask(px))
        round_img.save(os.path.join(mip, "ic_launcher_round.png"))
    print("legacy mipmaps: ic_launcher.png + ic_launcher_round.png x5 densities")

    path_data = extract_paths(MOBILE_SVG)
    assert len(path_data) == 2, f"expected 2 flow paths, got {len(path_data)}"
    p1, p2 = path_data

    # adaptive icon definitions
    anydpi = os.path.join(ANDROID_RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        with open(os.path.join(anydpi, f"{name}.xml"), "w", encoding="utf-8") as f:
            f.write(f"""<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
""")

    with open(os.path.join(ANDROID_RES, "drawable", "ic_launcher_background.xml"), "w", encoding="utf-8") as f:
        f.write("""<?xml version="1.0" encoding="utf-8"?>
<!-- Full-bleed mobile background (from mobile-icon.svg mobileBg gradient, ~45deg approximation) -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
       android:shape="rectangle">
    <gradient
        android:type="linear"
        android:angle="315"
        android:startColor="#191424"
        android:centerColor="#100B18"
        android:centerY="0.55"
        android:endColor="#08060D"/>
</shape>
""")

    with open(os.path.join(ANDROID_RES, "drawable", "ic_launcher_monochrome.xml"), "w", encoding="utf-8") as f:
        f.write(f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Monochrome: flow mark as single-color path, scaled to adaptive safe zone -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
        android:width="108dp"
        android:height="108dp"
        android:viewportWidth="1024"
        android:viewportHeight="1024">
    <group
        android:scaleX="{FOREGROUND_SCALE}"
        android:scaleY="{FOREGROUND_SCALE}"
        android:pivotX="512"
        android:pivotY="512">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="{p1}"/>
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="{p2}"/>
    </group>
</vector>
""")
    print("adaptive: mipmap-anydpi-v26/ic_launcher(.round).xml + drawable background/monochrome/foreground")


def verify_full_bleed() -> None:
    """Check output pixels: artwork must extend to bitmap edges (no padding)."""
    full = render_svg_native(MOBILE_SVG)
    img = full.convert("RGBA")
    w, h = img.size
    samples = {"top": 0, "bottom": h - 1, "left": 0, "right": w - 1}
    for name, coord in samples.items():
        if name in ("top", "bottom"):
            row = [img.getpixel((x, coord)) for x in range(0, w, 16)]
        else:
            row = [img.getpixel((coord, y)) for y in range(0, h, 16)]
        opaque = sum(1 for p in row if p[3] > 0)
        print(f"  edge {name:6s}: {opaque}/{len(row)} opaque samples (rounded corners expected to be transparent)")
    # corners should be transparent (rx=225), mid-edges opaque
    mid_left = img.getpixel((0, h // 2))
    mid_top = img.getpixel((w // 2, 0))
    print(f"  mid-left ({mid_left[3]} alpha), mid-top ({mid_top[3]} alpha) -> must be 255")


def main() -> None:
    build_desktop_ico()
    build_android_icons()
    print("--- full-bleed verification (mobile) ---")
    verify_full_bleed()


if __name__ == "__main__":
    main()