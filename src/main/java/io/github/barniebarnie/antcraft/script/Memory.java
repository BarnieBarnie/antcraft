package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A bot's {@code memory} (docs/scripting.md, Memory): a dict that belongs to the bot, outlives its scripts and is saved
 * with it. It holds plain data only: numbers, strings, booleans, {@code None}, {@code Pos}, and lists, tuples and dicts
 * of these.
 *
 * <p>The dict checks every key and value stored into it, all the way down, so storing a function or an {@code Entity}
 * fails the script at the line of the store. A list or dict stored there can still be changed afterwards
 * ({@code memory['l'].append(f)}); such a value is checked again when the bot is saved, and left out of the save with a
 * report.
 */
public final class Memory {
	private static final String NOT_PLAIN = "memory holds only plain data (numbers, strings, booleans, None, Pos, and "
		+ "lists, tuples and dicts of these), not '";
	private static final String KEY = "ScriptMemory";

	private Memory() {
	}

	/** An empty memory. */
	public static PyDict create() {
		return new PyDict((key, value) -> {
			checkPlain(key);
			checkPlain(value);
		});
	}

	/** Fails with a {@link ScriptError} unless {@code value} is plain data. */
	public static void checkPlain(Object value) {
		checkPlain(value, Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	private static void checkPlain(Object value, Set<Object> open) {
		switch (value) {
			case Long l -> {
			}
			case Double d -> {
			}
			case String s -> {
			}
			case Boolean b -> {
			}
			case PyNone n -> {
			}
			case Pos p -> {
			}
			case PyList list -> within(list, open, () -> list.items.forEach(item -> checkPlain(item, open)));
			case PyTuple tuple -> within(tuple, open, () -> tuple.items().forEach(item -> checkPlain(item, open)));
			case PyDict dict -> within(dict, open, () -> dict.items().forEach(entry -> {
				checkPlain(entry.getKey(), open);
				checkPlain(entry.getValue(), open);
			}));
			default -> throw new ScriptError(NOT_PLAIN + Values.typeName(value) + "'");
		}
	}

	private static void within(Object container, Set<Object> open, Runnable check) {
		if (!open.add(container)) {
			throw new ScriptError("memory cannot hold a list or dict that contains itself");
		}
		check.run();
		open.remove(container);
	}

	/**
	 * Saves {@code memory} with the bot. An entry that is no longer plain data is left out, and {@code problems} hears
	 * why.
	 */
	public static void save(PyDict memory, ValueOutput output, Consumer<String> problems) {
		ListTag entries = new ListTag();
		for (Map.Entry<Object, Object> entry : memory.items()) {
			try {
				checkPlain(entry.getKey());
				checkPlain(entry.getValue());
			} catch (ScriptError e) {
				problems.accept("memory[" + Values.repr(entry.getKey()) + "] was not saved: " + e.getMessage());
				continue;
			}
			CompoundTag tag = new CompoundTag();
			tag.put("k", encode(entry.getKey()));
			tag.put("v", encode(entry.getValue()));
			entries.add(tag);
		}
		if (!entries.isEmpty()) {
			CompoundTag tag = new CompoundTag();
			tag.put("dict", entries);
			output.store(KEY, CompoundTag.CODEC, tag);
		}
	}

	/** The memory saved with the bot, or an empty one. */
	public static PyDict load(ValueInput input) {
		PyDict memory = create();
		input.read(KEY, CompoundTag.CODEC).ifPresent(tag -> fill(memory, tag.getListOrEmpty("dict")));
		return memory;
	}

	/** Each value as a compound with one entry, named by its type. */
	private static Tag encode(Object value) {
		CompoundTag tag = new CompoundTag();
		switch (value) {
			case Long l -> tag.putLong("int", l);
			case Double d -> tag.putDouble("float", d);
			case String s -> tag.putString("str", s);
			case Boolean b -> tag.putBoolean("bool", b);
			case PyNone n -> tag.putBoolean("none", true);
			case Pos p -> tag.putLongArray("pos", new long[] {p.x(), p.y(), p.z()});
			case PyList list -> tag.put("list", encodeAll(list.items));
			case PyTuple tuple -> tag.put("tuple", encodeAll(tuple.items()));
			case PyDict dict -> {
				ListTag entries = new ListTag();
				for (Map.Entry<Object, Object> entry : dict.items()) {
					CompoundTag pair = new CompoundTag();
					pair.put("k", encode(entry.getKey()));
					pair.put("v", encode(entry.getValue()));
					entries.add(pair);
				}
				tag.put("dict", entries);
			}
			default -> throw new IllegalArgumentException("not plain: " + Values.typeName(value));
		}
		return tag;
	}

	private static ListTag encodeAll(List<Object> items) {
		ListTag list = new ListTag();
		items.forEach(item -> list.add(encode(item)));
		return list;
	}

	private static Object decode(CompoundTag tag) {
		if (tag.contains("int")) {
			return tag.getLongOr("int", 0);
		}
		if (tag.contains("float")) {
			return tag.getDoubleOr("float", 0);
		}
		if (tag.contains("str")) {
			return tag.getStringOr("str", "");
		}
		if (tag.contains("bool")) {
			return tag.getBooleanOr("bool", false);
		}
		if (tag.contains("pos")) {
			long[] xyz = tag.getLongArray("pos").orElse(new long[3]);
			return new Pos(xyz[0], xyz[1], xyz[2]);
		}
		if (tag.contains("list")) {
			return new PyList(decodeAll(tag.getListOrEmpty("list")));
		}
		if (tag.contains("tuple")) {
			return new PyTuple(decodeAll(tag.getListOrEmpty("tuple")));
		}
		if (tag.contains("dict")) {
			PyDict dict = new PyDict();
			fill(dict, tag.getListOrEmpty("dict"));
			return dict;
		}
		return PyNone.NONE;
	}

	private static List<Object> decodeAll(ListTag list) {
		List<Object> items = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			items.add(decode(list.getCompoundOrEmpty(i)));
		}
		return items;
	}

	private static void fill(PyDict dict, ListTag entries) {
		for (int i = 0; i < entries.size(); i++) {
			CompoundTag pair = entries.getCompoundOrEmpty(i);
			dict.put(decode(pair.getCompoundOrEmpty("k")), decode(pair.getCompoundOrEmpty("v")));
		}
	}
}
