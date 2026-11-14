package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;

/** The job {@code jobs/settle.py} (#574; docs/cookbook/settle.md): a whole base over {@code lib.project}. */
public class SettleGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * An empty bot by a chest with stone tools, a crafting table, a chest, dirt, seeds, a water bucket and the cobble
	 * step's materials settles at (4, 1, 4) with the cobble step as its house: a dirt bump at the plot's corner is dug,
	 * the house built, the chest and crafting table placed, water poured in the middle of the 3 by 3 dirt patch and the 8
	 * round it planted with wheat (wet farmland, so it stays farmland), home set and the borrowed tools put back. Stopped
	 * as it starts the house and run again, it does not do the kit and the flattening again.
	 */
	// Measured: passes at tick 435 (953 to 1017 with the starter house, whose build WorkGameTest covers).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 900)
	public void settlesABaseAndResumesAfterAStop(GameTestHelper helper) {
		Container chest = plot(helper, true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 16, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(4, 1, 4));
		String run = "antcraft bot " + bot.getStringUUID() + " run jobs.settle " + at.getX() + " " + at.getY() + " " + at.getZ() + " 12 antcraft:cobble_step";
		BotScripts.command(helper, run);
		boolean[] rerun = new boolean[1];
		Runnable settled = () -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("settle 1/7: kit", "settle 2/7: flatten",
				"settle 3/7: hut", "settle 3/7: hut", "settle 4/7: furnish", "settle 5/7: field", "settle 6/7: store", "settle 7/7: home",
				"settled"), "said");
			assertBase(helper, bot, chest, at);
			helper.assertTrue(helper.getBlockState(new BlockPos(11, 0, 7)).getFluidState().isSource()
				&& helper.getBlockState(new BlockPos(11, 0, 7)).getFluidState().is(Fluids.WATER), "no water in the middle of the field");
			for (int x = 10; x <= 12; x++) {
				for (int z = 6; z <= 8; z++) {
					if (x == 11 && z == 7) {
						continue;
					}
					// A random tick dries farmland with no water near and wets farmland that has.
					BlockPos soil = helper.absolutePos(new BlockPos(x, 0, z));
					helper.assertBlockPresent(Blocks.FARMLAND, x, 0, z);
					helper.getLevel().getBlockState(soil).randomTick(helper.getLevel(), soil, helper.getLevel().getRandom());
					helper.assertBlockProperty(new BlockPos(x, 0, z), FarmlandBlock.MOISTURE, 7);
					helper.assertBlockPresent(Blocks.WHEAT, x, 1, z);
				}
			}
		};
		helper.onEachTick(() -> {
			ScriptRun script = bot.getScript();
			List<String> said = BotScripts.log(bot, Output.Level.PRINT);
			if (script != null && script.state() == ScriptRun.State.FAILED || !BotScripts.warnings(bot).isEmpty()) {
				helper.fail("failed or warned: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
			if (!rerun[0] && said.contains("settle 3/7: hut")) {
				rerun[0] = true;
				bot.stopScript();
				BotScripts.command(helper, run);
			}
			// Once it says it is done, what is wrong stays wrong: fail at once, with what the bot did.
			if (!said.isEmpty() && said.getLast().contains("settled")) {
				try {
					settled.run();
				} catch (RuntimeException e) {
					helper.fail(e.getMessage() + "; " + BotScripts.log(bot, Output.Level.DEBUG));
				}
			}
		});
		helper.succeedWhen(settled);
	}

	/**
	 * The field is optional: with no seeds anywhere and no water bucket, the field step fails (tilled, nothing planted, no
	 * water) and the bot still stores, sets home and is settled.
	 */
	// Measured: passes at tick 311.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 650)
	public void aFieldThatFailsLeavesTheBaseSettled(GameTestHelper helper) {
		Container chest = plot(helper, false);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 16, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(4, 1, 4));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.settle " + at.getX() + " " + at.getY() + " " + at.getZ()
			+ " 12 antcraft:cobble_step");
		helper.onEachTick(() -> {
			ScriptRun script = bot.getScript();
			if (script != null && script.state() == ScriptRun.State.FAILED || !BotScripts.warnings(bot).isEmpty()) {
				helper.fail("failed or warned: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("settle 1/7: kit", "settle 2/7: flatten",
				"settle 3/7: hut", "settle 4/7: furnish", "settle 5/7: field", "settle 6/7: store", "settle 7/7: home", "settled"), "said");
			assertBase(helper, bot, chest, at);
			for (int x = 10; x <= 12; x++) {
				for (int z = 6; z <= 8; z++) {
					helper.assertBlockPresent(Blocks.AIR, x, 1, z);
				}
			}
		});
	}

	/**
	 * The plot of the settle tests: a dirt bump at (12, 1, 9), a 3 by 3 dirt patch at (10, 0, 6) to (12, 0, 8), and a
	 * chest at (17, 1, 4) with the kit and the cobble step's materials, and with seeds and a water bucket where field.
	 */
	private static Container plot(GameTestHelper helper, boolean field) {
		helper.setBlock(12, 1, 9, Blocks.DIRT);
		helper.setBlock(12, 2, 9, Blocks.DIRT);
		for (int x = 10; x <= 12; x++) {
			for (int z = 6; z <= 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		helper.setBlock(17, 1, 4, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(17, 1, 4), ChestBlockEntity.class);
		List.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_SHOVEL, Items.STONE_HOE, Items.CRAFTING_TABLE, Items.CHEST, Items.OAK_STAIRS)
			.forEach(item -> add(chest, new ItemStack(item)));
		add(chest, new ItemStack(Items.DIRT, 16));
		add(chest, new ItemStack(Items.COBBLESTONE, 2));
		if (field) {
			add(chest, new ItemStack(Items.WHEAT_SEEDS, 9));
			add(chest, new ItemStack(Items.WATER_BUCKET));
		}
		return chest;
	}

	/** The cobble step at (4, 1, 4), the bump gone, the chest and table placed, home set and the pickaxe back in its chest. */
	private static void assertBase(GameTestHelper helper, AntBot bot, Container chest, BlockPos at) {
		helper.assertBlockPresent(Blocks.COBBLESTONE, 4, 1, 4);
		helper.assertBlockPresent(Blocks.COBBLESTONE, 5, 1, 4);
		helper.assertBlockPresent(Blocks.OAK_STAIRS, 4, 2, 4);
		helper.assertTrue(helper.getBlockState(new BlockPos(12, 1, 9)).isAir() && helper.getBlockState(new BlockPos(12, 2, 9)).isAir(), "the bump is left");
		helper.assertBlockPresent(Blocks.CHEST, 10, 1, 4);
		helper.assertBlockPresent(Blocks.CRAFTING_TABLE, 10, 1, 5);
		helper.assertValueEqual(bot.getHome(), at.offset(2, 1, 1), "home");
		helper.assertTrue(chest.countItem(Items.STONE_PICKAXE) == 1 && bot.getInventory().countItem(Items.STONE_PICKAXE) == 0,
			"the borrowed pickaxe is not back");
	}

	private static void add(Container chest, ItemStack stack) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			ItemStack in = chest.getItem(i);
			if (in.isEmpty()) {
				chest.setItem(i, stack);
				return;
			}
			if (ItemStack.isSameItemSameComponents(in, stack) && in.getCount() + stack.getCount() <= in.getMaxStackSize()) {
				in.grow(stack.getCount());
				return;
			}
		}
		throw new IllegalStateException("chest full");
	}
}
