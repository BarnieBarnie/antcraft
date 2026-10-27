package io.github.barniebarnie.antcraft.script.lang;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A script {@code dict}, in insertion order. Keys equal by Python's rules are one key ({@code 1}, {@code 1.0} and
 * {@code True}); the key kept is the first one stored, as in Python.
 */
public final class PyDict {
	/** By {@link Values#key}: the key as first stored, and its value. */
	private final LinkedHashMap<Object, Object[]> entries = new LinkedHashMap<>();
	private final StoreCheck check;

	/** Checks a key and value before they are stored; throws a {@link ScriptError} to refuse them. */
	@FunctionalInterface
	public interface StoreCheck {
		void check(Object key, Object value);
	}

	public PyDict() {
		this(null);
	}

	/**
	 * A dict that runs {@code check} on every key and value stored into it, such as a bot's {@code memory}, which holds
	 * plain data only. A refused store fails the script at its line.
	 */
	public PyDict(StoreCheck check) {
		this.check = check;
	}

	/** The value of {@code key}, or null when it has none. */
	public Object get(Object key) {
		Object[] entry = entries.get(Values.key(key));
		return entry == null ? null : entry[1];
	}

	public void put(Object key, Object value) {
		if (check != null) {
			check.check(key, value);
		}
		Object[] entry = entries.get(Values.key(key));
		if (entry == null) {
			entries.put(Values.key(key), new Object[] {key, value});
		} else {
			entry[1] = value;
		}
	}

	public boolean containsKey(Object key) {
		return entries.containsKey(Values.key(key));
	}

	/** Removes {@code key}; gives its value, or null when it had none. */
	public Object remove(Object key) {
		Object[] entry = entries.remove(Values.key(key));
		return entry == null ? null : entry[1];
	}

	public int size() {
		return entries.size();
	}

	/** A copy of the keys, in order. */
	public List<Object> keys() {
		List<Object> keys = new ArrayList<>(entries.size());
		entries.values().forEach(entry -> keys.add(entry[0]));
		return keys;
	}

	/** A copy of the entries, in order. */
	public List<Map.Entry<Object, Object>> items() {
		List<Map.Entry<Object, Object>> items = new ArrayList<>(entries.size());
		entries.values().forEach(entry -> items.add(Map.entry(entry[0], entry[1])));
		return items;
	}

	@Override
	public String toString() {
		return Values.repr(this);
	}
}
