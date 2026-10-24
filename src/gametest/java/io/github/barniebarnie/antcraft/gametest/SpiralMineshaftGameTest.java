package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.TestPositions.relative;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The 2-wide spiral mineshaft of new colonies: dug with crafted stair blocks, and walked by several bots at once. */
public class SpiralMineshaftGameTest implements CustomTestMethodInvoker {
	/** Beside the shaft, on its stone. */
	private static final BlockPos CORE = new BlockPos(0, 5, 0);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		// Stone up to y=4, the ground the shaft is dug into; the test area ends at y=7, 3 above the top step.
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper) {
		return colony(helper, CORE);
	}

	private static Colony colony(GameTestHelper helper, BlockPos core) {
		helper.setBlock(core, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(core)).orElseThrow();
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Container core(GameTestHelper helper) {
		return (Container) helper.getBlockEntity(CORE, BlockEntity.class);
	}

	/**
	 * A spiral from the ground at y=5 down to a landing at {@code level}, filling the test area's 6x6 middle. With the
	 * shortest hall, 3 long, and one round of 1-long branches: the hall floors and branch cells are protected and kept
	 * open for every colony, so the default 12-long hall and 64 blocks of branches each side would reach into other tests.
	 */
	private static Mineshaft shaft(GameTestHelper helper, int level) {
		int y = helper.absolutePos(new BlockPos(0, level, 0)).getY();
		return new Mineshaft(helper.absolutePos(new BlockPos(1, 5, 1)), List.of(y), 1, 1, Mineshaft.DEFAULT_TORCH_SPACING, 1, Mineshaft.Layout.SPIRAL);
	}

	private static AntBot member(GameTestHelper helper, Colony colony, BlockPos at) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, at.getX(), at.getY(), at.getZ());
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	private static int stairBlocks(GameTestHelper helper) {
		return stairBlocks(helper, Blocks.COBBLESTONE_STAIRS);
	}

	private static int stairBlocks(GameTestHelper helper, Block block) {
		int found = 0;
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 7, 7)) {
			if (helper.getBlockState(pos).is(block)) {
				found++;
			}
		}
		return found;
	}

	/**
	 * Two bots dig the top of a new shaft. The colony has no stair blocks: the bots make them from the
	 * cobblestone they dig, with the crafting table one carries, and put one on every step.
	 */
	@GameTest(maxTicks = 1000)
	public void botsDigTheTopOfTheShaftAndPlaceCraftedCobblestoneStairs(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.TORCH, 8));
		core(helper).setItem(1, new ItemStack(Items.CHEST, 1));
		Mineshaft shaft = shaft(helper, 3);
		colony.setMineshaft(shaft);
		for (BlockPos at : List.of(new BlockPos(7, 5, 0), new BlockPos(7, 5, 7))) {
			AntBot bot = member(helper, colony, at);
			bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
			if (at.getZ() == 0) {
				bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
			}
		}

		helper.succeedWhen(() -> {
			helper.assertTrue(stairBlocks(helper) == 4, "stair blocks placed: " + stairBlocks(helper));
			for (BuildPlan.Step step : shaft.plan().steps()) {
				BlockState state = helper.getLevel().getBlockState(step.pos());
				helper.assertTrue(step.isDone(state), "not done at " + relative(helper, step.pos()) + ": " + state);
			}
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished");
		});
	}

	/**
	 * A bot carrying a crafting table digs the top of a new shaft with an empty core: it crafts the stairs in the shaft
	 * from the cobblestone it dug and places them, and never brings anything to the core to craft them there.
	 */
	@GameTest(maxTicks = 820)
	public void aBotCraftsTheStairsInTheShaftWithTheCraftingTableItCarries(GameTestHelper helper) {
		Colony colony = colony(helper);
		Mineshaft shaft = shaft(helper, 3);
		colony.setMineshaft(shaft);
		AntBot bot = member(helper, colony, new BlockPos(7, 5, 7));
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
		boolean[] tablePlaced = {false};
		helper.onEachTick(() -> {
			tablePlaced[0] |= stairBlocks(helper, Blocks.CRAFTING_TABLE) > 0;
			if (!core(helper).isEmpty()) {
				helper.fail("brought to the core: " + core(helper).getItem(0));
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(stairBlocks(helper) == 4, "stair blocks placed: " + stairBlocks(helper));
			helper.assertTrue(tablePlaced[0], "crafting table never placed");
			helper.assertTrue(bot.getInventory().countItem(Items.CRAFTING_TABLE) == 1, "crafting table not taken back");
		});
	}

	/** With stone brick stairs in the core and no cobblestone ones, the bots put those on the steps and make none. */
	@GameTest(maxTicks = 910)
	public void botsBuildTheStepsFromTheStoneStairsTheCoreHas(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.TORCH, 8));
		core(helper).setItem(1, new ItemStack(Items.CHEST, 1));
		core(helper).setItem(2, new ItemStack(Items.STONE_BRICK_STAIRS, 4));
		Mineshaft shaft = shaft(helper, 3);
		colony.setMineshaft(shaft);
		for (BlockPos at : List.of(new BlockPos(7, 5, 0), new BlockPos(7, 5, 7))) {
			member(helper, colony, at).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		}
		helper.onEachTick(() -> {
			if (colony.orders().containsKey(Items.COBBLESTONE_STAIRS)) {
				helper.fail("ordered cobblestone stairs");
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(stairBlocks(helper, Blocks.STONE_BRICK_STAIRS) == 4, "stone brick stairs placed: " + stairBlocks(helper, Blocks.STONE_BRICK_STAIRS));
			for (BuildPlan.Step step : shaft.plan().steps()) {
				BlockState state = helper.getLevel().getBlockState(step.pos());
				helper.assertTrue(step.isDone(state), "not done at " + relative(helper, step.pos()) + ": " + state);
			}
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished");
		});
	}

	/**
	 * In a 16x16 area of stone up to y=5, no ores: a dug spiral down to a landing at y=3, whose hall heads west
	 * under a stone roof, with one branch each side of it. A miner digs the hall the colony posts once the landing
	 * is dug, and only then the branches off it. The level is at y=3 because the halls of the 8x8 tests' shafts
	 * run out of their areas, and their floors, at y=2 and y=0, are protected blocks no bot breaks.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1100)
	public void aMinerDigsTheHallOfASpiralLevelThenTheBranchesOffIt(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 5, 15)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = colony(helper, new BlockPos(0, 6, 0));
		// The colony mines only while it wants something mining yields.
		colony.setStockTarget(StockTarget.of(Items.COBBLESTONE, 1000));
		int y = helper.absolutePos(new BlockPos(0, 3, 0)).getY();
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(5, 6, 1)), List.of(y), 1, 3, 4, 1, Mineshaft.Layout.SPIRAL);
		colony.setMineshaft(shaft);
		for (BuildPlan.Step step : shaft.plan().steps()) {
			if (!step.isDone(helper.getLevel().getBlockState(step.pos()))) {
				helper.getLevel().setBlockAndUpdate(step.pos(), step.state());
			}
		}
		colony.removeBuild(shaft.plan());
		List<BlockPos> hall = shaft.hallWork(y).orElseThrow().cells();
		List<Mineshaft.Branch> branches = shaft.branches(y);
		AntBot miner = member(helper, colony, new BlockPos(12, 6, 12));
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		// The scaffold blocks a miner keeps.
		miner.getInventory().addItem(new ItemStack(Items.DIRT, 8));
		helper.onEachTick(() -> {
			if (hall.stream().anyMatch(cell -> !helper.getLevel().getBlockState(cell).isAir())
					&& branches.stream().flatMap(branch -> branch.cells().stream()).anyMatch(cell -> helper.getLevel().getBlockState(cell).isAir())) {
				helper.fail("a branch dug before the hall");
			}
		});

		helper.succeedWhen(() -> {
			for (Mineshaft.Branch branch : branches) {
				for (BlockPos cell : branch.cells()) {
					BlockState state = helper.getLevel().getBlockState(cell);
					helper.assertTrue(state.isAir() || state.is(Blocks.TORCH), "branch not dug at " + relative(helper, cell).toShortString());
				}
			}
		});
	}

	/**
	 * As above, with bedrock on the floor of the hall's middle block, in the way a miner walks: the miner digs the hall
	 * up to it, then the hall job is given up and no branch off the hall is posted or dug.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 640)
	public void aHallWithBedrockInItsWayIsGivenUpAndNoBranchOffItIsPosted(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 5, 15)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = colony(helper, new BlockPos(0, 6, 0));
		colony.setStockTarget(StockTarget.of(Items.COBBLESTONE, 1000));
		int y = helper.absolutePos(new BlockPos(0, 3, 0)).getY();
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(5, 6, 1)), List.of(y), 1, 3, 4, 1, Mineshaft.Layout.SPIRAL);
		colony.setMineshaft(shaft);
		for (BuildPlan.Step step : shaft.plan().steps()) {
			if (!step.isDone(helper.getLevel().getBlockState(step.pos()))) {
				helper.getLevel().setBlockAndUpdate(step.pos(), step.state());
			}
		}
		colony.removeBuild(shaft.plan());
		Mineshaft.Branch hall = shaft.hallWork(y).orElseThrow();
		// The cells run floor, then ceiling, block by block from the landing.
		helper.getLevel().setBlockAndUpdate(hall.cells().get(2), Blocks.BEDROCK.defaultBlockState());
		List<Mineshaft.Branch> branches = shaft.branches(y);
		AntBot miner = member(helper, colony, new BlockPos(12, 6, 12));
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		miner.getInventory().addItem(new ItemStack(Items.DIRT, 8));
		long[] givenUpAt = {-1};
		helper.onEachTick(() -> {
			for (Mineshaft.Branch branch : branches) {
				if (branch.segments().stream().anyMatch(segment -> colony.board().has(JobType.MINE_BRANCH, segment.start()))) {
					helper.fail("branch posted at " + relative(helper, branch.start()).toShortString());
				}
				if (branch.cells().stream().anyMatch(cell -> helper.getLevel().getBlockState(cell).isAir())) {
					helper.fail("branch dug at " + relative(helper, branch.start()).toShortString());
				}
			}
			if (givenUpAt[0] < 0 && colony.board().jobs().stream()
					.anyMatch(job -> job.type() == JobType.MINE_BRANCH && job.givenUp() && job.target().equals(hall.start()))) {
				givenUpAt[0] = helper.getTick();
			}
		});

		// Two surveys after it gave up, so a branch would have been posted.
		helper.succeedWhen(() -> helper.assertTrue(givenUpAt[0] >= 0 && helper.getTick() - givenUpAt[0] > 200, "hall not given up"));
	}

	/**
	 * A dug hall with one branch each side and two rounds of them, bedrock in the walking line of one first-round
	 * branch: that branch is given up and its second round never posted, while the other side's second round is dug.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1200)
	public void aBranchWithBedrockInItsWayIsGivenUpAndNotContinued(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 5, 15)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = colony(helper, new BlockPos(0, 6, 0));
		colony.setStockTarget(StockTarget.of(Items.COBBLESTONE, 1000));
		int y = helper.absolutePos(new BlockPos(0, 3, 0)).getY();
		// Branches 2 long a round, so both rounds stay in the area.
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(7, 6, 5)), List.of(y), 1, 2, 4, 2, Mineshaft.Layout.SPIRAL);
		colony.setMineshaft(shaft);
		for (BuildPlan.Step step : shaft.plan().steps()) {
			if (!step.isDone(helper.getLevel().getBlockState(step.pos()))) {
				helper.getLevel().setBlockAndUpdate(step.pos(), step.state());
			}
		}
		colony.removeBuild(shaft.plan());
		shaft.hallWork(y).orElseThrow().cells().forEach(cell -> helper.getLevel().setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState()));
		Mineshaft.Branch blocked = shaft.branches(y, 0).get(0);
		Mineshaft.Branch blockedNext = shaft.branches(y, 1).get(0);
		Mineshaft.Branch otherNext = shaft.branches(y, 1).get(1);
		// The cells run floor, then ceiling: bedrock on the floor of the second block.
		helper.getLevel().setBlockAndUpdate(blocked.cells().get(2), Blocks.BEDROCK.defaultBlockState());
		AntBot miner = member(helper, colony, new BlockPos(12, 6, 12));
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		miner.getInventory().addItem(new ItemStack(Items.DIRT, 8));
		helper.onEachTick(() -> {
			if (blockedNext.segments().stream().anyMatch(segment -> colony.board().has(JobType.MINE_BRANCH, segment.start()))) {
				helper.fail("the blocked branch continued at " + relative(helper, blockedNext.start()).toShortString());
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.board().jobs().stream()
				.anyMatch(job -> job.type() == JobType.MINE_BRANCH && job.givenUp() && job.target().equals(blocked.start())), "blocked branch not given up");
			for (BlockPos cell : otherNext.cells()) {
				BlockState state = helper.getLevel().getBlockState(cell);
				helper.assertTrue(state.isAir() || state.is(Blocks.TORCH), "other branch not continued at " + relative(helper, cell).toShortString());
			}
		});
	}

	/** Every block of the test area that is not air. */
	private static Set<BlockPos> solid(GameTestHelper helper) {
		Set<BlockPos> solid = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 7, 7)) {
			if (!helper.getBlockState(pos).isAir()) {
				solid.add(pos.immutable());
			}
		}
		return solid;
	}

	/** On the 2-wide stairs, a bot on its way up and one on its way down get past each other, without digging a way round. */
	@GameTest(maxTicks = 180)
	public void twoBotsPassEachOtherOnTheStairs(GameTestHelper helper) {
		Colony colony = colony(helper);
		Mineshaft shaft = shaft(helper, 1);
		colony.setMineshaft(shaft);
		for (BuildPlan.Step step : shaft.plan().steps()) {
			if (!step.isDone(helper.getLevel().getBlockState(step.pos()))) {
				helper.getLevel().setBlockAndUpdate(step.pos(), step.state());
			}
		}
		colony.removeBuild(shaft.plan());
		Set<BlockPos> dug = solid(helper);
		BlockPos top = shaft.top();
		BlockPos bottom = shaft.landing(shaft.levels().getLast());
		AntBot up = member(helper, colony, relative(helper, bottom));
		AntBot down = member(helper, colony, relative(helper, top));
		up.setWalkTarget(top);
		down.setWalkTarget(bottom);
		// Where each bot was as its walk ended. Once there it may go off to work between jobs, such as after an item
		// another test dropped in the colony's radius, so the two need not both be at their targets on the same tick.
		BlockPos[] ended = new BlockPos[2];
		helper.onEachTick(() -> {
			if (ended[0] == null && up.getWalkTarget() == null) {
				ended[0] = up.blockPosition();
			}
			if (ended[1] == null && down.getWalkTarget() == null) {
				ended[1] = down.blockPosition();
			}
		});

		helper.succeedWhen(() -> {
			// A walk ends next to its target.
			helper.assertTrue(ended[0] != null && ended[0].distManhattan(top) <= 1,
				"the bot going up is at " + relative(helper, up.blockPosition()));
			helper.assertTrue(ended[1] != null && ended[1].distManhattan(bottom) <= 1,
				"the bot going down is at " + relative(helper, down.blockPosition()));
			helper.assertTrue(solid(helper).equals(dug), "blocks changed on the way");
		});
	}
}
