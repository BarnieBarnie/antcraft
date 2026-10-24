package io.github.barniebarnie.antcraft.colony;

import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.Sites;
import io.github.barniebarnie.antcraft.work.Terraform;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Placing a blueprint for a colony to build, the one way in for {@code /antcraft colony build place} and the client's
 * {@code PlaceBlueprint} request: the server checks the player may act on the colony and that the site is free, levels
 * the ground under it first when it is uneven (see {@link Terraform}), then has the colony build the structure there.
 */
public final class BlueprintPlacement {
	/**
	 * How far below the layer a site stands on the levelling fills holes from: none, as the structure needs only that layer
	 * to stand on, and filling deeper would fill the air under thin ground for nothing.
	 */
	static final int FILL_DEPTH = 0;

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
		/** The blueprint names a palette its structure does not have. */
		NO_PALETTE,
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
		Optional<Refusal> refusal = SiteRules.of(colony).check(area);
		if (refusal.isPresent()) {
			return Either.left(refusal.get());
		}
		Blueprint print = blueprint.get();
		return Either.right(new PlacedBlueprint(new UUID(0, 0), id, print.name(), at, rotation, mirror, area, print.role(),
			print.chestsAt(extent, at, mirror, rotation), print.workSpotsAt(extent, at, mirror, rotation), List.of(), List.of(), Optional.empty(), false, true));
	}

	/**
	 * Places blueprint {@code id} at {@code at} for {@code colony}, mirrored then turned, if the player {@code mayAct} on it
	 * and the {@linkplain #site site} is free. When the ground under the site is uneven, or anything stands in it, the colony
	 * first levels it (see {@link Terraform#plan}), filling holes from {@link #FILL_DEPTH} below; the structure follows.
	 */
	public static Either<Refusal, PlacedBlueprint> place(ServerLevel level, Colony colony, boolean mayAct, Identifier id, BlockPos at,
			Rotation rotation, Mirror mirror) {
		return place(level, colony, mayAct, id, at, rotation, mirror, OptionalInt.empty());
	}

	/** {@link #place(ServerLevel, Colony, boolean, Identifier, BlockPos, Rotation, Mirror)} in {@code palette}, if given, rather than the blueprint's own. */
	public static Either<Refusal, PlacedBlueprint> place(ServerLevel level, Colony colony, boolean mayAct, Identifier id, BlockPos at,
			Rotation rotation, Mirror mirror, OptionalInt palette) {
		if (!mayAct) {
			return Either.left(Refusal.NOT_ALLOWED);
		}
		Optional<Blueprint> blueprint = Blueprints.get(id).map(print -> palette.isPresent() ? print.withPalette(palette.getAsInt()) : print);
		Optional<StructureTemplate> template = blueprint.flatMap(print -> level.getServer().getStructureTemplateManager().get(print.structure()));
		Either<Refusal, PlacedBlueprint> site = site(colony, id, blueprint, template.map(StructureTemplate::getSize), at, rotation, mirror);
		if (site.left().isPresent()) {
			return site;
		}
		PlacedBlueprint placed = site.right().orElseThrow();
		Either<Refusal, BuildPlan> structure = structure(template.orElseThrow(), blueprint.orElseThrow(), at, rotation, mirror);
		if (structure.left().isPresent()) {
			return Either.left(structure.left().get());
		}
		return Either.right(colony.placeBlueprint(placed, levelling(level::getBlockState, placed.area()), structure.right().orElseThrow()));
	}

	/**
	 * The plan that builds {@code blueprint} from {@code template} at {@code at}, mirrored then turned, in the palette the
	 * blueprint names: refused if the structure has no such palette or nothing to build.
	 */
	public static Either<Refusal, BuildPlan> structure(StructureTemplate template, Blueprint blueprint, BlockPos at, Rotation rotation, Mirror mirror) {
		if (blueprint.palette() >= RegionPlan.paletteCount(template)) {
			return Either.left(Refusal.NO_PALETTE);
		}
		return RegionPlan.load(template, at, mirror, rotation, blueprint.palette())
			.<Either<Refusal, BuildPlan>>map(Either::right).orElse(Either.left(Refusal.NOTHING_TO_BUILD));
	}

	/**
	 * The plan that levels the site {@code area} in {@code world}, or empty if it is level already: everything in the area
	 * is cleared, and holes in the layer it stands on are filled, down to {@link #FILL_DEPTH} below that layer, so flat ground
	 * with air or a cave under it needs nothing.
	 */
	public static Optional<BuildPlan> levelling(Function<BlockPos, BlockState> world, BoundingBox area) {
		int ground = area.minY() - 1;
		return Terraform.plan(world, new BlockPos(area.minX(), ground - FILL_DEPTH, area.minZ()),
			new BlockPos(area.maxX(), area.maxY(), area.maxZ()), ground);
	}
}
