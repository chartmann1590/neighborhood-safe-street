import os
import math
from PIL import Image, ImageDraw

def create_safety_icon(size):
    # Create image with RGBA
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Background circle / rounded squircle
    bg_color_top = (10, 25, 47, 255)      # Deep Navy #0A192F
    bg_color_bottom = (5, 12, 25, 255)
    
    # Draw rounded background
    corner_radius = int(size * 0.22)
    draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=corner_radius, fill=bg_color_top)

    # Concentric radar rings (cyan accent)
    cx, cy = size / 2.0, size / 2.0
    radar_color = (0, 229, 255, 60)      # Cyan with opacity
    for r_pct in [0.22, 0.35, 0.44]:
        r = size * r_pct
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], outline=radar_color, width=max(1, int(size * 0.02)))

    # Draw Shield
    shield_pts = [
        (cx, cy - size * 0.28),                  # top peak
        (cx + size * 0.24, cy - size * 0.20),    # top right
        (cx + size * 0.24, cy + size * 0.06),    # mid right
        (cx, cy + size * 0.30),                  # bottom tip
        (cx - size * 0.24, cy + size * 0.06),    # mid left
        (cx - size * 0.24, cy - size * 0.20),    # top left
    ]
    shield_fill = (0, 176, 255, 240)             # Vibrant Primary Blue #00B0FF
    draw.polygon(shield_pts, fill=shield_fill)

    # Inner shield accent
    inner_shield = [
        (cx, cy - size * 0.23),
        (cx + size * 0.18, cy - size * 0.16),
        (cx + size * 0.18, cy + size * 0.04),
        (cx, cy + size * 0.23),
        (cx - size * 0.18, cy + size * 0.04),
        (cx - size * 0.18, cy - size * 0.16),
    ]
    inner_fill = (10, 25, 47, 255)
    draw.polygon(inner_shield, fill=inner_fill)

    # Alert Pulse / Star in the center
    star_color = (255, 145, 0, 255)              # Alert Amber #FF9100
    star_radius = size * 0.09
    draw.ellipse([cx - star_radius, cy - star_radius, cx + star_radius, cy + star_radius], fill=star_color)

    # Core white eye/dot
    core_radius = size * 0.04
    draw.ellipse([cx - core_radius, cy - core_radius, cx + core_radius, cy + core_radius], fill=(255, 255, 255, 255))

    return img

def main():
    targets = [
        ("app/src/main/res", [
            ("mipmap-mdpi", 48),
            ("mipmap-hdpi", 72),
            ("mipmap-xhdpi", 96),
            ("mipmap-xxhdpi", 144),
            ("mipmap-xxxhdpi", 192),
        ]),
        ("wear/src/main/res", [
            ("mipmap-mdpi", 48),
            ("mipmap-hdpi", 72),
            ("mipmap-xhdpi", 96),
            ("mipmap-xxhdpi", 144),
            ("mipmap-xxxhdpi", 192),
        ])
    ]

    for base_dir, sizes in targets:
        for folder, sz in sizes:
            out_dir = os.path.join(base_dir, folder)
            os.makedirs(out_dir, exist_ok=True)
            img = create_safety_icon(sz)
            img.save(os.path.join(out_dir, "ic_launcher.png"))
            img.save(os.path.join(out_dir, "ic_launcher_round.png"))

    # Play store 512
    os.makedirs("fastlane/metadata/android/en-US/images/icon", exist_ok=True)
    store_icon = create_safety_icon(512)
    store_icon.save("fastlane/metadata/android/en-US/images/icon/icon.png")
    store_icon.save("app-icon-512.png")
    print("Generated all app icons successfully!")

if __name__ == "__main__":
    main()
