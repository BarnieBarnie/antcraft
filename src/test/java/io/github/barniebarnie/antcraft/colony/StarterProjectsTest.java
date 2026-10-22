package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.barniebarnie.antcraft.pathing.WorldView;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.VanillaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StarterProjectsTest {
	private static final BlockPos CORE = new BlockPos(0, 64, 0);
	/** Flat rock up to just below the core, as in the game tests: nothing to plant in, so the farm needs soil brought in. */
	private static final Function<BlockPos, BlockState> WORLD = pos -> pos.getY() < CORE.getY() ? Blocks.STONE.defaultBlockState()
		: Blocks.AIR.defaultBlockState();
	private static final WorldView VIEW = new WorldView() {
		@Override
		public boolean isPassable(BlockPos pos) {
			return pos.getY() >= CORE.getY();
		}

		@Override
		public boolean canStandOn(BlockPos pos) {
			return pos.getY() < CORE.getY();
		}

		@Override
		public boolean isHazard(BlockPos pos) {
			return false;
		}

		@Override
		public float breakTicks(BlockPos pos) {
			return 30;
		}
	};

	@BeforeAll
	static void bootstrap() {
		VanillaData.recipeBook();
	}

	private static Colony found() {
		Colony colony = new Colony(UUID.randomUUID(), CORE, List.of(), new JobBoard(), Colony.DEFAULT_RADIUS, Map.of(), Optional.empty(),
			DefaultStock.TARGETS, Map.of(), List.of(), Optional.empty(), Optional.empty(), Map.of(), List.of(), Map.of());
		StarterProjects.start(colony, WORLD, VIEW, pos -> true, List.of(CORE.getY() - 20), VanillaData.structure("starter_house"));
		return colony;
	}

	@Test
	void aNewColonyHasTheFiveStarterProjectsToolsFirst() {
		Colony colony = found();

		assertEquals(List.of("tools", "farm", "mineshaft", "storage", "house"), colony.projects().stream().map(Project::name).toList());
		List<Integer> priorities = colony.projects().stream().map(Project::priority).toList();
		for (int i = 1; i < priorities.size(); i++) {
			assertTrue(priorities.get(i) < priorities.get(i - 1), "priorities not in the list's order: " + priorities);
		}
	}

	@Test
	void theStarterProjectsAreShownTranslated() {
		Colony colony = found();

		assertEquals(colony.projects().stream().map(project -> Component.translatable("project.antcraft." + project.name())).toList(),
			colony.projects().stream().map(Project::displayName).toList());
	}

	@Test
	void theStarterAreasDoNotOverlap() {
		Colony colony = found();

		List<BoundingBox> areas = new ArrayList<>();
		areas.add(colony.field().orElseThrow().area());
		areas.add(colony.mineshaft().orElseThrow().area());
		areas.add(colony.storage().orElseThrow().area());
		colony.projects().stream().filter(project -> project.name().equals("house")).flatMap(project -> colony.plans(project).stream())
			.forEach(plan -> areas.add(BoundingBox.encapsulatingPositions(plan.steps().stream().map(BuildPlan.Step::pos).toList()).orElseThrow()));
		for (int i = 0; i < areas.size(); i++) {
			for (int j = i + 1; j < areas.size(); j++) {
				assertFalse(areas.get(i).intersects(areas.get(j)), areas.get(i) + " overlaps " + areas.get(j));
			}
		}
	}

	@Test
	void theFarmBringsSoilToRock() {
		Colony colony = found();
		Project farm = colony.projects().stream().filter(project -> project.name().equals("farm")).findFirst().orElseThrow();
		BlockPos center = colony.field().orElseThrow().center();

		List<BuildPlan.Step> steps = colony.plans(farm).stream().flatMap(plan -> plan.steps().stream()).toList();
		assertTrue(steps.contains(new BuildPlan.Step(center.offset(1, 0, 1), Blocks.DIRT.defaultBlockState())), "no dirt on the rock: " + steps);
	}

	@Test
	void theToolsProjectWaitsForTheStarterToolKitUntilTheColonyHasIt() {
		Colony colony = found();
		Project tools = colony.projects().getFirst();

		colony.planProjects(WORLD, step -> true, Map.of(), tool -> false);
		Map<net.minecraft.world.item.Item, Integer> waiting = colony.waitingFor(tools.id());
		assertEquals(Map.of(Items.WOODEN_PICKAXE, 2, Items.WOODEN_AXE, 1, Items.WOODEN_SHOVEL, 1, Items.WOODEN_HOE, 1, Items.WOODEN_SWORD, 2), waiting);
		assertEquals(2, colony.orders().get(Items.WOODEN_PICKAXE));

		// Any pickaxe will do, and what bots carry counts as held.
		colony.planProjects(WORLD, step -> true, Map.of(Items.STONE_PICKAXE, 2, Items.WOODEN_AXE, 1, Items.WOODEN_SHOVEL, 1, Items.WOODEN_HOE, 1,
			Items.IRON_SWORD, 2), tool -> true);
		assertTrue(colony.projects().stream().noneMatch(project -> project.name().equals("tools")), "tools project not done");
		assertFalse(colony.orders().containsKey(Items.WOODEN_PICKAXE), "pickaxes still ordered: " + colony.orders());
	}
}
