package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;

/**
 * {@code lib/treefarm.py} (#605): tree cells per sapling kind, built, grown, harvested and replanted. In the 24x16x24
 * {@code wide_tall} structure: a cell clears 8 to 14 blocks over its floor, more than the 8 high of {@code wide}.
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
				"{'minecraft:dirt': 1, 'minecraft:oak_sapling': 1, 'minecraft:torch': 2, '#minecraft:wooden_slabs': 9}",
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
