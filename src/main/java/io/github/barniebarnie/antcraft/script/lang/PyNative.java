package io.github.barniebarnie.antcraft.script.lang;

/**
 * A native function, by the name it is registered under in {@link Natives}. Holding the name rather than the Java
 * function keeps a running script plain data that can be saved.
 */
public record PyNative(String name) {
	@Override
	public String toString() {
		return "<built-in function " + name + ">";
	}
}
