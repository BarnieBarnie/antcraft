package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.SiteRules;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlueprintGhostTest {
	private static final BlockPos AT = new BlockPos(8, 64, 8);
	private static final Vec3i SIZE = new Vec3i(3, 1, 2);

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static BlockState stairs(Direction facing) {
		return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing);
	}

	/** A 3 by 1 by 2 blueprint: stairs facing north at its origin, stone in its far corner. */
	private static BlueprintSummary summary() {
		return new BlueprintSummary(Identifier.fromNamespaceAndPath("antcraft", "step"), "blueprint.antcraft.step", Optional.empty(), SIZE,
			List.of(new BlueprintSummary.Block(BlockPos.ZERO, stairs(Direction.NORTH)),
				new BlueprintSummary.Block(new BlockPos(2, 0, 1), Blocks.STONE.defaultBlockState())));
	}

	@Test
	void anUnturnedGhostDrawsTheBlocksWhereTheyStand() {
		BlueprintGhost ghost = new BlueprintGhost(summary(), AT, Rotation.NONE, Mirror.NONE);
		assertEquals(List.of(new BlueprintSummary.Block(AT, stairs(Direction.NORTH)),
			new BlueprintSummary.Block(AT.offset(2, 0, 1), Blocks.STONE.defaultBlockState())), ghost.blocks());
		assertEquals(new BoundingBox(8, 64, 8, 10, 64, 9), ghost.area());
	}

	@Test
	void aTurnedGhostTurnsThePositionsAndTheStates() {
		BlueprintGhost ghost = new BlueprintGhost(summary(), AT, Rotation.CLOCKWISE_90, Mirror.NONE);
		// Turned a quarter, the blueprint is 2 wide and 3 deep: its origin moves to the east side.
		assertEquals(List.of(new BlueprintSummary.Block(AT.offset(1, 0, 0), stairs(Direction.EAST)),
			new BlueprintSummary.Block(AT.offset(0, 0, 2), Blocks.STONE.defaultBlockState())), ghost.blocks());
		assertEquals(new BoundingBox(8, 64, 8, 9, 64, 10), ghost.area());
	}

	@Test
	void aMirroredGhostMirrorsThePositionsAndTheStates() {
		BlueprintGhost ghost = new BlueprintGhost(summary(), AT, Rotation.NONE, Mirror.LEFT_RIGHT);
		assertEquals(List.of(new BlueprintSummary.Block(AT.offset(0, 0, 1), stairs(Direction.SOUTH)),
			new BlueprintSummary.Block(AT.offset(2, 0, 0), Blocks.STONE.defaultBlockState())), ghost.blocks());
	}

	@Test
	void theGhostDrawsWhatThePlacementBuildsForEveryTurnAndMirror() {
		for (Rotation rotation : Rotation.values()) {
			for (Mirror mirror : Mirror.values()) {
				BlueprintGhost ghost = new BlueprintGhost(summary(), AT, rotation, mirror);
				List<BlueprintSummary.Block> expected = summary().blocks().stream()
					.map(block -> new BlueprintSummary.Block(RegionPlan.placed(block.pos(), SIZE, AT, mirror, rotation),
						block.state().mirror(mirror).rotate(rotation)))
					.toList();
				assertEquals(expected, ghost.blocks(), rotation + " " + mirror);
			}
		}
	}

	@Test
	void theGhostIsCentredOnTheBlockLookedAt() {
		BlockPos target = new BlockPos(20, 70, 20);
		Vec3i size = new Vec3i(5, 2, 3);
		assertEquals(new BlockPos(18, 70, 19), BlueprintGhost.origin(size, target, Rotation.NONE));
		assertEquals(new BlockPos(18, 70, 19), BlueprintGhost.origin(size, target, Rotation.CLOCKWISE_180));
		// Turned a quarter, it is 3 wide and 5 deep.
		assertEquals(new BlockPos(19, 70, 18), BlueprintGhost.origin(size, target, Rotation.CLOCKWISE_90));
		BlueprintGhost ghost = BlueprintGhost.centredOn(new BlueprintSummary(Identifier.fromNamespaceAndPath("antcraft", "hut"), "blueprint.antcraft.hut",
			Optional.empty(), size, List.of()), target, Rotation.COUNTERCLOCKWISE_90, Mirror.NONE);
		assertEquals(new BoundingBox(19, 70, 18, 21, 71, 22), ghost.area());
	}

	@Test
	void theGhostStandsAgainstTheFaceLookedAt() {
		BlockPos looked = new BlockPos(20, 70, 20);
		assertEquals(new BlockPos(20, 71, 20), BlueprintGhost.target(looked, Direction.UP));
		assertEquals(new BlockPos(21, 70, 20), BlueprintGhost.target(looked, Direction.EAST));
	}

	@Test
	void turningStepsThroughTheQuartersBothWays() {
		assertEquals(Rotation.CLOCKWISE_90, BlueprintGhost.turn(Rotation.NONE, 1));
		assertEquals(Rotation.COUNTERCLOCKWISE_90, BlueprintGhost.turn(Rotation.NONE, -1));
		assertEquals(Rotation.NONE, BlueprintGhost.turn(Rotation.COUNTERCLOCKWISE_90, 1));
		assertEquals(Mirror.LEFT_RIGHT, BlueprintGhost.flip(Mirror.NONE));
		assertEquals(Mirror.NONE, BlueprintGhost.flip(Mirror.LEFT_RIGHT));
	}

	private static final int RADIUS = 16;

	/** The rules of a colony with its core at 0, 64, 0 and nothing else. */
	private static SiteRules rules() {
		return new SiteRules(new BlockPos(0, 64, 0), RADIUS, Optional.empty(), Optional.empty(), List.of(), List.of());
	}

	@Test
	void withNoColonyKnownTheGhostIsRefused() {
		assertEquals(Optional.of(BlueprintPlacement.Refusal.NO_COLONY), new BlueprintGhost(summary(), AT, Rotation.NONE, Mirror.NONE).refusal(Optional.empty()));
	}

	@Test
	void aGhostOfABlueprintWithoutAStructureIsRefused() {
		BlueprintSummary empty = new BlueprintSummary(Identifier.fromNamespaceAndPath("antcraft", "gone"), "blueprint.antcraft.gone", Optional.empty(),
			Vec3i.ZERO, List.of());
		assertEquals(Optional.of(BlueprintPlacement.Refusal.NO_STRUCTURE),
			new BlueprintGhost(empty, AT, Rotation.NONE, Mirror.NONE).refusal(Optional.of(rules())));
	}

	@Test
	void theGhostAsksTheColonysRules() {
		Optional<SiteRules> rules = Optional.of(rules());
		assertEquals(Optional.empty(), new BlueprintGhost(summary(), AT, Rotation.NONE, Mirror.NONE).refusal(rules));
		assertEquals(Optional.of(BlueprintPlacement.Refusal.OVERLAPS_CORE),
			new BlueprintGhost(summary(), new BlockPos(-1, 64, -1), Rotation.NONE, Mirror.NONE).refusal(rules));
		assertEquals(Optional.of(BlueprintPlacement.Refusal.OUTSIDE),
			new BlueprintGhost(summary(), new BlockPos(RADIUS, 64, 0), Rotation.NONE, Mirror.NONE).refusal(rules));
	}
}
