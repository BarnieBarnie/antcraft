package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Terraform;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuildGhostTest {
	private static final Identifier HUT = Identifier.fromNamespaceAndPath("antcraft", "hut");
	private static final Blueprint BLUEPRINT = new Blueprint(HUT, "blueprint.antcraft.hut", Optional.empty(), List.of(), List.of());
	private static final BlockPos AT = new BlockPos(8, 64, 8);
	/** Set once the registries are bootstrapped. */
	private static BlockState STONE;
	private static BlockState PLANKS;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		STONE = Blocks.STONE.defaultBlockState();
		PLANKS = Blocks.OAK_PLANKS.defaultBlockState();
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), new BlockPos(0, 64, 0), List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(),
			Optional.empty(), List.of(), Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
	}

	/** The hut's structure: stone at its origin, planks on it, and a block of air to clear beside it. */
	private static BuildPlan structure() {
		return new BuildPlan(List.of(new BuildPlan.Step(AT, STONE), new BuildPlan.Step(AT.above(), PLANKS), BuildPlan.Step.clear(AT.east())));
	}

	private static PlacedBlueprint place(Colony colony, Optional<BuildPlan> levelling) {
		PlacedBlueprint site = BlueprintPlacement.site(colony, HUT, Optional.of(BLUEPRINT), Optional.of(new Vec3i(2, 2, 1)), AT, Rotation.NONE, Mirror.NONE)
			.right().orElseThrow();
		return colony.placeBlueprint(site, levelling, structure());
	}

	/** A world of air, but for the blocks in {@code blocks}; the clear step beside the hut holds dirt. */
	private static Function<BlockPos, BlockState> world(Map<BlockPos, BlockState> blocks) {
		Map<BlockPos, BlockState> all = new HashMap<>(blocks);
		all.putIfAbsent(AT.east(), Blocks.DIRT.defaultBlockState());
		return pos -> all.getOrDefault(pos, Blocks.AIR.defaultBlockState());
	}

	@Test
	void aGhostHoldsTheBlocksOfAPlacedBuildThatAreNotPlacedYet() {
		Colony colony = colony();
		PlacedBlueprint placed = place(colony, Optional.empty());
		List<BuildGhost> ghosts = BuildGhost.of(colony, world(Map.of()));
		// Clearing a block leaves nothing to see, so only the blocks to place show.
		assertEquals(List.of(new BuildGhost(colony.core(), placed.project(), "blueprint.antcraft.hut",
			List.of(new BlueprintSummary.Block(AT, STONE), new BlueprintSummary.Block(AT.above(), PLANKS)))), ghosts);
	}

	@Test
	void aPlacedStepLeavesTheGhost() {
		Colony colony = colony();
		place(colony, Optional.empty());
		List<BuildGhost> ghosts = BuildGhost.of(colony, world(Map.of(AT, STONE)));
		assertEquals(List.of(List.of(new BlueprintSummary.Block(AT.above(), PLANKS))), ghosts.stream().map(BuildGhost::blocks).toList());
		// With every block placed nothing is left to show.
		assertEquals(List.of(), BuildGhost.of(colony, world(Map.of(AT, STONE, AT.above(), PLANKS))));
	}

	@Test
	void whileTheSiteIsLevelledTheGhostShowsTheStructure() {
		Colony colony = colony();
		Optional<BuildPlan> levelling = Terraform.plan(pos -> pos.getY() >= AT.getY() ? Blocks.DIRT.defaultBlockState() : STONE, AT, AT.offset(1, 1, 0),
			AT.getY() - 1);
		assertTrue(levelling.isPresent());
		place(colony, levelling);
		assertEquals(List.of(List.of(new BlueprintSummary.Block(AT, STONE), new BlueprintSummary.Block(AT.above(), PLANKS))),
			BuildGhost.of(colony, world(Map.of())).stream().map(BuildGhost::blocks).toList());
	}

	@Test
	void buildsPlayersDidNotPlaceAndFinishedOnesHaveNoGhost() {
		Colony colony = colony();
		colony.addBuild(new BuildPlan(List.of(new BuildPlan.Step(AT.offset(0, 0, 4), STONE))));
		assertEquals(List.of(), BuildGhost.of(colony, world(Map.of())));

		PlacedBlueprint placed = place(colony, Optional.empty());
		colony.cancelProject(placed.project());
		assertEquals(List.of(), BuildGhost.of(colony, world(Map.of())));
	}

	@Test
	void theGhostPayloadRoundTrips() {
		ColonyNetworking.BuildGhosts payload = new ColonyNetworking.BuildGhosts(List.of(
			new BuildGhost(new BlockPos(1, 2, 3), UUID.randomUUID(), "blueprint.antcraft.hut",
				List.of(new BlueprintSummary.Block(AT, STONE), new BlueprintSummary.Block(AT.above(), PLANKS))),
			new BuildGhost(new BlockPos(1, 2, 3), UUID.randomUUID(), "blueprint.antcraft.farm", List.of())));
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ColonyNetworking.BuildGhosts.STREAM_CODEC.encode(buf, payload);
		assertEquals(payload, ColonyNetworking.BuildGhosts.STREAM_CODEC.decode(buf));
		assertEquals(0, buf.readableBytes());
	}

	/**
	 * The blocks left of a large build, in many states, go in the compact encoding of blueprint blocks: well under the
	 * payload limit, where a full position and state per block would not fit.
	 */
	@Test
	void theGhostPayloadOfALargeBuildStaysUnderTheLimit() {
		List<BlockState> states = BuiltInRegistries.BLOCK.stream().filter(block -> !block.defaultBlockState().isAir())
			.flatMap(block -> block.getStateDefinition().getPossibleStates().stream()).limit(20000).toList();
		List<BlueprintSummary.Block> blocks = new ArrayList<>();
		for (int x = 47; x >= 0; x--) {
			for (int y = 0; y < 48; y++) {
				for (int z = 0; z < 48; z++) {
					blocks.add(new BlueprintSummary.Block(new BlockPos(-30_000 + x, -60 + y, 1_000_000 + z), states.get(blocks.size() % states.size())));
				}
			}
		}
		ColonyNetworking.BuildGhosts payload = new ColonyNetworking.BuildGhosts(List.of(
			new BuildGhost(new BlockPos(1, 2, 3), UUID.randomUUID(), "blueprint.antcraft.castle", blocks)));
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ColonyNetworking.BuildGhosts.STREAM_CODEC.encode(buf, payload);
		assertTrue(buf.readableBytes() < ColonyNetworking.PAYLOAD_LIMIT / 2, buf.readableBytes() + " bytes");
		ColonyNetworking.BuildGhosts decoded = ColonyNetworking.BuildGhosts.STREAM_CODEC.decode(buf);
		assertEquals(0, buf.readableBytes());
		assertEquals(payload, decoded);
		assertEquals(new HashSet<>(blocks), new HashSet<>(decoded.builds().getFirst().blocks()));
	}

	/**
	 * The ghosts of a build too large for one payload are capped: each keeps its lowest layers, those built next, and a
	 * small ghost beside it is kept whole, so the payload stays under the limit.
	 */
	@Test
	void theGhostsOfAVeryLargeBuildAreCappedUnderTheLimit() {
		List<BlockState> states = BuiltInRegistries.BLOCK.stream().filter(block -> !block.defaultBlockState().isAir())
			.flatMap(block -> block.getStateDefinition().getPossibleStates().stream()).limit(20000).toList();
		List<BlueprintSummary.Block> blocks = new ArrayList<>();
		for (int y = 0; y < 60; y++) {
			for (int z = 0; z < 100; z++) {
				for (int x = 0; x < 100; x++) {
					blocks.add(new BlueprintSummary.Block(new BlockPos(x, -60 + y, z), states.get((int) ((blocks.size() * 7919L) % states.size()))));
				}
			}
		}
		BuildGhost big = new BuildGhost(new BlockPos(1, 2, 3), UUID.randomUUID(), "blueprint.antcraft.castle", blocks);
		BuildGhost small = new BuildGhost(new BlockPos(1, 2, 3), UUID.randomUUID(), "blueprint.antcraft.hut",
			List.of(new BlueprintSummary.Block(AT, STONE), new BlueprintSummary.Block(AT.above(), PLANKS)));
		RegistryFriendlyByteBuf whole = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ColonyNetworking.BuildGhosts.STREAM_CODEC.encode(whole, new ColonyNetworking.BuildGhosts(List.of(big, small)));
		assertTrue(whole.readableBytes() > ColonyNetworking.PAYLOAD_LIMIT, "the build fits uncapped: " + whole.readableBytes() + " bytes");

		ColonyNetworking.BuildGhosts payload = ColonyNetworking.BuildGhosts.capped(List.of(big, small));
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ColonyNetworking.BuildGhosts.STREAM_CODEC.encode(buf, payload);
		assertTrue(buf.readableBytes() < ColonyNetworking.PAYLOAD_LIMIT, buf.readableBytes() + " bytes");
		assertEquals(payload, ColonyNetworking.BuildGhosts.STREAM_CODEC.decode(buf));

		assertEquals(List.of(big.project(), small.project()), payload.builds().stream().map(BuildGhost::project).toList());
		List<BlueprintSummary.Block> kept = payload.builds().getFirst().blocks();
		assertTrue(kept.size() > blocks.size() / 4, "kept only " + kept.size());
		assertTrue(kept.size() < blocks.size());
		assertEquals(big.blocks().subList(0, kept.size()), kept, "the lowest layers are kept");
		assertEquals(small, payload.builds().get(1));

		// Ghosts that fit are sent as they are.
		assertEquals(new ColonyNetworking.BuildGhosts(List.of(small)), ColonyNetworking.BuildGhosts.capped(List.of(small)));
	}

	@Test
	void theCancelRequestRoundTrips() {
		ColonyNetworking.CancelBuild request = new ColonyNetworking.CancelBuild(new BlockPos(1, 2, 3), UUID.randomUUID());
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ColonyNetworking.CancelBuild.STREAM_CODEC.encode(buf, request);
		assertEquals(request, ColonyNetworking.CancelBuild.STREAM_CODEC.decode(buf));
	}

	@Test
	void theGhostLookedAtIsTheNearestOneTheRayPassesThrough() {
		BuildGhost near = new BuildGhost(BlockPos.ZERO, UUID.randomUUID(), "near", List.of(new BlueprintSummary.Block(new BlockPos(3, 0, 0), STONE)));
		BuildGhost far = new BuildGhost(BlockPos.ZERO, UUID.randomUUID(), "far", List.of(new BlueprintSummary.Block(new BlockPos(6, 0, 0), STONE)));
		Vec3 eye = new Vec3(0.5, 0.5, 0.5);
		assertEquals(Optional.of(near), BuildGhost.lookedAt(List.of(far, near), eye, new Vec3(10.5, 0.5, 0.5)));
		// The ray stops short of both.
		assertEquals(Optional.empty(), BuildGhost.lookedAt(List.of(far, near), eye, new Vec3(2.5, 0.5, 0.5)));
		// It passes beside them.
		assertEquals(Optional.empty(), BuildGhost.lookedAt(List.of(far, near), eye, new Vec3(10.5, 5.5, 0.5)));
	}

	@Test
	void cancellingNeedsASecondClickOnTheSameBuildSoonAfter() {
		UUID hut = UUID.randomUUID();
		UUID farm = UUID.randomUUID();
		CancelConfirm confirm = new CancelConfirm();
		assertFalse(confirm.click(hut, 100), "the first click only asks");
		assertEquals(Optional.of(hut), confirm.armed(100));
		assertTrue(confirm.click(hut, 120), "the second click cancels");
		assertEquals(Optional.empty(), confirm.armed(120));

		assertFalse(confirm.click(hut, 200));
		assertFalse(confirm.click(farm, 210), "a click on another build asks again");
		assertTrue(confirm.click(farm, 220));

		assertFalse(confirm.click(hut, 300));
		assertEquals(Optional.empty(), confirm.armed(300 + CancelConfirm.TIMEOUT + 1), "the question lapses");
		assertFalse(confirm.click(hut, 300 + CancelConfirm.TIMEOUT + 1), "a click after it lapsed asks again");
	}
}
