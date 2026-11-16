package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * {@code lib/treefarm.py} (#605): tree cells per sapling kind, built, grown, harvested and replanted. In the 24x16x24
 * {@code wide_tall} structure: a cell clears 8 to 14 blocks over its floor, more than the 8 high of {@code wide}; the giant
 * kinds clear 31 and 33, in the 24x40x24 {@code giant} structure.
 */
public class TreeFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * A birch cell of 5x5 from (2, 0, 2) on the stone floor: dirt under the sapling in the middle, the sapling planted, a
	 * torch at two opposite corners and block light on every floor block of the cell.
	 */
	// Measured: passes at tick 89.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 180)
	public void aBirchCellGetsDirtASaplingAndLight(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 10, 1, 10);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.DIRT), new ItemStack(Items.BIRCH_SAPLING),
			new ItemStack(Items.TORCH, 2));
		start(helper, bot, "minecraft:birch_sapling", new BlockPos(2, 0, 2), new BlockPos(6, 0, 6), """
			print(lib.treefarm.materials(kind))
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"])
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT),
				List.of("{'minecraft:dirt': 1, 'minecraft:birch_sapling': 1, 'minecraft:torch': 2}", "True None"), "printed");
			helper.assertBlockPresent(Blocks.DIRT, 4, 0, 4);
			helper.assertBlockPresent(Blocks.BIRCH_SAPLING, 4, 1, 4);
			helper.assertBlockPresent(Blocks.TORCH, 2, 1, 2);
			helper.assertBlockPresent(Blocks.TORCH, 6, 1, 6);
			for (int x = 2; x <= 6; x++) {
				for (int z = 2; z <= 6; z++) {
					int light = helper.getLevel().getBrightness(LightLayer.BLOCK, helper.absolutePos(new BlockPos(x, 1, z)));
					helper.assertTrue(light > 0, "dark at (" + x + ", 1, " + z + ")");
				}
			}
		});
	}

	/** A birch cell whose sapling spot stands on grass (#805): planted as it is, the grass neither mined nor dirt put in. */
	// Measured: passes at tick 77 (red: mined to dirt at tick 70 to 73).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 180)
	public void aSpotOnGrassIsPlantedWithoutDirt(GameTestHelper helper) {
		plantedOn(helper, Blocks.GRASS_BLOCK);
	}

	/** A birch cell whose sapling spot stands on podzol, as a giant spruce leaves it (#805): planted as it is. */
	// Measured: passes at tick 77 (red: mined to dirt at tick 70 to 73).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 180)
	public void aSpotOnPodzolIsPlantedWithoutDirt(GameTestHelper helper) {
		plantedOn(helper, Blocks.PODZOL);
	}

	private static void plantedOn(GameTestHelper helper, Block ground) {
		BlockPos under = new BlockPos(4, 0, 4);
		helper.setBlock(under, ground);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 10, 1, 10);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.DIRT), new ItemStack(Items.BIRCH_SAPLING),
			new ItemStack(Items.TORCH, 2));
		helper.onEachTick(() -> {
			if (!helper.getBlockState(under).is(ground)) {
				helper.fail("the spot's " + ground + " was mined: " + helper.getBlockState(under));
			}
		});
		start(helper, bot, "minecraft:birch_sapling", new BlockPos(2, 0, 2), new BlockPos(6, 0, 6), """
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"], count("minecraft:dirt"))
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True None 1"), "printed");
			helper.assertBlockPresent(Blocks.BIRCH_SAPLING, 4, 1, 4);
		});
	}

	/**
	 * A birch cell built again over its own torches, one on the wall of a block beside its corner, one standing (#760):
	 * neither is mined (flatten keeps them) nor placed again; the bot keeps its 2 torches.
	 */
	// Measured: passes at tick 68 (red: torches mined by flatten / a wall torch).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 150)
	public void buildCellLeavesTheTorchesThereAlone(GameTestHelper helper) {
		BlockPos wall = new BlockPos(2, 1, 2);
		BlockPos standing = new BlockPos(6, 1, 6);
		BlockState wallTorch = Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.EAST);
		helper.setBlock(wall.west(), Blocks.STONE);
		helper.setBlock(wall, wallTorch);
		helper.setBlock(standing, Blocks.TORCH);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 10, 1, 10);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.DIRT), new ItemStack(Items.BIRCH_SAPLING),
			new ItemStack(Items.TORCH, 2));
		helper.onEachTick(() -> {
			if (helper.getBlockState(wall) != wallTorch || !helper.getBlockState(standing).is(Blocks.TORCH)) {
				helper.fail("a torch was touched: " + helper.getBlockState(wall) + ", " + helper.getBlockState(standing));
			}
		});
		start(helper, bot, "minecraft:birch_sapling", new BlockPos(2, 0, 2), new BlockPos(6, 0, 6), """
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"], count("minecraft:torch"))
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True None 2"), "printed");
			helper.assertBlockPresent(Blocks.BIRCH_SAPLING, 4, 1, 4);
		});
	}

	/**
	 * A birch cell whose corners have a 1-high wall just outside them and part of its floor 1 higher (#760): its torches
	 * stand on the floor, not on the wall, each placed once.
	 */
	// Measured: passes at tick 71 (red: torches mined by flatten / a wall torch).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 150)
	public void aCellBesideAOneHighWallGetsStandingTorches(GameTestHelper helper) {
		for (int i = 0; i <= 8; i++) {
			helper.setBlock(new BlockPos(1, 1, i), Blocks.STONE);
			helper.setBlock(new BlockPos(i, 1, 1), Blocks.STONE);
			helper.setBlock(new BlockPos(7, 1, i), Blocks.STONE);
			helper.setBlock(new BlockPos(i, 1, 7), Blocks.STONE);
		}
		helper.setBlock(new BlockPos(3, 1, 2), Blocks.DIRT);
		helper.setBlock(new BlockPos(5, 1, 6), Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.DIRT), new ItemStack(Items.BIRCH_SAPLING),
			new ItemStack(Items.TORCH, 2));
		start(helper, bot, "minecraft:birch_sapling", new BlockPos(2, 0, 2), new BlockPos(6, 0, 6), """
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"], count("minecraft:torch"))
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True None 0"), "printed");
			helper.assertBlockPresent(Blocks.TORCH, 2, 1, 2);
			helper.assertBlockPresent(Blocks.TORCH, 6, 1, 6);
		});
	}

	/**
	 * An oak cell of 7x7 from (2, 0, 2): a 3x3 bottom-slab roof at y=9, 8 blocks over the sapling (OAK's tallest trunk, 6,
	 * plus the 2 the game's free-height check wants), under which the test grows the sapling as the game does (oak or,
	 * clipped, fancy oak); the bot sees it grown, fells it whole, carries the logs and replants.
	 */
	// Measured: passes at tick 385 to 455 (the oak grows plain or, one in ten, fancy).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 900)
	public void anOakCellIsRoofedGrownHarvestedAndReplanted(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 12);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 17),
			new ItemStack(Items.OAK_SAPLING, 2), new ItemStack(Items.TORCH, 2), new ItemStack(Items.OAK_SLAB, 9));
		BlockPos spot = new BlockPos(5, 1, 5);
		start(helper, bot, "minecraft:oak_sapling", new BlockPos(2, 0, 2), new BlockPos(8, 0, 8), """
			print(lib.treefarm.materials(kind))
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"])
			while not lib.treefarm.grown(kind, low, high):
			    wait(5)
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], r["reason"])
			""");
		boolean[] grew = new boolean[1];
		helper.onEachTick(() -> {
			List<String> said = BotScripts.log(bot, Output.Level.PRINT);
			if (grew[0] || said.size() < 2) {
				return;
			}
			// Built: the roof is there; the sapling grows as a random tick or bone meal would make it.
			for (int x = 4; x <= 6; x++) {
				for (int z = 4; z <= 6; z++) {
					BlockState slab = helper.getBlockState(new BlockPos(x, 9, z));
					helper.assertTrue(slab.is(Blocks.OAK_SLAB) && slab.getValue(SlabBlock.TYPE) == SlabType.BOTTOM,
						"no bottom slab at (" + x + ", 9, " + z + "): " + slab);
				}
			}
			BlockPos at = helper.absolutePos(spot);
			BlockState sapling = helper.getLevel().getBlockState(at);
			if (sapling.is(Blocks.OAK_SAPLING)) {
				((SaplingBlock) Blocks.OAK_SAPLING).advanceTree(helper.getLevel(), at, sapling.setValue(SaplingBlock.STAGE, 1),
					helper.getLevel().getRandom());
			}
			grew[0] = helper.getBlockState(spot).is(Blocks.OAK_LOG);
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of(
				"{'minecraft:dirt': 9, 'minecraft:oak_sapling': 1, 'minecraft:torch': 2, '#minecraft:wooden_slabs': 9}",
				"True None", "True None"), "printed");
			helper.assertTrue(grew[0], "the oak never grew");
			for (int x = 0; x < 24; x++) {
				for (int y = 1; y < 12; y++) {
					for (int z = 0; z < 24; z++) {
						helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(x, y, z));
					}
				}
			}
			helper.assertBlockPresent(Blocks.OAK_SAPLING, spot);
			helper.assertTrue(GameTests.carried(bot, Items.OAK_LOG) >= 4,
				"the bot carries " + GameTests.carried(bot, Items.OAK_LOG) + " oak logs");
			AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(24, 12, 24);
			helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area, e -> e.getItem().is(Items.OAK_LOG)).isEmpty(),
				"oak logs left lying");
		});
	}

	/**
	 * A cherry cell of 11x11 from (2, 0, 2): built, then grown as the game does; the cherry's branches, up to 5 out of
	 * the trunk, keep its logs inside the cell.
	 */
	// Measured: passes at tick 229 to 230.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 460)
	public void aCherryCellGrowsItsTreeInside(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 16, 1, 16);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.DIRT), new ItemStack(Items.CHERRY_SAPLING),
			new ItemStack(Items.TORCH, 2));
		BlockPos spot = new BlockPos(7, 1, 7);
		start(helper, bot, "minecraft:cherry_sapling", new BlockPos(2, 0, 2), new BlockPos(12, 0, 12), """
			print(lib.treefarm.materials(kind))
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"])
			""");
		boolean[] grew = growOnceBuilt(helper, bot, 2, spot, Blocks.CHERRY_LOG, at -> advance(helper, at));
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT),
				List.of("{'minecraft:dirt': 1, 'minecraft:cherry_sapling': 1, 'minecraft:torch': 2}", "True None"), "printed");
			helper.assertTrue(grew[0], "the cherry never grew");
			for (int x = 2; x <= 12; x++) {
				for (int z = 2; z <= 12; z++) {
					BlockPos p = new BlockPos(x, 1, z);
					int light = helper.getLevel().getBrightness(LightLayer.BLOCK, helper.absolutePos(p));
					helper.assertTrue(!helper.getBlockState(p).isAir() || light > 0, "dark at (" + x + ", 1, " + z + ")");
				}
			}
			for (int x = 0; x < 24; x++) {
				for (int y = 1; y < 16; y++) {
					for (int z = 0; z < 24; z++) {
						if (x < 2 || x > 12 || z < 2 || z > 12) {
							helper.assertBlockNotPresent(Blocks.CHERRY_LOG, new BlockPos(x, y, z));
						}
					}
				}
			}
		});
	}

	/**
	 * A dark oak cell of 8x8 from (2, 0, 2): four saplings on dirt in its middle, grown into the 2x2 tree, felled whole
	 * and replanted, all four. Its leaves drop a sapling 1 in 20 as in the game, so the bot is given the saplings to
	 * replant. Its top logs stand past its reach from the ground, so it carries the 16 dirt to climb with that
	 * jobs.wood_farm's kit holds (#652): with none, a tree whose leaves it could not climb kept a log out_of_reach.
	 */
	// Measured: passes at tick 1376 to 1743 in 3 runs, since harvest leaves the drops on the leaves to the sweep (#655);
	// at tick 988 once its leaves drop no apples (#790); 1006 to 1302 in 4 runs at once (#795), so maxTicks 2600.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 2600)
	public void aDarkOakCellOfFourSaplingsIsGrownAndHarvested(GameTestHelper helper) {
		darkOak(helper, at -> advance(helper, at));
	}

	/**
	 * The dark oak four saplings grow with seed 36 (#652): its one stand, below the crown, is in reach of a top log of the
	 * trunk that lower logs and leaves hid from it, and once those were mined no walk got in sight of it. Felled whole too.
	 * harvest no longer pillars up to the sticks and apples the decaying leaves drop (#655), which took most of its time.
	 */
	// Measured: passes at tick 1520 to 1933 in 3 runs (3250 to 4248 before #655); at tick 1065 with no apples (#790);
	// 1064 to 1198 in 4 runs at once (#795), so maxTicks 2400.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 2400)
	public void aDarkOakWithATopLogHiddenFromItsStandIsFelled(GameTestHelper helper) {
		darkOak(helper, at -> advance(helper, at, 36));
	}

	/**
	 * The dark oak cell of 8x8 from (2, 0, 2) built by a bot carrying 16 dirt to climb with, its tree grown with grow
	 * (given the absolute position of (5, 1, 5)), felled whole and all four saplings replanted.
	 */
	private static void darkOak(GameTestHelper helper, Consumer<BlockPos> grow) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 13, 1, 13);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 20),
			new ItemStack(Items.DARK_OAK_SAPLING, 8), new ItemStack(Items.TORCH, 2));
		BlockPos spot = new BlockPos(5, 1, 5);
		start(helper, bot, "minecraft:dark_oak_sapling", new BlockPos(2, 0, 2), new BlockPos(9, 0, 9), """
			print(lib.treefarm.materials(kind))
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"])
			while not lib.treefarm.grown(kind, low, high):
			    wait(5)
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], r["reason"], r["at"])
			""");
		List<String> printed = List.of("{'minecraft:dirt': 4, 'minecraft:dark_oak_sapling': 4, 'minecraft:torch': 2}", "True None",
			"True None None");
		boolean[] grew = growOnceBuilt(helper, bot, 2, spot, Blocks.DARK_OAK_LOG, grow);
		helper.onEachTick(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			if (log.size() == printed.size() && !log.equals(printed)) {
				helper.fail("printed " + log);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), printed, "printed");
			helper.assertTrue(grew[0], "the dark oak never grew");
			assertFelled(helper, bot, Blocks.DARK_OAK_LOG, Items.DARK_OAK_LOG);
			for (BlockPos p : List.of(spot, spot.east(), spot.south(), spot.east().south())) {
				helper.assertBlockPresent(Blocks.DARK_OAK_SAPLING, p);
			}
		});
	}

	/**
	 * An oak cell whose sapling grows a fancy oak (one in ten): the roof clips its trunk, of up to 14, to 6, and the bot
	 * fells it whole, branches and all, under the roof it leaves standing.
	 */
	// Measured: passes at tick 373 to 405.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 800)
	public void aClippedFancyOakUnderTheRoofIsHarvested(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 12);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 17),
			new ItemStack(Items.OAK_SAPLING, 2), new ItemStack(Items.TORCH, 2), new ItemStack(Items.OAK_SLAB, 9));
		BlockPos spot = new BlockPos(5, 1, 5);
		start(helper, bot, "minecraft:oak_sapling", new BlockPos(2, 0, 2), new BlockPos(8, 0, 8), """
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"])
			while not lib.treefarm.grown(kind, low, high):
			    wait(5)
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], r["reason"])
			""");
		boolean[] grew = growOnceBuilt(helper, bot, 1, spot, Blocks.OAK_LOG, at -> {
			ServerLevel level = helper.getLevel();
			level.setBlock(at, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			level.registryAccess().lookupOrThrow(Registries.FEATURE).getOrThrow(TreeFeatures.FANCY_OAK).value()
				.place(level, level.getChunkSource().getGenerator(), level.getRandom(), at);
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True None", "True None"), "printed");
			helper.assertTrue(grew[0], "the fancy oak never grew");
			assertFelled(helper, bot, Blocks.OAK_LOG, Items.OAK_LOG);
			helper.assertBlockPresent(Blocks.OAK_SAPLING, spot);
			for (int x = 4; x <= 6; x++) {
				for (int z = 4; z <= 6; z++) {
					helper.assertBlockPresent(Blocks.OAK_SLAB, new BlockPos(x, 9, z));
				}
			}
		});
	}

	/**
	 * A birch cell of 5x5 from (2, 0, 2) whose sapling stands under two logs a failed harvest left (#644): grown, as those
	 * logs stop the sapling growing; harvest fells them and leaves the sapling.
	 */
	// Measured: passes at tick 184 to 185.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 370)
	public void logsLeftOverTheSaplingAreATreeToFell(GameTestHelper helper) {
		helper.setBlock(4, 0, 4, Blocks.DIRT);
		helper.setBlock(4, 1, 4, Blocks.BIRCH_SAPLING);
		helper.setBlock(4, 4, 4, Blocks.BIRCH_LOG);
		helper.setBlock(4, 5, 4, Blocks.BIRCH_LOG);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 8, 1, 8);
		give(bot, new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 8));
		start(helper, bot, "minecraft:birch_sapling", new BlockPos(2, 0, 2), new BlockPos(6, 0, 6), """
			print(lib.treefarm.grown(kind, low, high))
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], lib.treefarm.grown(kind, low, high))
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True", "True False"), "printed");
			helper.assertBlockNotPresent(Blocks.BIRCH_LOG, 4, 4, 4);
			helper.assertBlockNotPresent(Blocks.BIRCH_LOG, 4, 5, 4);
			helper.assertBlockPresent(Blocks.BIRCH_SAPLING, 4, 1, 4);
		});
	}

	/**
	 * A birch cell of 5x5 from (2, 0, 2) whose spot's dirt a path dug out, a hole under its trunk of three logs (#764):
	 * harvest fells it, puts dirt back under the spot and plants the sapling.
	 */
	// Measured: passes at tick 104 (red: no dirt, no sapling at tick 372).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 220)
	public void harvestPutsBackTheDirtUnderASpot(GameTestHelper helper) {
		helper.setBlock(4, 0, 4, Blocks.AIR);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(4, y, 4, Blocks.BIRCH_LOG);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 8, 1, 8);
		give(bot, new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 8), new ItemStack(Items.BIRCH_SAPLING));
		start(helper, bot, "minecraft:birch_sapling", new BlockPos(2, 0, 2), new BlockPos(6, 0, 6), """
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], r["reason"])
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True None"), "printed");
			helper.assertBlockPresent(Blocks.DIRT, 4, 0, 4);
			helper.assertBlockPresent(Blocks.BIRCH_SAPLING, 4, 1, 4);
		});
	}

	/**
	 * A giant spruce cell from (2, 0, 2) whose 2x2 trunk stands with the dirt of two of its spots dug out, one a hole,
	 * one cobblestone a path put there (#764): harvest puts dirt back under both and replants all four spots.
	 */
	// Measured: passes at tick 314 (red: no dirt under (7, 0, 6) at tick 702).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 650)
	public void harvestPutsBackTheDirtUnderTheSpotsOfASquareCell(GameTestHelper helper) {
		List<BlockPos> spots = List.of(new BlockPos(6, 1, 6), new BlockPos(7, 1, 6), new BlockPos(6, 1, 7), new BlockPos(7, 1, 7));
		for (BlockPos p : spots) {
			helper.setBlock(p.below(), Blocks.DIRT);
			for (int y = 0; y < 3; y++) {
				helper.setBlock(p.above(y), Blocks.SPRUCE_LOG);
			}
		}
		helper.setBlock(spots.get(1).below(), Blocks.AIR);
		helper.setBlock(spots.get(2).below(), Blocks.COBBLESTONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 12);
		give(bot, new ItemStack(Items.STONE_AXE), new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.DIRT, 8),
			new ItemStack(Items.SPRUCE_SAPLING, 4));
		start(helper, bot, "giant_spruce", new BlockPos(2, 0, 2), new BlockPos(11, 0, 11), """
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], r["reason"])
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True None"), "printed");
			for (BlockPos p : spots) {
				helper.assertBlockPresent(Blocks.DIRT, p.below());
				helper.assertBlockPresent(Blocks.SPRUCE_SAPLING, p);
			}
		});
	}

	/**
	 * A birch cell of 5x5 from (2, 0, 2) with a trunk of three logs at its spot and a log up at a corner, on leaves,
	 * touching none of them, as a bot that died up a giant jungle, having mined the logs round a branch log, left that log
	 * cut off from its tree (#713): harvest fells the trunk, then the log it left, so the cell is no longer grown.
	 */
	// Measured: passes at tick 247 (red: printed True None None True at tick 402, the log left).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 500)
	public void harvestAlsoFellsALogCutOffFromTheTree(GameTestHelper helper) {
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(4, y, 4, Blocks.BIRCH_LOG);
		}
		helper.setBlock(6, 4, 6, Blocks.BIRCH_LOG);
		helper.setBlock(6, 3, 6, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 8, 1, 8);
		give(bot, new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 8));
		start(helper, bot, "minecraft:birch_sapling", new BlockPos(2, 0, 2), new BlockPos(6, 0, 6), """
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], r["reason"], r["at"], lib.treefarm.grown(kind, low, high))
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True None None False"), "printed");
			helper.assertBlockNotPresent(Blocks.BIRCH_LOG, 4, 1, 4);
			helper.assertBlockNotPresent(Blocks.BIRCH_LOG, 6, 4, 6);
		});
	}

	/**
	 * A birch cell of 5x5 from (2, 0, 2) with three logs over its spot, a stick on the floor and one on three persistent
	 * leaves (#655): harvest picks up the stick on the floor but does not pillar up to the one on the leaves, which
	 * jobs.wood_farm's sweep leaves for later.
	 */
	// Measured: passes at tick 128.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 260)
	public void harvestDoesNotPillarToADropOnLeaves(GameTestHelper helper) {
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(4, y, 4, Blocks.BIRCH_LOG);
			helper.setBlock(6, y, 6, Blocks.BIRCH_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		}
		ItemEntity high = helper.spawnItem(Items.STICK, new BlockPos(6, 4, 6));
		helper.spawnItem(Items.STICK, new BlockPos(2, 1, 6));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 8, 1, 8);
		give(bot, new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 8));
		start(helper, bot, "minecraft:birch_sapling", new BlockPos(2, 0, 2), new BlockPos(6, 0, 6), """
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], count("minecraft:stick"), count("minecraft:dirt"))
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True 1 8"), "printed");
			helper.assertTrue(high.isAlive(), "the stick on the leaves was picked up");
			helper.assertBlockNotPresent(Blocks.BIRCH_LOG, 4, 1, 4);
		});
	}

	/**
	 * A giant spruce cell of 10x10 from (2, 0, 2) (#650): a kind of its own, though it grows from spruce saplings, four
	 * of them in its middle; grown as the game does, MEGA_SPRUCE or MEGA_PINE, its 2x2 trunk of up to 29 stays inside.
	 */
	// Measured: passes at tick 328 to 329.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 700)
	public void aGiantSpruceCellOfFourSaplingsGrowsInside(GameTestHelper helper) {
		growsAGiant(helper, "giant_spruce", Items.SPRUCE_SAPLING, Blocks.SPRUCE_LOG,
			"{'minecraft:dirt': 4, 'minecraft:spruce_sapling': 4, 'minecraft:torch': 2}");
	}

	/**
	 * A giant jungle cell of 10x10 from (2, 0, 2) (#650): four jungle saplings grow MEGA_JUNGLE_TREE, whose branches,
	 * up to 4 out of its 2x2 trunk, stay inside.
	 */
	// Measured: passes at tick 334 to 336.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 700)
	public void aGiantJungleCellOfFourSaplingsGrowsInside(GameTestHelper helper) {
		growsAGiant(helper, "giant_jungle", Items.JUNGLE_SAPLING, Blocks.JUNGLE_LOG,
			"{'minecraft:dirt': 4, 'minecraft:jungle_sapling': 4, 'minecraft:torch': 2}");
	}

	/**
	 * A poplar cell of 7x7 from (2, 0, 2) (#650): built, grown as the game does (red, orange or yellow poplar), its logs,
	 * up to 3 out of the trunk, inside the cell; felled whole and replanted.
	 */
	// Measured: 48 seeded poplars pass at tick 422 to 1587 (#656).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 3200)
	public void aPoplarCellIsGrownHarvestedAndReplanted(GameTestHelper helper) {
		poplar(helper, at -> advance(helper, at));
	}

	/**
	 * The poplar grown with seed 6 (#656): its trunk's top log is in its leaves, out of reach from the ground, and its one
	 * stand is in the trunk under it. The bot, stood by the trunk with its body over the edge of its block into the
	 * trunk's column, waited for its own body to leave the block it bridged there and gave up out_of_reach.
	 */
	// Measured: passes at tick 514 to 516 (red: out_of_reach at the top log, at tick 1370).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 1100)
	public void aPoplarWithItsTopLogInItsLeavesIsFelled(GameTestHelper helper) {
		poplar(helper, at -> advance(helper, at, 6));
	}

	/**
	 * The poplar cell of 7x7 from (2, 0, 2) built by a bot carrying 16 dirt, its tree grown with grow (given the absolute
	 * position of (5, 1, 5)), its logs inside the cell, felled whole and replanted. Fails at once on a wrong print.
	 */
	private static void poplar(GameTestHelper helper, Consumer<BlockPos> grow) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 14, 1, 14);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 16),
			new ItemStack(Items.POPLAR_SAPLING, 2), new ItemStack(Items.TORCH, 2));
		BlockPos spot = new BlockPos(5, 1, 5);
		start(helper, bot, "poplar_sapling", new BlockPos(2, 0, 2), new BlockPos(8, 0, 8), """
			print(lib.treefarm.materials(kind))
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"])
			while not lib.treefarm.grown(kind, low, high):
			    wait(5)
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], r["reason"], r["at"])
			""");
		List<String> printed = List.of("{'minecraft:dirt': 1, 'minecraft:poplar_sapling': 1, 'minecraft:torch': 2}", "True None",
			"True None None");
		boolean[] inside = {true};
		boolean[] grew = growOnceBuilt(helper, bot, 2, spot, Blocks.POPLAR_LOG, grow);
		helper.onEachTick(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			if (log.size() == printed.size() && !log.equals(printed)) {
				helper.fail("printed " + log);
			}
			if (!grew[0] || !inside[0]) {
				return;
			}
			for (int x = 0; x < 24; x++) {
				for (int y = 1; y < 16; y++) {
					for (int z = 0; z < 24; z++) {
						if ((x < 2 || x > 8 || z < 2 || z > 8) && helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.POPLAR_LOG)) {
							inside[0] = false;
						}
					}
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), printed, "printed");
			helper.assertTrue(grew[0], "the poplar never grew");
			helper.assertTrue(inside[0], "a poplar log grew outside the cell");
			assertFelled(helper, bot, Blocks.POPLAR_LOG, Items.POPLAR_LOG);
			helper.assertBlockPresent(Blocks.POPLAR_SAPLING, spot);
		});
	}

	/**
	 * A mangrove cell of 11x11 from (2, 0, 2) (#650): built, grown as the game does with seed 0 (a TALL_MANGROVE), its
	 * trunk on roots over the propagule's spot and its logs inside the cell; felled whole from the trunk's lowest log, the
	 * roots in the cell mined, so the spot is free, and picked up (#751), and a propagule planted again.
	 */
	// Measured: passes at tick 1722 to 2181. 40 seeded trees passed at tick 553 to 3875 (red: key not in KINDS).
	// fell_whole alone, seeds 0 to 23 in temporary copies (#768): 190 to 2900 ticks (2 to 34 logs), 1000 on average;
	// its take_back 0 to 1370 of that, as the top branch logs no stand reaches each take a pillar of some 8 to 10. In 48
	// copies of seed 13, 46 took 1250 to 2900, but two took 5761 and over 8000: a mine's walk to a log beside its stand
	// in the crown got no nearer for 2400 ticks (MoveApi.STALL_TICKS) and timed out, the bot climbing at 0.12 a tick
	// against a block face and slipping back. That was the follower dropping off a vine under a block, its head bumping
	// the block (#776): since, harvest of seed 13 alone, 144 temporary copies, took 2060 to 4318, no walk timing out.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 4400)
	public void aMangroveCellIsGrownHarvestedAndReplanted(GameTestHelper helper) {
		mangrove(helper, at -> advance(helper, at, 0));
	}

	/**
	 * The mangrove grown with seed 12 at the spot of the cell of 11x11 from (2, 0, 2), the first of a farm of two built
	 * cells (lib.cells), the second from (14, 0, 2): 7 of its roots reach out of the cell (#768). harvest mines the roots
	 * up to 2 blocks out of the cell, as far as they reach, but none in the columns of the farm's other cell, such as a
	 * root put at (14, 1, 7), which that cell's own harvest mines.
	 */
	// Measured: passes at tick 2081 (red: a root left at (13, 1, 7)).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 4400)
	public void aMangroveHarvestMinesItsRootsOutOfTheCellButNotInAnotherCell(GameTestHelper helper) {
		BlockPos spot = new BlockPos(7, 1, 7);
		helper.setBlock(spot.below(), Blocks.DIRT);
		helper.setBlock(spot, Blocks.MANGROVE_PROPAGULE);
		advance(helper, helper.absolutePos(spot), 12);
		BlockPos other = new BlockPos(14, 1, 7);
		helper.setBlock(other, Blocks.MANGROVE_ROOTS);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 16, 1, 16);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 23),
			new ItemStack(Items.MANGROVE_PROPAGULE, 2));
		start(helper, bot, "mangrove_propagule", new BlockPos(2, 0, 2), new BlockPos(12, 0, 12), """
			import lib.cells
			lib.cells.layout("m", low, (11, 11))
			lib.cells.add("m", 0)
			lib.cells.add("m", 1)
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], r["reason"])
			""");
		Runnable mined = () -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True None"), "printed");
			for (int x = 0; x <= 14; x++) {
				for (int y = 1; y < 40; y++) {
					for (int z = 0; z <= 14; z++) {
						if (x < 14 || z < 2 || z > 12) {
							helper.assertBlockNotPresent(Blocks.MANGROVE_ROOTS, new BlockPos(x, y, z));
							helper.assertBlockNotPresent(Blocks.MUDDY_MANGROVE_ROOTS, new BlockPos(x, y, z));
						}
					}
				}
			}
			helper.assertBlockPresent(Blocks.MANGROVE_ROOTS, other);
		};
		// Checked at once when harvest ends, so a root left fails then.
		helper.onEachTick(() -> {
			if (!BotScripts.log(bot, Output.Level.PRINT).isEmpty()) {
				mined.run();
			}
		});
		helper.succeedWhen(mined);
	}

	/**
	 * The mangrove cell of 11x11 from (2, 0, 2) built by a bot carrying 23 dirt, its tree grown with grow (given the
	 * absolute position of (7, 1, 7)), its logs inside the cell, felled whole, its roots in the cell mined and replanted.
	 * Fails at once on a wrong print.
	 */
	private static void mangrove(GameTestHelper helper, Consumer<BlockPos> grow) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 16, 1, 16);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 23),
			new ItemStack(Items.MANGROVE_PROPAGULE, 2), new ItemStack(Items.TORCH, 2));
		BlockPos spot = new BlockPos(7, 1, 7);
		start(helper, bot, "mangrove_propagule", new BlockPos(2, 0, 2), new BlockPos(12, 0, 12), """
			print(lib.treefarm.materials(kind))
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"])
			while not lib.treefarm.grown(kind, low, high):
			    wait(5)
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], r["reason"], r["at"])
			""");
		List<String> printed = List.of("{'minecraft:dirt': 1, 'minecraft:mangrove_propagule': 1, 'minecraft:torch': 2}",
			"True None", "True None None");
		boolean[] inside = {true};
		boolean[] grew = growOnceBuilt(helper, bot, 2, spot, Blocks.MANGROVE_LOG, grow);
		helper.onEachTick(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			if (log.size() == printed.size() && !log.equals(printed)) {
				helper.fail("printed " + log);
			}
			if (!grew[0] || !inside[0] || log.size() > 2) {
				return;
			}
			for (int x = 0; x < 24; x++) {
				for (int y = 1; y < 40; y++) {
					for (int z = 0; z < 24; z++) {
						if ((x < 2 || x > 12 || z < 2 || z > 12) && helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.MANGROVE_LOG)) {
							inside[0] = false;
						}
					}
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), printed, "printed");
			helper.assertTrue(grew[0], "the mangrove never grew");
			helper.assertTrue(inside[0], "a mangrove log grew outside the cell");
			assertFelled(helper, bot, Blocks.MANGROVE_LOG, Items.MANGROVE_LOG, 40, 2);
			for (int x = 2; x <= 12; x++) {
				for (int y = 1; y < 40; y++) {
					for (int z = 2; z <= 12; z++) {
						helper.assertBlockNotPresent(Blocks.MANGROVE_ROOTS, new BlockPos(x, y, z));
					}
				}
			}
			// The roots it mined are picked up, not left lying (#751).
			AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(24, 40, 24);
			helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area,
				e -> e.getItem().is(Items.MANGROVE_ROOTS) || e.getItem().is(Items.MUDDY_MANGROVE_ROOTS)).isEmpty(), "roots left lying");
			helper.assertTrue(GameTests.carried(bot, Items.MANGROVE_ROOTS) > 0, "the bot carries no roots");
			helper.assertBlockPresent(Blocks.MANGROVE_PROPAGULE, spot);
		});
	}

	/**
	 * A giant spruce cell from (2, 0, 2) whose 2x2 trunk the bot fells carrying 2 saplings (#650): harvest gets the 2
	 * more from the chest and replants all four spots.
	 */
	// Measured: passes at tick 326 to 328.
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 700)
	public void aSquareCellHarvestedWithTwoSaplingsIsFullyReplanted(GameTestHelper helper) {
		List<BlockPos> spots = List.of(new BlockPos(6, 1, 6), new BlockPos(7, 1, 6), new BlockPos(6, 1, 7), new BlockPos(7, 1, 7));
		for (BlockPos p : spots) {
			helper.setBlock(p.below(), Blocks.DIRT);
			for (int y = 0; y < 3; y++) {
				helper.setBlock(p.above(y), Blocks.SPRUCE_LOG);
			}
		}
		helper.setBlock(14, 1, 4, Blocks.CHEST);
		helper.getBlockEntity(new BlockPos(14, 1, 4), ChestBlockEntity.class).setItem(0, new ItemStack(Items.SPRUCE_SAPLING, 2));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 12);
		give(bot, new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 8), new ItemStack(Items.SPRUCE_SAPLING, 2));
		start(helper, bot, "giant_spruce", new BlockPos(2, 0, 2), new BlockPos(11, 0, 11), """
			r = lib.treefarm.harvest(kind, low, high)
			print(r["ok"], r["reason"])
			""");
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True None"), "printed");
			for (BlockPos p : spots) {
				helper.assertBlockPresent(Blocks.SPRUCE_SAPLING, p);
			}
		});
	}

	/**
	 * The giant spruce four saplings grow with seed 58, a 2x2 trunk of 29, the tallest a giant spruce grows (#667): its
	 * top stand is some 28 up beside the trunk, and harvest, with the 31 dirt of jobs.wood_farm's giant kit, fells it whole.
	 * It kept its top log until a bot stepping up onto a block it had just bridged faced it before the jump (#670): until
	 * then harvest had not ended at tick 20000.
	 */
	// Measured: passes at tick 4103 to 4323 in 19 runs (was 7063 to 7991: taking its scaffold back from the floor once
	// the tree was down took some 4100 ticks, #677).
	// Since #687, where a path no longer stands on a block it broke, 4451 to 4787 in 12 runs, 6 at once (#695).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 9600)
	public void theTallestGiantSpruceIsFelledWhole(GameTestHelper helper) {
		BlockPos spot = new BlockPos(6, 1, 6);
		for (BlockPos p : List.of(spot, spot.east(), spot.south(), spot.east().south())) {
			helper.setBlock(p.below(), Blocks.DIRT);
			helper.setBlock(p, Blocks.SPRUCE_SAPLING);
		}
		advance(helper, helper.absolutePos(spot), 58);
		helper.assertBlockPresent(Blocks.SPRUCE_LOG, spot.above(28));
		helper.assertBlockNotPresent(Blocks.SPRUCE_LOG, spot.above(29));
		fellsWhole(helper, "giant_spruce", Items.SPRUCE_SAPLING, Blocks.SPRUCE_LOG, new BlockPos(16, 1, 16), 31,
			new BlockPos(2, 0, 2), new BlockPos(11, 0, 11), bot -> false);
	}

	/**
	 * The giant jungle four saplings grow with seed 37, a 2x2 trunk of 20 and 89 logs (#674): harvest, with the 33 dirt of
	 * jobs.wood_farm's giant kit, fells it whole. A branch log in the top of its crown, leaves on every side, kept no stand
	 * once the logs around it were mined, and mine's walk from the floor, with most of the dirt in scaffold, got nowhere:
	 * fell_whole now mines it from the leaves over it. Mining the scaffold from that crown then cut the pillars under the
	 * bot, which had no way down; take_back now stands on a block it is over before it mines it. Still, in some runs its
	 * walks left it on a floating platform of its own scaffold with no way down within 3 blocks: a path now drops past
	 * MAX_DROP for a share of a death (#682), its spawn its home. Such drops hurt it, and as it does not heal, about one
	 * harvest in a hundred ends in a fall that kills it (#694): it respawns and runs the script again, recover first.
	 */
	// Measured: passes at tick 2849 to 7809, 39 of 39 in three runs of 13 copies (red: out_of_reach in 3 of 14 runs;
	// with the fallback but no fall search, left up on its scaffold in 5 of 39). Since #677, 2657 to 4838 in 18 runs (one
	// at 7452 before: the walk to its first stand got nowhere and each log's mine walked 305 ticks to no end).
	// Without #674's walk onto the leaves over the buried log, which #685 made dead code (#691): 268 of 273 in 21 runs of
	// 13 copies, 2409 to 4909 and one at 6865; 5 ran out of maxTicks after the last stand, never out_of_reach. Those were
	// deaths (#694): logged per tick, 9 of 13 copies dropped 4 to 10 blocks on purpose, and in 8 of some 870 copies the
	// bot died; the test read the dead entity's log, not the respawned bot's. Watching the bot by its UUID: 144 of 144 in
	// 12 runs of 12 copies. Again (#695): 2467 to 3757 in 12 runs, 6 at once; maxTicks stays at some twice the 4909 of
	// #691's 273 runs (one at 6865).
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 10000)
	public void aSeed37GiantJungleIsFelledWhole(GameTestHelper helper) {
		fellsTheSeed37GiantJungle(helper, bot -> false);
	}

	/**
	 * A bot that dies on its way up the seed 37 giant jungle, killed 4 up, as one whose path drops past MAX_DROP for a share
	 * of a death (#682) may once its falls add up (#694), respawns at its home, where it was spawned, and runs its script
	 * again: recover takes back its scaffold and picks up its drops, and harvest fells the rest of the tree whole.
	 */
	// Measured: passes at tick 2571 to 5850, 52 of 52 in 4 runs of 13 copies (red, watching the first entity: printed
	// nothing at tick 10002). Killed 12 up, harvest after the respawn gave out_of_reach in 3 of 12 (#713): recover
	// walked to the leaves or branch under where it died, out of reach of the dirt that fell past them; and in some runs a
	// branch log was left cut off from the tree by the dead run's mining, which harvest left standing. Killed 12 up since:
	// 57 of 60 in 5 runs of 12 copies, some 3000 to 7000; 2 ran out of maxTicks with nothing printed, 1 gave out_of_reach.
	// Again (#731): 58 of 60 in runs of 12, 24 and 24 copies, 2751 to 7498. Both failures ran out of maxTicks with every
	// log felled: after the kill the bot died again, of a fall (a death share drop, #682), and the third run's recover,
	// re-felling and scaffold taken back from some 20 up ran past tick 10000. 4 of 24 in one run died twice and passed at
	// some 5900 to 7500. No out_of_reach in those 60. The second death (#739): recover's take_back, on the dead run's
	// scaffold bridge some 7 up, climbed a block a round to some 38 up, as mine's walk to each lone block it stood on
	// placed one beside and over it, and the bot died of the drop down; 3 of 120 after #736 (all passed, at up to 6439),
	// and 2 out_of_reach of a log some 10 up the dead run could not reach either. take_back now stops a round that
	// climbs: 96 of 96 in 4 runs of 24, 2981 to 6076, no second death. With take_back dropping off a lone block (#758):
	// 120 of 120 in 5 runs of 24, 2825 to 5428 (3734 on average), no out_of_reach. How often it drops off (#774, counted
	// in memory in temporary copies killed 12 up): 120 of 120 in 5 runs of 24, 2803 to 5401 (3757 on average); 39 copies
	// dropped off, 55 times in all (1 to 3 a copy), from 4 to 17 blocks over the ground, and 1 copy still stopped a round
	// that climbed.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 10000)
	public void aSeed37GiantJungleIsFelledWholeByABotThatDiesUpIt(GameTestHelper helper) {
		fellsTheSeed37GiantJungle(helper, bot -> helper.relativePos(bot.blockPosition()).getY() >= 4);
	}

	/**
	 * A bot killed over a tree's crown, here on leaves 10 up with a vine under them, as one killed 12 up the seed 37 giant
	 * jungle (#713): most of its dirt falls past the leaves to the floor, its axe stays on them. recover, once it respawns,
	 * walks down through the tree to the floor under where it died and picks up the dirt before it climbs, then to the
	 * leaves for the axe. It walked to the leaves first, out of reach of the dirt, so harvest had too little scaffold to
	 * reach the crown: out_of_reach.
	 */
	// Measured: passes at tick 554 (red: walked up to the leaves before it fetched its dirt, tick 307).
	@GameTest(structure = "antcraft-gametest:wide_tall", maxTicks = 1100)
	public void recoverPicksUpTheDropsThatFellThroughTheCrown(GameTestHelper helper) {
		for (int x = 11; x <= 13; x++) {
			for (int z = 11; z <= 13; z++) {
				helper.setBlock(x, 10, z, Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
			}
		}
		helper.setBlock(12, 9, 12, Blocks.VINE.defaultBlockState().setValue(VineBlock.UP, true));
		AntBot first = helper.spawn(ModEntities.ANT_BOT, new BlockPos(4, 1, 4));
		UUID id = first.getUUID();
		give(first, new ItemStack(Items.STONE_AXE));
		BotScripts.run(first, "import lib.trees\nif not lib.trees.recover():\n    wait(1000)\nprint(count(\"minecraft:dirt\"), has(\"stone_axe\"))\n");
		Supplier<@Nullable AntBot> bot = () -> helper.getLevel().getEntity(id) instanceof AntBot b && b.isAlive() ? b : null;
		BotScripts.failOnError(helper, bot);
		helper.runAfterDelay(5, () -> {
			BlockPos over = helper.absolutePos(new BlockPos(12, 11, 12));
			first.teleportTo(over.getX() + 0.5, over.getY(), over.getZ() + 0.5);
			helper.spawnItem(Items.DIRT, new BlockPos(13, 1, 13)).setItem(new ItemStack(Items.DIRT, 20));
			first.kill(helper.getLevel());
		});
		boolean[] fetched = {false};
		helper.onEachTick(() -> {
			AntBot now = bot.get();
			if (now == null || now == first) {
				return;
			}
			fetched[0] |= now.getInventory().countItem(Items.DIRT) >= 20;
			if (!fetched[0] && helper.relativePos(now.blockPosition()).getY() > 2) {
				helper.fail("walked up to " + helper.relativePos(now.blockPosition()).toShortString() + " before it fetched its dirt");
			}
		});
		helper.succeedWhen(() -> {
			AntBot now = bot.get();
			helper.assertTrue(now != null && now != first, "not respawned");
			helper.assertValueEqual(BotScripts.log(Objects.requireNonNull(now), Output.Level.PRINT), List.of("20 True"), "printed");
		});
	}

	/** Grows the seed 37 giant jungle and fells it as {@link #fellsWhole} does. */
	private static void fellsTheSeed37GiantJungle(GameTestHelper helper, Predicate<AntBot> killWhen) {
		BlockPos spot = new BlockPos(10, 1, 8);
		for (BlockPos p : List.of(spot, spot.east(), spot.south(), spot.east().south())) {
			helper.setBlock(p.below(), Blocks.DIRT);
			helper.setBlock(p, Blocks.JUNGLE_SAPLING);
		}
		advance(helper, helper.absolutePos(spot), 37);
		helper.assertBlockPresent(Blocks.JUNGLE_LOG, spot.above(19));
		fellsWhole(helper, "giant_jungle", Items.JUNGLE_SAPLING, Blocks.JUNGLE_LOG, new BlockPos(4, 1, 18), 33,
			new BlockPos(6, 0, 4), new BlockPos(15, 0, 13), killWhen);
	}

	/**
	 * Harvests the grown giant of kind in the cell from low to high as jobs.wood_farm does, recover first, with a bot at
	 * botAt given a stone axe, dirt and four saplings, so a bot that died and runs the script again once it respawns takes
	 * back the dead run's scaffold and picks up its drops. Kills the bot the first time {@code killWhen} holds for it.
	 * Watches the bot by its UUID: the bot that respawns is a new entity, and a test that read the first one's log saw
	 * nothing printed till maxTicks (#694). Passes once the script printed an ok harvest and no log of the tree is left.
	 */
	private static void fellsWhole(GameTestHelper helper, String kind, Item sapling, Block log, BlockPos botAt, int dirt,
			BlockPos low, BlockPos high, Predicate<AntBot> killWhen) {
		AntBot first = helper.spawn(ModEntities.ANT_BOT, botAt);
		UUID id = first.getUUID();
		give(first, new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, dirt), new ItemStack(sapling, 4));
		BlockPos l = helper.absolutePos(low);
		BlockPos h = helper.absolutePos(high);
		BotScripts.run(first, "import lib.trees\nimport lib.treefarm\nlib.trees.recover()\n"
			+ "r = lib.treefarm.harvest(\"" + kind + "\", Pos(" + l.getX() + ", " + l.getY() + ", " + l.getZ() + "), Pos("
			+ h.getX() + ", " + h.getY() + ", " + h.getZ() + "))\nprint(r[\"ok\"], r[\"reason\"], r[\"at\"])\n");
		Supplier<@Nullable AntBot> bot = () -> helper.getLevel().getEntity(id) instanceof AntBot b && b.isAlive() ? b : null;
		BotScripts.failOnWarningButDeath(helper, bot);
		boolean[] killed = {false};
		helper.onEachTick(() -> {
			AntBot now = bot.get();
			if (now == null) {
				return;
			}
			if (!killed[0] && killWhen.test(now)) {
				killed[0] = true;
				now.kill(helper.getLevel());
				return;
			}
			List<String> printed = BotScripts.log(now, Output.Level.PRINT);
			if (!printed.isEmpty() && !printed.equals(List.of("True None None"))) {
				helper.fail("printed " + printed);
			}
		});
		helper.succeedWhen(() -> {
			AntBot now = bot.get();
			helper.assertTrue(now != null, "no bot alive");
			helper.assertValueEqual(BotScripts.log(Objects.requireNonNull(now), Output.Level.PRINT), List.of("True None None"), "printed");
			for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 23, 39, 23)) {
				helper.assertBlockNotPresent(log, p);
			}
		});
	}

	/**
	 * The seed 37 giant jungle's branch log in the top of its crown, leaves on all six sides (#685): mine from the floor, with
	 * the 6 dirt goto(p.up().up()) needs, walks up and breaks it. Its reach goal's heuristic was 0 anywhere within 4 blocks
	 * sideways, as it counted a block up as 1 against a reach of 20 (4 walked blocks): 17 up was in reach, so the search,
	 * out of nodes, took the floor under the log as closest, and each replan from there got no closer: out_of_reach.
	 */
	// Measured: passes at tick 386 to 421 (red: printed False out_of_reach at tick 115). Not slower since (#727): 28 runs
	// each at #685 (29f149c9) and at 7002a68a, alone and in 8 and 16 copies at once, spread alike: 313 to 991 then (and
	// one False out_of_reach at 1123 among 16 copies), 291 to 1130 now; medians 568 then, 513 now. The first 386 to 421
	// was a few lucky runs. The slow ones (#736): a step up off the vines that broke leaves let go of the vines while it dug,
	// slid down, at times to the floor, and planned again from there; and a pillar in a vine grown since the path was
	// planned jumped only from the ground, where a jump only climbs, until the step timed out. Since: 290 to 528 in 34 runs,
	// alone and in 16 copies at once, the spread left from the routes the crown leaves, and a path that places more dirt
	// than the bot carries planned again half way. maxTicks 1100, about twice the slowest pass.
	@GameTest(structure = "antcraft-gametest:giant", maxTicks = 1100)
	public void mineReachesALogBuriedInTheTopOfAGiantJunglesCrown(GameTestHelper helper) {
		BlockPos spot = new BlockPos(10, 1, 8);
		for (BlockPos p : List.of(spot, spot.east(), spot.south(), spot.east().south())) {
			helper.setBlock(p.below(), Blocks.DIRT);
			helper.setBlock(p, Blocks.JUNGLE_SAPLING);
		}
		advance(helper, helper.absolutePos(spot), 37);
		BlockPos log = new BlockPos(11, 18, 6);
		helper.assertBlockPresent(Blocks.JUNGLE_LOG, log);
		for (Direction side : Direction.values()) {
			helper.assertBlockPresent(Blocks.JUNGLE_LEAVES, log.relative(side));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 18);
		give(bot, new ItemStack(Items.STONE_AXE), new ItemStack(Items.DIRT, 6));
		BlockPos at = helper.absolutePos(log);
		BotScripts.run(bot, "r = mine(Pos(" + at.getX() + ", " + at.getY() + ", " + at.getZ() + "))\nprint(r.ok, r.reason)\n");
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			if (!printed.isEmpty() && !printed.equals(List.of("True None"))) {
				helper.fail("printed " + printed);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True None"), "printed");
			helper.assertBlockNotPresent(Blocks.JUNGLE_LOG, log);
		});
	}

	/**
	 * Builds the 10x10 cell of the giant kind from (2, 0, 2) with the bot given its four saplings, prints materials,
	 * grows the tree once built and checks its 2x2 trunk stands on the spots and no log of it is outside the cell.
	 */
	private void growsAGiant(GameTestHelper helper, String kind, Item sapling, Block log, String materials) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 16, 1, 16);
		give(bot, new ItemStack(Items.STONE_PICKAXE), new ItemStack(Items.DIRT, 4), new ItemStack(sapling, 4),
			new ItemStack(Items.TORCH, 2));
		BlockPos spot = new BlockPos(6, 1, 6);
		start(helper, bot, kind, new BlockPos(2, 0, 2), new BlockPos(11, 0, 11), """
			print(lib.treefarm.materials(kind))
			r = lib.treefarm.build_cell(kind, low, high)
			print(r["ok"], r["reason"])
			""");
		boolean[] grew = growOnceBuilt(helper, bot, 2, spot, log, at -> advance(helper, at));
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of(materials, "True None"), "printed");
			helper.assertTrue(grew[0], "the giant never grew");
			for (BlockPos p : List.of(spot, spot.east(), spot.south(), spot.east().south())) {
				helper.assertBlockPresent(log, p);
			}
			for (int x = 0; x < 24; x++) {
				for (int y = 1; y < 40; y++) {
					for (int z = 0; z < 24; z++) {
						if (x < 2 || x > 11 || z < 2 || z > 11) {
							helper.assertBlockNotPresent(log, new BlockPos(x, y, z));
						}
					}
				}
			}
		});
	}

	/**
	 * Once the bot has printed printed lines, grows the tree at spot with grow (given the absolute position) each tick
	 * the spot holds a sapling, until a log stands there; gives whether it grew.
	 */
	private static boolean[] growOnceBuilt(GameTestHelper helper, AntBot bot, int printed, BlockPos spot, Block log,
			Consumer<BlockPos> grow) {
		boolean[] grew = new boolean[1];
		helper.onEachTick(() -> {
			if (grew[0] || BotScripts.log(bot, Output.Level.PRINT).size() < printed) {
				return;
			}
			if (helper.getBlockState(spot).getBlock() instanceof SaplingBlock) {
				grow.accept(helper.absolutePos(spot));
			}
			// Not the log: a mangrove's trunk stands on roots over its spot (#650).
			grew[0] = !(helper.getBlockState(spot).getBlock() instanceof SaplingBlock);
		});
		return grew;
	}

	/** Grows the sapling at the absolute at as a random tick or bone meal would. */
	private static void advance(GameTestHelper helper, BlockPos at) {
		advance(helper, at, helper.getLevel().getRandom());
	}

	/** Grows the sapling at the absolute at with the random of seed: the same tree each run. */
	private static void advance(GameTestHelper helper, BlockPos at, long seed) {
		advance(helper, at, RandomSource.create(seed));
	}

	private static void advance(GameTestHelper helper, BlockPos at, RandomSource random) {
		BlockState sapling = helper.getLevel().getBlockState(at);
		((SaplingBlock) sapling.getBlock()).advanceTree(helper.getLevel(), at, sapling.setValue(SaplingBlock.STAGE, 1), random);
	}

	/** No log left standing in the area nor lying about, and the bot carries 4 or more. */
	private static void assertFelled(GameTestHelper helper, AntBot bot, Block log, Item item) {
		assertFelled(helper, bot, log, item, 16, 4);
	}

	/**
	 * As {@link #assertFelled(GameTestHelper, AntBot, Block, Item)} in an area height high, the bot carrying least logs or
	 * more.
	 */
	private static void assertFelled(GameTestHelper helper, AntBot bot, Block log, Item item, int height, int least) {
		for (int x = 0; x < 24; x++) {
			for (int y = 1; y < height; y++) {
				for (int z = 0; z < 24; z++) {
					helper.assertBlockNotPresent(log, new BlockPos(x, y, z));
				}
			}
		}
		helper.assertTrue(GameTests.carried(bot, item) >= least, "the bot carries " + GameTests.carried(bot, item) + " logs");
		AABB area = new AABB(helper.absolutePos(BlockPos.ZERO)).expandTowards(24, height, 24);
		helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, area, e -> e.getItem().is(item)).isEmpty(),
			"logs left lying");
	}

	private static void give(AntBot bot, ItemStack... stacks) {
		for (ItemStack stack : stacks) {
			bot.getInventory().addItem(stack);
		}
	}

	/** Runs body on bot with kind, low and high (relative positions made absolute) set, failing at once on an error. */
	private static void start(GameTestHelper helper, AntBot bot, String kind, BlockPos low, BlockPos high, String body) {
		BlockPos l = helper.absolutePos(low);
		BlockPos h = helper.absolutePos(high);
		BotScripts.run(bot, "import lib.treefarm\nkind = \"" + kind + "\"\nlow = Pos(" + l.getX() + ", " + l.getY() + ", " + l.getZ()
			+ ")\nhigh = Pos(" + h.getX() + ", " + h.getY() + ", " + h.getZ() + ")\n" + body);
		BotScripts.failOnError(helper, bot);
	}
}
