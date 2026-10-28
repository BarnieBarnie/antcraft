package io.github.barniebarnie.antcraft.script;

import io.github.barniebarnie.antcraft.script.lang.HostValue;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.Values;
import org.jspecify.annotations.Nullable;

/**
 * What an action returns (docs/scripting.md, Types): {@code ok}, the {@code reason} it failed (a code such as
 * {@code unreachable}, None on success) and the {@code value} it produced, if any. False in a test when the action failed.
 */
public record Result(boolean ok, @Nullable String reason, Object value) implements HostValue {
	public static final String TYPE = "Result";
	public static final Result OK = new Result(true, null, PyNone.NONE);

	public static final String UNREACHABLE = "unreachable";
	public static final String NOT_LOADED = "not_loaded";
	public static final String WRONG_DIMENSION = "wrong_dimension";
	public static final String INTERRUPTED = "interrupted";
	public static final String STOPPED = "stopped";
	public static final String TIMEOUT = "timeout";
	public static final String NO_PORTAL = "no_portal";
	public static final String OUT_OF_REACH = "out_of_reach";
	public static final String NO_TOOL = "no_tool";
	public static final String NO_ITEM = "no_item";
	public static final String NO_SPACE = "no_space";
	public static final String BLOCKED = "blocked";
	public static final String PROTECTED = "protected";
	public static final String TARGET_GONE = "target_gone";
	public static final String NO_RECIPE = "no_recipe";
	public static final String MISSING_INGREDIENTS = "missing_ingredients";
	public static final String NO_TABLE = "no_table";
	public static final String NO_FURNACE = "no_furnace";

	public static Result fail(String reason) {
		return new Result(false, reason, PyNone.NONE);
	}

	/** A failure that still produced {@code value}, such as how many items {@code pick_up} took before it ran out of space. */
	public static Result fail(String reason, Object value) {
		return new Result(false, reason, value);
	}

	public static Result ok(Object value) {
		return new Result(true, null, value);
	}

	@Override
	public String typeName() {
		return TYPE;
	}

	@Override
	public boolean truthy() {
		return ok;
	}

	@Override
	public @Nullable Object attribute(String name) {
		return switch (name) {
			case "ok" -> ok;
			case "reason" -> reason == null ? PyNone.NONE : reason;
			case "value" -> value;
			default -> null;
		};
	}

	@Override
	public String toString() {
		if (ok) {
			return value instanceof PyNone ? "Result(ok=True)" : "Result(ok=True, value=" + Values.repr(value) + ")";
		}
		return "Result(ok=False, reason=" + Values.repr(reason) + ")";
	}
}
