package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GhostCacheTest {
	private final List<String> computed = new ArrayList<>();
	private final GhostCache<String, String> cache = new GhostCache<>();

	private String get(String key) {
		return this.cache.get(key, k -> {
			this.computed.add(k);
			return k.toUpperCase();
		});
	}

	@Test
	void aValueIsWorkedOutOnceWhileItsKeyStaysTheSame() {
		String first = get("a");
		for (int frame = 0; frame < 3; frame++) {
			// An equal key, not the same object: a ghost sent again unchanged is not worked out again.
			assertSame(first, get(new String("a")));
			this.cache.endFrame();
		}
		assertEquals(List.of("a"), this.computed);
	}

	@Test
	void aChangedKeyIsWorkedOutAgain() {
		assertEquals("A", get("a"));
		this.cache.endFrame();
		assertEquals("B", get("b"));
		assertEquals(List.of("a", "b"), this.computed);
	}

	@Test
	void severalKeysAreKeptAtOnce() {
		get("a");
		get("b");
		this.cache.endFrame();
		get("a");
		get("b");
		assertEquals(List.of("a", "b"), this.computed);
	}

	@Test
	void aValueForgottenAfterAChangeIsWorkedOutAgainForTheSameKey() {
		get("a");
		get("b");
		// Something the values were worked out from changed, such as the biomes near a ghost: only the values it touches go.
		this.cache.forgetIf((key, value) -> value.equals("A"));
		assertEquals(1, this.cache.size());
		get("a");
		get("b");
		assertEquals(List.of("a", "b", "a"), this.computed);
	}

	@Test
	void aKeyNotUsedInAFrameIsForgotten() {
		get("a");
		get("b");
		this.cache.endFrame();
		get("a");
		this.cache.endFrame();
		assertEquals(1, this.cache.size());
		get("b");
		get("a");
		assertEquals(List.of("a", "b", "b"), this.computed);
	}
}
