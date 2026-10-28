package io.github.barniebarnie.antcraft.script.lang;

import java.util.List;

/**
 * An iterator over a list, tuple, str, dict or range, as a {@code for} loop keeps it on the operand stack. Its state is
 * plain data (the value and a position), so a paused loop can be saved ({@link VmState}).
 */
public sealed interface Iter {
	/** The next item, or null when there is none left. */
	Object next();

	default String typeName() {
		return "iterator";
	}

	/** Reads the list as it is now at each step, as Python does, so items added while looping are visited too. */
	final class OfList implements Iter {
		final PyList list;
		int index;

		OfList(PyList list) {
			this.list = list;
		}

		@Override
		public Object next() {
			return index < list.items.size() ? list.items.get(index++) : null;
		}
	}

	final class OfTuple implements Iter {
		final PyTuple tuple;
		int index;

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
		final String string;
		int index;

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

	/**
	 * Over the keys, values or (key, value) tuples; a dict that changes size meanwhile is an error, as in Python.
	 */
	final class OfDict implements Iter {
		final PyDict dict;
		final PyDictView.Kind kind;
		final List<Object> keys;
		int index;

		OfDict(PyDict dict, PyDictView.Kind kind) {
			this(dict, kind, dict.keys());
		}

		/** Over {@code keys}, the dict's keys when the iteration started, as a saved one is loaded. */
		OfDict(PyDict dict, PyDictView.Kind kind, List<Object> keys) {
			this.dict = dict;
			this.kind = kind;
			this.keys = keys;
		}

		@Override
		public Object next() {
			if (dict.size() != keys.size()) {
				throw new ScriptError("dictionary changed size during iteration");
			}
			if (index >= keys.size()) {
				return null;
			}
			Object key = keys.get(index++);
			return switch (kind) {
				case KEYS -> key;
				case VALUES -> dict.get(key);
				case ITEMS -> PyTuple.of(key, dict.get(key));
			};
		}
	}

	/** Over a fixed list of items, such as what {@code reversed} gives. */
	final class OfItems implements Iter {
		final String typeName;
		final List<Object> items;
		int index;

		OfItems(String typeName, List<Object> items) {
			this.typeName = typeName;
			this.items = items;
		}

		@Override
		public Object next() {
			return index < items.size() ? items.get(index++) : null;
		}

		@Override
		public String typeName() {
			return typeName;
		}

		@Override
		public String toString() {
			return "<" + typeName + " object>";
		}
	}

	/** {@code enumerate}: (count, item) tuples of another iterator. */
	final class OfEnumerate implements Iter {
		final Iter items;
		long count;

		OfEnumerate(Iter items, long start) {
			this.items = items;
			this.count = start;
		}

		@Override
		public Object next() {
			Object next = items.next();
			return next == null ? null : PyTuple.of(count++, next);
		}

		@Override
		public String typeName() {
			return "enumerate";
		}

		@Override
		public String toString() {
			return "<enumerate object>";
		}
	}

	/** {@code zip}: tuples of the next item of each iterator, until the shortest is done. */
	final class OfZip implements Iter {
		final List<Iter> iters;

		OfZip(List<Iter> iters) {
			this.iters = iters;
		}

		@Override
		public Object next() {
			if (iters.isEmpty()) {
				return null;
			}
			Object[] items = new Object[iters.size()];
			for (int i = 0; i < items.length; i++) {
				items[i] = iters.get(i).next();
				if (items[i] == null) {
					return null;
				}
			}
			return PyTuple.of(items);
		}

		@Override
		public String typeName() {
			return "zip";
		}

		@Override
		public String toString() {
			return "<zip object>";
		}
	}

	final class OfRange implements Iter {
		final PyRange range;
		long index;

		OfRange(PyRange range) {
			this.range = range;
		}

		@Override
		public Object next() {
			return index < range.length() ? range.get(index++) : null;
		}
	}
}
