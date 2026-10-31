package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A function defined by a script: its code, the values of its defaults, evaluated when the {@code def} ran, for its last
 * {@code defaults.size()} parameters, and the module that defined it, whose globals it sees. A builtin written in the
 * script language has no module: it sees the globals of the script's main module. A method has the class whose body
 * defined it as its {@code owner}, which its {@code super()} starts from; any other function has none. Its
 * {@code closure} holds the {@link Cell}s of the variables of the functions around it that it uses
 * ({@link Code#freeSlots}).
 */
public record PyFunction(Code code, List<Object> defaults, @Nullable PyModule module, @Nullable PyClass owner,
		List<Cell> closure) {
	public PyFunction(Code code, List<Object> defaults, @Nullable PyModule module) {
		this(code, defaults, module, null, List.of());
	}

	@Override
	public String toString() {
		return "<function " + code.name() + ">";
	}
}
