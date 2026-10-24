package io.github.barniebarnie.antcraft.blueprint;

import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.SiteRules;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * A blueprint as the client previews it before placing: standing at {@code at}, its lowest corner, mirrored then turned the
 * way a placement is (see {@link BlueprintPlacement#place}), so the ghost shows what the colony would build.
 */
public record BlueprintGhost(BlueprintSummary blueprint, BlockPos at, Rotation rotation, Mirror mirror) {
	/** The ghost of {@code blueprint} centred sideways on {@code target}, standing on its level (see {@link #origin}). */
	public static BlueprintGhost centredOn(BlueprintSummary blueprint, BlockPos target, Rotation rotation, Mirror mirror) {
		return new BlueprintGhost(blueprint, origin(blueprint.size(), target, rotation), rotation, mirror);
	}

	/** The block a ghost is centred on when the player looks at {@code face} of {@code looked}: the one in front, as for placing a block. */
	public static BlockPos target(BlockPos looked, Direction face) {
		return looked.relative(face);
	}

	/**
	 * Where a structure of {@code size}, turned by {@code rotation}, has its lowest corner when it is centred sideways on
	 * {@code target} with its bottom layer at {@code target}'s height. Mirroring does not change its footprint.
	 */
	public static BlockPos origin(Vec3i size, BlockPos target, Rotation rotation) {
		boolean quarter = rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90;
		int width = quarter ? size.getZ() : size.getX();
		int depth = quarter ? size.getX() : size.getZ();
		return target.offset(-width / 2, 0, -depth / 2);
	}

	/** {@code rotation} turned a quarter {@code steps} times, clockwise for positive steps. */
	public static Rotation turn(Rotation rotation, int steps) {
		Rotation quarter = steps >= 0 ? Rotation.CLOCKWISE_90 : Rotation.COUNTERCLOCKWISE_90;
		Rotation turned = rotation;
		for (int i = 0; i < Math.abs(steps) % 4; i++) {
			turned = turned.getRotated(quarter);
		}
		return turned;
	}

	/** The other mirroring: none or left to right. Front to back is left to right turned half round, so two are enough. */
	public static Mirror flip(Mirror mirror) {
		return mirror == Mirror.NONE ? Mirror.LEFT_RIGHT : Mirror.NONE;
	}

	/** The blocks to draw: the blueprint's, where the colony would place them and turned as it would. */
	public List<BlueprintSummary.Block> blocks() {
		return this.blueprint.blocks().stream()
			.map(block -> new BlueprintSummary.Block(RegionPlan.placed(block.pos(), this.blueprint.size(), this.at, this.mirror, this.rotation),
				block.state().mirror(this.mirror).rotate(this.rotation)))
			.toList();
	}

	/** The box the structure fills; the server checks the same box (see {@link BlueprintPlacement#site}). */
	public BoundingBox area() {
		Vec3i size = this.blueprint.size();
		return BoundingBox.fromCorners(RegionPlan.placed(BlockPos.ZERO, size, this.at, this.mirror, this.rotation),
			RegionPlan.placed(new BlockPos(size.getX() - 1, size.getY() - 1, size.getZ() - 1), size, this.at, this.mirror, this.rotation));
	}

	/**
	 * Why the server would refuse placing it here, as far as the client can tell from the {@code rules} of the colony it
	 * would be placed for: none known, no structure, or the colony's own reasons (see {@link SiteRules#check}).
	 */
	public Optional<BlueprintPlacement.Refusal> refusal(Optional<SiteRules> rules) {
		if (rules.isEmpty()) {
			return Optional.of(BlueprintPlacement.Refusal.NO_COLONY);
		}
		Vec3i size = this.blueprint.size();
		if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
			return Optional.of(BlueprintPlacement.Refusal.NO_STRUCTURE);
		}
		return rules.get().check(this.area());
	}
}
