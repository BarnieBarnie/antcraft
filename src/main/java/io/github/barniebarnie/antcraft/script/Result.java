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
	public static final String INTERRUPTED = "interrupted";
	public static final String STOPPED = "stopped";
	public static final String TIMEOUT = "timeout";

	public static Result fail(String reason) {
		return new Result(false, reason, PyNone.NONE);
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
