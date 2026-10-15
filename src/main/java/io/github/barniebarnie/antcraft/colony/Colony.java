package io.github.barniebarnie.antcraft.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;

/** A colony: its core block (storage), member bots, work radius and job board. */
public class Colony {
	public static final int DEFAULT_RADIUS = 32;

	public static final Codec<Colony> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.CODEC.fieldOf("id").forGetter(Colony::id),
		BlockPos.CODEC.fieldOf("core").forGetter(Colony::core),
		UUIDUtil.CODEC.listOf().optionalFieldOf("members", List.of()).forGetter(c -> List.copyOf(c.members)),
		JobBoard.CODEC.optionalFieldOf("jobs", new JobBoard()).forGetter(Colony::board),
		Codec.INT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(Colony::radius)
	).apply(i, Colony::new));

	private final UUID id;
	private final BlockPos core;
	private final Set<UUID> members;
	private final JobBoard board;
	private int radius;
	/** Game time of the last tree survey, or -1 if the colony has not surveyed yet. */
	long lastSurvey = -1;

	Colony(UUID id, BlockPos core, List<UUID> members, JobBoard board, int radius) {
		this.id = id;
		this.core = core;
		this.members = new LinkedHashSet<>(members);
		this.board = board;
		this.radius = radius;
	}

	public UUID id() {
		return this.id;
	}

	public BlockPos core() {
		return this.core;
	}

	public Set<UUID> members() {
		return this.members;
	}

	public JobBoard board() {
		return this.board;
	}

	/** How far from the core the colony looks for work. */
	public int radius() {
		return this.radius;
	}

	public void setRadius(int radius) {
		this.radius = radius;
		this.lastSurvey = -1;
	}
}
