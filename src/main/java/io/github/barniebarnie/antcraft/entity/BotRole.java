package io.github.barniebarnie.antcraft.entity;

import java.util.Locale;

/** The job a bot works on when it has no direct order. */
public enum BotRole {
	NONE,
	LUMBERJACK,
	/** Crafts colony orders at the core and activates stored Bot Cores. */
	CRAFTER,
	/** Digs out the colony quarry layer by layer and stores what it mines. */
	MINER,
	/** Harvests ripe crops around the colony and replants them. */
	FARMER;

	public String id() {
		return this.name().toLowerCase(Locale.ROOT);
	}

	public static BotRole byId(String id) {
		for (BotRole role : values()) {
			if (role.id().equals(id)) {
				return role;
			}
		}
		return NONE;
	}
}
