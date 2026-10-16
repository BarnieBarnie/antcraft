package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

public enum JobType implements StringRepresentable {
	/** Chop the tree whose lowest log is at the job target. */
	CHOP_TREE,
	/** Mine the colony quarry layer at the job target's height. */
	MINE_LAYER,
	/** Harvest and replant the ripe crop at the job target. */
	HARVEST_CROP,
	/** Plant a sapling at the job target, where a felled tree stood that had no sapling to replant it. */
	REPLANT,
	/** Carry out the colony build plan whose first step is at the job target. */
	BUILD,
	/** Till the soil under the job target if needed and plant a crop there, in the colony field. */
	PLANT_CROP;

	public static final Codec<JobType> CODEC = StringRepresentable.fromEnum(JobType::values);

	@Override
	public String getSerializedName() {
		return this.name().toLowerCase(Locale.ROOT);
	}
}
