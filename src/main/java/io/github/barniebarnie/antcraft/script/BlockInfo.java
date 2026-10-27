package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.HostValue;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A block in a script, the {@code Block} type (docs/scripting.md, Types): {@code id} such as
 * {@code minecraft:oak_log}, {@code pos}, {@code is_air}, {@code is_fluid} (water or lava, flowing or not, and
 * waterlogged blocks) and {@code tags}, the block's tags as selectors such as {@code #minecraft:logs}. A snapshot of the
 * block when it was read.
 */
public record BlockInfo(String id, Pos pos, boolean air, boolean fluid, List<String> tags) implements HostValue {
	public static final String TYPE = "Block";

	public BlockInfo {
		tags = List.copyOf(tags);
	}

	@Override
	public String typeName() {
		return TYPE;
	}

	@Override
	public @Nullable Object attribute(String name) {
		return switch (name) {
			case "id" -> id;
			case "pos" -> pos;
			case "is_air" -> air;
			case "is_fluid" -> fluid;
			case "tags" -> new PyList(new ArrayList<>(tags));
			default -> null;
		};
	}

	@Override
	public String toString() {
		return "Block(" + Values.repr(id) + ", " + pos + ")";
	}
}
