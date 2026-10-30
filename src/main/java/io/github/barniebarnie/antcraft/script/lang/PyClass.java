package io.github.barniebarnie.antcraft.script.lang;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A class a script defined (docs/language/classes.md): its name, its base, and its attributes, the methods and class
 * attributes of its body and those set on it later. The base is another class, the name of a builtin exception type
 * (such as {@code Exception}) for an exception class, or null. Mutable, as {@code Ant.count += 1} changes it; equal only
 * to itself.
 */
public final class PyClass {
	final String name;
	/** A {@link PyClass}, the name of a builtin exception type, or null; set again only as a saved class is loaded. */
	@Nullable Object base;
	final Map<String, Object> attributes = new LinkedHashMap<>();

	PyClass(String name, @Nullable Object base) {
		this.name = name;
		this.base = base;
	}

	public String name() {
		return name;
	}

	/** The attribute {@code name} of this class or of the nearest base that has it, or null. */
	@Nullable Object lookup(String name) {
		for (PyClass c = this; c != null; c = c.base instanceof PyClass b ? b : null) {
			Object value = c.attributes.get(name);
			if (value != null) {
				return value;
			}
		}
		return null;
	}

	/** Whether this is {@code other} or derives from it. */
	boolean derivesFrom(PyClass other) {
		for (PyClass c = this; c != null; c = c.base instanceof PyClass b ? b : null) {
			if (c == other) {
				return true;
			}
		}
		return false;
	}

	/** The builtin exception type this class derives from, or null when it is not an exception class. */
	@Nullable String exceptionType() {
		PyClass c = this;
		while (c.base instanceof PyClass b) {
			c = b;
		}
		return c.base instanceof String type ? type : null;
	}

	@Override
	public String toString() {
		return "<class '" + name + "'>";
	}
}
