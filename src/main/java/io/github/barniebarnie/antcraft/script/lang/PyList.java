package io.github.barniebarnie.antcraft.script.lang;

import java.util.ArrayList;
import java.util.List;

/** A script {@code list}: mutable, and equal to another only by {@link Values#eq}. */
public final class PyList {
	public final List<Object> items;

	public PyList() {
		this(new ArrayList<>());
	}

	/** Wraps {@code items}, which must be mutable; the list does not copy it. */
	public PyList(List<Object> items) {
		this.items = items;
	}

	@Override
	public String toString() {
		return Values.repr(this);
	}
}
