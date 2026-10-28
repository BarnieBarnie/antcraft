package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.pathing.GridWorld;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.Values;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The senses {@code mineshaft(levels, near)} and {@code mineshaft_at(top, levels)} (docs/scripting.md, Senses). */
class MineApiTest {
	private static final BlockPos NEAR = new BlockPos(0, 64, 0);
	private static final List<Integer> LEVELS = List.of(40, 20);

	@BeforeAll
	static void bootstrap() {
		VanillaData.recipeBook();
	}

	private static Pos pos(BlockPos pos) {
		return new Pos(pos.getX(), pos.getY(), pos.getZ());
	}

	private static List<Object> positions(List<BlockPos> list) {
		return list.stream().<Object>map(MineApiTest::pos).toList();
	}

	/** The positions of the plan's steps that place {@code block}. */
	private static List<BlockPos> placed(BuildPlan plan, net.minecraft.world.level.block.Block block) {
		return plan.steps().stream().filter(step -> step.state().is(block)).map(BuildPlan.Step::pos).toList();
	}

	@Test
	void mineshaftGivesTheSiteAndItsPlanAsPlainData() {
		Mineshaft shaft = Mineshaft.diggableNextTo(NEAR, LEVELS, List.of(), new GridWorld()).orElseThrow();
		BuildPlan plan = shaft.plan();
		PyDict data = MineApi.describe(shaft, null);
		assertEquals(pos(shaft.top()), data.get("top"));
		assertEquals(List.of(40L, 20L), ((PyList) data.get("levels")).items);
		assertEquals(pos(shaft.top().below()), data.get("hatch"));
		PyDict level = (PyDict) ((PyDict) data.get("level")).get(20L);
		assertEquals(pos(shaft.landing(20)), level.get("landing"));
		assertEquals(pos(shaft.landingChest(20)), level.get("chest"));
		assertTrue(placed(plan, Blocks.CHEST).contains(shaft.landingChest(20)), "the chest is not where the plan puts it");
		assertEquals(positions(shaft.hall(20)), ((PyList) level.get("hall")).items);
		List<Object> branches = ((PyList) level.get("branches")).items;
		assertEquals(shaft.branches(20).size(), branches.size());
		assertEquals(positions(shaft.branches(20).getFirst().cells()), ((PyList) branches.getFirst()).items);
		List<Object> ladders = ((PyList) data.get("ladders")).items;
		List<BlockPos> planLadders = placed(plan, Blocks.LADDER);
		assertEquals(planLadders.size(), ladders.size());
		assertEquals(pos(planLadders.getFirst()), ((PyList) ladders.getFirst()).items.get(0));
		String facing = plan.steps().stream().filter(step -> step.state().is(Blocks.LADDER)).findFirst().orElseThrow()
			.state().getValue(LadderBlock.FACING).getSerializedName();
		assertEquals(facing, ((PyList) ladders.getFirst()).items.get(1));
		assertEquals(positions(placed(plan, Blocks.TORCH)), ((PyList) data.get("torches")).items);
	}

	@Test
	void noSiteWhereEverySpotIsBlocked() {
		// Lava right under the ground everywhere a site could be.
		GridWorld lava = new GridWorld();
		for (int x = -24; x <= 24; x++) {
			for (int z = -24; z <= 24; z++) {
				lava.set(x, NEAR.getY() - 1, z, 'L');
			}
		}
		assertTrue(Mineshaft.diggableNextTo(NEAR, LEVELS, List.of(), lava).isEmpty());
	}

	@Test
	void mineshaftAtGivesTheSameShaftBack() {
		Mineshaft shaft = Mineshaft.diggableNextTo(NEAR, LEVELS, List.of(), new GridWorld()).orElseThrow();
		assertEquals(Values.repr(MineApi.describe(shaft, null)), Values.repr(MineApi.describe(new Mineshaft(shaft.top(), shaft.levels()), null)));
	}
}
