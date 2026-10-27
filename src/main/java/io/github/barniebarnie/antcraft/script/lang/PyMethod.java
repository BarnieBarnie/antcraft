package io.github.barniebarnie.antcraft.script.lang;

/**
 * A method looked up on a value, such as {@code xs.append}: calling it calls the native registered for the value's type
 * name and {@code name} (see {@link Natives#registerMethod}), with {@code self} as the first argument.
 */
public record PyMethod(Object self, String type, String name) {
	@Override
	public String toString() {
		return "<built-in method " + name + " of " + type + " object>";
	}
}
