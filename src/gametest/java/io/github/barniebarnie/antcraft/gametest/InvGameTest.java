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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** {@code lib/inv.py} (#443): {@code take_all} takes what the bot lacks from a chest at (2, 1, 2), the bot starting at (5, 1, 5). */
public class InvGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CHEST = new BlockPos(2, 1, 2);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** The chest has enough: the bot takes 4 cobblestone, no torches (it carries its 3), and nothing is short. */
	@GameTest(maxTicks = 40)
	public void takeAllFillsWhatIsLacking(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.COBBLESTONE, 10), new ItemStack(Items.TORCH, 5));
		AntBot bot = run(helper, List.of(new ItemStack(Items.TORCH, 3)), """
			{"minecraft:cobblestone": 4, "minecraft:torch": 3}""", """
			print(r == {}, count("minecraft:cobblestone"), count("minecraft:torch"))""");
		succeedWhenPrinted(helper, bot, "True 4 3", () -> {
			helper.assertTrue(count(chest, Items.COBBLESTONE) == 6, "cobblestone left " + count(chest, Items.COBBLESTONE));
			helper.assertTrue(count(chest, Items.TORCH) == 5, "torches left " + count(chest, Items.TORCH));
		});
	}

	/** The chest has too little: the bot takes its 2 cobblestone and gives what is still short, a selector too. */
	@GameTest(maxTicks = 40)
	public void takeAllGivesWhatIsShort(GameTestHelper helper) {
		Container chest = chest(helper, new ItemStack(Items.COBBLESTONE, 2));
		AntBot bot = run(helper, List.of(new ItemStack(Items.COBBLESTONE, 1)), """
			{"minecraft:cobblestone": 4, "#minecraft:planks": 5}""", """
			print(len(r), r["minecraft:cobblestone"], r["#minecraft:planks"], count("minecraft:cobblestone"))""");
		succeedWhenPrinted(helper, bot, "2 1 5 3", () -> helper.assertTrue(chest.isEmpty(), "chest not empty"));
	}

	private static Container chest(GameTestHelper helper, ItemStack... stacks) {
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		for (int i = 0; i < stacks.length; i++) {
			chest.setItem(i, stacks[i]);
		}
		return chest;
	}

	/** Starts a bot carrying {@code carried} on {@code r = take_all(chest, need)} and then {@code then}. */
	private static AntBot run(GameTestHelper helper, List<ItemStack> carried, String need, String then) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		carried.forEach(bot.getInventory()::addItem);
		BlockPos c = helper.absolutePos(CHEST);
		String name = BotScripts.libraryScript(helper, "inv_", """
			from lib.inv import take_all
			r = take_all(Pos(%d, %d, %d), %s)
			%s
			""".formatted(c.getX(), c.getY(), c.getZ(), need, then));
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
		return bot;
	}

	/** Fails at once when the script fails; succeeds once it printed {@code printed} and {@code check} holds. */
	private static void succeedWhenPrinted(GameTestHelper helper, AntBot bot, String printed, Runnable check) {
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(log.equals(List.of(printed)), "printed " + log);
			check.run();
		});
	}

	private static int count(Container chest, Item item) {
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			if (chest.getItem(i).is(item)) {
				n += chest.getItem(i).getCount();
			}
		}
		return n;
	}
}
