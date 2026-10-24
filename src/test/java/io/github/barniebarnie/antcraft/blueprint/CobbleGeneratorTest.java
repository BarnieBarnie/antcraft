package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The bundled cobblestone generator: once its mining block is mined, lava flowing into it meets water flowing into a
 * hole beside it and turns to cobblestone, the way a player builds one. Its first work spot is that mining block, the second where a bot
 * stands to mine it.
 */
class CobbleGeneratorTest {
	private static final BlockPos AT = new BlockPos(6, 64, 6);
	private static Blueprint blueprint;
	private static StructureTemplate structure;

	@BeforeAll
	static void bootstrap() throws IOException {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		blueprint = Blueprint.parse(JsonParser.parseString(Files.readString(Path.of("src/main/resources/data/antcraft/blueprint/cobble_generator.json"))))
			.getOrThrow();
		structure = VanillaData.structure(blueprint.structure().getPath());
	}

	/** What the plan puts where: a block, air for a clear step; no entry where the structure leaves the world alone. */
	private static Map<BlockPos, BlockState> built(Mirror mirror, Rotation rotation) {
		Map<BlockPos, BlockState> built = new HashMap<>();
		for (BuildPlan.Step step : RegionPlan.load(structure, AT, mirror, rotation, 0).orElseThrow().steps()) {
			built.put(step.pos(), step.state());
		}
		return built;
	}

	private static boolean solid(Map<BlockPos, BlockState> built, BlockPos pos) {
		BlockState state = built.get(pos);
		return state != null && state.is(Blocks.COBBLESTONE);
	}

	@Test
	void theBundledGeneratorIsAGeneratorWithAMiningBlockAndASpotToStand() {
		assertEquals(Optional.of(Blueprint.Role.GENERATOR), blueprint.role());
		assertEquals("blueprint.antcraft.cobble_generator", blueprint.name());
		assertEquals(2, blueprint.workSpots().size(), "work spots: " + blueprint.workSpots());
	}

	@Test
	void lavaFlowsIntoTheMiningBlockBesideWaterFallingIntoAHoleTurnedAndMirroredEveryWay() {
		for (Mirror mirror : Mirror.values()) {
			for (Rotation rotation : Rotation.values()) {
				String how = mirror + " " + rotation;
				Map<BlockPos, BlockState> built = built(mirror, rotation);
				List<BlockPos> spots = blueprint.workSpotsAt(structure.getSize(), AT, mirror, rotation);
				BlockPos mine = spots.get(0);
				BlockPos stand = spots.get(1);

				// The mining block is built as cobblestone, on a floor, so the lava stays put until it is first mined, whatever is poured first.
				assertTrue(solid(built, mine), "the mining block is not built as cobblestone " + how);
				assertTrue(solid(built, mine.below()), "no floor under the mining block " + how);

				List<BlockPos> lava = built.entrySet().stream().filter(e -> e.getValue().is(Blocks.LAVA)).map(Map.Entry::getKey).toList();
				assertEquals(1, lava.size(), "lava sources " + how);
				BlockPos source = lava.getFirst();
				assertTrue(built.get(source).getFluidState().isSource(), "lava not a source " + how);
				Direction toLava = direction(mine, source);
				assertNotNull(toLava, "lava not beside the mining block " + how);
				// Walled in and covered, so it flows only into the mining block.
				for (Direction side : Direction.values()) {
					if (side != toLava.getOpposite()) {
						assertTrue(solid(built, source.relative(side)), "lava open to the " + side + " " + how);
					}
				}

				// Across from the lava, water flows over a hole and falls into it, so it never spreads into the mining block.
				BlockPos channel = mine.relative(toLava.getOpposite());
				assertFalse(built.containsKey(channel), "a step in the water channel " + how);
				assertFalse(built.containsKey(channel.below()), "a step in the hole " + how);
				for (Direction side : Direction.Plane.HORIZONTAL) {
					assertTrue(solid(built, channel.below().relative(side)), "hole open to the " + side + " " + how);
				}
				BlockPos water = channel.relative(toLava.getOpposite());
				assertTrue(built.containsKey(water) && built.get(water).is(Blocks.WATER) && built.get(water).getFluidState().isSource(),
					"no water source beyond the channel " + how);
				for (Direction side : Direction.Plane.HORIZONTAL) {
					if (side != toLava) {
						assertTrue(solid(built, water.relative(side)), "water open to the " + side + " " + how);
					}
					if (side.getAxis() != toLava.getAxis()) {
						assertTrue(solid(built, channel.relative(side)), "channel open to the " + side + " " + how);
					}
				}

				// The bot stands on the floor beside the mining block, with room for its head, the other side walled.
				Direction toStand = direction(mine, stand);
				assertNotNull(toStand, "stand spot not beside the mining block " + how);
				assertTrue(toStand.getAxis() != toLava.getAxis(), "stand spot in the lava's line " + how);
				assertTrue(built.get(stand) != null && built.get(stand).isAir(), "stand spot not cleared " + how);
				assertTrue(solid(built, stand.below()), "no floor under the stand spot " + how);
				assertFalse(built.containsKey(stand.above()) && !built.get(stand.above()).isAir(), "no head room " + how);
				assertTrue(solid(built, mine.relative(toStand.getOpposite())), "mining block open behind " + how);
			}
		}
	}

	/** The side of {@code from} that {@code to} is on, if it is right beside it sideways. */
	private static Direction direction(BlockPos from, BlockPos to) {
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (from.relative(side).equals(to)) {
				return side;
			}
		}
		return null;
	}
}
