package io.github.barniebarnie.antcraft.gametest;

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
import io.github.barniebarnie.antcraft.work.Storage;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
	 */
	static Mineshaft mine(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		int y3 = helper.absolutePos(new BlockPos(0, 3, 0)).getY();
		int y1 = helper.absolutePos(new BlockPos(0, 1, 0)).getY();
		return new Mineshaft(helper.absolutePos(new BlockPos(3, 5, 3)), List.of(y3, y1), 1, 3, 4);
	}

	/** Digs the stairs and halls as a builder would. */
	static void dig(GameTestHelper helper, Colony colony, Mineshaft shaft) {
		colony.setMineshaft(shaft);
		for (BuildPlan.Step step : shaft.plan().steps()) {
			helper.getLevel().setBlockAndUpdate(step.pos(), step.state());
		}
		colony.removeBuild(shaft.plan());
	}

	/** Every block of the shaft, its halls and its branches that bots walk through. */
	static List<BlockPos> dugPath(Mineshaft shaft, boolean branches) {
		List<BlockPos> path = new ArrayList<>(shaft.plan().steps().stream().map(BuildPlan.Step::pos).toList());
		if (branches) {
			shaft.levels().forEach(y -> shaft.branches(y).forEach(branch -> path.addAll(branch.cells())));
		}
		return path;
	}

	static void assertLit(GameTestHelper helper, List<BlockPos> path) {
		for (BlockPos pos : path) {
			int light = helper.getLevel().getBrightness(LightLayer.BLOCK, pos);
			helper.assertTrue(light > 0, "dark at " + helper.relativePos(pos).toShortString());
		}
	}

	private static void assertBranchesDug(GameTestHelper helper, Mineshaft shaft, int level) {
		for (Mineshaft.Branch branch : shaft.branches(level)) {
			for (BlockPos cell : branch.cells()) {
				BlockState state = helper.getLevel().getBlockState(cell);
				helper.assertTrue(state.isAir() || state.is(Blocks.TORCH), "branch not dug at " + helper.relativePos(cell).toShortString());
			}
			for (BlockPos torch : branch.torches()) {
				helper.assertTrue(helper.getLevel().getBlockState(torch).is(Blocks.TORCH), "no torch at " + helper.relativePos(torch).toShortString());
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
			helper.assertTrue(view.breakTicks(pos) < 0, "pathing may break " + helper.relativePos(pos).toShortString());
		}

		helper.onEachTick(() -> {
			for (BlockPos pos : kept) {
				if (helper.getLevel().getBlockState(pos).isAir()) {
					helper.fail("broke " + helper.relativePos(pos).toShortString());
				}
			}
			for (BlockPos torch : shaft.torches()) {
				if (!helper.getLevel().getBlockState(torch).is(Blocks.TORCH)) {
					helper.fail("torch gone at " + helper.relativePos(torch).toShortString());
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
							"branch not dug at " + helper.relativePos(cell).toShortString());
					}
				}
			}
		});
	}

	/** A miner at the deepest landing with a pickaxe and every other slot full of {@code haul}. */
	private static AntBot fullMiner(GameTestHelper helper, Colony colony, Mineshaft shaft, ItemStack... haul) {
		BlockPos landing = helper.relativePos(shaft.landing(shaft.levels().getLast()));
		AntBot miner = member(helper, colony, BotRole.MINER, landing.getX(), landing.getY(), landing.getZ());
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
