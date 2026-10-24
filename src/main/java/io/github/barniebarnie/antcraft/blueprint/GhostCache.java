package io.github.barniebarnie.antcraft.blueprint;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * What the ghosts drawn each frame are worked out to, kept while they stay the same: a value is worked out once for a key
 * and given again for any equal key, until a frame goes by without it ({@link #endFrame()}). Keys are compared with
 * {@code equals} only, never hashed, so a key holding a ghost's whole block list is cheap while it is the same list. Meant
 * for the few ghosts on screen at once: lookups go through every kept key.
 *
 * @param <K> what a value is worked out from
 * @param <V> what it is worked out to
 */
public final class GhostCache<K, V> {
	private final List<Entry<K, V>> entries = new ArrayList<>();

	private static final class Entry<K, V> {
		private final K key;
		private final V value;
		private boolean used = true;

		private Entry(K key, V value) {
			this.key = key;
			this.value = value;
		}
	}

	/** The value kept for a key equal to {@code key}, or {@code compute} applied to it, kept from now on. */
	public V get(K key, Function<? super K, ? extends V> compute) {
		for (Entry<K, V> entry : this.entries) {
			if (entry.key.equals(key)) {
				entry.used = true;
				return entry.value;
			}
		}
		Entry<K, V> entry = new Entry<>(key, compute.apply(key));
		this.entries.add(entry);
		return entry.value;
	}

	/** Forgets each value not asked for since the last call, once a frame is drawn. */
	public void endFrame() {
		this.entries.removeIf(entry -> !entry.used);
		this.entries.forEach(entry -> entry.used = false);
	}

	/**
	 * Forgets each value {@code which} holds for, so it is worked out again the next time it is asked for: for a change
	 * the keys do not show, such as the biomes of a chunk near a ghost loading after its colours were worked out.
	 */
	public void forgetIf(BiPredicate<? super K, ? super V> which) {
		this.entries.removeIf(entry -> which.test(entry.key, entry.value));
	}

	/** How many values are kept. */
	public int size() {
		return this.entries.size();
	}
}
