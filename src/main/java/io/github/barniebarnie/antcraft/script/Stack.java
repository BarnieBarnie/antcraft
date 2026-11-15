package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.HostValue;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Items in a script (docs/reference/types/stack.md): {@code item}, the item's full id such as {@code minecraft:oak_log}, and
 * {@code count}, the {@code damage} and {@code max_damage} of an item that wears (both 0 for one that does not), and its
 * {@code enchantments}, by id with their level (#754). A snapshot: it does not change when the inventory it came from does.
 */
public record Stack(String item, long count, long damage, long maxDamage, Map<String, Long> enchantments) implements HostValue {
	public static final String TYPE = "Stack";

	/** Items with no enchantments. */
	public Stack(String item, long count, long damage, long maxDamage) {
		this(item, count, damage, maxDamage, Map.of());
	}

	/** Items that do not wear. */
	public Stack(String item, long count) {
		this(item, count, 0, 0);
	}

	@Override
	public String typeName() {
		return TYPE;
	}

	@Override
	public @Nullable Object attribute(String name) {
		return switch (name) {
			case "item" -> item;
			case "count" -> count;
			case "damage" -> damage;
			case "max_damage" -> maxDamage;
			case "enchantments" -> {
				PyDict dict = new PyDict();
				new TreeMap<>(enchantments).forEach(dict::put);
				yield dict;
			}
			default -> null;
		};
	}

	@Override
	public String toString() {
		return "Stack(" + Values.repr(item) + ", " + count + ")";
	}
}
