package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A function defined by a script: its code, the values of its defaults, evaluated when the {@code def} ran, for its last
 * {@code defaults.size()} parameters, and the module that defined it, whose globals it sees. A builtin written in the
 * script language has no module: it sees the globals of the script's main module.
 */
public record PyFunction(Code code, List<Object> defaults, @Nullable PyModule module) {
	@Override
	public String toString() {
		return "<function " + code.name() + ">";
	}
}
