package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.TestPositions.relative;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.goal.HaulerGoal;
import io.github.barniebarnie.antcraft.entity.navigation.LineOfSight;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.OreVein;
import io.github.barniebarnie.antcraft.work.PickaxeTier;
import io.github.barniebarnie.antcraft.work.Storage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

public class MinerGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);
	/** On top of the stone of {@link #mine}. */
	static final BlockPos MINE_CORE = new BlockPos(0, 5, 0);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper, BlockPos core) {
		helper.setBlock(core, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(core)).orElseThrow();
		colony.setRadius(6);
		// No default stock targets, so only the test's own orders give the colony work.
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Colony colony(GameTestHelper helper) {
		return colony(helper, CORE);
	}

	/** The colony wants stone: the colony mines only while it wants something mining yields. */
	private static void wantStone(Colony colony) {
		colony.setStockTarget(StockTarget.of(Items.COBBLESTONE, 1000));
	}

	/** The colony wants stone and iron: a miner posts a vein job only for an ore the colony wants. */
	private static void wantStoneAndIron(Colony colony) {
		wantStone(colony);
		colony.setStockTarget(StockTarget.of(Items.RAW_IRON, 64));
	}

	private static Container core(GameTestHelper helper, BlockPos core) {
		return (Container) helper.getBlockEntity(core, BlockEntity.class);
	}

	private static Container core(GameTestHelper helper) {
		return core(helper, CORE);
	}

	/**
	 * Fills the area with stone up to y=4 and gives the colony a shallow mineshaft in it: its stairs
	 * wind down from (3,5,3) to a landing at y=3, whose hall heads east (open to the sky), and one at
	 * y=1, whose hall heads west. One branch each side of each hall, 3 long, and a torch every 4 blocks.
	 * No further rounds of branches, which would run out of the test area.
	 */
	static Mineshaft mine(GameTestHelper helper) {
		return mine(helper, 3, 1);
	}

	static Mineshaft mine(GameTestHelper helper, int branchLength, int rounds) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		int y3 = helper.absolutePos(new BlockPos(0, 3, 0)).getY();
		int y1 = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		return new Mineshaft(helper.absolutePos(new BlockPos(3, 5, 3)), List.of(y3, y1), 1, branchLength, 4, rounds, Mineshaft.Layout.STAIRCASE);
	}

	/** Digs the stairs and halls as a builder would. */
	static void dig(GameTestHelper helper, Colony colony, Mineshaft shaft) {
		colony.setMineshaft(shaft);
		for (BuildPlan.Step step : shaft.plan().steps()) {
			if (!step.isDone(helper.getLevel().getBlockState(step.pos()))) {
				helper.getLevel().setBlockAndUpdate(step.pos(), step.state());
			}
		}
		colony.removeBuild(shaft.plan());
	}

	/** Every block of the shaft, its halls and its branches that bots walk through. */
	static List<BlockPos> dugPath(Mineshaft shaft, boolean branches) {
		List<BlockPos> path = new ArrayList<>(shaft.plan().steps().stream().filter(step -> !step.anySolid()).map(BuildPlan.Step::pos).toList());
		if (branches) {
			shaft.levels().forEach(y -> shaft.branches(y).forEach(branch -> path.addAll(branch.cells())));
		}
		return path;
	}

	static void assertLit(GameTestHelper helper, List<BlockPos> path) {
		for (BlockPos pos : path) {
			int light = helper.getLevel().getBrightness(LightLayer.BLOCK, pos);
			helper.assertTrue(light > 0, "dark at " + relative(helper, pos).toShortString());
		}
	}

	private static void assertBranchesDug(GameTestHelper helper, Mineshaft shaft, int level) {
		for (Mineshaft.Branch branch : shaft.branches(level)) {
			for (BlockPos cell : branch.cells()) {
				BlockState state = helper.getLevel().getBlockState(cell);
				helper.assertTrue(state.isAir() || state.is(Blocks.TORCH), "branch not dug at " + relative(helper, cell).toShortString());
			}
			for (BlockPos torch : branch.torches()) {
				helper.assertTrue(helper.getLevel().getBlockState(torch).is(Blocks.TORCH), "no torch at " + relative(helper, torch).toShortString());
			}
		}
	}

	private static AntBot member(GameTestHelper helper, Colony colony, int x, int y, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, y, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	private static AntBot member(GameTestHelper helper, Colony colony, int x, int z) {
		return member(helper, colony, x, 1, z);
	}

	@GameTest
	public void whatTheCoreWantsCountsWhatBotsCarry(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.STICK, 8));
		colony.addOrder(Items.STONE_PICKAXE, 1);
		// Cobblestone a member carries is the colony's, as in Storage.held.
		AntBot carrier = member(helper, colony, 4, 4);
		carrier.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		helper.assertTrue(Storage.held(helper.getLevel(), colony).getOrDefault(Items.COBBLESTONE, 0) == 3, "held leaves out what bots carry");
		// So a miner depositing its haul, which goes by Storage.wanted, leaves more cobblestone in the landing chest.
		helper.assertFalse(Storage.wanted(helper.getLevel(), colony).test(Items.COBBLESTONE), "the order still wants cobblestone a bot carries");
		// Unless that cobblestone is the haul being deposited.
		helper.assertTrue(Storage.wanted(helper.getLevel(), colony, Storage.stock(carrier.getInventory())).test(Items.COBBLESTONE),
			"a miner's own haul counts as already held");
		helper.succeed();
	}

	@GameTest(maxTicks = 610)
	public void digsBranchFromLandingAndLightsIt(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		GameTests.failOnGiveUp(helper, colony);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.TORCH, 8));
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		int deepest = shaft.levels().getLast();

		helper.succeedWhen(() -> {
			// With no ores wanted, the deepest level comes first.
			assertBranchesDug(helper, shaft, deepest);
			List<BlockPos> path = new ArrayList<>(dugPath(shaft, false));
			shaft.branches(deepest).forEach(branch -> path.addAll(branch.cells()));
			assertLit(helper, path);
			helper.assertTrue(core(helper, MINE_CORE).countItem(Items.COBBLESTONE) > 0, "no cobblestone stored yet");
		});
	}

	@GameTest(maxTicks = 1400)
	public void minersNeverBreakTheStairsOrTorches(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.TORCH, 8));
		// Built by a builder, in the way at the end of a branch: the miner digs up to it, then gives the branch up.
		Mineshaft.Branch first = shaft.branches(shaft.levels().getLast()).getFirst();
		BlockPos planks = first.cells().getLast();
		helper.getLevel().setBlockAndUpdate(planks, Blocks.OAK_PLANKS.defaultBlockState());
		colony.protect(planks, Blocks.OAK_PLANKS);
		for (int i = 0; i < 2; i++) {
			AntBot miner = member(helper, colony, 1 + i, 5, 1);
			miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		}
		Set<BlockPos> kept = shaft.protectedBlocks();
		LevelWorldView view = new LevelWorldView(helper.getLevel(), new SimpleContainer(1), ItemStack.EMPTY, Set.of());
		for (BlockPos pos : kept) {
			helper.assertTrue(view.breakTicks(pos) < 0, "pathing may break " + relative(helper, pos).toShortString());
		}

		helper.onEachTick(() -> {
			for (BlockPos pos : kept) {
				if (helper.getLevel().getBlockState(pos).isAir()) {
					helper.fail("broke " + relative(helper, pos).toShortString());
				}
			}
			for (BlockPos torch : shaft.torches()) {
				if (!helper.getLevel().getBlockState(torch).is(Blocks.TORCH)) {
					helper.fail("torch gone at " + relative(helper, torch).toShortString());
				}
			}
			if (!helper.getLevel().getBlockState(planks).is(Blocks.OAK_PLANKS)) {
				helper.fail("broke the builder's planks");
			}
			colony.board().jobs().stream().filter(job -> job.givenUp() && !job.target().equals(first.start())).findFirst()
				.ifPresent(job -> helper.fail("gave up " + job.type() + " at " + relative(helper, job.target()).toShortString()));
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(colony.board().jobs().stream().anyMatch(job -> job.givenUp() && job.target().equals(first.start())),
				"branch with the planks not given up");
			for (int level : shaft.levels()) {
				for (Mineshaft.Branch branch : shaft.branches(level)) {
					for (BlockPos cell : branch.cells()) {
						BlockState state = helper.getLevel().getBlockState(cell);
						helper.assertTrue(state.isAir() || state.is(Blocks.TORCH) || cell.equals(planks),
							"branch not dug at " + relative(helper, cell).toShortString());
					}
				}
			}
		});
	}

	/** Branches 1 long, so three rounds of them fit in the test area. */
	@GameTest(maxTicks = 1700)
	public void minedOutLevelGetsFurtherBranches(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		GameTests.failOnGiveUp(helper, colony);
		wantStone(colony);
		Mineshaft shaft = mine(helper, 1, 3);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.TORCH, 8));
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		int deepest = shaft.levels().getLast();

		helper.succeedWhen(() -> {
			for (int round = 0; round < shaft.rounds(); round++) {
				for (Mineshaft.Branch branch : shaft.branches(deepest, round)) {
					for (BlockPos cell : branch.cells()) {
						BlockState state = helper.getLevel().getBlockState(cell);
						helper.assertTrue(state.isAir() || state.is(Blocks.TORCH), "round " + round + " not dug at " + relative(helper, cell).toShortString());
					}
				}
			}
		});
	}

	/**
	 * In a 16 long area: one level at y=1, its hall heading east along z=8 to a branch spot at x=4.
	 * The branch south is bedrock but for its last cell, 4 blocks from the hall, and so is everything
	 * south of the hall, so no miner can get within reach of that cell. The miner gives it up, so the
	 * round still ends, and the next round north is dug.
	 */
	@GameTest(structure = "antcraft-gametest:long", maxTicks = 630)
	public void unreachableBranchCellDoesNotHoldUpTheNextRound(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 0, 15)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 2, 8)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 9, 7, 7, 15)) {
			helper.setBlock(pos, Blocks.BEDROCK);
		}
		BlockPos walledOff = new BlockPos(4, 1, 12);
		helper.setBlock(walledOff, Blocks.STONE);
		Colony colony = colony(helper, new BlockPos(6, 3, 2));
		wantStone(colony);
		int y = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(0, 3, 7)), List.of(y), 1, 4, 4, 2, Mineshaft.Layout.STAIRCASE);
		helper.assertTrue(shaft.branches(y).getFirst().cells().contains(helper.absolutePos(walledOff)), "walled-off cell not in the south branch");
		dig(helper, colony, shaft);
		AntBot miner = member(helper, colony, 5, 3, 3);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		Mineshaft.Branch north = shaft.branches(y, 1).getLast();

		helper.succeedWhen(() -> {
			for (BlockPos cell : north.cells()) {
				BlockState state = helper.getLevel().getBlockState(cell);
				helper.assertTrue(state.isAir(), "round 1 not dug at " + relative(helper, cell).toShortString());
			}
			helper.assertBlockPresent(Blocks.STONE, walledOff);
		});
	}

	/**
	 * Stone up to y=4 and a ladder shaft from (1, 5, 3) down to a landing at y=1, whose hall the miner digs east along
	 * z=3, with branches 3 long, so the miner walks into the hall to reach their far ends. A small cave opens off the
	 * hall at x=3..4, z=4..5, with a lava source sunk in its floor at (3, 0, 5): more than the scan height below the core
	 * and beside no cell of the mine, so only a bot that saw it knows it is there. Once the miner has seen it from the
	 * hall, a lava order is filled there.
	 */
	@GameTest(maxTicks = 1300)
	public void lavaAMinerSawFromAHallIsUsedForALavaOrder(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		for (BlockPos pos : BlockPos.betweenClosed(3, 1, 4, 4, 2, 5)) {
			helper.setBlock(pos, Blocks.AIR);
		}
		BlockPos pool = new BlockPos(3, 0, 5);
		helper.setBlock(pool.below(), Blocks.STONE);
		helper.setBlock(pool, Blocks.LAVA);
		BlockPos corePos = new BlockPos(6, 5, 6);
		Colony colony = colony(helper, corePos);
		wantStone(colony);
		int y = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(1, 5, 3)), List.of(y), 1, 3, Mineshaft.DEFAULT_TORCH_SPACING, 1);
		dig(helper, colony, shaft);
		helper.assertFalse(Direction.stream().map(side -> helper.absolutePos(pool).relative(side)).anyMatch(colony.mineCells()::contains),
			"the lava is beside the mine");
		AntBot miner = member(helper, colony, 5, 5, 5);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		// Once the miner has seen the lava, the colony wants a bucket of it.
		boolean[] ordered = {false};
		helper.onEachTick(() -> {
			if (!ordered[0] && colony.seenLava().contains(helper.absolutePos(pool))) {
				ordered[0] = true;
				core(helper, corePos).setItem(0, new ItemStack(Items.BUCKET));
				colony.addOrder(Items.LAVA_BUCKET, 1);
			}
			helper.assertFalse(miner.isInLava() || miner.isOnFire(), "miner in lava or on fire at " + relative(helper, miner.blockPosition()).toShortString());
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper, corePos).countItem(Items.LAVA_BUCKET) == 1, "no lava bucket in the core");
			helper.assertBlockNotPresent(Blocks.LAVA, pool);
		});
	}

	/**
	 * Stone up to y=4 and a ladder shaft from (1, 5, 1) down to a landing at y=1, whose hall the miner digs east along
	 * z=1. The miner starts on the ground right above the end of the hall: it climbs down the ladder rather than dig its
	 * own way down, so it breaks no block that is not a cell of the mine.
	 */
	@GameTest(maxTicks = 300)
	public void minerClimbsDownTheLadderRatherThanDigItsOwnWayToTheHall(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = colony(helper, new BlockPos(6, 5, 6));
		GameTests.failOnGiveUp(helper, colony);
		wantStone(colony);
		int y = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(1, 5, 1)), List.of(y), 1, 1, Mineshaft.DEFAULT_TORCH_SPACING, 1);
		dig(helper, colony, shaft);
		Mineshaft.Branch hall = shaft.hallWork(y).orElseThrow();
		AntBot miner = member(helper, colony, 5, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.onEachTick(() -> {
			for (BlockPos pos : BlockPos.betweenClosed(helper.absolutePos(new BlockPos(0, 1, 0)), helper.absolutePos(new BlockPos(7, 4, 7)))) {
				if (helper.getLevel().getBlockState(pos).isAir() && !colony.mineCells().contains(pos)) {
					helper.fail("broke " + relative(helper, pos).toShortString() + ", outside the mine");
				}
			}
		});
		helper.succeedWhen(() -> {
			for (BlockPos cell : hall.cells()) {
				helper.assertTrue(helper.getLevel().getBlockState(cell).isAir(), "hall not dug at " + relative(helper, cell).toShortString());
			}
		});
	}

	/**
	 * In a 16x16 area: stone only 2 high, so a level at y=1 has no roof but the sky, and its hall heading east along
	 * z=8 has one branch a side at x=4. The miner starts on the stone right above the north branch: it digs each
	 * branch cell standing in the mine, as from the hall, not from the surface above it.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 500)
	public void minerDigsABranchFromInsideNotFromTheSurfaceAboveIt(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 0, 15)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 15, 2, 15)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = colony(helper, new BlockPos(6, 3, 2));
		GameTests.failOnGiveUp(helper, colony);
		wantStone(colony);
		int y = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(0, 3, 7)), List.of(y), 1, 4, 4, 1, Mineshaft.Layout.STAIRCASE);
		dig(helper, colony, shaft);
		List<BlockPos> cells = shaft.branches(y).stream().flatMap(branch -> branch.cells().stream()).toList();
		AntBot miner = member(helper, colony, 4, 3, 5);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		helper.assertTrue(cells.contains(miner.blockPosition().below()), "the miner is not above a branch");

		Set<BlockPos> solid = new HashSet<>(cells.stream().filter(cell -> !helper.getLevel().getBlockState(cell).isAir()).toList());
		helper.onEachTick(() -> {
			for (BlockPos cell : List.copyOf(solid)) {
				if (helper.getLevel().getBlockState(cell).isAir()) {
					solid.remove(cell);
					if (!colony.mineCells().contains(miner.blockPosition())) {
						helper.fail("broke " + relative(helper, cell).toShortString() + " standing outside the mine at " + relative(helper, miner.blockPosition()).toShortString());
					}
				}
			}
		});
		helper.succeedWhen(() -> {
			for (BlockPos cell : cells) {
				BlockState state = helper.getLevel().getBlockState(cell);
				helper.assertTrue(state.isAir() || state.is(Blocks.TORCH), "branch not dug at " + relative(helper, cell).toShortString());
			}
		});
	}

	/**
	 * In a 16x16 area: one level at y=1, its hall heading east along z=8 with two branches a side, 7 long, at
	 * x=4 and x=7. Each branch is two jobs, the second ready once the first is dug. Four bots dig the four
	 * branches at the same time.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 990)
	public void fourBotsDigTheBranchesOfOneLevelAtTheSameTime(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 0, 15)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 15, 2, 15)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = colony(helper, new BlockPos(6, 3, 2));
		wantStone(colony);
		int y = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(0, 3, 7)), List.of(y), 2, 7, 4, 1, Mineshaft.Layout.STAIRCASE);
		dig(helper, colony, shaft);
		List<Mineshaft.Branch> branches = shaft.branches(y);
		helper.assertTrue(branches.size() == 4 && branches.stream().allMatch(branch -> branch.segments().size() == 2), "not 4 branches of 2 segments");
		List<AntBot> bots = new ArrayList<>();
		for (BlockPos at : List.of(new BlockPos(3, 3, 3), new BlockPos(4, 3, 3), new BlockPos(5, 3, 4), new BlockPos(3, 3, 5))) {
			AntBot bot = member(helper, colony, at.getX(), at.getY(), at.getZ());
			bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
			bots.add(bot);
		}
		int[] most = {0};
		helper.onEachTick(() -> {
			Set<Mineshaft.Branch> dug = new HashSet<>();
			for (AntBot bot : bots) {
				colony.board().jobOf(bot.getUUID()).filter(job -> job.type() == JobType.MINE_BRANCH)
					.ifPresent(job -> branches.stream().filter(branch -> branch.cells().contains(job.target())).forEach(dug::add));
			}
			most[0] = Math.max(most[0], dug.size());
		});
		helper.succeedWhen(() -> {
			for (Mineshaft.Branch branch : branches) {
				for (BlockPos cell : branch.cells()) {
					helper.assertTrue(helper.getLevel().getBlockState(cell).isAir(), "branch not dug at " + relative(helper, cell).toShortString());
				}
			}
			helper.assertTrue(most[0] == 4, "at most " + most[0] + " branches dug at once");
		});
	}

	/**
	 * In a 16x16 area: one branch, 7 long, of a level at y=1, so two segments dug one after the other. The miner of
	 * the first walks out with its haul while the other walks in to the second, through the same 1-wide branch:
	 * neither keeps the other in. Both segments get dug and both miners get their haul out to the core: an order wants
	 * the stone, so it goes straight up rather than into the landing chest.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 640)
	public void twoMinersDigConsecutiveSegmentsOfOneBranchWithoutBoxingEachOtherIn(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 0, 15)) {
			helper.setBlock(pos, Blocks.DIRT);
		}
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 15, 2, 15)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = colony(helper, new BlockPos(6, 3, 2));
		colony.addOrder(Items.COBBLESTONE, 1000);
		int y = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(0, 3, 7)), List.of(y), 1, 7, 4, 1, Mineshaft.Layout.STAIRCASE);
		dig(helper, colony, shaft);
		// The other branch is dug already, so both miners work the one left.
		Mineshaft.Branch branch = shaft.branches(y).getFirst();
		shaft.branches(y).getLast().cells().forEach(cell -> helper.getLevel().setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState()));
		// A roof over the branch, so it is a tunnel: in and out only through the hall.
		branch.cells().forEach(cell -> helper.getLevel().setBlockAndUpdate(cell.above(), Blocks.STONE.defaultBlockState()));
		List<Mineshaft.Branch> segments = branch.segments();
		helper.assertTrue(segments.size() == 2, segments.size() + " segments");
		List<AntBot> bots = new ArrayList<>();
		for (BlockPos at : List.of(new BlockPos(3, 3, 3), new BlockPos(5, 3, 4))) {
			AntBot bot = member(helper, colony, at.getX(), at.getY(), at.getZ());
			bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
			// The scaffold blocks a miner keeps, so it takes all of its haul out after the first segment.
			bot.getInventory().addItem(new ItemStack(Items.DIRT, 8));
			bots.add(bot);
		}
		Set<UUID> diggers = new HashSet<>();
		helper.onEachTick(() -> bots.forEach(bot -> colony.board().jobOf(bot.getUUID())
			.filter(job -> job.type() == JobType.MINE_BRANCH).ifPresent(job -> diggers.add(bot.getUUID()))));
		helper.succeedWhen(() -> {
			for (BlockPos cell : branch.cells()) {
				BlockState state = helper.getLevel().getBlockState(cell);
				helper.assertTrue(state.isAir() || state.is(Blocks.TORCH), "branch not dug at " + relative(helper, cell).toShortString());
			}
			helper.assertTrue(diggers.size() == 2, diggers.size() + " miners dug the branch");
			for (AntBot bot : bots) {
				helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 0,
					"a miner still holds its haul, at " + relative(helper, bot.blockPosition()).toShortString());
				helper.assertTrue(!branch.cells().contains(bot.blockPosition()), "a miner is still in the branch, at " + relative(helper, bot.blockPosition()).toShortString());
			}
			helper.assertTrue(core(helper, new BlockPos(6, 3, 2)).countItem(Items.COBBLESTONE) > 0, "no haul in the core");
		});
	}

	/**
	 * A branch of the deepest level starts with bedrock, and the cells behind it are walled in
	 * with bedrock all round. They are within reach of the hall, but a miner cannot see them, so like
	 * a player it cannot break them: it gives the branch up instead.
	 */
	@GameTest(maxTicks = 30)
	public void cellBehindAWallIsNotMinedFromTheOtherSide(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Mineshaft.Branch branch = shaft.branches(shaft.levels().getLast()).getFirst();
		List<BlockPos> walledIn = branch.cells().subList(2, 6);
		for (BlockPos cell : walledIn) {
			for (Direction dir : Direction.values()) {
				BlockPos side = cell.relative(dir);
				if (!walledIn.contains(side)) {
					helper.getLevel().setBlockAndUpdate(side, Blocks.BEDROCK.defaultBlockState());
				}
			}
		}
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.onEachTick(() -> {
			for (BlockPos cell : walledIn) {
				if (!helper.getLevel().getBlockState(cell).is(Blocks.STONE)) {
					helper.fail("mined the walled-in cell at " + relative(helper, cell).toShortString() + " through bedrock");
				}
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(colony.board().jobs().stream()
			.anyMatch(job -> job.target().equals(branch.start()) && job.givenUp()), "walled-in branch not given up yet"));
	}

	/**
	 * The branches are dug, but the first block of one of the deepest level's is bedrock and the rest is walled in with
	 * bedrock all round, so its torch spot is within reach of the hall but out of sight: like a player, the miner does not
	 * put a torch there through the wall.
	 */
	@GameTest(maxTicks = 40)
	public void torchIsNotPlacedThroughAWall(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		shaft.levels().forEach(y -> shaft.branches(y)
			.forEach(branch -> branch.cells().forEach(cell -> helper.getLevel().setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState()))));
		Mineshaft.Branch walled = shaft.branches(shaft.levels().getLast()).getFirst();
		walled.cells().subList(0, 2).forEach(cell -> helper.getLevel().setBlockAndUpdate(cell, Blocks.BEDROCK.defaultBlockState()));
		for (BlockPos cell : walled.cells().subList(2, walled.cells().size())) {
			for (Direction dir : Direction.values()) {
				if (!walled.cells().contains(cell.relative(dir))) {
					helper.getLevel().setBlockAndUpdate(cell.relative(dir), Blocks.BEDROCK.defaultBlockState());
				}
			}
		}
		BlockPos torch = walled.torches().getFirst();
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		miner.getInventory().addItem(new ItemStack(Items.TORCH, 8));

		boolean[] tried = {false};
		helper.onEachTick(() -> {
			if (helper.getLevel().getBlockState(torch).is(Blocks.TORCH)) {
				helper.fail("torch placed through the bedrock at " + relative(helper, torch).toShortString());
			}
			if (colony.board().jobOf(miner.getUUID()).map(Job::target).filter(walled.start()::equals).isPresent()) {
				tried[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(tried[0], "walled-in branch not taken up yet");
			helper.assertFalse(colony.board().jobOf(miner.getUUID()).map(Job::target).filter(walled.start()::equals).isPresent(), "still on the walled-in branch");
		});
	}

	@GameTest(maxTicks = 640)
	public void branchesDugWithoutTorchesAreLitOnceTorchesReachTheCore(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		// Dug while the colony had no torches.
		List<Mineshaft.Branch> branches = new ArrayList<>();
		shaft.levels().forEach(y -> branches.addAll(shaft.branches(y)));
		branches.forEach(branch -> branch.cells().forEach(cell -> helper.getLevel().setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState())));
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		helper.runAfterDelay(100, () -> core(helper, MINE_CORE).setItem(0, new ItemStack(Items.TORCH, 8)));

		helper.succeedWhen(() -> {
			for (Mineshaft.Branch branch : branches) {
				for (BlockPos torch : branch.torches()) {
					helper.assertTrue(helper.getLevel().getBlockState(torch).is(Blocks.TORCH), "dark at " + relative(helper, torch).toShortString());
				}
			}
			assertLit(helper, branches.stream().flatMap(branch -> branch.cells().stream()).toList());
		});
	}

	/** A miner at the deepest landing with a pickaxe and every other slot full of {@code haul}. */
	private static AntBot fullMiner(GameTestHelper helper, Colony colony, Mineshaft shaft, ItemStack... haul) {
		BlockPos landing = relative(helper, shaft.landing(shaft.levels().getLast()));
		AntBot miner = member(helper, colony, landing.getX(), landing.getY(), landing.getZ());
		helper.assertTrue(miner.blockPosition().equals(shaft.landing(shaft.levels().getLast())), "miner not spawned on the landing");
		SimpleContainer inventory = miner.getInventory();
		inventory.addItem(new ItemStack(Items.STONE_PICKAXE));
		for (ItemStack stack : haul) {
			inventory.addItem(stack);
		}
		while (inventory.canAddItem(new ItemStack(Items.DIRT))) {
			inventory.addItem(new ItemStack(Items.DIRT, 64));
		}
		return miner;
	}

	private static Container landingChest(GameTestHelper helper, Mineshaft shaft) {
		return Storage.containerAt(helper.getLevel(), shaft.landingChest(shaft.levels().getLast()));
	}

	private static boolean anyBranchDug(GameTestHelper helper, Mineshaft shaft) {
		return shaft.branches(shaft.levels().getLast()).stream().anyMatch(branch -> helper.getLevel().getBlockState(branch.start()).isAir());
	}

	@GameTest(maxTicks = 50)
	public void fullMinerUnloadsIntoTheLandingChestAndGoesBackToItsBranch(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		fullMiner(helper, colony, shaft);

		helper.onEachTick(() -> {
			if (core(helper, MINE_CORE).countItem(Items.DIRT) > 0) {
				helper.fail("miner walked its haul up to the core");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(landingChest(helper, shaft).countItem(Items.DIRT) > 0, "landing chest is empty");
			helper.assertTrue(anyBranchDug(helper, shaft), "no branch dug");
		});
	}

	/**
	 * A miner on the deepest level stores the cobblestone of its branches, which only a stock target wants, in the
	 * landing chest it sees there rather than climbing out to the core on top of the shaft after each branch.
	 */
	@GameTest(maxTicks = 700)
	public void minerAtALowerLevelDepositsWithoutLeavingTheShaft(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		GameTests.failOnGiveUp(helper, colony);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		miner.getInventory().addItem(new ItemStack(Items.TORCH, 16));

		helper.onEachTick(() -> {
			// A hauler may bring it up later, once it is in the landing chest.
			if (core(helper, MINE_CORE).countItem(Items.COBBLESTONE) > 0 && landingChest(helper, shaft).countItem(Items.COBBLESTONE) == 0) {
				helper.fail("miner climbed out to the core with its haul");
			}
		});
		helper.succeedWhen(() -> {
			assertBranchesDug(helper, shaft, shaft.levels().getLast());
			helper.assertTrue(landingChest(helper, shaft).countItem(Items.COBBLESTONE) > 0, "no cobblestone in the landing chest");
		});
	}

	/** A miner out of torches at the deepest level takes them from the landing chest there rather than climbing out to the core. */
	@GameTest(maxTicks = 350)
	public void minerOutOfTorchesTakesThemFromTheLandingChest(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		GameTests.failOnGiveUp(helper, colony);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.TORCH, 16));
		// Both landing chests, so no haul job takes the miner up to top one up.
		shaft.levels().forEach(y -> Storage.containerAt(helper.getLevel(), shaft.landingChest(y)).setItem(0, new ItemStack(Items.TORCH, 16)));
		int deepest = shaft.levels().getLast();
		BlockPos landing = relative(helper, shaft.landing(deepest));
		AntBot miner = member(helper, colony, landing.getX(), landing.getY(), landing.getZ());
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		int surface = helper.absolutePos(MINE_CORE).getY();

		helper.onEachTick(() -> {
			if (miner.getBlockY() >= surface) {
				helper.fail("miner climbed out of the shaft");
			}
		});
		helper.succeedWhen(() -> assertBranchesDug(helper, shaft, deepest));
	}

	/**
	 * The mine is the colony's, however far it reaches past the colony's radius (its branches do, 64 blocks out by
	 * default): miners with no job at the bottom of the shaft, at the landing and at a branch end, stay there rather
	 * than walk home to the core.
	 */
	@GameTest(maxTicks = 140)
	public void idleMinersAtTheShaftBottomStayInTheMine(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		colony.setRadius(1);
		int bottom = shaft.levels().getLast();
		Mineshaft.Branch branch = shaft.branches(bottom).getLast();
		branch.cells().forEach(cell -> helper.getLevel().setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState()));
		BlockPos landing = relative(helper, shaft.landing(bottom));
		BlockPos branchEnd = relative(helper, branch.cells().get(branch.cells().size() - 2));
		List<AntBot> miners = List.of(member(helper, colony, landing.getX(), landing.getY(), landing.getZ()),
			member(helper, colony, branchEnd.getX(), branchEnd.getY(), branchEnd.getZ()));
		miners.forEach(miner -> miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE)));

		helper.onEachTick(() -> miners.forEach(miner -> {
			if (miner.getBlockY() > bottom + 1) {
				helper.fail("idle miner climbed out of the mine to " + relative(helper, miner.blockPosition()).toShortString());
			}
		}));
		helper.runAfterDelay(120, helper::succeed);
	}

	@GameTest(maxTicks = 240)
	public void minerBringsWhatTheColonyOrderedToTheCore(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		colony.addOrder(Items.RAW_IRON, 1);
		fullMiner(helper, colony, shaft, new ItemStack(Items.RAW_IRON));

		helper.onEachTick(() -> {
			if (landingChest(helper, shaft).countItem(Items.RAW_IRON) > 0) {
				helper.fail("ordered raw iron left in the landing chest");
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(core(helper, MINE_CORE).countItem(Items.RAW_IRON) == 1, "raw iron not in the core"));
	}

	/**
	 * Cobblestone for an ordered stone pickaxe goes up to the core; the dirt it carries along, which
	 * nobody wants, stays in the landing chest.
	 */
	@GameTest(maxTicks = 130)
	public void minerLeavesWhatNobodyWantsInTheLandingChestOnItsWayToTheCore(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		colony.addOrder(Items.STONE_PICKAXE, 1);
		fullMiner(helper, colony, shaft, new ItemStack(Items.COBBLESTONE, 3));

		helper.onEachTick(() -> {
			if (core(helper, MINE_CORE).countItem(Items.DIRT) > 0) {
				helper.fail("miner walked the dirt up to the core");
			}
			if (landingChest(helper, shaft).countItem(Items.COBBLESTONE) > 0) {
				helper.fail("cobblestone for the order left in the landing chest");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(landingChest(helper, shaft).countItem(Items.DIRT) > 0, "dirt not in the landing chest");
			// It may dig more on its way back; what it carried comes up at least.
			helper.assertTrue(core(helper, MINE_CORE).countItem(Items.COBBLESTONE) >= 3, "cobblestone not in the core");
		});
	}

	@GameTest(maxTicks = 190)
	public void haulerEmptiesTheLandingChestIntoTheCore(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Container chest = landingChest(helper, shaft);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		chest.setItem(1, new ItemStack(Items.RAW_IRON, 3));
		// Without a storage area for the surplus, only what the colony wants comes up.
		wantStone(colony);
		colony.setStockTarget(StockTarget.of(Items.RAW_IRON, 64));
		// The colony counts it as its own already, so nothing down there gets ordered again.
		helper.assertTrue(Storage.colonyStock(helper.getLevel(), colony).getOrDefault(Items.RAW_IRON, 0) == 3, "landing chest not in colony stock");
		member(helper, colony, 1, 5, 1);

		helper.succeedWhen(() -> {
			helper.assertTrue(chest.isEmpty(), "landing chest not emptied");
			Container core = core(helper, MINE_CORE);
			helper.assertTrue(core.countItem(Items.COBBLESTONE) == 64 && core.countItem(Items.RAW_IRON) == 3, "haul not in the core");
		});
	}

	/** A hauler brings torches down from the core to the landing chests, and leaves them there. */
	@GameTest(maxTicks = 200)
	public void haulerStocksTheLandingChestsWithTorches(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.TORCH, 64));
		// Torches are wanted in the core too, so none come back up.
		colony.setStockTarget(StockTarget.of(Items.TORCH, 64));
		member(helper, colony, 1, 5, 1);
		List<Container> chests = shaft.levels().stream().map(y -> Storage.containerAt(helper.getLevel(), shaft.landingChest(y))).toList();

		helper.succeedWhen(() -> {
			for (Container chest : chests) {
				helper.assertTrue(chest.countItem(Items.TORCH) > 0, "no torches in a landing chest");
			}
			helper.assertTrue(core(helper, MINE_CORE).countItem(Items.TORCH) > 0, "core emptied of torches");
		});
	}

	/** A build opened while the colony's only torches sit in a landing chest gets them brought up, and only what it needs. */
	@GameTest(maxTicks = 210)
	public void aBuildGetsTorchesBroughtUpFromALandingChest(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		GameTests.failOnGiveUp(helper, colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Container chest = Storage.containerAt(helper.getLevel(), shaft.landingChest(shaft.levels().getLast()));
		chest.setItem(0, new ItemStack(Items.TORCH, 16));
		BlockPos torch = new BlockPos(1, 5, 6);
		colony.addBuild(new BuildPlan(List.of(new BuildPlan.Step(helper.absolutePos(torch), Blocks.TORCH.defaultBlockState()))));
		member(helper, colony, 1, 5, 1);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.TORCH, torch);
			helper.assertTrue(chest.countItem(Items.TORCH) == 15, "landing chest holds " + chest.countItem(Items.TORCH) + " torches, not 15");
		});
	}

	/** A build opened while the colony's only cobblestone sits in a landing chest, where a miner left it, gets it brought up, and only what it needs. */
	@GameTest(maxTicks = 210)
	public void aBuildGetsCobblestoneBroughtUpFromALandingChest(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		GameTests.failOnGiveUp(helper, colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Container chest = Storage.containerAt(helper.getLevel(), shaft.landingChest(shaft.levels().getLast()));
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		BlockPos block = new BlockPos(1, 5, 6);
		colony.addBuild(new BuildPlan(List.of(new BuildPlan.Step(helper.absolutePos(block), Blocks.COBBLESTONE.defaultBlockState()))));
		member(helper, colony, 1, 5, 1);

		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.COBBLESTONE, block);
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 63, "landing chest holds " + chest.countItem(Items.COBBLESTONE) + " cobblestone, not 63");
		});
	}

	/** A bot carrying torches of its own that brings up the one a build needs from a landing chest puts only that one in the core. */
	@GameTest(maxTicks = 190)
	public void aHaulerCarryingTorchesBringsUpOnlyTheAskedCount(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		GameTests.failOnGiveUp(helper, colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Container chest = Storage.containerAt(helper.getLevel(), shaft.landingChest(shaft.levels().getLast()));
		chest.setItem(0, new ItemStack(Items.TORCH, 16));
		BlockPos torch = new BlockPos(1, 5, 6);
		colony.addBuild(new BuildPlan(List.of(new BuildPlan.Step(helper.absolutePos(torch), Blocks.TORCH.defaultBlockState()))));
		AntBot bot = member(helper, colony, 1, 5, 1);
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 16));
		// Straight on to the haul job, as a miner from its branch, before it stores its torches between jobs.
		long now = helper.getLevel().getGameTime();
		BlockPos landing = shaft.landingChest(shaft.levels().getLast());
		colony.board().post(JobType.HAUL, landing);
		helper.assertTrue(colony.board().claim(bot.getUUID(), Set.of(JobType.HAUL), landing, now).isPresent(), "haul job not claimed");

		helper.onEachTick(() -> {
			if (core(helper, MINE_CORE).countItem(Items.TORCH) > 1) {
				helper.fail("core holds " + core(helper, MINE_CORE).countItem(Items.TORCH) + " torches, the hauler's own with the one asked");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.TORCH, torch);
			helper.assertTrue(chest.countItem(Items.TORCH) == 15, "landing chest holds " + chest.countItem(Items.TORCH) + " torches, not 15");
		});
	}

	/** Torches brought down to a landing chest that filled up meanwhile go back to the core in the same haul job. */
	@GameTest(maxTicks = 210)
	public void torchesThatDoNotFitAFullLandingChestGoBackToTheCore(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.TORCH, 64));
		colony.setStockTarget(StockTarget.of(Items.TORCH, 64));
		// Full of what the colony does not want, but for the slot each chest leaves for torches.
		List<Container> chests = shaft.levels().stream().map(y -> Storage.containerAt(helper.getLevel(), shaft.landingChest(y))).toList();
		chests.forEach(chest -> {
			for (int i = 1; i < chest.getContainerSize(); i++) {
				chest.setItem(i, new ItemStack(Items.DIRT, 64));
			}
		});
		AntBot bot = member(helper, colony, 1, 5, 1);

		boolean[] filled = {false};
		helper.onEachTick(() -> {
			if (!filled[0] && bot.getInventory().countItem(Items.TORCH) > 0) {
				// The chests fill up while the bot is on its way down.
				chests.forEach(chest -> chest.setItem(0, new ItemStack(Items.DIRT, 64)));
				filled[0] = true;
			}
			if (bot.getInventory().countItem(Items.TORCH) > 0 && colony.board().jobOf(bot.getUUID()).isEmpty()) {
				helper.fail("bot kept " + bot.getInventory().countItem(Items.TORCH) + " torches after its haul job");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(filled[0], "no torches taken down");
			helper.assertTrue(core(helper, MINE_CORE).countItem(Items.TORCH) == 64, "core holds " + core(helper, MINE_CORE).countItem(Items.TORCH) + " torches");
			helper.assertTrue(colony.board().jobs().stream().noneMatch(job -> job.type() == JobType.HAUL), "haul job left for a full chest");
		});
	}

	/** A miner whose pickaxe breaks at the deepest level takes the spare in the landing chest there rather than climbing out to the core. */
	@GameTest(maxTicks = 620)
	public void minerWhosePickaxeBreaksTakesASpareFromTheLandingChest(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		GameTests.failOnGiveUp(helper, colony);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
		shaft.levels().forEach(y -> Storage.containerAt(helper.getLevel(), shaft.landingChest(y)).setItem(0, new ItemStack(Items.TORCH, 16)));
		int deepest = shaft.levels().getLast();
		Container chest = Storage.containerAt(helper.getLevel(), shaft.landingChest(deepest));
		chest.setItem(1, new ItemStack(Items.WOODEN_PICKAXE));
		BlockPos landing = relative(helper, shaft.landing(deepest));
		AntBot miner = member(helper, colony, landing.getX(), landing.getY(), landing.getZ());
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		int surface = helper.absolutePos(MINE_CORE).getY();

		boolean[] broke = {false};
		helper.onEachTick(() -> {
			if (!broke[0] && anyBranchDug(helper, shaft)) {
				// Its pickaxe breaks: gone from hand and pack.
				if (miner.getMainHandItem().is(Items.WOODEN_PICKAXE)) {
					miner.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
				}
				miner.getInventory().removeItemType(Items.WOODEN_PICKAXE, 1);
				broke[0] = true;
			}
			if (miner.getBlockY() >= surface) {
				helper.fail("miner climbed out of the shaft");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(broke[0], "pickaxe never broke");
			assertBranchesDug(helper, shaft, deepest);
			helper.assertTrue(chest.countItem(Items.WOODEN_PICKAXE) == 0, "spare left in the landing chest");
		});
	}

	/** A bot brings a spare pickaxe down from the core to a landing chest, leaving the one an open job declares in the core. */
	@GameTest(maxTicks = 100)
	public void haulerKeepsASparePickaxeInALandingChest(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.STONE_PICKAXE));
		core(helper, MINE_CORE).setItem(1, new ItemStack(Items.STONE_PICKAXE));
		// An open job no bot here can take, which declares a stone pickaxe.
		BlockPos ore = helper.absolutePos(new BlockPos(7, 1, 7));
		helper.getLevel().setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
		colony.board().post(JobType.MINE_VEIN, ore, List.of(ore), Optional.empty(), helper.getLevel().getGameTime(), Optional.of(Items.STONE_PICKAXE));
		member(helper, colony, 1, 5, 1);
		List<Container> chests = shaft.levels().stream().map(y -> Storage.containerAt(helper.getLevel(), shaft.landingChest(y))).toList();

		helper.onEachTick(() -> {
			if (core(helper, MINE_CORE).countItem(Items.STONE_PICKAXE) == 0) {
				helper.fail("took the pickaxe an open job declares out of the core");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(chests.stream().mapToInt(chest -> chest.countItem(Items.STONE_PICKAXE)).sum() == 1, "no spare pickaxe in a landing chest");
			helper.assertTrue(core(helper, MINE_CORE).countItem(Items.STONE_PICKAXE) == 1, "core holds no stone pickaxe");
		});
	}

	/** A landing chest with two pickaxes, such as one a miner swapped out for the spare, keeps the better one and gets the other hauled up to the core. */
	@GameTest(maxTicks = 200)
	public void haulerBringsAnExtraPickaxeUpFromALandingChest(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Container chest = landingChest(helper, shaft);
		chest.setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
		chest.setItem(1, new ItemStack(Items.STONE_PICKAXE));
		member(helper, colony, 1, 5, 1);

		helper.onEachTick(() -> {
			if (chest.countItem(Items.STONE_PICKAXE) == 0) {
				helper.fail("took the better pickaxe out of the landing chest");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper, MINE_CORE).countItem(Items.WOODEN_PICKAXE) == 1, "extra pickaxe not in the core");
			helper.assertTrue(chest.countItem(Items.WOODEN_PICKAXE) == 0, "extra pickaxe left in the landing chest");
		});
	}

	/** A tree job posted near a bot that carries a landing chest's load waits until the load is in the core. */
	@GameTest(maxTicks = 460)
	public void aBotMidHaulDeliversItsLoadBeforeItTakesATreeJob(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Container chest = landingChest(helper, shaft);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		wantStone(colony);
		AntBot bot = member(helper, colony, 1, 5, 1);
		List<BlockPos> logs = List.of(helper.absolutePos(new BlockPos(6, 5, 6)), helper.absolutePos(new BlockPos(6, 6, 6)));
		boolean[] posted = {false};

		helper.onEachTick(() -> {
			if (!posted[0] && bot.getInventory().countItem(Items.COBBLESTONE) > 0) {
				logs.forEach(log -> helper.getLevel().setBlockAndUpdate(log, Blocks.OAK_LOG.defaultBlockState()));
				colony.board().post(JobType.CHOP_TREE, logs.getFirst(), logs);
				posted[0] = true;
			}
			boolean chopped = posted[0] && logs.stream().anyMatch(log -> !helper.getLevel().getBlockState(log).is(Blocks.OAK_LOG));
			if (chopped && core(helper, MINE_CORE).countItem(Items.COBBLESTONE) < 64) {
				helper.fail("bot chopped the tree before it delivered its load");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(posted[0], "bot never took the load");
			helper.assertTrue(core(helper, MINE_CORE).countItem(Items.COBBLESTONE) == 64, "load not in the core");
			helper.assertTrue(logs.stream().noneMatch(log -> helper.getLevel().getBlockState(log).is(Blocks.OAK_LOG)), "tree not chopped");
		});
	}

	/** Any colony bot hauls up what the colony wants. */
	@GameTest(maxTicks = 190)
	public void colonyBotOfAnotherRoleEmptiesTheLandingChestIntoTheCore(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Container chest = landingChest(helper, shaft);
		chest.setItem(0, new ItemStack(Items.RAW_IRON, 3));
		colony.addOrder(Items.RAW_IRON, 3);
		AntBot guard = member(helper, colony, 1, 5, 1);

		helper.succeedWhen(() -> {
			helper.assertTrue(chest.isEmpty(), "landing chest not emptied");
			helper.assertTrue(core(helper, MINE_CORE).countItem(Items.RAW_IRON) == 3, "raw iron not in the core");
		});
	}

	/** Turns the landing chest at {@code pos} into a double chest, with its other half in the stone beside it. */
	private static Container doubleChest(GameTestHelper helper, Mineshaft shaft, BlockPos pos) {
		BlockState state = helper.getLevel().getBlockState(pos);
		Direction facing = state.getValue(ChestBlock.FACING);
		List<BlockPos> path = dugPath(shaft, false);
		// A LEFT half's other half is clockwise of its facing.
		boolean left = !path.contains(pos.relative(facing.getClockWise()));
		BlockPos other = pos.relative(left ? facing.getClockWise() : facing.getCounterClockWise());
		helper.getLevel().setBlockAndUpdate(pos, state.setValue(ChestBlock.TYPE, left ? ChestType.LEFT : ChestType.RIGHT));
		helper.getLevel().setBlockAndUpdate(other, state.setValue(ChestBlock.TYPE, left ? ChestType.RIGHT : ChestType.LEFT));
		return Storage.containerAt(helper.getLevel(), pos);
	}

	private static int count(Container container) {
		int count = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			count += container.getItem(i).getCount();
		}
		return count;
	}

	@GameTest(maxTicks = 240)
	public void twoHaulersEmptyDifferentLandingChests(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		// Without a storage area for the surplus, only what the colony wants comes up: more than both chests hold.
		colony.setStockTarget(StockTarget.of(Items.COBBLESTONE, 5000));
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		List<BlockPos> positions = shaft.levels().stream().map(shaft::landingChest).toList();
		List<Container> chests = new ArrayList<>();
		for (BlockPos pos : positions) {
			Container chest = doubleChest(helper, shaft, pos);
			helper.assertTrue(chest.getContainerSize() == 54, "not a double chest at " + pos);
			// More than a hauler carries at once, so each chest takes two trips.
			for (int i = 0; i < 28; i++) {
				chest.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
			}
			chests.add(chest);
		}
		List<AntBot> haulers = List.of(member(helper, colony, 1, 5, 1), member(helper, colony, 1, 5, 1));
		List<Set<AntBot>> loaders = List.of(new HashSet<>(), new HashSet<>());
		int[] inChest = chests.stream().mapToInt(MinerGameTest::count).toArray();
		int[] carried = new int[haulers.size()];

		helper.onEachTick(() -> {
			// The colony uses up whatever reaches the core, so it never fills.
			core(helper, MINE_CORE).clearContent();
			for (int c = 0; c < chests.size(); c++) {
				int now = count(chests.get(c));
				if (now < inChest[c]) {
					BlockPos pos = positions.get(c);
					// Whoever picked items up this tick, nearest the chest.
					haulers.stream()
						.filter(hauler -> count(hauler.getInventory()) > carried[haulers.indexOf(hauler)])
						.min(Comparator.comparingDouble(hauler -> hauler.distanceToSqr(pos.getX(), pos.getY(), pos.getZ())))
						.ifPresent(loaders.get(c)::add);
					if (loaders.get(c).size() > 1) {
						helper.fail("both haulers loaded from the landing chest at " + pos);
					}
				}
				inChest[c] = now;
			}
			for (int h = 0; h < haulers.size(); h++) {
				carried[h] = count(haulers.get(h).getInventory());
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(chests.stream().allMatch(Container::isEmpty), "landing chests not emptied");
			helper.assertTrue(!loaders.get(0).equals(loaders.get(1)), "one hauler emptied both chests");
		});
	}

	/** A stone block in the wall of the deepest level's first branch, beside its second floor cell. */
	private static BlockPos wall(GameTestHelper helper, Mineshaft shaft) {
		Mineshaft.Branch branch = shaft.branches(shaft.levels().getLast()).getFirst();
		List<BlockPos> dug = dugPath(shaft, true);
		BlockPos cell = branch.cells().get(2);
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos side = cell.relative(dir);
			BlockPos rel = relative(helper, side);
			if (!dug.contains(side) && rel.getX() >= 0 && rel.getX() < 8 && rel.getZ() >= 0 && rel.getZ() < 8) {
				return side;
			}
		}
		throw new IllegalStateException("no wall beside " + cell);
	}

	private static boolean branchesDug(GameTestHelper helper, Mineshaft shaft) {
		return shaft.branches(shaft.levels().getLast()).stream().flatMap(branch -> branch.cells().stream())
			.allMatch(cell -> helper.getLevel().getBlockState(cell).isAir() || helper.getLevel().getBlockState(cell).is(Blocks.TORCH));
	}

	/** A wooden pickaxe cannot harvest iron ore, so the miner leaves it in the wall. */
	@GameTest(maxTicks = 710)
	public void woodenPickaxeLeavesIronOreInTheWall(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		BlockPos ore = wall(helper, shaft);
		helper.getLevel().setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));

		helper.onEachTick(() -> {
			if (!helper.getLevel().getBlockState(ore).is(Blocks.IRON_ORE)) {
				helper.fail("broke iron ore with a wooden pickaxe");
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(branchesDug(helper, shaft), "branches not dug"));
	}

	@GameTest(maxTicks = 520)
	public void stonePickaxeMinesIronOreInTheWall(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStoneAndIron(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		BlockPos ore = wall(helper, shaft);
		helper.getLevel().setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getLevel().getBlockState(ore).isAir(), "iron ore not mined");
			helper.assertTrue(Storage.colonyStock(helper.getLevel(), colony).getOrDefault(Items.RAW_IRON, 0) == 1, "raw iron not stored");
		});
	}

	/** A diamond ore its stone pickaxe left in a branch wall is mined once the miner gets an iron pickaxe, after the branch was done. */
	@GameTest(maxTicks = 1060)
	public void ironPickaxeSendsTheMinerBackForADiamondItLeft(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		GameTests.failOnGiveUp(helper, colony);
		colony.setStockTarget(StockTarget.of(Items.DIAMOND, 1));
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		BlockPos ore = wall(helper, shaft);
		helper.getLevel().setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		BlockPos start = shaft.branches(shaft.levels().getLast()).getFirst().start();
		boolean[] given = {false};

		helper.onEachTick(() -> {
			// Its branch is done and off the board: only a new job brings the miner back.
			if (!given[0] && branchesDug(helper, shaft) && !colony.board().has(JobType.MINE_BRANCH, start)) {
				helper.assertTrue(helper.getLevel().getBlockState(ore).is(Blocks.DIAMOND_ORE), "diamond ore broken with a stone pickaxe");
				miner.getInventory().addItem(new ItemStack(Items.IRON_PICKAXE));
				given[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(given[0], "branch not done yet");
			helper.assertTrue(helper.getLevel().getBlockState(ore).isAir(), "diamond ore not mined");
			int diamonds = Storage.colonyStock(helper.getLevel(), colony).getOrDefault(Items.DIAMOND, 0) + miner.getInventory().countItem(Items.DIAMOND);
			helper.assertTrue(diamonds == 1, "no diamond");
		});
	}

	/**
	 * {@code size} blocks from {@code from} on, each touching one before it, out of sight of the mineshaft: inside the
	 * stone of the test area and not beside anything dug. {@code from} itself may show.
	 */
	private static List<BlockPos> hidden(GameTestHelper helper, Mineshaft shaft, BlockPos from, int size) {
		Set<BlockPos> dug = new HashSet<>(dugPath(shaft, true));
		List<BlockPos> blocks = new ArrayList<>(List.of(from));
		for (int i = 0; i < blocks.size() && blocks.size() < size; i++) {
			for (Direction dir : Direction.values()) {
				BlockPos next = blocks.get(i).relative(dir);
				BlockPos rel = relative(helper, next);
				boolean inside = rel.getX() >= 0 && rel.getX() < 8 && rel.getZ() >= 0 && rel.getZ() < 8 && rel.getY() >= 1 && rel.getY() <= 4;
				if (inside && blocks.size() < size && !blocks.contains(next) && Direction.stream().noneMatch(side -> dug.contains(next.relative(side)))) {
					blocks.add(next);
				}
			}
		}
		helper.assertTrue(blocks.size() == size, "no room for " + size + " hidden blocks");
		return blocks;
	}

	/** An iron vein that shows one block in a branch wall is mined whole, the blocks behind it too. */
	@GameTest(maxTicks = 780)
	public void minerMinesAWholeIronVeinThatShowsOneBlockInABranchWall(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStoneAndIron(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		List<BlockPos> vein = hidden(helper, shaft, wall(helper, shaft), 5);
		vein.forEach(pos -> helper.getLevel().setBlockAndUpdate(pos, Blocks.IRON_ORE.defaultBlockState()));
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.succeedWhen(() -> {
			vein.forEach(pos -> helper.assertTrue(helper.getLevel().getBlockState(pos).isAir(), "iron ore left at " + relative(helper, pos).toShortString()));
			int iron = Storage.colonyStock(helper.getLevel(), colony).getOrDefault(Items.RAW_IRON, 0) + miner.getInventory().countItem(Items.RAW_IRON);
			helper.assertTrue(iron == 5, "raw iron: " + iron);
			// The pocket is part of the mine, so a bot left idle there stays.
			helper.assertTrue(colony.mineCells().containsAll(vein), "the dug vein is not part of the mine");
		});
	}

	/**
	 * Water hidden in the rock beside an ore of a vein, where the miner sees it from nowhere it can stand: like a player,
	 * it does not wall it off blind, and leaves that ore rather than break into the water.
	 */
	@GameTest(maxTicks = 520)
	public void oreBesideWaterTheMinerCannotSeeIsLeft(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStoneAndIron(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		List<BlockPos> blocks = hidden(helper, shaft, wall(helper, shaft), 3);
		List<BlockPos> vein = blocks.subList(0, 2);
		BlockPos water = blocks.get(2);
		vein.forEach(pos -> helper.getLevel().setBlockAndUpdate(pos, Blocks.IRON_ORE.defaultBlockState()));
		helper.getLevel().setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		miner.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));

		helper.onEachTick(() -> {
			vein.forEach(pos -> helper.assertTrue(helper.getLevel().getFluidState(pos).isEmpty(), "water in the vein at " + relative(helper, pos).toShortString()));
			helper.assertTrue(helper.getLevel().getBlockState(water).is(Blocks.WATER), "hidden water walled off or let out");
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getLevel().getBlockState(vein.getFirst()).isAir(), "the ore in the wall not mined");
			helper.assertTrue(helper.getLevel().getBlockState(vein.get(1)).is(Blocks.IRON_ORE), "the ore beside the water mined");
			helper.assertTrue(colony.board().jobs().stream().anyMatch(job -> job.type() == JobType.MINE_VEIN && job.isOpen() && job.tries() > 0),
				"vein not given back to retry");
		});
	}

	/**
	 * Water beside the ore of a vein, which the miner cannot see from where it starts: like a player, it walls it off only
	 * from within reach and in sight of it, walking there first, before the water floods the vein, then mines the ore.
	 */
	@GameTest(maxTicks = 160)
	public void minerWallsOffWaterOnlyOnceItSeesItFromWithinReach(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStoneAndIron(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		// In the wall of a branch of the top level, open to the sky, at its top; the water one further out.
		List<BlockPos> dug = dugPath(shaft, true);
		BlockPos cell = shaft.branches(shaft.levels().getFirst()).getFirst().cells().get(2).atY(helper.absolutePos(new BlockPos(0, 4, 0)).getY());
		Direction out = Direction.Plane.HORIZONTAL.stream().filter(dir -> {
			BlockPos rel = relative(helper, cell.relative(dir, 2));
			return !dug.contains(cell.relative(dir)) && !dug.contains(cell.relative(dir, 2)) && rel.getX() >= 0 && rel.getX() < 8 && rel.getZ() >= 0 && rel.getZ() < 8;
		}).findFirst().orElseThrow();
		BlockPos ore = cell.relative(out);
		BlockPos water = ore.relative(out);
		helper.getLevel().setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
		helper.getLevel().setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());
		OreVein.post(colony.board(), ore, helper.getLevel()::getBlockState, helper.getLevel().getGameTime(), item -> true);
		// The far corner, out of reach of the water.
		BlockPos far = relative(helper, water);
		AntBot miner = member(helper, colony, far.getX() < 4 ? 7 : 0, 5, far.getZ() < 4 ? 7 : 0);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		miner.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));

		Goal sees = new Goal.InSight(water, 4.0, (feet, target, broken) -> LineOfSight.sees(helper.getLevel(), feet, target, broken));
		helper.onEachTick(() -> {
			helper.assertTrue(helper.getLevel().getFluidState(ore).isEmpty(), "water in the vein");
			if (helper.getLevel().getFluidState(water).isEmpty() && !sees.isReached(miner.blockPosition())) {
				helper.fail("water walled off out of reach or sight from " + relative(helper, miner.blockPosition()).toShortString());
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getLevel().getBlockState(ore).isAir(), "iron ore not mined");
			helper.assertTrue(helper.getLevel().getFluidState(water).isEmpty() && !helper.getLevel().getBlockState(water).isAir(), "water not walled off");
		});
	}

	/** The pickaxe a branch job at the deepest level of {@code shaft} declares, the colony wanting iron (a stone one). */
	private static Item need(Mineshaft shaft) {
		return PickaxeTier.forBranch(shaft.levels().getLast(), Items.RAW_IRON::equals);
	}

	/** Its wooden pickaxe harvests no ore, so the miner orders a stone one first, the step to any better one. */
	@GameTest(maxTicks = 30)
	public void ordersAGoodEnoughPickaxeBeforeMining(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStoneAndIron(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		helper.assertTrue(PickaxeTier.toOrder(Items.WOODEN_PICKAXE, need(shaft)) == Items.STONE_PICKAXE, "not a stone pickaxe first");

		helper.onEachTick(() -> {
			if (anyBranchDug(helper, shaft) && !colony.orders().containsKey(Items.STONE_PICKAXE)) {
				helper.fail("mined before ordering a stone pickaxe: " + colony.orders());
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(colony.orders().containsKey(Items.STONE_PICKAXE), "orders: " + colony.orders()));
	}

	@GameTest(maxTicks = 140)
	public void takesAGoodEnoughPickaxeFromTheCoreBeforeMining(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStoneAndIron(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Item need = need(shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(need));
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));

		helper.onEachTick(() -> {
			if (anyBranchDug(helper, shaft) && pickaxes(miner, need) == 0) {
				helper.fail("mined without a " + need);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(pickaxes(miner, need) == 1, "miner has no " + need);
			helper.assertTrue(anyBranchDug(helper, shaft), "no branch dug");
		});
	}

	private static long cellsDug(GameTestHelper helper, Mineshaft shaft) {
		return shaft.branches(shaft.levels().getLast()).stream().flatMap(branch -> branch.cells().stream())
			.filter(cell -> helper.getLevel().getBlockState(cell).isAir() || helper.getLevel().getBlockState(cell).is(Blocks.TORCH)).count();
	}

	/** A miner whose pickaxe of the branch's tier breaks mid-branch fetches another from the core at once, not dig on with the weaker one it carries. */
	@GameTest(maxTicks = 270)
	public void fetchesAGoodEnoughPickaxeWhenItsOwnBreaksMidBranch(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		// Iron, so the branch declares a stone pickaxe.
		colony.setStockTarget(StockTarget.of(Items.RAW_IRON, 1));
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Item need = PickaxeTier.forBranch(shaft.levels().getLast(), Items.RAW_IRON::equals);
		helper.assertTrue(need == Items.STONE_PICKAXE, "the branch takes a " + need);
		core(helper, MINE_CORE).setItem(0, new ItemStack(need));
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		miner.getInventory().addItem(new ItemStack(need));

		long[] dugAtBreak = {-1};
		helper.onEachTick(() -> {
			if (dugAtBreak[0] < 0 && anyBranchDug(helper, shaft)) {
				// Its pickaxe breaks: gone from hand and pack, the wooden one left.
				if (miner.getMainHandItem().is(need)) {
					miner.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
				}
				miner.getInventory().removeItemType(need, 1);
				dugAtBreak[0] = cellsDug(helper, shaft);
			}
			if (dugAtBreak[0] >= 0 && pickaxes(miner, need) == 0 && cellsDug(helper, shaft) > dugAtBreak[0]) {
				helper.fail("dug on with a wooden pickaxe");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(dugAtBreak[0] >= 0, "pickaxe never broke");
			helper.assertTrue(pickaxes(miner, need) == 1, "miner has no " + need);
			helper.assertTrue(core(helper, MINE_CORE).countItem(need) == 0, need + " left in the core");
		});
	}

	@GameTest(maxTicks = 600)
	public void fetchesPickaxeFromCore(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.STONE_PICKAXE));
		core(helper, MINE_CORE).setItem(1, new ItemStack(Items.TORCH, 8));
		member(helper, colony, 1, 5, 1);

		helper.succeedWhen(() -> assertBranchesDug(helper, shaft, shaft.levels().getLast()));
	}

	@GameTest(maxTicks = 20)
	public void ordersPickaxeWhenNoneAvailable(GameTestHelper helper) {
		Colony colony = colony(helper);
		// A colony that wants nothing mining yields needs no pickaxe.
		wantStone(colony);
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> helper.assertTrue(colony.orders().containsKey(Items.WOODEN_PICKAXE), "orders: " + colony.orders()));
	}

	@GameTest(maxTicks = 20)
	public void minerGetsAMineshaftSiteBesideTheCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		wantStone(colony);
		member(helper, colony, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.mineshaft().isPresent(), "no mineshaft site");
			helper.assertTrue(colony.builds().contains(colony.mineshaft().get().plan()), "no plan for the builders");
		});
	}

	/** Bots that join the colony, as freshly built ones do. */
	private static void newcomers(GameTestHelper helper, Colony colony, int count, int y) {
		for (int i = 0; i < count; i++) {
			ColonyManager.get(helper.getLevel()).join(helper.spawn(ModEntities.ANT_BOT, 1 + i, y, 1), colony);
		}
	}

	/** A colony that wants stone picks a mineshaft site, and its bots dig the shaft. */
	@GameTest(maxTicks = 30)
	public void starterMinerGetsMineshaftWork(GameTestHelper helper) {
		// The barrier under the test area cannot be dug through, so the shaft would get no levels.
		for (BlockPos pos : BlockPos.betweenClosed(0, -1, 0, 7, -1, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = colony(helper);
		wantStone(colony);
		newcomers(helper, colony, 3, 1);

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.mineshaft().isPresent(), "no mineshaft site");
			helper.assertTrue(colony.board().jobs().stream().anyMatch(job -> job.type() == JobType.BUILD && !job.isOpen()), "nobody digs the shaft");
		});
	}

	@GameTest(maxTicks = 130)
	public void newColonyStoresCobblestone(GameTestHelper helper) {
		Mineshaft shaft = mine(helper);
		Colony colony = colony(helper, MINE_CORE);
		wantStone(colony);
		// Shallow levels in place of the configured ones, which lie below the test area.
		colony.setMineshaft(shaft);
		// One each for the bot digging a branch and the one digging the shaft.
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.STONE_PICKAXE));
		core(helper, MINE_CORE).setItem(1, new ItemStack(Items.STONE_PICKAXE));
		// The shaft plan places torches, and a new colony cannot make them yet.
		core(helper, MINE_CORE).setItem(2, new ItemStack(Items.TORCH, 8));
		newcomers(helper, colony, 3, 5);

		helper.succeedWhen(() -> helper.assertTrue(core(helper, MINE_CORE).countItem(Items.COBBLESTONE) > 0, "no cobblestone stored yet"));
	}

	private static int pickaxes(AntBot bot, net.minecraft.world.item.Item pickaxe) {
		return bot.getInventory().countItem(pickaxe) + (bot.getMainHandItem().is(pickaxe) ? bot.getMainHandItem().getCount() : 0);
	}

	/**
	 * Cobblestone and sticks in the core become a stone pickaxe that replaces the wooden one. The colony's stock wants
	 * it: no job needs more than the wooden one, so the miner orders none itself.
	 */
	@GameTest(maxTicks = 210)
	public void upgradesToStonePickaxe(GameTestHelper helper) {
		Colony colony = colony(helper);
		colony.setStockTarget(StockTarget.of(Items.STONE_PICKAXE, 1));
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		core(helper).setItem(1, new ItemStack(Items.STICK, 2));
		AntBot miner = member(helper, colony, 1, 4);
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		member(helper, colony, 2, 1);

		helper.onEachTick(() -> {
			if (colony.orders().getOrDefault(Items.STONE_PICKAXE, 0) > 1) {
				helper.fail("upgrade ordered more than once: " + colony.orders());
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(pickaxes(miner, Items.STONE_PICKAXE) == 1, "miner has no stone pickaxe");
			helper.assertTrue(pickaxes(miner, Items.WOODEN_PICKAXE) == 0, "miner kept its wooden pickaxe");
			helper.assertTrue(core(helper).countItem(Items.WOODEN_PICKAXE) == 1, "wooden pickaxe not back in the core");
		});
	}

	@GameTest(maxTicks = 300)
	public void ordersNothingAtTheBestReachableTier(GameTestHelper helper) {
		Colony colony = colony(helper);
		// Plenty for stone pickaxes, but no ingots or diamonds for anything better.
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		core(helper).setItem(1, new ItemStack(Items.STICK, 16));
		AntBot miner = member(helper, colony, 1, 4);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.onEachTick(() -> {
			if (!colony.orders().isEmpty()) {
				helper.fail("miner ordered " + colony.orders());
			}
		});
		helper.runAfterDelay(250, helper::succeed);
	}

	/** Branches that need no more than a wooden pickaxe: the miner orders no stone one, though the colony could make it. */
	@GameTest(maxTicks = 710)
	public void ordersNoStonePickaxeForWoodenBranches(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		helper.assertTrue(PickaxeTier.forBranch(shaft.levels().getLast(), Items.COBBLESTONE::equals) == Items.WOODEN_PICKAXE,
			"branches need more than a wooden pickaxe");
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		core(helper, MINE_CORE).setItem(1, new ItemStack(Items.STICK, 2));
		AntBot miner = member(helper, colony, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));

		helper.onEachTick(() -> {
			if (colony.orders().containsKey(Items.STONE_PICKAXE)) {
				helper.fail("ordered a stone pickaxe no branch needs: " + colony.orders());
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(branchesDug(helper, shaft), "branches not dug"));
	}

	/** A better pickaxe in the core that another bot's job waits for is left for that bot. */
	@GameTest(maxTicks = 160)
	public void leavesAPickaxeAnotherBotsJobWaitsFor(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.STONE_PICKAXE));
		AntBot miner = member(helper, colony, 2, 1);
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		// Walled in, so it cannot fetch the pickaxe its vein job needs, and waits.
		for (BlockPos pos : BlockPos.betweenClosed(5, 1, 5, 7, 3, 7)) {
			helper.setBlock(pos, Blocks.BARRIER);
		}
		helper.setBlock(6, 1, 6, Blocks.AIR);
		helper.setBlock(6, 2, 6, Blocks.AIR);
		BlockPos ore = helper.absolutePos(new BlockPos(6, 3, 6));
		helper.getLevel().setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
		AntBot waiting = member(helper, colony, 6, 6);
		waiting.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		long now = helper.getLevel().getGameTime();
		colony.board().post(JobType.MINE_VEIN, ore, List.of(ore), Optional.empty(), now, Optional.of(Items.STONE_PICKAXE));
		helper.assertTrue(colony.board().claim(waiting.getUUID(), Set.of(JobType.MINE_VEIN), ore, now).isPresent(), "vein job not claimed");

		helper.onEachTick(() -> {
			if (pickaxes(miner, Items.STONE_PICKAXE) > 0) {
				helper.fail("took the stone pickaxe another bot's job waits for");
			}
		});
		helper.runAfterDelay(120, () -> {
			helper.assertTrue(colony.board().jobOf(waiting.getUUID()).isPresent(), "the other bot gave its job up");
			helper.succeed();
		});
	}

	/** A miner whose branch needs a stone pickaxe takes the stone one from the core, leaving the iron one an open vein job needs. */
	@GameTest(maxTicks = 230)
	public void leavesThePickaxeAnOpenJobNeedsWhenAnotherFits(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		colony.setStockTarget(StockTarget.of(Items.RAW_IRON, 1));
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		helper.assertTrue(need(shaft) == Items.STONE_PICKAXE, "the branch takes a " + need(shaft));
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.IRON_PICKAXE));
		core(helper, MINE_CORE).setItem(1, new ItemStack(Items.STONE_PICKAXE));
		BlockPos ore = helper.absolutePos(new BlockPos(7, 1, 7));
		helper.getLevel().setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState());
		colony.board().post(JobType.MINE_VEIN, ore, List.of(ore), Optional.empty(), helper.getLevel().getGameTime(), Optional.of(Items.IRON_PICKAXE));
		AntBot miner = member(helper, colony, 1, 5, 1);

		helper.onEachTick(() -> {
			if (pickaxes(miner, Items.IRON_PICKAXE) > 0) {
				helper.fail("took the iron pickaxe an open job needs");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(pickaxes(miner, Items.STONE_PICKAXE) == 1, "miner has no stone pickaxe");
			helper.assertTrue(cellsDug(helper, shaft) >= 3, "dug " + cellsDug(helper, shaft) + " cells");
		});
	}

	/** A colony that wants stone and has no pickaxe fells a tree for one, crafts it, and a bot takes it up to dig. */
	@GameTest(maxTicks = 440)
	public void colonyMakesItsOwnPickaxe(GameTestHelper helper) {
		Colony colony = colony(helper);
		GameTests.failOnGiveUp(helper, colony);
		wantStone(colony);
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				helper.setBlock(5 + dx, 4, 1 + dz + 1, leaves);
			}
		}
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(5, y, 2, Blocks.OAK_LOG);
		}
		List<AntBot> bots = List.of(member(helper, colony, 2, 3), member(helper, colony, 1, 3), member(helper, colony, 2, 4));

		helper.succeedWhen(() -> helper.assertTrue(bots.stream().anyMatch(bot -> pickaxes(bot, Items.WOODEN_PICKAXE) == 1), "no bot has a pickaxe"));
	}

	@GameTest(maxTicks = 450)
	public void anyColonyBotDigsAMineshaftBranch(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		GameTests.failOnGiveUp(helper, colony);
		wantStone(colony);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		AntBot bot = member(helper, colony, 1, 5, 1);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		int deepest = shaft.levels().getLast();

		helper.succeedWhen(() -> {
			for (Mineshaft.Branch branch : shaft.branches(deepest)) {
				for (BlockPos cell : branch.cells()) {
					helper.assertTrue(helper.getLevel().getBlockState(cell).isAir(), "branch not dug at " + relative(helper, cell).toShortString());
				}
			}
		});
	}

	/**
	 * The colony wants raw iron, so its one branch left at the deepest level needs a stone pickaxe. The
	 * bot with a wooden one is nearer, but leaves it to the bot with a stone one.
	 */
	@GameTest(maxTicks = 30)
	public void ofTwoBotsTheOneCarryingAStonePickaxeTakesTheIronLevelBranch(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		colony.setStockTarget(StockTarget.of(Items.RAW_IRON, 8));
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		int deepest = shaft.levels().getLast();
		shaft.branches(deepest).getFirst().cells().forEach(cell -> helper.getLevel().setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState()));
		BlockPos left = shaft.branches(deepest).getLast().start();
		BlockPos landing = relative(helper, shaft.landing(deepest));
		AntBot wooden = member(helper, colony, landing.getX(), landing.getY(), landing.getZ());
		wooden.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		AntBot stone = member(helper, colony, 1, 5, 1);
		stone.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.onEachTick(() -> {
			if (colony.board().jobOf(wooden.getUUID()).isPresent()) {
				helper.fail("the bot with a wooden pickaxe took " + colony.board().jobOf(wooden.getUUID()).get());
			}
		});
		helper.succeedWhen(() -> {
			Optional<Job> taken = colony.board().jobOf(stone.getUUID());
			helper.assertTrue(taken.isPresent(), "the bot with a stone pickaxe has no job");
			Job job = taken.get();
			helper.assertTrue(job.type() == JobType.MINE_BRANCH && job.target().equals(left), "took " + job);
			helper.assertTrue(job.tool().equals(Optional.of(Items.STONE_PICKAXE)), "the branch needs " + job.tool());
		});
	}
}
