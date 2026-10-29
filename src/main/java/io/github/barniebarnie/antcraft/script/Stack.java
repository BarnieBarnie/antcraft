package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.HostValue;
import io.github.barniebarnie.antcraft.script.lang.Values;
import org.jspecify.annotations.Nullable;

/**
 * Items in a script (docs/reference/types/stack.md): {@code item}, the item's full id such as {@code minecraft:oak_log}, and
 * {@code count}. A snapshot: it does not change when the inventory it came from does.
 */
public record Stack(String item, long count) implements HostValue {
	public static final String TYPE = "Stack";

	@Override
	public String typeName() {
		return TYPE;
	}

	@Override
	public @Nullable Object attribute(String name) {
		return switch (name) {
			case "item" -> item;
			case "count" -> count;
			default -> null;
		};
	}

	@Override
	public String toString() {
		return "Stack(" + Values.repr(item) + ", " + count + ")";
	}
}
