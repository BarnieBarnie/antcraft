package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@code lib/cellfarm.py} (#826; docs/reference/modules/lib/cellfarm.md) with a farm of no trees: cells of one block,
 * planted with a torch, ripe when a hay block stands there instead.
 */
public class CellFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * A spec of cells of one block, corner at (1, 1, 3): the engine lays the farm out, builds cell 1 at (1, 0, 3) and,
	 * with the torches got from the chest, cell 2 at (3, 0, 3); the test puts hay in cell 1, and the bot harvests it,
	 * stores it in its depot north of the cells (a chest from the supply chest) and plants the cell again.
	 */
	// Measured: passes at tick 256 to 257, alone and with the wood farm tests (red: No module named 'lib.cellfarm').
	@GameTest(maxTicks = 520)
	public void aSpecOfOneBlockCellsIsLaidOutGrownAndHarvested(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.TORCH, 3));
		chest.setItem(1, new ItemStack(Items.CHEST));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		BotScripts.run(bot, """
			import lib.cellfarm
			from lib.get import get
			from lib.project import Step
			from lib.result import result
			T = "minecraft:torch"
			H = "minecraft:hay_block"
			def build_cell(low, high):
			    r = place(low.up(), T)
			    return result() if r else result(r.reason, low.up())
			def harvest(low, high, told):
			    mine(low.up())
			    pick_up(H)
			    return result()
			def tend(boxes, told):
			    for b in boxes:
			        if block(b[0].up()).is_air and get(T, 1, 8)["ok"]:
			            place(b[0].up(), T)
			f = {"size": (1, 1), "categories": {"hay": [H]}, "corners": "test_farms", "keep": [T],
			     "materials": lambda: {T: 1}, "build_cell": build_cell, "ripe": lambda low, high: block(low.up()).id == H,
			     "harvest": harvest, "tend": tend, "most": lambda: 2}
			lib.cellfarm.run(f, "test_farm", "torch", Pos(%d, %d, %d), [Step("farm", lib.cellfarm.farm, (f, "test_farm", None, 8))], 8)
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnError(helper, bot);
		boolean[] ripe = new boolean[1];
		helper.onEachTick(() -> {
			if (!ripe[0] && BotScripts.log(bot, Output.Level.PRINT).contains("built cell 2")) {
				helper.setBlock(1, 1, 3, Blocks.HAY_BLOCK);
				ripe[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(ripe[0], "cell 2 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.TORCH, 1, 1, 3);
			helper.assertBlockPresent(Blocks.TORCH, 3, 1, 3);
			helper.assertBlockPresent(Blocks.CHEST, 1, 1, 1);
			helper.assertTrue(helper.getBlockEntity(new BlockPos(1, 1, 1), ChestBlockEntity.class).countItem(Items.HAY_BLOCK) == 1,
				"no hay in the depot; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}
}
