# Borrowing from chests and giving back, for any script: `from lib.borrow import borrow, borrowed, give_back, keep`.
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
    had = count(item)
    r = take(chest, item, n)
    got = r.value or 0
    if got:
        memory["borrowed"] = borrowed() + [record(chest, item, got, had)]
    if got < n:
        return result(r.reason or "no_item", chest, {item: n - got})
    return result()


def borrowed():
    """What is out, oldest first: a list of dicts with the chest, the item, how many (n) and how many of it the bot
    carried before (had: its own and what it borrowed earlier)."""
    return list(memory.get("borrowed", []))


def give_back(radius=16, since=0):
    """Puts each borrowed item back in its chest, else in the nearest chests with room within radius blocks of it;
    what the bot no longer carries (used up, a tool that broke) is struck off. Only the records from index since of
    borrowed() on, those borrowed after it had that many; the older ones stay. Gives result(): ok, or no_space with
    missing, item to how many the bot kept as no chest took them; those stay borrowed."""
    out = borrowed()
    left = []
    missing = {}
    # Newest first, each record gives back only what the bot carries above what it had before it: what the bot used
    # counts as borrowed first, and its own items stay. A record from before "had" was kept counts all as borrowed.
    for b in reversed(out[since:]):
        had = b.get("had", 0)
        n = min(b["n"], count(b["item"]) - had)
        if n > 0:
            n = n - (put(b["chest"], b["item"], n).value or 0)
        if n > 0:
            for chest in scan(CHESTS, radius=radius, around=b["chest"]):
                if n > 0 and chest != b["chest"]:
                    n = n - (put(chest, b["item"], n).value or 0)
        if n > 0:
            left = [record(b["chest"], b["item"], n, had)] + left
            missing[b["item"]] = missing.get(b["item"], 0) + n
    memory["borrowed"] = out[:since] + left
    return result("no_space" if missing else None, None, missing)


def keep(item, n=None):
    """Strikes n of item (all when None) off what is borrowed, newest first, for a job that keeps them: give_back then
    leaves them with the bot. item matches a record's item as borrow was given it, with or without "minecraft:", and a
    selector also each item id it selects. What the bot owns of it counts towards n: give_back leaves the bot what it
    carried before its oldest record of each matching item (had), so only n less those are struck off."""
    out = borrowed()
    if n is not None:
        # Each item's oldest matching record has what the bot owns of it; a selector over several items sums them.
        owned = {}
        for b in out:
            if same(b["item"], item) and full(b["item"]) not in owned:
                owned[full(b["item"])] = min(count(b["item"]), b.get("had", 0))
        n = max(0, n - sum(owned.values()))
    left = []
    for b in reversed(out):
        k = b["n"] if n is None else min(n, b["n"])
        if k == 0 or not same(b["item"], item):
            left = [b] + left
            continue
        if n is not None:
            n = n - k
        if k < b["n"]:
            # The kept ones are the bot's own now.
            left = [record(b["chest"], b["item"], b["n"] - k, b.get("had", 0) + k)] + left
    memory["borrowed"] = left


def record(chest, item, n, had):
    return {"chest": chest, "item": item, "n": n, "had": had}


def same(recorded, item):
    if full(recorded) == full(item):
        return True
    return item.startswith("#") and not recorded.startswith("#") and matches(recorded, item)


def full(item):
    return item if item.startswith("#") or ":" in item else "minecraft:" + item
