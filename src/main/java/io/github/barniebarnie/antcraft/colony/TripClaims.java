package io.github.barniebarnie.antcraft.colony;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which hauler is working which trip source (a chest, or an item to fetch or store), so two
 * haulers do not plan the same trip. Each bot holds one claim, kept until it claims another, is
 * released, or runs out; kept in memory only, since trips are short.
 */
public final class TripClaims {
	private record Claim(Object source, long until) {
	}

	private final Map<UUID, Claim> claims = new HashMap<>();

	/** Whether {@code bot} may plan a trip from {@code source}: no other bot holds a claim on it at game time {@code now}. */
	public boolean isFree(UUID bot, Object source, long now) {
		return this.claims.entrySet().stream()
			.noneMatch(claim -> !claim.getKey().equals(bot) && claim.getValue().source().equals(source) && now < claim.getValue().until());
	}

	/** {@code bot} claims {@code source} until game time {@code until}, giving up its earlier claim. */
	public void claim(UUID bot, Object source, long until) {
		this.claims.put(bot, new Claim(source, until));
	}

	public void release(UUID bot) {
		this.claims.remove(bot);
	}
}
