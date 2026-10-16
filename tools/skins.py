"""Generates AntCraft role skins (64x64 wide-arm player layout). Original art, drawn from code."""
import sys
from PIL import Image

out_dir = sys.argv[1]

# part: (base uv, overlay uv, w, h, d)
PARTS = {
    "head": ((0, 0), (32, 0), 8, 8, 8),
    "body": ((16, 16), (16, 32), 8, 12, 4),
    "rarm": ((40, 16), (40, 32), 4, 12, 4),
    "larm": ((32, 48), (48, 48), 4, 12, 4),
    "rleg": ((0, 16), (0, 32), 4, 12, 4),
    "lleg": ((16, 48), (0, 48), 4, 12, 4),
}


def faces(u, v, w, h, d):
    # name -> (x0, y0, width, height)
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "right": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "left": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


def shade(c, f):
    return tuple(max(0, min(255, int(x * f))) for x in c[:3]) + (c[3],)


def paint(role):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    px = img.load()
    for part, (base, over, w, h, d) in PARTS.items():
        for layer, (u, v) in (("base", base), ("over", over)):
            for face, (x0, y0, fw, fh) in faces(u, v, w, h, d).items():
                for y in range(fh):
                    for x in range(fw):
                        c = role(part, layer, face, x, y, fw, fh)
                        if c is not None:
                            px[x0 + x, y0 + y] = c
    return img


def person(skin, hair, eyes, shirt, pants, shoes, extra=None):
    """shirt/pants are colours or fn(part, face, x, y) -> colour. extra may override any pixel (incl. overlay)."""
    skin, hair, eyes, shoes = rgb(skin), rgb(hair), rgb(eyes), rgb(shoes)
    white = rgb("f4f4f4")

    def col(c, part, face, x, y):
        return c(part, face, x, y) if callable(c) else rgb(c)

    def role(part, layer, face, x, y, fw, fh):
        if extra:
            c = extra(part, layer, face, x, y, fw, fh)
            if c is not None:
                return c if c != "skip" else None
        if layer == "over":
            return None
        if part == "head":
            if face == "top":
                return hair
            if face == "bottom":
                return skin
            if face == "front":
                if y < 2:
                    return hair
                if y == 4 and x in (1, 6):
                    return white
                if y == 4 and x in (2, 5):
                    return eyes
                if y == 6 and 3 <= x <= 4:
                    return shade(skin, 0.7)
                return skin
            if face == "back":
                return hair if y < 6 else shade(hair, 0.85)
            return hair if y < 3 or (y < 5 and x in ((0, 1) if face == "left" else (6, 7))) else skin
        if part == "body":
            return col(shirt, part, face, x, y)
        if part in ("rarm", "larm"):
            if face == "bottom" or (face != "top" and y >= 9):
                return skin
            return col(shirt, part, face, x, y)
        # legs
        if face == "bottom" or (face != "top" and y >= 10):
            return shoes
        return col(pants, part, face, x, y)

    return role


# --- roles -----------------------------------------------------------------

def lumberjack_shirt(part, face, x, y):
    red, dark, black = rgb("b22222"), rgb("7a1414"), rgb("1c1c1c")
    if (x % 4 == 1) and (y % 4 == 1):
        return black
    if x % 4 == 1 or y % 4 == 1:
        return dark
    return red


def lumberjack_extra(part, layer, face, x, y, fw, fh):
    # Brown beard on the face front.
    if part == "head" and layer == "base" and face == "front" and (y == 7 or (y == 6 and x in (0, 1, 2, 5, 6, 7)) or (y == 5 and x in (0, 7))):
        return rgb("5a3a1e")
    # Dark knit cap rows on the hat overlay.
    if part == "head" and layer == "over" and (face == "top" or (face not in ("bottom",) and y < 2)):
        return rgb("2e2e2e") if face != "top" or (x + y) % 2 else rgb("3a3a3a")
    return None


def miner_extra(part, layer, face, x, y, fw, fh):
    yellow, dark_yellow, lamp, lamp_glow = rgb("e8b923"), rgb("b38a12"), rgb("fff3a0"), rgb("ffd84a")
    if part == "head" and layer == "over":
        if face == "top":
            return yellow if 1 <= x <= 6 and 1 <= y <= 6 else dark_yellow
        if face != "bottom" and y < 3:
            if face == "front" and y in (1, 2) and x in (3, 4):
                return lamp if y == 1 else lamp_glow
            return dark_yellow if y == 2 else yellow
        return None
    # Soot on the cheeks.
    if part == "head" and layer == "base" and face == "front" and y == 5 and x in (1, 6):
        return rgb("8a6b55")
    # Orange reflective stripe across body and arms.
    if layer == "base" and part in ("body", "rarm", "larm") and face not in ("top", "bottom") and y == 5:
        return rgb("ff7a1a")
    return None


def farmer_shirt(part, face, x, y):
    return rgb("e9dfc4")  # pale linen


def farmer_extra(part, layer, face, x, y, fw, fh):
    straw, band, dark_straw = rgb("e3c565"), rgb("8b3a2b"), rgb("c2a246")
    overall = rgb("3f7d3a")
    if part == "head" and layer == "over":
        if face == "top":
            return straw if (x + y) % 3 else dark_straw
        if face != "bottom" and y < 3:
            return band if y == 2 else (straw if (x + y) % 3 else dark_straw)
        return None
    # Green overalls: bib on the front, straps on the back, full on the legs.
    if layer == "base" and part == "body" and face in ("front", "back", "left", "right"):
        if y >= 6 or (face == "front" and y >= 2 and 1 <= x <= 6) or (face in ("front", "back") and x in (1, 6)):
            if face == "front" and y == 3 and x in (1, 6):
                return rgb("d4d4d4")  # buttons
            return overall
    return None


def guard_shirt(part, face, x, y):
    steel, light = rgb("8e959c"), rgb("b7bec4")
    return light if (y % 3 == 0) else steel


def guard_extra(part, layer, face, x, y, fw, fh):
    blue, gold = rgb("23408e"), rgb("d8b23a")
    # Blue tabard with a gold stripe over the chainmail.
    if layer == "base" and part == "body" and face in ("front", "back") and 2 <= x <= 5 and y >= 1:
        return gold if x in (3, 4) and face == "front" and 2 <= y <= 4 else blue
    if part == "head" and layer == "over":
        steel, dark = rgb("9aa1a8"), rgb("5d6369")
        if face == "top":
            return steel if (x + y) % 2 else dark
        if face != "bottom" and y < 3:
            if face == "front" and y == 2 and x in (3, 4):
                return dark  # nose guard
            return dark if y == 2 else steel
        return None
    return None


def hauler_extra(part, layer, face, x, y, fw, fh):
    pack, strap, buckle = rgb("6b4423"), rgb("3d2611"), rgb("c9a227")
    # Backpack on the body back (overlay so it sits a little proud).
    if part == "body" and layer == "over" and face == "back":
        if 1 <= x <= 6 and 1 <= y <= 9:
            return buckle if y == 5 and x in (3, 4) else (strap if y in (1, 9) else pack)
        return None
    # Straps over the shoulders on the front.
    if part == "body" and layer == "base" and face == "front" and x in (1, 6) and y <= 7:
        return strap
    # Flat cap.
    if part == "head" and layer == "over" and (face == "top" or (face != "bottom" and y < 2)):
        return rgb("8c6239") if face != "front" or y == 0 else rgb("5e3f22")
    return None


def crafter_robe(part, face, x, y):
    return rgb("6a2c91")


def crafter_extra(part, layer, face, x, y, fw, fh):
    trim, apron, apron_dark = rgb("e0c050"), rgb("c9b08a"), rgb("9c845f")
    # Leather apron on the front of body and legs.
    if layer == "base" and face == "front" and part == "body" and y >= 3 and 1 <= x <= 6:
        return apron_dark if y == 3 else apron
    if layer == "base" and face == "front" and part in ("rleg", "lleg") and y < 6:
        return apron
    # Gold trim on the sleeve cuffs.
    if layer == "base" and part in ("rarm", "larm") and face not in ("top", "bottom") and y == 8:
        return trim
    # Goggles on the hat overlay.
    if part == "head" and layer == "over":
        if face == "front" and y == 3 and x in (1, 2, 5, 6):
            return rgb("4fb3c9")
        if face == "front" and y == 3 and x in (0, 3, 4, 7):
            return rgb("3b2a1a")
        if face in ("left", "right", "back") and y == 3:
            return rgb("3b2a1a")
        return None
    return None


def builder_vest(part, face, x, y):
    # Orange hi-vis vest over a grey shirt, with two yellow reflective bands.
    if part == "body" and face == "front" and x in (3, 4):
        return rgb("8a8f96")  # shirt showing at the open front
    if y in (4, 8):
        return rgb("f4e04d")
    return rgb("ff7a1a")


def builder_extra(part, layer, face, x, y, fw, fh):
    yellow, dark = rgb("f2c230"), rgb("c99a12")
    # Yellow hard hat with a darker rim, no lamp (that is the miner's).
    if part == "head" and layer == "over":
        if face == "top":
            return dark if x in (0, 7) or y in (0, 7) else yellow
        if face != "bottom" and y < 3:
            return dark if y == 2 else yellow
        return None
    # Grey sleeves: the vest has none.
    if layer == "base" and part in ("rarm", "larm") and face not in ("top", "bottom") and y < 4:
        return rgb("8a8f96")
    return None


ROLES = {
    "lumberjack": person("c68e6a", "5a3a1e", "3a5f8a", lumberjack_shirt, "34508a", "4a2f1b", lumberjack_extra),
    "miner": person("b5815f", "2a2a2a", "4a3420", "3b3f45", "2d2f33", "1e1e1e", miner_extra),
    "farmer": person("e0ac84", "c9893a", "4f7a2e", farmer_shirt, "3f7d3a", "5b3b20", farmer_extra),
    "guard": person("d9a27e", "1f1f1f", "2c4a8a", guard_shirt, "23408e", "3a3a3a", guard_extra),
    "hauler": person("a8724f", "3a2818", "5a3a22", "9b6b3c", "c2a77a", "4a3220", hauler_extra),
    "crafter": person("f0c8a0", "a0a0a0", "6a2c91", crafter_robe, "4b1f68", "2b2b2b", crafter_extra),
    "builder": person("d8a07a", "6b4a2b", "3f6e4a", builder_vest, "3d5470", "5a3b22", builder_extra),
}

for name, role in ROLES.items():
    paint(role).save(f"{out_dir}/{name}.png")
    print("wrote", name)
