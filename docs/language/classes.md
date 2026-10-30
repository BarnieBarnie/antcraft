# Classes

A class groups state and the functions that work on it, such as a miner with its depth and a `dig` method. Classes work as in Python, with single inheritance and without the special methods beyond `__init__`.

```python
class Miner:
    dug = 0                       # a class attribute, shared by every miner

    def __init__(self, depth):
        self.depth = depth        # an instance attribute, one per miner

    def dig(self):
        Miner.dug += 1
        return mine(pos().down())


m = Miner(12)
if not m.dig():
    say(f"cannot dig at depth {m.depth}")
```

- `class Name:` defines a class. Calling it, `Miner(12)`, makes an **instance** and runs its `__init__` with the instance as `self` and the arguments of the call. A class without `__init__` takes no arguments.
- A **method** is a `def` in the class body. `m.dig()` calls it with `m` as its first parameter, `self`. `m.dig` without the call is a bound method you can pass around, such as to `sorted(..., key=...)`. `Miner.dig(m)` calls it through the class.
- `self.depth = ...` sets an attribute of the instance. An assignment in the class body (`dug = 0`) is a **class attribute**: every instance reads it until it sets its own of the same name. Change it through the class, `Miner.dug += 1`.
- Reading an attribute that is not there is an `AttributeError`: `'Miner' object has no attribute 'depht'`.
- `print(m)` shows `<Miner object>`, `print(Miner)` shows `<class 'Miner'>`, and `Miner.__name__` is `"Miner"`. Two instances are equal only if they are the same instance; an instance can be a dict key.
- `isinstance(m, Miner)` is true for instances of `Miner` and of classes derived from it.

## Inheritance

A class may derive from **one** other class. It has the base class's methods and class attributes, and a method of its own replaces the base's of the same name. `super()` in a method reaches the base class's version:

```python
class Tunneler(Miner):
    def __init__(self, depth, length):
        super().__init__(depth)
        self.length = length

    def dig(self):
        for i in range(self.length):
            if not super().dig():
                return False
            goto(pos().east())
        return True
```

`super()` works without arguments, in a method; `super(Tunneler, self)` and `super()` outside a method are compile errors.

## Exception classes

A class that derives from `Exception` (or another [exception type](errors.md#exception-types)) is an exception of your own. Raise it, and catch it by its class or any class it derives from:

```python
class NoOre(Exception):
    pass


def dig_for(ore):
    found = find_nearest(ore, radius=16)
    if found is None:
        raise NoOre(f"no {ore} within 16 blocks")
    return mine(found)


try:
    dig_for("#minecraft:iron_ores")
except NoOre as e:
    say(str(e))
```

- `except NoOre` and `except Exception` both catch it; `isinstance(e, NoOre)` is true.
- `str(e)` is its message, made of the arguments it was made with (kept in `e.args`), as in Python. An exception class can have an `__init__` and attributes of its own; call `super().__init__(message)` in it to set the message.
- A `NoOre` nothing catches stops the script with `miner.py:7: NoOre: no #minecraft:iron_ores within 16 blocks`.
- `raise NoOre` without the call works for a class without an `__init__`. Only exception classes can be raised or caught.

## Classes in modules

A class defined in a module of the script folders is imported like a function, `from lib.miner import Miner`, and its methods see the globals of that module (see [Modules & import](modules.md)).

## Saving

A bot's script is saved with the world as usual when it holds classes and instances: in variables, lists and dicts, or in the middle of a method. After a restart it goes on as if it had never stopped, with the same instances (two variables that held one instance still do) and the class attributes as the script left them. `memory` holds plain data only, so an instance does not go into it: keep the values you need in a dict there, and make the instance again from it.

## What a class cannot do

These are compile errors, or runtime errors where only the running script can tell:

| Not supported | Instead |
| --- | --- |
| more than one base class, `class A(B, C)` | one base; keep the other as an attribute |
| `metaclass=` or other keywords in the class line | leave them out |
| decorators: `@property`, `@staticmethod`, `@classmethod` | a plain method; a function outside the class for a static one |
| special methods other than `__init__`: `__str__`, `__repr__`, `__eq__`, `__add__`, `__len__`, ... | a method with a name of its own, such as `describe()` |
| `__slots__` | leave it out |
| a class body holding anything but methods, attribute assignments, `pass` and a docstring (an `if`, a loop, a nested class) | set the attribute on the class after it, `Miner.limit = ...` |
| a class attribute whose value uses another one (`y = x + 1` in the class body) | use the value itself, or set it after the class |
| deriving from a builtin type such as `list` or `dict` (`class A(list)`) | keep the list as an attribute |
| `type(x)`, `getattr`, `setattr`, `hasattr` | `isinstance`, and plain attribute access |

A class defined inside a function is a variable of that function, so its methods cannot use the class by its name ([closures](not-supported.md#closures-and-nonlocal) are not supported); define such a class at the top level.
