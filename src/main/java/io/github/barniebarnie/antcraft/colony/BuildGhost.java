package io.github.barniebarnie.antcraft.colony;

import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What is left to build of a blueprint a player placed: the blocks its structure still has to place, as the world stands.
 * The server sends these to the colony's players nearby, whose clients draw them as ghosts and cancel a build by clicking
 * its ghost ({@code ColonyNetworking.CancelBuild}).
 *
 * @param core the core of the colony building it
 * @param project the colony project that builds it, which cancelling drops
 * @param name the lang key of the blueprint's name
 * @param blocks the blocks not placed yet, where they go
 */
public record BuildGhost(BlockPos core, UUID project, String name, List<BlueprintSummary.Block> blocks) {
	public static final StreamCodec<RegistryFriendlyByteBuf, BuildGhost> STREAM_CODEC = StreamCodec.composite(
		BlockPos.STREAM_CODEC, BuildGhost::core,
		UUIDUtil.STREAM_CODEC, BuildGhost::project,
		ByteBufCodecs.stringUtf8(256), BuildGhost::name,
		BlueprintSummary.Block.STREAM_CODEC.apply(ByteBufCodecs.list()), BuildGhost::blocks,
		BuildGhost::new);

	public BuildGhost {
		blocks = List.copyOf(blocks);
	}

	/**
	 * The ghosts of {@code colony}'s placed blueprints still being built, oldest first, each with the blocks its structure
	 * has not placed in {@code world} yet; blocks to clear show nothing. While the site is levelled the whole structure is
	 * left. Builds with nothing left to place have no ghost.
	 */
	public static List<BuildGhost> of(Colony colony, Function<BlockPos, BlockState> world) {
		return colony.blueprints().stream()
			.filter(placed -> !placed.finished())
			.map(placed -> new BuildGhost(colony.core(), placed.project(), placed.name(), left(structure(colony, placed), world)))
			.filter(ghost -> !ghost.blocks().isEmpty())
			.toList();
	}

	/** The plans that build {@code placed}'s structure: the one waiting on the levelling, else its project's. */
	private static List<BuildPlan> structure(Colony colony, PlacedBlueprint placed) {
		return placed.waiting().map(List::of).orElseGet(() -> colony.projects().stream()
			.filter(project -> project.id().equals(placed.project()))
			.findFirst()
			.map(colony::plans)
			.orElse(List.of()));
	}

	private static List<BlueprintSummary.Block> left(List<BuildPlan> plans, Function<BlockPos, BlockState> world) {
		return plans.stream()
			.flatMap(plan -> plan.steps().stream())
			.filter(step -> !step.isClear() && !step.isDone(world.apply(step.pos())))
			.map(step -> new BlueprintSummary.Block(step.pos(), step.state()))
			.distinct()
			.toList();
	}

	/** The ghost whose block the ray from {@code from} to {@code to} passes through first, if any. */
	public static Optional<BuildGhost> lookedAt(List<BuildGhost> ghosts, Vec3 from, Vec3 to) {
		record Hit(BuildGhost ghost, double distance) {
		}
		return ghosts.stream()
			.flatMap(ghost -> ghost.blocks().stream()
				.flatMap(block -> new AABB(block.pos()).clip(from, to).stream())
				.map(point -> new Hit(ghost, point.distanceToSqr(from))))
			.min(Comparator.comparingDouble(Hit::distance))
			.map(Hit::ghost);
	}
}
