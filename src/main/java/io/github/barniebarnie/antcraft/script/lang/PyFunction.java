package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;

/**
 * A function defined by a script: its code and the values of its defaults, evaluated when the {@code def} ran, for its
 * last {@code defaults.size()} parameters.
 */
public record PyFunction(Code code, List<Object> defaults) {
	@Override
	public String toString() {
		return "<function " + code.name() + ">";
	}
}
