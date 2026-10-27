package io.github.barniebarnie.antcraft.script.lang;

/**
 * What {@code dict.keys()}, {@code values()} and {@code items()} give: a live view of the dict, which shows its entries
 * as they are when it is used, as in Python.
 */
public record PyDictView(PyDict dict, Kind kind) {
	public enum Kind {
		KEYS("dict_keys"),
		VALUES("dict_values"),
		ITEMS("dict_items");

		final String typeName;

		Kind(String typeName) {
			this.typeName = typeName;
		}
	}

	@Override
	public String toString() {
		return Values.repr(this);
	}

	// A view is equal only to itself in Java; Values.eq compares views as Python does.
	@Override
	public boolean equals(Object other) {
		return this == other;
	}

	@Override
	public int hashCode() {
		return System.identityHashCode(this);
	}
}
