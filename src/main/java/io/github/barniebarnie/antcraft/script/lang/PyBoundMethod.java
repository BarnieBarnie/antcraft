package io.github.barniebarnie.antcraft.script.lang;

/**
 * A method of a script's class looked up on an instance, such as {@code miner.dig}: calling it calls {@code function}
 * with {@code self} as the first argument. {@code function} is a {@link PyFunction}, or the native {@code $init} that
 * {@code super().__init__} gives for {@code object} or a builtin exception type.
 */
public record PyBoundMethod(Object self, Object function) {
	@Override
	public String toString() {
		String name = function instanceof PyFunction f ? f.code().name() : "__init__";
		return "<bound method " + name + " of " + Values.repr(self) + ">";
	}
}
