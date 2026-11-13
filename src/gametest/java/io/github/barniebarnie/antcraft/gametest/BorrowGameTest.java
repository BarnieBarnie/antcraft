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
 * {@code lib/borrow.py} (#505): borrow from the chest at (2, 1, 2) and give back, to it or, when it is gone, the chest at
 * (2, 1, 6); the bot starts at (5, 1, 5).
 */
public class BorrowGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CHEST = new BlockPos(2, 1, 2);
	private static final BlockPos OTHER = new BlockPos(2, 1, 6);
	private static final String IMPORT = "from lib.borrow import borrow, borrowed, give_back\n";

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A pickaxe and 4 planks borrowed, a block mined and 2 planks placed: the worn pickaxe and 2 planks go back. */
	@GameTest(maxTicks = 60)
	public void givesBackWhatIsLeftWorn(GameTestHelper helper) {
		Container chest = chest(helper, CHEST, new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.OAK_PLANKS, 10));
		helper.setBlock(6, 1, 3, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		run(helper, bot, IMPORT + """
			a = %s
			r1 = borrow(a, "iron_pickaxe")
			r2 = borrow(a, "oak_planks", 4)
			mine(%s)
			place(%s, "oak_planks")
			place(%s, "oak_planks")
			print(r1["ok"], r2["ok"], len(borrowed()), count("oak_planks"))
			r = give_back()
			print(r["ok"], borrowed(), count("iron_pickaxe"), count("oak_planks"))
			""".formatted(pos(helper, CHEST), pos(helper, new BlockPos(6, 1, 3)), pos(helper, new BlockPos(6, 1, 7)),
			pos(helper, new BlockPos(7, 1, 7))));
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True True 2 2", "True [] 0 0");
			helper.assertTrue(count(chest, Items.OAK_PLANKS) == 8, "planks in the chest " + count(chest, Items.OAK_PLANKS));
			ItemStack pickaxe = find(chest, Items.IRON_PICKAXE);
			helper.assertTrue(!pickaxe.isEmpty() && pickaxe.getDamageValue() > 0, "pickaxe back: " + pickaxe);
		});
	}

	/** The chest is broken (with the bot's own axe) before give_back: what was borrowed goes into the other chest. */
	@GameTest(maxTicks = 60)
	public void givesBackToAnotherChestWhenTheChestIsGone(GameTestHelper helper) {
		chest(helper, CHEST, new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.OAK_PLANKS, 4));
		Container other = chest(helper, OTHER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.IRON_AXE));
		run(helper, bot, IMPORT + """
			a = %s
			borrow(a, "iron_pickaxe")
			borrow(a, "oak_planks", 4)
			mine(a)
			r = give_back()
			print(r["ok"], borrowed())
			""".formatted(pos(helper, CHEST)));
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True []");
			helper.assertTrue(count(other, Items.OAK_PLANKS) == 4, "planks in the other chest " + count(other, Items.OAK_PLANKS));
			helper.assertTrue(count(other, Items.IRON_PICKAXE) == 1, "no pickaxe in the other chest");
		});
	}

	/** No chest left within 3 blocks (a neighbouring test's chest may be within 16): the bot keeps the items, names them in missing and still lists them as borrowed. */
	@GameTest(maxTicks = 50)
	public void keepsWhatNoChestTakes(GameTestHelper helper) {
		chest(helper, CHEST, new ItemStack(Items.OAK_PLANKS, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.IRON_AXE));
		run(helper, bot, IMPORT + """
			a = %s
			borrow(a, "oak_planks", 4)
			mine(a)
			r = give_back(radius=3)
			print(r["ok"], r["reason"], r["missing"], len(borrowed()), count("oak_planks"))
			""".formatted(pos(helper, CHEST)));
		failOnError(helper, bot);
		helper.succeedWhen(() -> printed(helper, bot, "False no_space {'oak_planks': 4} 1 4"));
	}

	/** The bot owns 3 planks, borrows 4 and uses 5 (a crafting table and one placed): the borrowed ones count as used first, so nothing goes back and it keeps its own 2. */
	@GameTest(maxTicks = 40)
	public void ownItemsAreNotGivenBack(GameTestHelper helper) {
		Container chest = chest(helper, CHEST, new ItemStack(Items.OAK_PLANKS, 10));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 3));
		run(helper, bot, IMPORT + """
			borrow(%s, "oak_planks", 4)
			craft("crafting_table")
			place(%s, "oak_planks")
			r = give_back()
			print(r["ok"], borrowed(), count("oak_planks"))
			""".formatted(pos(helper, CHEST), pos(helper, new BlockPos(6, 1, 7))));
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True [] 2");
			helper.assertTrue(count(chest, Items.OAK_PLANKS) == 6, "planks in the chest " + count(chest, Items.OAK_PLANKS));
		});
	}

	/** keep strikes what a job keeps off the record: all the planks, then 1 of 2 pickaxes; the other pickaxe goes back. */
	@GameTest(maxTicks = 40)
	public void keepLeavesAnItemOutOfGiveBack(GameTestHelper helper) {
		Container chest = chest(helper, CHEST, new ItemStack(Items.OAK_PLANKS, 4), new ItemStack(Items.IRON_PICKAXE),
			new ItemStack(Items.IRON_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		run(helper, bot, "from lib.borrow import borrow, borrowed, give_back, keep\n" + """
			a = %s
			borrow(a, "oak_planks", 4)
			borrow(a, "iron_pickaxe", 2)
			keep("minecraft:oak_planks")
			keep("iron_pickaxe", 1)
			print([(b["item"], b["n"]) for b in borrowed()])
			r = give_back()
			print(r["ok"], borrowed(), count("oak_planks"), count("iron_pickaxe"))
			""".formatted(pos(helper, CHEST)));
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "[('iron_pickaxe', 1)]", "True [] 4 1");
			helper.assertTrue(count(chest, Items.IRON_PICKAXE) == 1, "pickaxes in the chest " + count(chest, Items.IRON_PICKAXE));
		});
	}

	/** A record from before the bot's own count was kept (no "had") still gives back. */
	@GameTest(maxTicks = 40)
	public void anOlderRecordStillGivesBack(GameTestHelper helper) {
		Container chest = chest(helper, CHEST);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 4));
		run(helper, bot, IMPORT + """
			memory["borrowed"] = [{"chest": %s, "item": "oak_planks", "n": 4}]
			r = give_back()
			print(r["ok"], borrowed(), count("oak_planks"))
			""".formatted(pos(helper, CHEST)));
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True [] 0");
			helper.assertTrue(count(chest, Items.OAK_PLANKS) == 4, "planks in the chest " + count(chest, Items.OAK_PLANKS));
		});
	}

	/** One script borrows, the next gives back: the record is kept in memory between them. */
	@GameTest(maxTicks = 60)
	public void theRecordSurvivesARestart(GameTestHelper helper) {
		Container chest = chest(helper, CHEST, new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.OAK_PLANKS, 10));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		run(helper, bot, IMPORT + """
			borrow(%s, "iron_pickaxe")
			borrow(%s, "oak_planks", 4)
			""".formatted(pos(helper, CHEST), pos(helper, CHEST)));
		String second = BotScripts.libraryScript(helper, "borrow_", IMPORT + """
			print(len(borrowed()), borrowed()[1]["item"], borrowed()[1]["n"])
			r = give_back()
			print(r["ok"], borrowed(), count("iron_pickaxe"), count("oak_planks"))
			""");
		boolean[] restarted = {false};
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
			if (!restarted[0] && run != null && run.state() == ScriptRun.State.FINISHED) {
				restarted[0] = true;
				BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + second);
			}
		});
		helper.succeedWhen(() -> {
			printed(helper, bot, "2 oak_planks 4", "True [] 0 0");
			helper.assertTrue(count(chest, Items.OAK_PLANKS) == 10, "planks in the chest " + count(chest, Items.OAK_PLANKS));
			helper.assertTrue(count(chest, Items.IRON_PICKAXE) == 1, "no pickaxe in the chest");
		});
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
		String name = BotScripts.libraryScript(helper, "borrow_", source);
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

	private static ItemStack find(Container chest, Item item) {
		for (int i = 0; i < chest.getContainerSize(); i++) {
			if (chest.getItem(i).is(item)) {
				return chest.getItem(i);
			}
		}
		return ItemStack.EMPTY;
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
