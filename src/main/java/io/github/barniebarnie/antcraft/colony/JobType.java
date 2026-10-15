package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import java.util.Locale;
import net.minecraft.util.StringRepresentable;

public enum JobType implements StringRepresentable {
	/** Chop the tree whose lowest log is at the job target. */
	CHOP_TREE;

	public static final Codec<JobType> CODEC = StringRepresentable.fromEnum(JobType::values);

	@Override
	public String getSerializedName() {
		return this.name().toLowerCase(Locale.ROOT);
	}
}
