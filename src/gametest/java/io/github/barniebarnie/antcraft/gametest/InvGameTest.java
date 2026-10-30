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

/**
 * {@code lib/inv.py}: {@code take_all} (#443) takes what the bot lacks from a chest at (2, 1, 2), {@code store_all} (#444) puts
 * all but tools, food and what it keeps into it; the bot starts at (5, 1, 5).
 */
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
			take_all(chest, {"minecraft:cobblestone": 4, "minecraft:torch": 3})""", """
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
			take_all(chest, {"minecraft:cobblestone": 4, "#minecraft:planks": 5})""", """
			print(len(r), r["minecraft:cobblestone"], r["#minecraft:planks"], count("minecraft:cobblestone"))""");
		succeedWhenPrinted(helper, bot, "2 1 5 3", () -> helper.assertTrue(chest.isEmpty(), "chest not empty"));
	}

	/** Logs and dirt go into the chest; the pickaxe (a tool) and the bread (food) stay with the bot. */
	@GameTest(maxTicks = 40)
	public void storeAllKeepsToolsAndFood(GameTestHelper helper) {
		Container chest = chest(helper);
		AntBot bot = run(helper, List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.IRON_PICKAXE),
			new ItemStack(Items.BREAD, 3), new ItemStack(Items.DIRT, 5)), "store_all(chest)", """
			print(r["ok"], count("oak_log"), count("iron_pickaxe"), count("bread"), count("dirt"))""");
		succeedWhenPrinted(helper, bot, "True 0 1 3 0", () -> {
			helper.assertTrue(count(chest, Items.OAK_LOG) == 10, "logs stored " + count(chest, Items.OAK_LOG));
			helper.assertTrue(count(chest, Items.DIRT) == 5, "dirt stored " + count(chest, Items.DIRT));
		});
	}

	/** keep selects the logs: only the dirt goes in. */
	@GameTest(maxTicks = 40)
	public void storeAllKeepsWhatKeepSelects(GameTestHelper helper) {
		Container chest = chest(helper);
		AntBot bot = run(helper, List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.DIRT, 5)),
			"store_all(chest, keep=[\"#minecraft:logs\"])", """
			print(r["ok"], count("oak_log"), count("dirt"))""");
		succeedWhenPrinted(helper, bot, "True 10 0", () -> helper.assertTrue(count(chest, Items.DIRT) == 5,
			"dirt stored " + count(chest, Items.DIRT)));
	}

	/** A full chest takes nothing: no_space, with what is left in the bot as missing. */
	@GameTest(maxTicks = 40)
	public void storeAllIntoAFullChestGivesNoSpace(GameTestHelper helper) {
		ItemStack[] stone = new ItemStack[27];
		for (int i = 0; i < stone.length; i++) {
			stone[i] = new ItemStack(Items.STONE, 64);
		}
		chest(helper, stone);
		AntBot bot = run(helper, List.of(new ItemStack(Items.OAK_LOG, 10), new ItemStack(Items.DIRT, 5)), "store_all(chest)", """
			print(r["reason"], len(r["missing"]), r["missing"]["minecraft:oak_log"], r["missing"]["minecraft:dirt"], count("oak_log"))""");
		succeedWhenPrinted(helper, bot, "no_space 2 10 5 10", () -> { });
	}

	private static Container chest(GameTestHelper helper, ItemStack... stacks) {
		helper.setBlock(CHEST, Blocks.CHEST);
		Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		for (int i = 0; i < stacks.length; i++) {
			chest.setItem(i, stacks[i]);
		}
		return chest;
	}

	/** Starts a bot carrying {@code carried} on {@code r = call} ({@code chest} is the chest's position) and then {@code then}. */
	private static AntBot run(GameTestHelper helper, List<ItemStack> carried, String call, String then) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		carried.forEach(bot.getInventory()::addItem);
		BlockPos c = helper.absolutePos(CHEST);
		String name = BotScripts.libraryScript(helper, "inv_", """
			from lib.inv import take_all, store_all
			chest = Pos(%d, %d, %d)
			r = %s
			%s
			""".formatted(c.getX(), c.getY(), c.getZ(), call, then));
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
