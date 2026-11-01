#!/usr/bin/env python3
"""
GkTeDPI Custom Logo Generator & Injector
Usage:
  python3 scripts/apply_logo.py                  # Generates default Liquid Glass GkTeDPI emblem
  python3 scripts/apply_logo.py path/to/logo.png # Applies your custom logo to all Android mipmaps
"""

import math
import os
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
RES_DIR = ROOT / "app" / "src" / "main" / "res"
PLAYSTORE_ICON = ROOT / "app" / "src" / "main" / "ic_launcher-playstore.png"

MIPMAP_SIZES = {
    "mipmap-mdpi": (48, 108),
    "mipmap-hdpi": (72, 162),
    "mipmap-xhdpi": (96, 216),
    "mipmap-xxhdpi": (144, 324),
    "mipmap-xxxhdpi": (192, 432),
}


def render_default_liquid_glass_logo(size: int = 1024) -> tuple[Image.Image, Image.Image]:
    """Returns (full_icon_rgba, transparent_foreground_rgba) at `size`x`size`."""
    bg = Image.new("RGBA", (size, size), (7, 13, 25, 255))
    glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(glow)

    # Ambient cyan + cobalt aurora orbs
    gdraw.ellipse(
        (int(size * 0.05), int(size * 0.02), int(size * 0.78), int(size * 0.75)),
        fill=(14, 165, 233, 120),
    )
    gdraw.ellipse(
        (int(size * 0.28), int(size * 0.30), int(size * 0.96), int(size * 0.98)),
        fill=(99, 102, 241, 110),
    )
    glow = glow.filter(ImageFilter.GaussianBlur(radius=size * 0.10))
    bg = Image.alpha_composite(bg, glow)

    fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    fdraw = ImageDraw.Draw(fg)

    cx, cy = size / 2, size / 2

    # Frosted Liquid Glass Shield polygon
    shield = [
        (cx, size * 0.20),
        (size * 0.74, size * 0.30),
        (size * 0.74, size * 0.53),
        (cx, size * 0.80),
        (size * 0.26, size * 0.53),
        (size * 0.26, size * 0.30),
    ]
    fdraw.polygon(shield, fill=(255, 255, 255, 42))

    # Specular glass outline with seamless rounded joins
    w_line = max(4, int(size * 0.022))
    fdraw.line(shield + [shield[0], shield[1]], fill=(56, 189, 248, 210), width=w_line, joint="curve")
    fdraw.line([shield[3], shield[4], shield[5], shield[0], shield[1]], fill=(255, 255, 255, 235), width=w_line, joint="curve")

    # Inner glowing bolt / DPI packet splitter symbol
    bolt = [
        (cx + size * 0.04, size * 0.31),
        (cx - size * 0.11, size * 0.51),
        (cx - size * 0.01, size * 0.51),
        (cx - size * 0.05, size * 0.69),
        (cx + size * 0.12, size * 0.47),
        (cx + size * 0.01, size * 0.47),
    ]
    bolt_glow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(bolt_glow).polygon(bolt, fill=(56, 189, 248, 220))
    bolt_glow = bolt_glow.filter(ImageFilter.GaussianBlur(radius=size * 0.028))
    fg = Image.alpha_composite(fg, bolt_glow)

    fdraw = ImageDraw.Draw(fg)
    fdraw.polygon(bolt, fill=(240, 249, 255, 255))

    # Subtle Liquid Glass top sheen arc
    sheen = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(sheen)
    sdraw.ellipse(
        (int(size * 0.30), int(size * 0.22), int(size * 0.70), int(size * 0.46)),
        fill=(255, 255, 255, 58),
    )
    sheen = sheen.filter(ImageFilter.GaussianBlur(radius=size * 0.018))
    fg = Image.alpha_composite(fg, sheen)

    full = Image.alpha_composite(bg, fg)
    return full, fg


def load_custom_logo(logo_path: Path, size: int = 1024) -> tuple[Image.Image, Image.Image]:
    src = Image.open(logo_path).convert("RGBA")
    # Fit into square canvas
    canvas_fg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    target_box = int(size * 0.66)
    src_ratio = min(target_box / src.width, target_box / src.height)
    new_w = max(1, int(src.width * src_ratio))
    new_h = max(1, int(src.height * src_ratio))
    resized_fg = src.resize((new_w, new_h), Image.Resampling.LANCZOS)
    canvas_fg.paste(resized_fg, ((size - new_w) // 2, (size - new_h) // 2), resized_fg)

    bg = Image.new("RGBA", (size, size), (7, 13, 25, 255))
    full_box = int(size * 0.84)
    full_ratio = min(full_box / src.width, full_box / src.height)
    fw = max(1, int(src.width * full_ratio))
    fh = max(1, int(src.height * full_ratio))
    resized_full = src.resize((fw, fh), Image.Resampling.LANCZOS)
    bg.paste(resized_full, ((size - fw) // 2, (size - fh) // 2), resized_full)
    return bg, canvas_fg


def mask_squircle(img: Image.Image) -> Image.Image:
    size = img.width
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    pad = int(size * 0.04)
    radius = int(size * 0.22)
    draw.rounded_rectangle((pad, pad, size - pad, size - pad), radius=radius, fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def mask_circle(img: Image.Image) -> Image.Image:
    size = img.width
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    pad = int(size * 0.03)
    draw.ellipse((pad, pad, size - pad, size - pad), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def main() -> None:
    if len(sys.argv) > 1:
        custom_path = Path(sys.argv[1]).expanduser().resolve()
        if not custom_path.exists():
            print(f"Error: Logo file not found: {custom_path}", file=sys.stderr)
            sys.exit(1)
        print(f"Applying custom logo from {custom_path}...")
        full_master, fg_master = load_custom_logo(custom_path, 1024)
    else:
        print("Generating default GkTeDPI Liquid Glass emblem...")
        full_master, fg_master = render_default_liquid_glass_logo(1024)

    # Save 512x512 Play Store icon
    play_icon = mask_squircle(full_master.resize((512, 512), Image.Resampling.LANCZOS))
    play_icon.save(PLAYSTORE_ICON, format="PNG")

    squircle_master = mask_squircle(full_master)
    circle_master = mask_circle(full_master)

    for folder, (legacy_px, fg_px) in MIPMAP_SIZES.items():
        out_dir = RES_DIR / folder
        out_dir.mkdir(parents=True, exist_ok=True)

        ic_std = squircle_master.resize((legacy_px, legacy_px), Image.Resampling.LANCZOS)
        ic_rnd = circle_master.resize((legacy_px, legacy_px), Image.Resampling.LANCZOS)
        ic_fg = fg_master.resize((fg_px, fg_px), Image.Resampling.LANCZOS)

        ic_std.save(out_dir / "ic_launcher.webp", format="WEBP", quality=95)
        ic_rnd.save(out_dir / "ic_launcher_round.webp", format="WEBP", quality=95)
        ic_fg.save(out_dir / "ic_launcher_foreground.webp", format="WEBP", quality=95)

    print("All GkTeDPI launcher mipmaps & Play Store icon generated successfully!")


if __name__ == "__main__":
    main()
