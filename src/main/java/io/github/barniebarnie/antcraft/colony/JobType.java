package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

public enum JobType implements StringRepresentable {
	/**
	 * Pick up the drops and replant the tree that stood at the job target once its {@link #CHOP_LOG} jobs are
	 * done, felling any of its logs left without one, such as all of them for a tree posted before log jobs.
	 */
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
	ACTIVATE_BOT,
	/** Chop the log at the job target, one of a tree's, so several bots fell a tree together. */
	CHOP_LOG,
	/** Dig out the ore vein a miner saw a block of at the job target, the job's area (see {@link io.github.barniebarnie.antcraft.work.OreVein}). */
	MINE_VEIN;

	public static final Codec<JobType> CODEC = StringRepresentable.fromEnum(JobType::values);
	/** Prefix of the lang keys that name the job types, shared by the colony screen and the commands. */
	public static final String KEY_PREFIX = "job.antcraft.";
	/** The lang key that says a bot has no job. */
	public static final String NO_JOB_KEY = "job.antcraft.none";

	/** Whether the work fills the bot's inventory, such as digging, chopping and harvesting. */
	public boolean yieldsItems() {
		return this == CHOP_TREE || this == CHOP_LOG || this == MINE_BRANCH || this == MINE_VEIN || this == HARVEST_CROP || this == CLEAR_AREA;
	}

	public String translationKey() {
		return KEY_PREFIX + this.getSerializedName();
	}

	/** What a bot on a job of this type is doing, or that it has no job, translated. */
	public static Component describe(Optional<JobType> job) {
		return Component.translatable(job.map(JobType::translationKey).orElse(NO_JOB_KEY));
	}

	@Override
	public String getSerializedName() {
		return this.name().toLowerCase(Locale.ROOT);
	}
}
