package io.github.barniebarnie.antcraft.entity;

import java.util.Locale;

/** The job a bot works on when it has no direct order. */
public enum BotRole {
	NONE,
	LUMBERJACK,
	/** Crafts colony orders at the core and activates stored Bot Cores. */
	CRAFTER,
	/** Digs branches off the colony mineshaft, lights them, and stores what it mines. */
	MINER,
	/** Harvests ripe crops around the colony and replants them. */
	FARMER,
	/** Arms itself from the core and patrols around it, fighting hostile mobs. */
	GUARD,
	/** Collects loose items around the colony and stores them in the core. */
	HAULER,
	/** Carries out the colony's build plans with materials from the core. */
	BUILDER;

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
