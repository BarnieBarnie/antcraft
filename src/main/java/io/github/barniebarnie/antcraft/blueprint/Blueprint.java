package io.github.barniebarnie.antcraft.blueprint;

import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

/**
 * A building the colony knows how to build, loaded from {@code data/<namespace>/blueprint/<name>.json}: the structure
 * template it builds, its display name, and what it is for. Spots ({@link #chests}, {@link #workSpots}) are relative to the
 * structure's origin, as in the structure file; {@link #chestsAt} and {@link #workSpotsAt} give them for a placed copy.
 *
 * @param structure the {@code .nbt} structure template it builds, as {@code colony build load} takes it
 * @param name the lang key of its display name
 * @param role what the colony uses it for, if anything
 * @param chests where its chests stand
 * @param workSpots blocks a bot stands on or works at, such as farm plots or a generator's mining block
 * @param palette which of the structure's palettes (block variants, as {@code colony build load} takes) it builds, 0 if not given
 */
public record Blueprint(Identifier structure, String name, Optional<Role> role, List<BlockPos> chests, List<BlockPos> workSpots, int palette) {
	public static final Codec<Blueprint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Identifier.CODEC.fieldOf("structure").forGetter(Blueprint::structure),
		Codec.STRING.fieldOf("name").forGetter(Blueprint::name),
		Role.CODEC.optionalFieldOf("role").forGetter(Blueprint::role),
		BlockPos.CODEC.listOf().optionalFieldOf("chests", List.of()).forGetter(Blueprint::chests),
		BlockPos.CODEC.listOf().optionalFieldOf("work_spots", List.of()).forGetter(Blueprint::workSpots),
		Codec.intRange(0, Integer.MAX_VALUE).optionalFieldOf("palette", 0).forGetter(Blueprint::palette)
	).apply(instance, Blueprint::new));

	public Blueprint {
		chests = List.copyOf(chests);
		workSpots = List.copyOf(workSpots);
	}

	/** A blueprint that builds its structure's first palette. */
	public Blueprint(Identifier structure, String name, Optional<Role> role, List<BlockPos> chests, List<BlockPos> workSpots) {
		this(structure, name, role, chests, workSpots, 0);
	}

	/** This blueprint building {@code palette} of its structure instead. */
	public Blueprint withPalette(int palette) {
		return new Blueprint(this.structure, this.name, this.role, this.chests, this.workSpots, palette);
	}

	/** Reads a blueprint file; an error names what is wrong, such as a missing field or an unknown role. */
	public static DataResult<Blueprint> parse(JsonElement json) {
		return CODEC.parse(JsonOps.INSTANCE, json);
	}

	/** The chests of a copy built from a structure of {@code size} at {@code at}, mirrored then turned (see {@link RegionPlan#load}). */
	public List<BlockPos> chestsAt(Vec3i size, BlockPos at, Mirror mirror, Rotation rotation) {
		return placed(chests, size, at, mirror, rotation);
	}

	/** The work spots of a copy built from a structure of {@code size} at {@code at}, mirrored then turned (see {@link RegionPlan#load}). */
	public List<BlockPos> workSpotsAt(Vec3i size, BlockPos at, Mirror mirror, Rotation rotation) {
		return placed(workSpots, size, at, mirror, rotation);
	}

	private static List<BlockPos> placed(List<BlockPos> spots, Vec3i size, BlockPos at, Mirror mirror, Rotation rotation) {
		return spots.stream().map(spot -> RegionPlan.placed(spot, size, at, mirror, rotation)).toList();
	}

	/** What the colony uses a building for. */
	public enum Role implements StringRepresentable {
		/** Holds the colony's stock. */
		STORAGE("storage"),
		/** Grows crops on its work spots. */
		FARM("farm"),
		/** Yields blocks from its work spot, such as a cobblestone generator. */
		GENERATOR("generator");

		public static final Codec<Role> CODEC = StringRepresentable.fromEnum(Role::values);

		private final String name;

		Role(String name) {
			this.name = name;
		}

		@Override
		public String getSerializedName() {
			return name;
		}
	}
}
