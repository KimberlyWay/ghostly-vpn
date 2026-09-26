"""Renders the Ghostly app icon (desktop PNG/ICO/ICNS) from the same ghost geometry as
androidApp/src/main/res/drawable/ic_launcher_foreground.xml (108-unit adaptive-icon space).
Usage: python tools/make_icons.py   (needs Pillow)"""
import math
from PIL import Image, ImageDraw, ImageFilter

S = 1024
K = S / 108


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def P(x, y):
    return (x * K, y * K)


def quad(p0, c, p1, n=30):
    out = []
    for j in range(1, n + 1):
        t = j / n
        out.append(P((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0],
                     (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1]))
    return out


def ghost_outline():
    pts = [P(54 + 22 * math.cos(math.radians(180 + i)), 46 + 22 * math.sin(math.radians(180 + i))) for i in range(181)]
    pts.append(P(76, 80))
    pts += quad((76, 80), (68.67, 89.1), (61.33, 80))
    pts += quad((61.33, 80), (54, 89.1), (46.67, 80))
    pts += quad((46.67, 80), (39.33, 89.1), (32, 80))
    return pts


def render():
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    bg = Image.new("RGBA", (S, S))
    px = bg.load()
    for y in range(S):
        for x in range(S):
            px[x, y] = lerp((139, 108, 246, 255), (26, 13, 58, 255), (x + y) / (2 * S))
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((-200, -260, 700, 560), fill=(201, 184, 255, 110))
    bg = Image.alpha_composite(bg, glow.filter(ImageFilter.GaussianBlur(160)))
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle((40, 40, S - 40, S - 40), radius=230, fill=255)
    img.paste(bg, (0, 0), mask)

    pts = ghost_outline()
    shadow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).polygon([(x + 10, y + 22) for x, y in pts], fill=(10, 4, 30, 120))
    img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(26)))

    body = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(body).polygon(pts, fill=(255, 255, 255, 255))
    grad = Image.new("RGBA", (S, S))
    gp = grad.load()
    for y in range(S):
        col = lerp((255, 255, 255, 255), (220, 210, 255, 255), max(0.0, min(1.0, (y / K - 24) / 62)))
        for x in range(S):
            gp[x, y] = col
    img = Image.alpha_composite(img, Image.composite(grad, body, body.split()[3]))

    d = ImageDraw.Draw(img)
    for cx in (45.6, 62.4):
        d.ellipse(P(cx - 2.9, 48 - 4.2) + P(cx + 2.9, 48 + 4.2), fill=(27, 16, 48, 255))
        d.ellipse(P(cx - 0.2, 45.6) + P(cx + 1.3, 47.1), fill=(255, 255, 255, 230))
    for cx in (41.8, 66.2):
        d.ellipse(P(cx - 2.6, 56.5 - 2.6) + P(cx + 2.6, 56.5 + 2.6), fill=(255, 154, 200, 120))
    return img


if __name__ == "__main__":
    icon = render()
    icon.save("desktopApp/icons/ghostly.png")
    icon.resize((256, 256), Image.LANCZOS).save("desktopApp/src/main/resources/ghostly.png")
    icon.save("desktopApp/icons/ghostly.ico", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    icon.save("desktopApp/icons/ghostly.icns")
    icon.resize((512, 512), Image.LANCZOS).save("docs/icon.png") if __import__("os").path.isdir("docs") else None
    print("icons written")
