package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;
import java.util.Map;

/**
 * One call of a {@link NativeFunction}: its name, positional arguments (for a method, the value it was called on comes
 * first) and keyword arguments in the order given.
 */
public record NativeCall(String name, List<Object> args, Map<String, Object> kwargs) {
	/** What a native returns to pause the script; the value given to {@link Vm#resume} becomes the call's result. */
	static final Object SUSPEND = new Object() {
		@Override
		public String toString() {
			return "<suspend>";
		}
	};

	/** Pauses the script after this call: return this from the native. */
	public Object suspend() {
		return SUSPEND;
	}
}
