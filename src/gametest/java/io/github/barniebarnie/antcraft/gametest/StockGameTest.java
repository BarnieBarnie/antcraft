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
 * {@code lib/stock.py} (#506): fetch from the chests at (6, 1, 3) (near) and (1, 1, 1) (far); the bot starts at (5, 1, 5).
 * Each test passes radius=8 so a neighbouring test's chest is never one of its chests.
 */
public class StockGameTest implements CustomTestMethodInvoker {
	private static final BlockPos NEAR = new BlockPos(6, 1, 3);
	private static final BlockPos FAR = new BlockPos(1, 1, 1);
	private static final String IMPORT = "from lib.stock import chests, where, fetch\nfrom lib.borrow import borrowed\n";

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** 10 planks, 6 in each chest: the near chest gives 6, the far one 4, both recorded as borrowed. */
	@GameTest(maxTicks = 50)
	public void fetchesFromTheNearestChestFirstAndBorrows(GameTestHelper helper) {
		Container near = chest(helper, NEAR, new ItemStack(Items.OAK_PLANKS, 6));
		Container far = chest(helper, FAR, new ItemStack(Items.OAK_PLANKS, 6));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		run(helper, bot, IMPORT + """
			a = %s
			b = %s
			print(chests(radius=8) == [a, b], where("oak_planks", radius=8) == [a, b], where("dirt", radius=8))
			r = fetch("oak_planks", 10, radius=8)
			print(r["ok"], count("oak_planks"), [(x["chest"] == a, x["n"]) for x in borrowed()])
			""".formatted(pos(helper, NEAR), pos(helper, FAR)));
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True True []", "True 10 [(True, 6), (False, 4)]");
			helper.assertTrue(count(near, Items.OAK_PLANKS) == 0, "planks in the near chest " + count(near, Items.OAK_PLANKS));
			helper.assertTrue(count(far, Items.OAK_PLANKS) == 2, "planks in the far chest " + count(far, Items.OAK_PLANKS));
		});
	}

	/** 8 planks in the chests and 1 carried, 10 asked: missing names the 1 short and nothing is taken. */
	@GameTest(maxTicks = 40)
	public void tooFewTakesNothing(GameTestHelper helper) {
		Container near = chest(helper, NEAR, new ItemStack(Items.OAK_PLANKS, 4));
		Container far = chest(helper, FAR, new ItemStack(Items.OAK_PLANKS, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 1));
		run(helper, bot, IMPORT + """
			r = fetch("oak_planks", 10, radius=8)
			print(r["ok"], r["reason"], r["missing"], count("oak_planks"), borrowed())
			""");
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "False no_item {'oak_planks': 1} 1 []");
			helper.assertTrue(count(near, Items.OAK_PLANKS) == 4 && count(far, Items.OAK_PLANKS) == 4, "planks taken");
		});
	}

	/** The bot already carries 10: fetch takes nothing. */
	@GameTest(maxTicks = 40)
	public void alreadyCarryingDoesNothing(GameTestHelper helper) {
		Container near = chest(helper, NEAR, new ItemStack(Items.OAK_PLANKS, 6));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 10));
		run(helper, bot, IMPORT + """
			r = fetch("oak_planks", 10, radius=8)
			print(r["ok"], count("oak_planks"), borrowed())
			""");
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 10 []");
			helper.assertTrue(count(near, Items.OAK_PLANKS) == 6, "planks taken");
		});
	}

	/** take_up_to with chests (#545): only the far chest given, 4 of its 5 planks taken up to 6 with 2 carried, none from the near one. */
	@GameTest(maxTicks = 50)
	public void takeUpToTakesOnlyFromTheChestsGiven(GameTestHelper helper) {
		Container near = chest(helper, NEAR, new ItemStack(Items.OAK_PLANKS, 6));
		Container far = chest(helper, FAR, new ItemStack(Items.OAK_PLANKS, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 2));
		run(helper, bot, IMPORT + """
			from lib.stock import take_up_to
			r = take_up_to("oak_planks", 6, chests=[%s])
			print(r["ok"], count("oak_planks"))
			r = take_up_to("oak_planks", 9, chests=[%s])
			print(r["ok"], r["missing"], count("oak_planks"))
			""".formatted(pos(helper, FAR), pos(helper, FAR)));
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 6", "False {'oak_planks': 2} 7");
			helper.assertTrue(count(near, Items.OAK_PLANKS) == 6, "planks in the near chest " + count(near, Items.OAK_PLANKS));
			helper.assertTrue(count(far, Items.OAK_PLANKS) == 0, "planks in the far chest " + count(far, Items.OAK_PLANKS));
		});
	}

	/** A remembered chest in another dimension (#525) is left out; one in the bot's dimension or with none stays. */
	@GameTest(maxTicks = 40)
	public void leavesOutARememberedChestInAnotherDimension(GameTestHelper helper) {
		chest(helper, NEAR, new ItemStack(Items.OAK_PLANKS, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		BlockPos far = helper.absolutePos(FAR);
		run(helper, bot, IMPORT + """
			a = %s
			b = Pos(%d, %d, %d, dimension())
			memory["chests"] = [Pos(%d, %d, %d, "minecraft:the_nether"), b]
			print(chests(radius=8) == [a, b])
			""".formatted(pos(helper, NEAR), far.getX(), far.getY(), far.getZ(), far.getX() + 1, far.getY(), far.getZ()));
		failOnError(helper, bot);
		helper.succeedWhen(() -> printed(helper, bot, "True"));
	}

	private static Container chest(GameTestHelper helper, BlockPos at, ItemStack... stacks) {
		helper.setBlock(at, Blocks.CHEST);
		Container chest = helper.getBlockEntity(at, ChestBlockEntity.class);
		for (int i = 0; i < stacks.length; i++) {
			chest.setItem(i, stacks[i]);
		}
		return chest;
	}

	private static String pos(GameTestHelper helper, BlockPos relative) {
		BlockPos p = helper.absolutePos(relative);
		return "Pos(%d, %d, %d)".formatted(p.getX(), p.getY(), p.getZ());
	}

	private static void run(GameTestHelper helper, AntBot bot, String source) {
		String name = BotScripts.libraryScript(helper, "stock_", source);
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
	}

	private static void failOnError(GameTestHelper helper, AntBot bot) {
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
	}

	private static void printed(GameTestHelper helper, AntBot bot, String... lines) {
		List<String> log = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(log.equals(List.of(lines)), "printed " + log + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
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
