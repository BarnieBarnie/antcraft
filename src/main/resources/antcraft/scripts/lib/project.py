# Resumable multi-step projects, for any script: `import lib.project`, then
# lib.project.run("house", [lib.project.Step("walls", walls), lib.project.Step("roof", roof)]).
# A module of the mod's default library (lib/), a kit: it imports primitives only (docs/design/library.md, Kits). What
# a project has done is kept in memory["projects"], so it survives restarts, reloads, respawns and a stop and rerun.
#
# This is the mod's default. Change it and the mod leaves your version alone;
# delete it and the default comes back when the server starts.

import lib.retry
from lib.borrow import give_back
from lib.result import result


class Step:
    """One step of a project: do(*args) does it and gives a Result, a result() dict, or None for done; it is called up
    to tries times while it fails with a passing reason (lib.retry.AGAIN). An optional step that fails is noted and
    passed, a required one stops the project."""

    def __init__(self, name, do, args=(), tries=1, optional=False):
        self.name = name
        self.do = do
        self.args = args
        self.tries = tries
        self.optional = optional


def status(name):
    """What the project name has done: a dict with done, the names of the steps done, in order, and failed, the name of
    each step whose last try failed to its reason. Empty for a project not run or reset."""
    s = memory.get("projects", {}).get(name, {})
    return {"done": list(s.get("done", [])), "failed": dict(s.get("failed", {}))}


def reset(name):
    """Forgets what the project name has done, so the next run starts at its first step."""
    projects = memory.get("projects", {})
    if name in projects:
        projects.pop(name)
        memory["projects"] = projects


def save(name, s):
    projects = memory.get("projects", {})
    projects[name] = s
    memory["projects"] = projects


def run(name, steps):
    """Does the steps of the project name in order, each a Step, skipping those it has done before, and says one line
    per step it starts. Gives result(): ok, or the reason the first required step that failed gave, at its name; missing
    is the list of the names of the steps not done (an optional step that failed too). Gives back what it borrowed at
    the end, also when a step failed."""
    s = status(name)
    stop = None
    for i, step in enumerate(steps):
        if step.name in s["done"]:
            continue
        say(name + " " + str(i + 1) + "/" + str(len(steps)) + ": " + step.name)

        def attempt():
            r = step.do(*step.args)
            return result() if r is None else r

        r = lib.retry.retry(attempt, (), step.tries)
        reason = r["reason"] if isinstance(r, dict) else r.reason
        if reason is None:
            s["done"].append(step.name)
            if step.name in s["failed"]:
                s["failed"].pop(step.name)
        else:
            s["failed"][step.name] = reason
        save(name, s)
        if reason is not None and not step.optional:
            stop = step
            break
    give_back()
    missing = [step.name for step in steps if step.name not in s["done"]]
    r = result(None if stop is None else s["failed"][stop.name], None if stop is None else stop.name)
    r["missing"] = missing  # a list, also when empty: result() makes an empty one {}
    return r
