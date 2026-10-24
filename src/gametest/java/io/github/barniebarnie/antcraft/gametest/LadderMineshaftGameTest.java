package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.TestPositions.relative;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
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
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The 1x1 ladder mineshaft of new colonies: dug by builders a ladder at a time, and climbed by bots both ways. */
public class LadderMineshaftGameTest implements CustomTestMethodInvoker {
	/** Beside the shaft, on its stone. */
	private static final BlockPos CORE = new BlockPos(0, 5, 0);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** Stone from the floor up to {@code ground}, the ground the shaft is dug into, across the test area. */
	private static void stone(GameTestHelper helper, int ground) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, ground, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
	}

	private static Colony colony(GameTestHelper helper, BlockPos core) {
		helper.setBlock(core, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(core)).orElseThrow();
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Container core(GameTestHelper helper, BlockPos core) {
		return (Container) helper.getBlockEntity(core, BlockEntity.class);
	}

	/**
	 * A ladder shaft from the ground at {@code top} down to a landing at {@code level}, its hall heading east. With the
	 * shortest hall, 3 long, and one round of 1-long branches, so the hall and branches stay in the test area: their
	 * floors are protected and their cells kept open for every colony.
	 */
	private static Mineshaft shaft(GameTestHelper helper, BlockPos top, int level) {
		int y = helper.absolutePos(new BlockPos(0, level, 0)).getY();
		return new Mineshaft(helper.absolutePos(top), List.of(y), 1, 1, Mineshaft.DEFAULT_TORCH_SPACING, 1);
	}

	/** What a shaft needs: its ladders, the hatch, a torch and a chest. */
	private static void stock(GameTestHelper helper, BlockPos core) {
		core(helper, core).setItem(0, new ItemStack(Items.LADDER, 16));
		core(helper, core).setItem(1, new ItemStack(Items.OAK_TRAPDOOR, 1));
		core(helper, core).setItem(2, new ItemStack(Items.TORCH, 4));
		core(helper, core).setItem(3, new ItemStack(Items.CHEST, 1));
	}

	private static AntBot member(GameTestHelper helper, Colony colony, BlockPos at) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, at.getX(), at.getY(), at.getZ());
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	/** The shaft as the builders leave it, its plan taken off the colony. */
	private static void dig(GameTestHelper helper, Colony colony, Mineshaft shaft) {
		for (BuildPlan.Step step : shaft.plan().steps()) {
			if (!step.isDone(helper.getLevel().getBlockState(step.pos()))) {
				helper.getLevel().setBlockAndUpdate(step.pos(), step.state());
			}
		}
		colony.removeBuild(shaft.plan());
	}

	/** Fails once a step of the plan is not done, or a ladder of the column has no block behind it. */
	private static void assertDug(GameTestHelper helper, Mineshaft shaft) {
		for (BuildPlan.Step step : shaft.plan().steps()) {
			BlockState state = helper.getLevel().getBlockState(step.pos());
			helper.assertTrue(step.isDone(state), "not done at " + relative(helper, step.pos()) + ": " + state);
		}
		for (BlockPos cell : shaft.stairs().subList(1, shaft.stairs().size())) {
			helper.assertTrue(helper.getLevel().getBlockState(cell).is(Blocks.LADDER), "no ladder at " + relative(helper, cell));
			helper.assertTrue(helper.getLevel().getBlockState(cell.relative(Direction.SOUTH)).isSolid(), "nothing behind the ladder at " + relative(helper, cell));
		}
	}

	/**
	 * Two bots dig a ladder shaft from the ground at y=5 down to a landing at y=3, the depth and the bots of
	 * {@code SpiralMineshaftGameTest.botsBuildTheStepsFromTheStoneStairsTheCoreHas}, so their ticks compare.
	 */
	@GameTest(maxTicks = 360)
	public void botsDigALadderShaftToALandingFasterThanTheSpiral(GameTestHelper helper) {
		stone(helper, 4);
		Colony colony = colony(helper, CORE);
		stock(helper, CORE);
		Mineshaft shaft = shaft(helper, new BlockPos(3, 5, 3), 3);
		colony.setMineshaft(shaft);
		for (BlockPos at : List.of(new BlockPos(7, 5, 0), new BlockPos(7, 5, 7))) {
			member(helper, colony, at).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		}

		helper.succeedWhen(() -> {
			assertDug(helper, shaft);
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished");
		});
	}

	/**
	 * As above, but the core holds spruce planks and no trapdoor: the colony, which has no oak, makes the hatch a spruce
	 * trapdoor, and never orders an oak one.
	 */
	@GameTest(maxTicks = 480)
	public void aColonyWithOnlySprucePlanksPutsASpruceTrapdoorHatchOnItsShaft(GameTestHelper helper) {
		// Any of the tag will do, and the list the plan takes them from is the tag.
		Set<Item> tag = new HashSet<>();
		BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.WOODEN_TRAPDOORS).forEach(holder -> tag.add(holder.value()));
		helper.assertTrue(tag.equals(BuildPlan.WOODEN_TRAPDOORS), "the wooden trapdoors are not the tag's: " + tag);
		stone(helper, 4);
		Colony colony = colony(helper, CORE);
		core(helper, CORE).setItem(0, new ItemStack(Items.LADDER, 16));
		core(helper, CORE).setItem(1, new ItemStack(Items.SPRUCE_PLANKS, 6));
		core(helper, CORE).setItem(2, new ItemStack(Items.TORCH, 4));
		core(helper, CORE).setItem(3, new ItemStack(Items.CHEST, 1));
		Mineshaft shaft = shaft(helper, new BlockPos(3, 5, 3), 3);
		colony.setMineshaft(shaft);
		for (BlockPos at : List.of(new BlockPos(7, 5, 0), new BlockPos(7, 5, 7))) {
			member(helper, colony, at).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		}
		helper.onEachTick(() -> {
			if (colony.orders().containsKey(Items.OAK_TRAPDOOR)) {
				helper.fail("ordered an oak trapdoor: " + colony.orders());
			}
		});

		helper.succeedWhen(() -> {
			assertDug(helper, shaft);
			BlockState hatch = helper.getLevel().getBlockState(shaft.stairs().getFirst());
			helper.assertTrue(hatch.is(Blocks.SPRUCE_TRAPDOOR), "the hatch is " + hatch);
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished");
		});
	}

	/** A spruce tree on the ground at y=5: four logs from {@code (x, 5, z)} up, leaves round the top. */
	private static void spruce(GameTestHelper helper, int x, int z) {
		BlockState leaves = Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
		for (BlockPos pos : BlockPos.betweenClosed(x - 1, 8, z - 1, x + 1, 8, z + 1)) {
			helper.setBlock(pos, leaves);
		}
		helper.setBlock(x, 9, z, leaves);
		for (int y = 5; y <= 8; y++) {
			helper.setBlock(x, y, z, Blocks.SPRUCE_LOG);
		}
	}

	/**
	 * As above, but the colony has no wood at all and only a spruce tree grows nearby: the bots fell it for spruce logs
	 * and make the hatch a spruce trapdoor, never ordering an oak one, which no tree nearby grows.
	 */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 560)
	public void aColonyWithOnlySpruceNearbyPutsASpruceTrapdoorHatchOnItsShaft(GameTestHelper helper) {
		stone(helper, 4);
		spruce(helper, 1, 6);
		Colony colony = colony(helper, CORE);
		core(helper, CORE).setItem(0, new ItemStack(Items.LADDER, 16));
		core(helper, CORE).setItem(2, new ItemStack(Items.TORCH, 4));
		core(helper, CORE).setItem(3, new ItemStack(Items.CHEST, 1));
		Mineshaft shaft = shaft(helper, new BlockPos(3, 5, 3), 3);
		colony.setMineshaft(shaft);
		for (BlockPos at : List.of(new BlockPos(7, 5, 0), new BlockPos(7, 5, 7))) {
			member(helper, colony, at).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		}
		helper.onEachTick(() -> {
			if (colony.orders().containsKey(Items.OAK_TRAPDOOR)) {
				helper.fail("ordered an oak trapdoor: " + colony.orders());
			}
		});

		helper.succeedWhen(() -> {
			assertDug(helper, shaft);
			BlockState hatch = helper.getLevel().getBlockState(shaft.stairs().getFirst());
			helper.assertTrue(hatch.is(Blocks.SPRUCE_TRAPDOOR), "the hatch is " + hatch);
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished");
		});
	}

	/**
	 * The core holds a birch trapdoor and no oak one: the builder takes it for the hatch on the same trip as its ladders,
	 * so it carries it from the first time it carries ladders, and never comes back to the core for it.
	 */
	@GameTest(maxTicks = 330)
	public void theBuilderTakesAStandInHatchOnTheSameTripAsItsLadders(GameTestHelper helper) {
		stone(helper, 4);
		Colony colony = colony(helper, CORE);
		core(helper, CORE).setItem(0, new ItemStack(Items.LADDER, 16));
		core(helper, CORE).setItem(1, new ItemStack(Items.BIRCH_TRAPDOOR, 1));
		core(helper, CORE).setItem(2, new ItemStack(Items.TORCH, 4));
		core(helper, CORE).setItem(3, new ItemStack(Items.CHEST, 1));
		Mineshaft shaft = shaft(helper, new BlockPos(3, 5, 3), 3);
		colony.setMineshaft(shaft);
		AntBot builder = member(helper, colony, new BlockPos(7, 5, 7));
		builder.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		helper.onEachTick(() -> {
			if (builder.getInventory().countItem(Items.LADDER) > 0 && core(helper, CORE).countItem(Items.BIRCH_TRAPDOOR) > 0) {
				helper.fail("took the ladders without the birch trapdoor");
			}
		});

		helper.succeedWhen(() -> {
			assertDug(helper, shaft);
			BlockState hatch = helper.getLevel().getBlockState(shaft.stairs().getFirst());
			helper.assertTrue(hatch.is(Blocks.BIRCH_TRAPDOOR), "the hatch is " + hatch);
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished");
		});
	}

	/** As above, but ten blocks down, from the ground at y=13 to a landing at y=3. */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 1800)
	public void botsDigALadderShaftTenBlocksDown(GameTestHelper helper) {
		stone(helper, 12);
		BlockPos core = new BlockPos(0, 13, 0);
		Colony colony = colony(helper, core);
		stock(helper, core);
		Mineshaft shaft = shaft(helper, new BlockPos(3, 13, 3), 3);
		colony.setMineshaft(shaft);
		for (BlockPos at : List.of(new BlockPos(7, 13, 0), new BlockPos(7, 13, 7))) {
			member(helper, colony, at).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		}

		helper.succeedWhen(() -> {
			assertDug(helper, shaft);
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished");
		});
	}

	/**
	 * The shaft crosses a cave, 3x3 and 2 high round the column at y=8 and 9, and pockets of water beside the column
	 * at y=6, one behind the ladder: the builder walls the column so every ladder has a block behind it and no water
	 * comes in.
	 */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 1600)
	public void theBuilderWallsTheColumnWhereItCrossesACaveAndWater(GameTestHelper helper) {
		stone(helper, 12);
		for (BlockPos pos : BlockPos.betweenClosed(2, 8, 2, 4, 9, 4)) {
			helper.setBlock(pos, Blocks.AIR);
		}
		for (BlockPos pos : List.of(new BlockPos(3, 6, 4), new BlockPos(2, 6, 3))) {
			helper.setBlock(pos, Blocks.WATER);
		}
		BlockPos core = new BlockPos(0, 13, 0);
		Colony colony = colony(helper, core);
		stock(helper, core);
		core(helper, core).setItem(4, new ItemStack(Items.COBBLESTONE, 32));
		Mineshaft shaft = shaft(helper, new BlockPos(3, 13, 3), 3);
		colony.setMineshaft(shaft);
		member(helper, colony, new BlockPos(7, 13, 7)).getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		helper.onEachTick(() -> {
			for (BlockPos cell : shaft.stairs()) {
				if (!helper.getLevel().getFluidState(cell).isEmpty()) {
					helper.fail("water in the column at " + relative(helper, cell));
				}
			}
		});

		helper.succeedWhen(() -> {
			assertDug(helper, shaft);
			helper.assertTrue(colony.builds().isEmpty(), "shaft not finished");
		});
	}

	/** Every block of the test area that is not air. */
	private static Set<BlockPos> solid(GameTestHelper helper, int height) {
		Set<BlockPos> solid = new HashSet<>();
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, height - 1, 7)) {
			if (!helper.getBlockState(pos).isAir()) {
				solid.add(pos.immutable());
			}
		}
		return solid;
	}

	/**
	 * A dug ladder shaft ten blocks deep with no landing chest: a miner climbs down, digs the hall and the branches
	 * off it, and climbs back up with what it dug to the core, without breaking a ladder or digging a way of its own.
	 */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 1200)
	public void aMinerClimbsDownMinesABranchAndClimbsBackUpWithItsHaul(GameTestHelper helper) {
		stone(helper, 12);
		BlockPos core = new BlockPos(0, 13, 0);
		Colony colony = colony(helper, core);
		colony.setStockTarget(StockTarget.of(Items.COBBLESTONE, 1000));
		Mineshaft shaft = shaft(helper, new BlockPos(3, 13, 3), 3);
		colony.setMineshaft(shaft);
		dig(helper, colony, shaft);
		int level = shaft.levels().getFirst();
		// So the miner brings its haul up to the core rather than leave it at the landing.
		helper.getLevel().setBlockAndUpdate(shaft.landingChest(level), Blocks.STONE.defaultBlockState());
		Set<BlockPos> mined = new HashSet<>(shaft.hallWork(level).orElseThrow().cells());
		shaft.branches(level).forEach(branch -> mined.addAll(branch.cells()));
		Set<BlockPos> before = solid(helper, 16);
		AntBot miner = member(helper, colony, new BlockPos(7, 13, 7));
		miner.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		helper.onEachTick(() -> {
			for (BlockPos cell : shaft.stairs().subList(1, shaft.stairs().size())) {
				if (!helper.getLevel().getBlockState(cell).is(Blocks.LADDER)) {
					helper.fail("the ladder is gone at " + relative(helper, cell));
				}
			}
			for (BlockPos pos : before) {
				if (helper.getBlockState(pos).isAir() && !mined.contains(helper.absolutePos(pos))) {
					helper.fail("dug a way of its own at " + pos.toShortString());
				}
			}
		});

		helper.succeedWhen(() -> {
			for (BlockPos cell : mined) {
				BlockState state = helper.getLevel().getBlockState(cell);
				helper.assertTrue(state.isAir() || state.is(Blocks.TORCH), "not mined at " + relative(helper, cell));
			}
			helper.assertTrue(core(helper, core).countItem(Items.COBBLESTONE) > 0, "no haul in the core");
		});
	}

	/** In the column, a bot on its way up and one on its way down get past each other, without digging a way round. */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 240)
	public void twoBotsPassEachOtherInTheColumn(GameTestHelper helper) {
		stone(helper, 12);
		Colony colony = colony(helper, new BlockPos(0, 13, 0));
		Mineshaft shaft = shaft(helper, new BlockPos(3, 13, 3), 3);
		colony.setMineshaft(shaft);
		dig(helper, colony, shaft);
		Set<BlockPos> dug = solid(helper, 16);
		// Beside the hatch, on the ground.
		BlockPos top = shaft.top().east();
		BlockPos bottom = shaft.landing(shaft.levels().getLast());
		AntBot up = member(helper, colony, relative(helper, bottom));
		AntBot down = member(helper, colony, relative(helper, shaft.top()).west());
		up.setWalkTarget(top);
		down.setWalkTarget(bottom);
		// Where each bot was as its walk ended; once there it may go off to work between jobs.
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
			helper.assertTrue(ended[0] != null && ended[0].distManhattan(top) <= 1, "the bot going up is at " + relative(helper, up.blockPosition()));
			helper.assertTrue(ended[1] != null && ended[1].distManhattan(bottom) <= 1, "the bot going down is at " + relative(helper, down.blockPosition()));
			helper.assertTrue(solid(helper, 16).equals(dug), "blocks changed on the way");
		});
	}
}
