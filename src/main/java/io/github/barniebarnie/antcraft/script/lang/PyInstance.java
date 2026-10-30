package io.github.barniebarnie.antcraft.script.lang;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An instance of a {@link PyClass}, with its own attributes. An instance of an exception class keeps the arguments it was
 * made with in {@code args}, as in Python, and its message is made of them. Mutable; equal only to itself.
 */
public final class PyInstance {
	/** Set again only as a saved instance is loaded. */
	PyClass type;
	final Map<String, Object> attributes = new LinkedHashMap<>();

	PyInstance(PyClass type) {
		this.type = type;
	}

	/** {@code str(e)} of an exception: its one argument, its arguments as a tuple, or empty without any. */
	String message() {
		Object args = attributes.get("args");
		if (args instanceof PyTuple tuple && tuple.items().size() < 2) {
			return tuple.items().isEmpty() ? "" : Values.str(tuple.items().getFirst());
		}
		return args == null ? "" : Values.str(args);
	}

	/** {@code <Miner object>}, or for an exception its type and arguments: {@code NoOre('none left')}. */
	@Override
	public String toString() {
		if (type.exceptionType() == null) {
			return "<" + type.name + " object>";
		}
		String args = attributes.get("args") instanceof PyTuple tuple ? Values.repr(tuple) : "()";
		return type.name + (args.endsWith(",)") ? args.substring(0, args.length() - 2) + ")" : args);
	}
}
