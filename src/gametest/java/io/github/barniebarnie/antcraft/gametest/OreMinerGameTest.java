package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@code jobs/ore_miner.py} (#1002; docs/cookbook/ore-miner.md). The bot's home is where it spawns, (3, 1, 6); stone is
 * filled under the floor down to y -4, and the job mines at y -2 in the area it is given in place of its home chunk:
 * stairs north from home to (3, -2, 3), a corridor along x there and branches north and south from it every 3 blocks.
 * The bot carries the MINER kit, three chests for its depot and two coal.
 */
public class OreMinerGameTest implements CustomTestMethodInvoker {
	private static final BlockPos HOME = new BlockPos(3, 1, 6);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (BlockPos pos : BlockPos.betweenClosed(0, -4, 0, 7, -1, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		method.invoke(this, helper);
	}

	/**
	 * Iron ore in the walls of the two columns of x 2 to 6 (x 3 and 6): the bot mines both, smelts the raw iron at home
	 * and ends there with the ingots in its depot, the grid dug.
	 */
	// Measured: passes at tick 1251 (red: No module named 'jobs.ore_miner').
	@GameTest(maxTicks = 2500)
	public void minesSmeltsAndStoresTheIronAtHome(GameTestHelper helper) {
		helper.setBlock(2, -2, 1, Blocks.IRON_ORE);
		helper.setBlock(5, -1, 1, Blocks.IRON_ORE);
		AntBot bot = miner(helper);
		ScriptRun run = run(helper, bot, 2, 0, 6, 7);
		helper.succeedWhen(() -> {
			ended(helper, run, bot);
			helper.assertValueEqual(stored(helper, Items.IRON_INGOT), 2, "iron ingots stored");
			helper.assertValueEqual(GameTests.carried(bot, Items.RAW_IRON), 0, "raw iron carried");
		});
	}

	/**
	 * A bot with 2 free slots goes home at the bottom of its stairs, before any branch, stores the dirt it carries and
	 * goes back down to dig the grid.
	 */
	// Measured: passes at tick 772 (red: No module named 'jobs.ore_miner'; with FREE 0, the dirt stored after the branches).
	@GameTest(maxTicks = 1600)
	public void aFullInventorySendsItHomeAndBackDown(GameTestHelper helper) {
		AntBot bot = miner(helper);
		for (int i = 0, free = 0; i < bot.getInventory().getContainerSize(); i++) {
			if (bot.getInventory().getItem(i).isEmpty() && free++ >= 2) {
				bot.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
			}
		}
		ScriptRun run = run(helper, bot, 2, 0, 6, 7);
		boolean[] early = {false, false};
		helper.onEachTick(() -> {
			if (!early[0] && stored(helper, Items.DIRT) > 0) {
				early[0] = true;
				early[1] = helper.getBlockState(new BlockPos(3, -2, 2)).is(Blocks.STONE);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(early[0], "no dirt stored");
			helper.assertTrue(early[1], "the dirt was stored after the first branch was dug");
			ended(helper, run, bot);
			helper.assertBlockPresent(Blocks.AIR, 6, -2, 0);
		});
	}

	/**
	 * The area x 2 to 4, z 2 to 6 has one column, x 3, whose north branch is one block long and whose south one would
	 * undermine the stairs: once the north one is dug the job ends with the bot at home.
	 */
	// Measured: passes at tick 212 (red: No module named 'jobs.ore_miner').
	@GameTest(maxTicks = 450)
	public void aDugGridEndsTheJobAtHome(GameTestHelper helper) {
		AntBot bot = miner(helper);
		ScriptRun run = run(helper, bot, 2, 2, 4, 6);
		helper.succeedWhen(() -> {
			ended(helper, run, bot);
			helper.assertBlockPresent(Blocks.AIR, 3, -2, 2);
			helper.assertBlockPresent(Blocks.STONE, 3, -2, 1);
			helper.assertBlockPresent(Blocks.STONE, 3, -2, 4);
		});
	}

	private static AntBot miner(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, HOME.getX(), HOME.getY(), HOME.getZ());
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 16));
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 3));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 2));
		return bot;
	}

	/** Runs jobs.ore_miner for raw iron at y -2 in the area x0..x1, z0..z1 (relative), failing at once on a warning. */
	private static ScriptRun run(GameTestHelper helper, AntBot bot, int x0, int z0, int x1, int z1) {
		BlockPos a = helper.absolutePos(new BlockPos(x0, -2, z0));
		BlockPos b = helper.absolutePos(new BlockPos(x1, -2, z1));
		String source = "import jobs.ore_miner\njobs.ore_miner.run(\"raw_iron\", 1, 32, %d, (%d, %d, %d, %d))\n".formatted(a.getY(),
			Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()));
		ScriptRun run = BotScripts.run(bot, source);
		BotScripts.failOnWarning(helper, bot);
		return run;
	}

	/** The job finished with the bot at home. */
	private static void ended(GameTestHelper helper, ScriptRun run, AntBot bot) {
		helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "the job " + run.state());
		helper.assertTrue(bot.blockPosition().equals(helper.absolutePos(HOME)), "the bot is at " + TestPositions.relative(helper, bot.blockPosition()));
	}

	/** How many of item the chests standing in the area at y 1 to 2 hold. */
	private static int stored(GameTestHelper helper, Item item) {
		int n = 0;
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 2, 7)) {
			if (helper.getBlockState(pos).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(pos.immutable(), ChestBlockEntity.class).countItem(item);
			}
		}
		return n;
	}
}
