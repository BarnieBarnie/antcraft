package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.TestPositions.relative;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.goal.HaulerGoal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.Mineshaft;
import io.github.barniebarnie.antcraft.work.PickaxeTier;
import io.github.barniebarnie.antcraft.work.Storage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
		return new Mineshaft(helper.absolutePos(new BlockPos(3, 5, 3)), List.of(y3, y1), 1, branchLength, 4, rounds);
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

	private static AntBot member(GameTestHelper helper, Colony colony, BotRole role, int x, int y, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, y, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setRole(role);
		return bot;
	}

	private static AntBot member(GameTestHelper helper, Colony colony, BotRole role, int x, int z) {
		return member(helper, colony, role, x, 1, z);
	}

	@GameTest(maxTicks = 1500)
	public void digsBranchFromLandingAndLightsIt(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.TORCH, 8));
		AntBot miner = member(helper, colony, BotRole.MINER, 1, 5, 1);
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

	@GameTest(maxTicks = 3000)
	public void minersNeverBreakTheStairsOrTorches(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.TORCH, 8));
		// Built by a builder, in the way of a branch.
		Mineshaft.Branch first = shaft.branches(shaft.levels().getLast()).getFirst();
		BlockPos planks = first.cells().get(3);
		helper.getLevel().setBlockAndUpdate(planks, Blocks.OAK_PLANKS.defaultBlockState());
		colony.protect(planks, Blocks.OAK_PLANKS);
		for (int i = 0; i < 2; i++) {
			AntBot miner = member(helper, colony, BotRole.MINER, 1 + i, 5, 1);
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
		});
		helper.succeedWhen(() -> {
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
	@GameTest(maxTicks = 1500)
	public void minedOutLevelGetsFurtherBranches(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper, 1, 3);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.TORCH, 8));
		AntBot miner = member(helper, colony, BotRole.MINER, 1, 5, 1);
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
	@GameTest(structure = "antcraft-gametest:long", maxTicks = 2400)
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
		int y = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		Mineshaft shaft = new Mineshaft(helper.absolutePos(new BlockPos(0, 3, 7)), List.of(y), 1, 4, 4, 2);
		helper.assertTrue(shaft.branches(y).getFirst().cells().contains(helper.absolutePos(walledOff)), "walled-off cell not in the south branch");
		dig(helper, colony, shaft);
		AntBot miner = member(helper, colony, BotRole.MINER, 5, 3, 3);
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

	@GameTest(maxTicks = 1200)
	public void branchesDugWithoutTorchesAreLitOnceTorchesReachTheCore(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		// Dug while the colony had no torches.
		List<Mineshaft.Branch> branches = new ArrayList<>();
		shaft.levels().forEach(y -> branches.addAll(shaft.branches(y)));
		branches.forEach(branch -> branch.cells().forEach(cell -> helper.getLevel().setBlockAndUpdate(cell, Blocks.AIR.defaultBlockState())));
		AntBot miner = member(helper, colony, BotRole.MINER, 1, 5, 1);
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
		AntBot miner = member(helper, colony, BotRole.MINER, landing.getX(), landing.getY(), landing.getZ());
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

	@GameTest(maxTicks = 600)
	public void fullMinerUnloadsIntoTheLandingChestAndGoesBackToItsBranch(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
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

	@GameTest(maxTicks = 1200)
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
	@GameTest(maxTicks = 1200)
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

	@GameTest(maxTicks = 1200)
	public void haulerEmptiesTheLandingChestIntoTheCore(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Container chest = landingChest(helper, shaft);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
		chest.setItem(1, new ItemStack(Items.RAW_IRON, 3));
		// The colony counts it as its own already, so nothing down there gets ordered again.
		helper.assertTrue(Storage.colonyStock(helper.getLevel(), colony).getOrDefault(Items.RAW_IRON, 0) == 3, "landing chest not in colony stock");
		helper.assertTrue(HaulerGoal.needsHauler(helper.getLevel(), colony), "a full landing chest does not call for a hauler");
		member(helper, colony, BotRole.HAULER, 1, 5, 1);

		helper.succeedWhen(() -> {
			helper.assertTrue(chest.isEmpty(), "landing chest not emptied");
			Container core = core(helper, MINE_CORE);
			helper.assertTrue(core.countItem(Items.COBBLESTONE) == 64 && core.countItem(Items.RAW_IRON) == 3, "haul not in the core");
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

	@GameTest(maxTicks = 1500)
	public void twoHaulersEmptyDifferentLandingChests(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
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
		List<AntBot> haulers = List.of(member(helper, colony, BotRole.HAULER, 1, 5, 1), member(helper, colony, BotRole.HAULER, 1, 5, 1));
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
	@GameTest(maxTicks = 1500)
	public void woodenPickaxeLeavesIronOreInTheWall(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		BlockPos ore = wall(helper, shaft);
		helper.getLevel().setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
		AntBot miner = member(helper, colony, BotRole.MINER, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));

		helper.onEachTick(() -> {
			if (!helper.getLevel().getBlockState(ore).is(Blocks.IRON_ORE)) {
				helper.fail("broke iron ore with a wooden pickaxe");
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(branchesDug(helper, shaft), "branches not dug"));
	}

	@GameTest(maxTicks = 1500)
	public void stonePickaxeMinesIronOreInTheWall(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		BlockPos ore = wall(helper, shaft);
		helper.getLevel().setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState());
		AntBot miner = member(helper, colony, BotRole.MINER, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getLevel().getBlockState(ore).isAir(), "iron ore not mined");
			helper.assertTrue(Storage.colonyStock(helper.getLevel(), colony).getOrDefault(Items.RAW_IRON, 0) == 1, "raw iron not stored");
		});
	}

	/** The pickaxe the deepest level of {@code shaft} needs, the colony wanting no ore in particular. */
	private static Item need(Mineshaft shaft) {
		return PickaxeTier.forLevel(shaft.levels().getLast(), item -> false);
	}

	/** Its wooden pickaxe harvests no ore, so the miner orders a stone one first, the step to any better one. */
	@GameTest(maxTicks = 300)
	public void ordersAGoodEnoughPickaxeBeforeMining(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		AntBot miner = member(helper, colony, BotRole.MINER, 1, 5, 1);
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		helper.assertTrue(PickaxeTier.toOrder(Items.WOODEN_PICKAXE, need(shaft)) == Items.STONE_PICKAXE, "not a stone pickaxe first");

		helper.onEachTick(() -> {
			if (anyBranchDug(helper, shaft) && !colony.orders().containsKey(Items.STONE_PICKAXE)) {
				helper.fail("mined before ordering a stone pickaxe: " + colony.orders());
			}
		});
		helper.succeedWhen(() -> helper.assertTrue(colony.orders().containsKey(Items.STONE_PICKAXE), "orders: " + colony.orders()));
	}

	@GameTest(maxTicks = 600)
	public void takesAGoodEnoughPickaxeFromTheCoreBeforeMining(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		Item need = need(shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(need));
		AntBot miner = member(helper, colony, BotRole.MINER, 1, 5, 1);
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

	@GameTest(maxTicks = 1500)
	public void fetchesPickaxeFromCore(GameTestHelper helper) {
		Colony colony = colony(helper, MINE_CORE);
		Mineshaft shaft = mine(helper);
		dig(helper, colony, shaft);
		core(helper, MINE_CORE).setItem(0, new ItemStack(Items.STONE_PICKAXE));
		core(helper, MINE_CORE).setItem(1, new ItemStack(Items.TORCH, 8));
		member(helper, colony, BotRole.MINER, 1, 5, 1);

		helper.succeedWhen(() -> assertBranchesDug(helper, shaft, shaft.levels().getLast()));
	}

	@GameTest(maxTicks = 200)
	public void ordersPickaxeWhenNoneAvailable(GameTestHelper helper) {
		Colony colony = colony(helper);
		member(helper, colony, BotRole.MINER, 1, 4);

		helper.succeedWhen(() -> helper.assertTrue(colony.orders().containsKey(Items.WOODEN_PICKAXE), "orders: " + colony.orders()));
	}

	@GameTest(maxTicks = 200)
	public void minerGetsAMineshaftSiteBesideTheCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		member(helper, colony, BotRole.MINER, 1, 4);

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.mineshaft().isPresent(), "no mineshaft site");
			helper.assertTrue(colony.builds().contains(colony.mineshaft().get().plan()), "no plan for the builders");
		});
	}

	/** Bots that join with no role, as freshly built ones do. */
	private static void newcomers(GameTestHelper helper, Colony colony, int count, int y) {
		for (int i = 0; i < count; i++) {
			ColonyManager.get(helper.getLevel()).join(helper.spawn(ModEntities.ANT_BOT, 1 + i, y, 1), colony);
		}
	}

	/** The starter roster has a builder, who digs the shaft the starter miner picked a site for. */
	@GameTest(maxTicks = 300)
	public void starterMinerGetsMineshaftWork(GameTestHelper helper) {
		// The barrier under the test area cannot be dug through, so the shaft would get no levels.
		for (BlockPos pos : BlockPos.betweenClosed(0, -1, 0, 7, -1, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = colony(helper);
		newcomers(helper, colony, 3, 1);

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.mineshaft().isPresent(), "no mineshaft site");
			helper.assertTrue(colony.board().jobs().stream().anyMatch(job -> job.type() == JobType.BUILD && !job.isOpen()), "nobody digs the shaft");
		});
	}

	@GameTest(maxTicks = 1200)
	public void newColonyStoresCobblestone(GameTestHelper helper) {
		Mineshaft shaft = mine(helper);
		Colony colony = colony(helper, MINE_CORE);
		// Shallow levels in place of the configured ones, which lie below the test area.
		colony.setMineshaft(shaft);
		// One each for the miner and the builder: a builder does not order a pickaxe of its own.
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

	/** Cobblestone and sticks in the core become a stone pickaxe that replaces the wooden one. */
	@GameTest(maxTicks = 1200)
	public void upgradesToStonePickaxe(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		core(helper).setItem(1, new ItemStack(Items.STICK, 2));
		AntBot miner = member(helper, colony, BotRole.MINER, 1, 4);
		miner.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		member(helper, colony, BotRole.CRAFTER, 2, 1);

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
		AntBot miner = member(helper, colony, BotRole.MINER, 1, 4);
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));

		helper.onEachTick(() -> {
			if (!colony.orders().isEmpty()) {
				helper.fail("miner ordered " + colony.orders());
			}
		});
		helper.runAfterDelay(250, helper::succeed);
	}

	/** Lumberjack logs become a pickaxe at the crafter, which the miner takes up. */
	@GameTest(maxTicks = 3000)
	public void colonyMakesItsOwnPickaxe(GameTestHelper helper) {
		Colony colony = colony(helper);
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				helper.setBlock(5 + dx, 4, 1 + dz + 1, leaves);
			}
		}
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(5, y, 2, Blocks.OAK_LOG);
		}
		member(helper, colony, BotRole.LUMBERJACK, 2, 3);
		member(helper, colony, BotRole.CRAFTER, 1, 3);
		AntBot miner = member(helper, colony, BotRole.MINER, 2, 4);

		helper.succeedWhen(() -> helper.assertTrue(pickaxes(miner, Items.WOODEN_PICKAXE) == 1, "miner has no pickaxe"));
	}
}
