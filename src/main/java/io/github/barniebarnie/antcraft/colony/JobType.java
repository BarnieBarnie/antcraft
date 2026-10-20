package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

public enum JobType implements StringRepresentable {
	/** Chop the tree whose lowest log is at the job target. */
	CHOP_TREE,
	/** Dig the mineshaft branch that starts at the job target. */
	MINE_BRANCH,
	/** Harvest and replant the ripe crop at the job target. */
	HARVEST_CROP,
	/** Plant a sapling at the job target, where a felled tree stood that had no sapling to replant it. */
	REPLANT,
	/** Carry out the colony build plan whose first step is at the job target. */
	BUILD,
	/** Till the soil under the job target if needed and plant a crop there, in the colony field. */
	PLANT_CROP,
	/**
	 * Dig out a batch of a colony build plan that only clears blocks, such as the ground above a
	 * terraform's height. Miners take it, and builders while the colony has no miner.
	 */
	CLEAR_AREA,
	/**
	 * Craft or smelt the job's item. At the core, a step of an order's plan from the core's stock; elsewhere, from
	 * the bot's own inventory at a station it carries (the job's tool) or one standing there.
	 */
	CRAFT,
	/** Carry what the colony wants from the container at the job target, such as a mineshaft landing chest, to the core. */
	HAUL,
	/** Turn the Bot Cores stored in the core at the job target into new bots. */
	ACTIVATE_BOT;

	public static final Codec<JobType> CODEC = StringRepresentable.fromEnum(JobType::values);

	/** Whether the work fills the bot's inventory, such as digging, chopping and harvesting. */
	public boolean yieldsItems() {
		return this == CHOP_TREE || this == MINE_BRANCH || this == HARVEST_CROP || this == CLEAR_AREA;
	}

	@Override
	public String getSerializedName() {
		return this.name().toLowerCase(Locale.ROOT);
	}
}
