package io.github.barniebarnie.antcraft.script.lang;

/**
 * What {@code super()} gives in a method of class {@code type}: its attributes are looked up from the base of
 * {@code type} on, and its methods are bound to {@code self}.
 */
public record PySuper(PyClass type, Object self) {
	@Override
	public String toString() {
		return "<super: " + type + ", " + Values.repr(self) + ">";
	}
}
