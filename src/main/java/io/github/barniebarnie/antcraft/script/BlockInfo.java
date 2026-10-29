package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.HostValue;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Fallable;
import org.jspecify.annotations.Nullable;

/**
 * A block in a script, the {@code Block} type (docs/reference/index.md, Types): {@code id} such as
 * {@code minecraft:oak_log}, {@code pos}, {@code is_air}, {@code is_fluid} (water or lava, flowing or not, and
 * waterlogged blocks), {@code falls} (a block that falls when nothing holds it up: gravel, sand, concrete powder, an
 * anvil), {@code tags}, the block's tags as selectors such as {@code #minecraft:logs}, and {@code state}, its
 * block state properties by name (an int, a bool or a string, such as {@code {'age': 7}} for ripe wheat). A snapshot of
 * the block when it was read.
 */
public record BlockInfo(String id, Pos pos, boolean air, boolean fluid, List<String> tags, Map<String, Object> state) implements HostValue {
	public static final String TYPE = "Block";

	public BlockInfo {
		tags = List.copyOf(tags);
		state = Map.copyOf(state);
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
			case "falls" -> BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)) instanceof Fallable;
			case "tags" -> new PyList(new ArrayList<>(tags));
			case "state" -> {
				PyDict dict = new PyDict();
				new TreeMap<>(state).forEach(dict::put);
				yield dict;
			}
			default -> null;
		};
	}

	@Override
	public String toString() {
		return "Block(" + Values.repr(id) + ", " + pos + ")";
	}
}
