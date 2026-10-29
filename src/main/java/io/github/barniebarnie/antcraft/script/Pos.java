package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.Args;
import io.github.barniebarnie.antcraft.script.lang.Ast;
import io.github.barniebarnie.antcraft.script.lang.HostValue;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.PyTuple;
import io.github.barniebarnie.antcraft.script.lang.ScriptError;
import io.github.barniebarnie.antcraft.script.lang.Values;
import org.jspecify.annotations.Nullable;

/**
 * A block position in a script (docs/reference/types/pos.md): {@code x}, {@code y} and {@code z}; {@code +} and {@code -}
 * with another {@code Pos} or a tuple of three ints; {@code up(n=1)}, {@code down}, {@code north}, {@code south},
 * {@code east} and {@code west} as in the game (north is towards -z, east towards +x); {@code distance(other)}.
 * Immutable, so it is usable as a dict key.
 *
 * <p>{@code dimension} is the id of the dimension the position is in, such as {@code minecraft:overworld}, for a
 * position from {@code pos()}, {@code home()} or {@code portals()}, or null for one the script made, which means the
 * dimension the bot is in; natives refuse one from another dimension than the bot's ({@link ScriptRun#elsewhere}). What is made
 * from a position keeps its dimension. It takes no part in equality or printing: positions compare by their coordinates.
 */
public record Pos(long x, long y, long z, @Nullable String dimension) implements HostValue {
	public static final String TYPE = "Pos";

	/** A position in no particular dimension: the one the bot is in. */
	public Pos(long x, long y, long z) {
		this(x, y, z, null);
	}

	/** This position in {@code dimension}. */
	public Pos in(@Nullable String dimension) {
		return new Pos(x, y, z, dimension);
	}

	/** Registers the constructor {@code Pos(x, y, z, dimension=None)} and the methods of {@code Pos}. */
	public static void register(Natives natives) {
		natives.register(TYPE, call -> {
			Object[] args = Args.bind(TYPE, call, 1, "x", "y", "z", "dimension");
			if (!Args.absent(args[3]) && !(args[3] instanceof String)) {
				throw new ScriptError("Pos() takes a dimension id such as 'minecraft:overworld' or None, not '"
					+ Values.typeName(args[3]) + "'");
			}
			Pos pos = args[1] == null && args[2] == null ? of(args[0]) : new Pos(coordinate(args[0]), coordinate(args[1]), coordinate(args[2]));
			return Args.absent(args[3]) ? pos : pos.in((String) args[3]);
		});
		step(natives, "up", 0, 1, 0);
		step(natives, "down", 0, -1, 0);
		step(natives, "north", 0, 0, -1);
		step(natives, "south", 0, 0, 1);
		step(natives, "east", 1, 0, 0);
		step(natives, "west", -1, 0, 0);
		natives.registerMethod(TYPE, "distance", call -> {
			Object[] args = Args.bind("distance", call, 2, "self", "other");
			Pos self = (Pos) args[0];
			Pos other = of(args[1]);
			double dx = self.x - other.x;
			double dy = self.y - other.y;
			double dz = self.z - other.z;
			return Math.sqrt(dx * dx + dy * dy + dz * dz);
		});
	}

	private static long coordinate(@Nullable Object value) {
		Object given = value == null ? PyNone.NONE : value;
		if (!(given instanceof Long || given instanceof Boolean)) {
			throw new ScriptError("Pos() takes ints x, y and z, not '" + Values.typeName(given) + "'");
		}
		return Args.integer(given);
	}

	private static void step(Natives natives, String name, int dx, int dy, int dz) {
		natives.registerMethod(TYPE, name, call -> {
			Object[] args = Args.bind(name, call, 1, "self", "n");
			long n = Args.absent(args[1]) ? 1 : Args.integer(args[1]);
			Pos self = (Pos) args[0];
			return self.plus(new Pos(Math.multiplyExact(n, dx), Math.multiplyExact(n, dy), Math.multiplyExact(n, dz)), 1);
		});
	}

	/** {@code value} as a position: a {@code Pos}, or a tuple of three ints. */
	public static Pos of(Object value) {
		Pos pos = orNull(value);
		if (pos == null) {
			throw new ScriptError("a position is a Pos or a tuple of three ints, not '" + Values.typeName(value) + "'");
		}
		return pos;
	}

	private static @Nullable Pos orNull(Object value) {
		if (value instanceof Pos pos) {
			return pos;
		}
		if (value instanceof PyTuple tuple && tuple.items().size() == 3
			&& tuple.items().stream().allMatch(item -> item instanceof Long || item instanceof Boolean)) {
			return new Pos(Args.integer(tuple.items().get(0)), Args.integer(tuple.items().get(1)), Args.integer(tuple.items().get(2)));
		}
		return null;
	}

	private Pos plus(Pos other, int sign) {
		try {
			return new Pos(Math.addExact(x, sign * other.x), Math.addExact(y, sign * other.y), Math.addExact(z, sign * other.z),
				dimension != null ? dimension : other.dimension);
		} catch (ArithmeticException e) {
			throw new ScriptError("int overflow: the result does not fit in 64 bits");
		}
	}

	@Override
	public String typeName() {
		return TYPE;
	}

	@Override
	public @Nullable Object attribute(String name) {
		return switch (name) {
			case "x" -> x;
			case "y" -> y;
			case "z" -> z;
			case "dimension" -> dimension == null ? PyNone.NONE : dimension;
			default -> null;
		};
	}

	@Override
	public @Nullable Object binary(Ast.BinaryOp op, Object other, boolean reflected) {
		Pos pos = orNull(other);
		if (pos == null) {
			return null;
		}
		return switch (op) {
			case ADD -> plus(pos, 1);
			case SUB -> reflected ? pos.plus(this, -1) : plus(pos, -1);
			default -> null;
		};
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof Pos pos && x == pos.x && y == pos.y && z == pos.z;
	}

	@Override
	public int hashCode() {
		return Long.hashCode(x) * 961 + Long.hashCode(y) * 31 + Long.hashCode(z);
	}

	@Override
	public String toString() {
		return "Pos(" + x + ", " + y + ", " + z + ")";
	}
}
