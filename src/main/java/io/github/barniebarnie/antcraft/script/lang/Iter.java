package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;

/**
 * An iterator over a list, tuple, str, dict or range, as a {@code for} loop keeps it on the operand stack. Its state is
 * plain data (the value and a position), so a paused loop can be saved.
 */
public sealed interface Iter {
	/** The next item, or null when there is none left. */
	Object next();

	default String typeName() {
		return "iterator";
	}

	/** Reads the list as it is now at each step, as Python does, so items added while looping are visited too. */
	final class OfList implements Iter {
		private final PyList list;
		private int index;

		OfList(PyList list) {
			this.list = list;
		}

		@Override
		public Object next() {
			return index < list.items.size() ? list.items.get(index++) : null;
		}
	}

	final class OfTuple implements Iter {
		private final PyTuple tuple;
		private int index;

		OfTuple(PyTuple tuple) {
			this.tuple = tuple;
		}

		@Override
		public Object next() {
			return index < tuple.items().size() ? tuple.items().get(index++) : null;
		}
	}

	/** By code point, as Python counts characters. */
	final class OfString implements Iter {
		private final String string;
		private int index;

		OfString(String string) {
			this.string = string;
		}

		@Override
		public Object next() {
			if (index >= string.length()) {
				return null;
			}
			int c = string.codePointAt(index);
			index += Character.charCount(c);
			return Character.toString(c);
		}
	}

	/** Over the keys; a dict that changes size meanwhile is an error, as in Python. */
	final class OfDict implements Iter {
		private final PyDict dict;
		private final List<Object> keys;
		private int index;

		OfDict(PyDict dict) {
			this.dict = dict;
			this.keys = dict.keys();
		}

		@Override
		public Object next() {
			if (dict.size() != keys.size()) {
				throw new ScriptError("dictionary changed size during iteration");
			}
			return index < keys.size() ? keys.get(index++) : null;
		}
	}

	final class OfRange implements Iter {
		private final PyRange range;
		private long index;

		OfRange(PyRange range) {
			this.range = range;
		}

		@Override
		public Object next() {
			return index < range.length() ? range.get(index++) : null;
		}
	}
}
