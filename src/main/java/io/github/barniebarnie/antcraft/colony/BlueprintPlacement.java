package io.github.barniebarnie.antcraft.colony;

import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.Sites;
import io.github.barniebarnie.antcraft.work.Terraform;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Placing a blueprint for a colony to build, the one way in for {@code /antcraft colony build place} and the client's
 * {@code PlaceBlueprint} request: the server checks the player may act on the colony and that the site is free, levels
 * the ground under it first when it is uneven (see {@link Terraform}), then has the colony build the structure there.
 */
public final class BlueprintPlacement {
	/** How far below the ground under a site the levelling fills holes from. */
	static final int FILL_DEPTH = 2;

	private BlueprintPlacement() {
	}

	/** Why a placement was refused; each has a message under {@link #key}. */
	public enum Refusal {
		/** The player is neither the colony's owner nor trusted (see {@link ColonyActions#mayAct}). */
		NOT_ALLOWED,
		/** There is no colony at the core the client named. */
		NO_COLONY,
		UNKNOWN_BLUEPRINT,
		/** The blueprint names a structure the server does not have. */
		NO_STRUCTURE,
		/** The structure holds nothing to build. */
		NOTHING_TO_BUILD,
		/** The site leaves the colony's territory. */
		OUTSIDE,
		OVERLAPS_CORE,
		OVERLAPS_MINESHAFT,
		/** The site overlaps a build, another placed blueprint, an area the colony uses, or blocks builders placed. */
		OVERLAPS_BUILD;

		public static final String KEY_PREFIX = "commands.antcraft.colony.build.place.";

		public String key() {
			return KEY_PREFIX + this.id();
		}

		/** Its name in lang keys, such as {@code overlaps_build}. */
		public String id() {
			return this.name().toLowerCase(Locale.ROOT);
		}

		public Component message() {
			return Component.translatable(this.key());
		}
	}

	/**
	 * Where blueprint {@code id}, if {@code blueprint} is known, built from a structure of {@code size}, if the server has
	 * it, would stand at {@code at}, mirrored then turned: refused if it leaves the colony's territory or overlaps the core,
	 * the mineshaft, another build or area the colony uses, or blocks builders placed, sideways at any height. Sites never
	 * stack, as the colony's own sites do not (see {@link Sites#sharesColumns}). The placement is not added to the colony.
	 */
	public static Either<Refusal, PlacedBlueprint> site(Colony colony, Identifier id, Optional<Blueprint> blueprint, Optional<Vec3i> size,
			BlockPos at, Rotation rotation, Mirror mirror) {
		if (blueprint.isEmpty()) {
			return Either.left(Refusal.UNKNOWN_BLUEPRINT);
		}
		if (size.isEmpty() || size.get().getX() <= 0 || size.get().getY() <= 0 || size.get().getZ() <= 0) {
			return Either.left(Refusal.NO_STRUCTURE);
		}
		Vec3i extent = size.get();
		BoundingBox area = BoundingBox.fromCorners(RegionPlan.placed(BlockPos.ZERO, extent, at, mirror, rotation),
			RegionPlan.placed(new BlockPos(extent.getX() - 1, extent.getY() - 1, extent.getZ() - 1), extent, at, mirror, rotation));
		if (!Territory.of(colony).contains(new BlockPos(area.minX(), area.minY(), area.minZ()), new BlockPos(area.maxX(), area.maxY(), area.maxZ()))) {
			return Either.left(Refusal.OUTSIDE);
		}
		BlockPos core = colony.core();
		if (Sites.sharesColumns(area, new BoundingBox(core))) {
			return Either.left(Refusal.OVERLAPS_CORE);
		}
		if (colony.mineshaft().filter(shaft -> Sites.sharesColumns(area, shaft.area())).isPresent()) {
			return Either.left(Refusal.OVERLAPS_MINESHAFT);
		}
		if (colony.occupied().stream().anyMatch(other -> Sites.sharesColumns(area, other))
				|| colony.placedBlocks().stream().anyMatch(pos -> Sites.sharesColumns(area, new BoundingBox(pos)))) {
			return Either.left(Refusal.OVERLAPS_BUILD);
		}
		Blueprint print = blueprint.get();
		return Either.right(new PlacedBlueprint(new UUID(0, 0), id, print.name(), at, rotation, mirror, area, print.role(),
			print.chestsAt(extent, at, mirror, rotation), print.workSpotsAt(extent, at, mirror, rotation), Optional.empty(), false));
	}

	/**
	 * Places blueprint {@code id} at {@code at} for {@code colony}, mirrored then turned, if the player {@code mayAct} on it
	 * and the {@linkplain #site site} is free. When the ground under the site is uneven, or anything stands in it, the colony
	 * first levels it (see {@link Terraform#plan}), filling holes from {@link #FILL_DEPTH} below; the structure follows.
	 */
	public static Either<Refusal, PlacedBlueprint> place(ServerLevel level, Colony colony, boolean mayAct, Identifier id, BlockPos at,
			Rotation rotation, Mirror mirror) {
		if (!mayAct) {
			return Either.left(Refusal.NOT_ALLOWED);
		}
		Optional<Blueprint> blueprint = Blueprints.get(id);
		Optional<StructureTemplate> template = blueprint.flatMap(print -> level.getServer().getStructureTemplateManager().get(print.structure()));
		Either<Refusal, PlacedBlueprint> site = site(colony, id, blueprint, template.map(StructureTemplate::getSize), at, rotation, mirror);
		if (site.left().isPresent()) {
			return site;
		}
		PlacedBlueprint placed = site.right().orElseThrow();
		Optional<BuildPlan> structure = RegionPlan.load(template.orElseThrow(), at, mirror, rotation, 0);
		if (structure.isEmpty()) {
			return Either.left(Refusal.NOTHING_TO_BUILD);
		}
		BoundingBox area = placed.area();
		int ground = area.minY() - 1;
		Optional<BuildPlan> levelling = Terraform.plan(level::getBlockState, new BlockPos(area.minX(), ground - FILL_DEPTH, area.minZ()),
			new BlockPos(area.maxX(), area.maxY(), area.maxZ()), ground);
		return Either.right(colony.placeBlueprint(placed, levelling, structure.get()));
	}
}
