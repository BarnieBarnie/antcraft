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
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@code jobs/giant_tree.py} (#513): the bot fells the giant spruce of {@link TreesGameTest#plantGiant}, crowned with
 * leaves, with an axe from {@code ensure_tool}, scaffold dirt and saplings borrowed from a chest, and gives back what it
 * borrowed. Each run passes radius 8 so a neighbouring test's chest or tree is not one of its own.
 */
public class GiantTreeGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CHEST = new BlockPos(1, 1, 6);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		TreesGameTest.plantGiant(helper);
		TreesGameTest.crownGiant(helper);
		method.invoke(this, helper);
	}

	/**
	 * A bot without an axe, carrying what a stone axe takes, by the tree it is nearest: it makes the axe, borrows the dirt
	 * and the saplings, fells all 26 logs, replants four saplings and gives the dirt back. Passes at about tick 770.
	 */
	@GameTest(maxTicks = 1600)
	public void fellsTheNearestGiantTreeWithAnAxeItMakes(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.DIRT, 6), new ItemStack(Items.SPRUCE_SAPLING, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 3));
		run(helper, bot, "8");
		helper.succeedWhen(() -> {
			felled(helper, bot);
			helper.assertTrue(bot.getInventory().countItem(Items.STONE_AXE) == 1, "no stone axe made");
			helper.assertTrue(chest.countItem(Items.DIRT) == 6 && chest.countItem(Items.SPRUCE_SAPLING) == 0,
				"the chest holds " + chest.countItem(Items.DIRT) + " dirt, " + chest.countItem(Items.SPRUCE_SAPLING) + " saplings");
		});
	}

	/** The tree's position given, an iron axe in the chest: the bot borrows it, fells the tree and gives it back worn. */
	@GameTest(maxTicks = 1250)
	public void borrowsAnIronAxeAndGivesItBack(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.IRON_AXE), new ItemStack(Items.DIRT, 6),
			new ItemStack(Items.SPRUCE_SAPLING, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos tree = helper.absolutePos(new BlockPos(4, 1, 4));
		run(helper, bot, tree.getX() + " " + tree.getY() + " " + tree.getZ() + " 8");
		helper.succeedWhen(() -> {
			felled(helper, bot);
			helper.assertTrue(bot.getInventory().countItem(Items.IRON_AXE) == 0, "the bot kept the iron axe");
			ItemStack axe = ItemStack.EMPTY;
			for (int i = 0; i < chest.getContainerSize(); i++) {
				if (chest.getItem(i).is(Items.IRON_AXE)) {
					axe = chest.getItem(i);
				}
			}
			helper.assertTrue(!axe.isEmpty() && axe.getDamageValue() > 0, "no worn iron axe in the chest: " + axe);
			helper.assertTrue(chest.countItem(Items.DIRT) == 6 && chest.countItem(Items.SPRUCE_SAPLING) == 0,
				"the chest holds " + chest.countItem(Items.DIRT) + " dirt, " + chest.countItem(Items.SPRUCE_SAPLING) + " saplings");
		});
	}

	/** The job finished, said it felled 26 logs, the bot carries them, and the tree is gone with four saplings planted. */
	private static void felled(GameTestHelper helper, AntBot bot) {
		ScriptRun run = bot.getScript();
		List<String> log = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(log.equals(List.of("felled a giant tree: 26 logs")), "printed " + log);
		helper.assertTrue(bot.getInventory().countItem(Items.SPRUCE_LOG) == 26,
			"the bot carries " + bot.getInventory().countItem(Items.SPRUCE_LOG) + " spruce logs");
		TreesGameTest.assertGiantFelled(helper);
		helper.assertTrue(run != null && run.state() == ScriptRun.State.FINISHED, "the job " + (run == null ? null : run.state()));
	}

	private static Container chest(GameTestHelper helper, ItemStack... stacks) {
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		for (int i = 0; i < stacks.length; i++) {
			chest.setItem(i, stacks[i]);
		}
		return chest;
	}

	/** Runs the job with args on bot and fails the test at once when it fails. */
	private static void run(GameTestHelper helper, AntBot bot, String args) {
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.giant_tree " + args);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[jobs.giant_tree, 1]")), "run answered " + started);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("job failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
	}
}
