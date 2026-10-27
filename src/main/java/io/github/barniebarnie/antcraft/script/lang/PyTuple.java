package io.github.barniebarnie.antcraft.script.lang;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A script {@code tuple}: an unmodifiable list of values. Its Java {@code equals} is only used for dict keys, whose
 * parts {@link Values#key} has made comparable.
 */
public record PyTuple(List<Object> items) {
	public static final PyTuple EMPTY = new PyTuple(List.of());

	public PyTuple {
		items = Collections.unmodifiableList(items);
	}

	public static PyTuple of(Object... items) {
		return new PyTuple(Arrays.asList(items.clone()));
	}

	@Override
	public String toString() {
		return Values.repr(this);
	}
}
