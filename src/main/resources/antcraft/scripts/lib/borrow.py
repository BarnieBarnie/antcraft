# Borrowing from chests and giving back, for any script: `from lib.borrow import borrow, borrowed, give_back`.
# A module of the mod's default library (lib/), a primitive: it imports only lib.result. What it lent out is kept in
# memory["borrowed"], so it survives restarts, reloads and respawns.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

from lib.result import result

CHESTS = ["chest", "trapped_chest", "barrel"]


def borrow(chest, item, n=1):
    """Takes up to n of item (an item id or selector) from chest and records them as borrowed. Gives result(): ok,
    or the reason take failed with and what it is short of in missing; what it did take is recorded either way."""
    r = take(chest, item, n)
    got = r.value or 0
    if got:
        memory["borrowed"] = borrowed() + [{"chest": chest, "item": item, "n": got}]
    if got < n:
        return result(r.reason or "no_item", chest, {item: n - got})
    return result()


def borrowed():
    """What is out, oldest first: a list of dicts with the chest, the item and how many (n)."""
    return list(memory.get("borrowed", []))


def give_back(radius=16):
    """Puts each borrowed item back in its chest, else in the nearest chests with room within radius blocks of it;
    what the bot no longer carries (used up, a tool that broke) is struck off. Gives result(): ok, or no_space with
    missing, item to how many the bot kept as no chest took them; those stay borrowed."""
    left = []
    missing = {}
    for b in borrowed():
        # ponytail: carried items of the same kind count as borrowed ones, the bot's own included.
        n = min(b["n"], count(b["item"]))
        if n > 0:
            n = n - (put(b["chest"], b["item"], n).value or 0)
        if n > 0:
            for chest in scan(CHESTS, radius=radius, around=b["chest"]):
                if n > 0 and chest != b["chest"]:
                    n = n - (put(chest, b["item"], n).value or 0)
        if n > 0:
            left.append({"chest": b["chest"], "item": b["item"], "n": n})
            missing[b["item"]] = missing.get(b["item"], 0) + n
    memory["borrowed"] = left
    return result("no_space" if missing else None, None, missing)
