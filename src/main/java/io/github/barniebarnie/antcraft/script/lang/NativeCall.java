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

	/** A call the native hands to the VM, whose result becomes the result of the native's own call. */
	record TailCall(Object callee, List<Object> args) {
	}

	/**
	 * Lets the VM finish this call by calling {@code callee} (a script function or a native) with {@code args}: return
	 * this from the native. The VM pushes a frame for a script function rather than calling back into Java, so a
	 * native that needs a script function's results (such as {@code sorted} with a {@code key}) hands the work to a
	 * function written in the script language, and the script stays pausable and plain data.
	 */
	public Object tailCall(Object callee, List<Object> args) {
		return new TailCall(callee, List.copyOf(args));
	}
}
