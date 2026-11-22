package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
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
 * A neighbouring test's chests are out of every scan, which reads no further than the test's area (GameTests.hides).
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
			print(chests() == [a, b], where("oak_planks") == [a, b], where("dirt"))
			r = fetch("oak_planks", 10)
			print(r["ok"], count("oak_planks"), [(x["chest"] == a, x["n"]) for x in borrowed()])
			""".formatted(pos(helper, NEAR), pos(helper, FAR)));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True True []", "True 10 [(True, 6), (False, 4)]");
			helper.assertTrue(count(near, Items.OAK_PLANKS) == 0, "planks in the near chest " + count(near, Items.OAK_PLANKS));
			helper.assertTrue(count(far, Items.OAK_PLANKS) == 2, "planks in the far chest " + count(far, Items.OAK_PLANKS));
		});
	}

	/** 8 planks in the chests and 1 carried, 10 asked: missing names the 1 short and nothing is taken. */
	@GameTest(maxTicks = 20)
	public void tooFewTakesNothing(GameTestHelper helper) {
		Container near = chest(helper, NEAR, new ItemStack(Items.OAK_PLANKS, 4));
		Container far = chest(helper, FAR, new ItemStack(Items.OAK_PLANKS, 4));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 1));
		run(helper, bot, IMPORT + """
			r = fetch("oak_planks", 10)
			print(r["ok"], r["reason"], r["missing"], count("oak_planks"), borrowed())
			""");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "False no_item {'oak_planks': 1} 1 []");
			helper.assertTrue(count(near, Items.OAK_PLANKS) == 4 && count(far, Items.OAK_PLANKS) == 4, "planks taken");
		});
	}

	/** The bot already carries 10: fetch takes nothing. */
	@GameTest(maxTicks = 20)
	public void alreadyCarryingDoesNothing(GameTestHelper helper) {
		Container near = chest(helper, NEAR, new ItemStack(Items.OAK_PLANKS, 6));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS, 10));
		run(helper, bot, IMPORT + """
			r = fetch("oak_planks", 10)
			print(r["ok"], count("oak_planks"), borrowed())
			""");
		BotScripts.failOnError(helper, bot);
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
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 6", "False {'oak_planks': 2} 7");
			helper.assertTrue(count(near, Items.OAK_PLANKS) == 6, "planks in the near chest " + count(near, Items.OAK_PLANKS));
			helper.assertTrue(count(far, Items.OAK_PLANKS) == 0, "planks in the far chest " + count(far, Items.OAK_PLANKS));
		});
	}

	/** A remembered chest in another dimension (#525) is left out; one in the bot's dimension or with none stays. */
	@GameTest(maxTicks = 20)
	public void leavesOutARememberedChestInAnotherDimension(GameTestHelper helper) {
		chest(helper, NEAR, new ItemStack(Items.OAK_PLANKS, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		BlockPos far = helper.absolutePos(FAR);
		run(helper, bot, IMPORT + """
			a = %s
			b = Pos(%d, %d, %d, dimension())
			memory["chests"] = [Pos(%d, %d, %d, "minecraft:the_nether"), b]
			print(chests() == [a, b])
			""".formatted(pos(helper, NEAR), far.getX(), far.getY(), far.getZ(), far.getX() + 1, far.getY(), far.getZ()));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> printed(helper, bot, "True"));
	}

	/**
	 * A chest just outside the test's area (#598), within the radius, is not one of the bot's chests, so a test never
	 * takes from a neighbouring test's chest; fetch takes from the one inside only.
	 */
	@GameTest(maxTicks = 20)
	public void leavesOutAChestOutsideTheTestArea(GameTestHelper helper) {
		Container near = chest(helper, NEAR, new ItemStack(Items.OAK_PLANKS, 2));
		Container out = chest(helper, new BlockPos(8, 1, 5), new ItemStack(Items.OAK_PLANKS, 6));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		run(helper, bot, IMPORT + """
			print(chests() == [%s])
			r = fetch("oak_planks", 4)
			print(r["ok"], r["missing"], count("oak_planks"))
			""".formatted(pos(helper, NEAR)));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True", "False {'oak_planks': 2} 0");
			helper.assertTrue(count(near, Items.OAK_PLANKS) == 2 && count(out, Items.OAK_PLANKS) == 6, "planks taken");
		});
	}

	/**
	 * The colony store first (#1019): a wooden pickaxe in the store chest at FAR, published as colony_memory["storage"],
	 * and another in the nearer chest at NEAR. ensure_tool borrows the store's and give_back puts it back there.
	 */
	@GameTest(maxTicks = 60)
	public void ensureToolBorrowsFromTheColonyStoreFirst(GameTestHelper helper) {
		Container near = chest(helper, NEAR, new ItemStack(Items.WOODEN_PICKAXE));
		Container store = chest(helper, FAR, new ItemStack(Items.WOODEN_PICKAXE));
		AntBot bot = colonyBot(helper);
		run(helper, bot, IMPORT + STORE.formatted(pos(helper, FAR)) + """
			from lib.get import ensure_tool
			from lib.borrow import give_back
			print(chests()[0] == far)
			r = ensure_tool("pickaxe")
			print(r["ok"], [b["chest"] == far for b in borrowed()])
			give_back()
			print(count("#minecraft:pickaxes"), borrowed())
			""");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True", "True [True]", "0 []");
			helper.assertTrue(count(near, Items.WOODEN_PICKAXE) == 1, "pickaxes in the near chest " + count(near, Items.WOODEN_PICKAXE));
			helper.assertTrue(count(store, Items.WOODEN_PICKAXE) == 1, "pickaxes in the store " + count(store, Items.WOODEN_PICKAXE));
		});
	}

	/**
	 * A stone pickaxe in the store (lib.stock.STORAGE) at (1, 1, 1), far from the bot at (20, 1, 20), which carries 3 oak
	 * logs (#1023): making a wooden pickaxe costs less than the walk, but ensure_tool borrows the store's all the same.
	 */
	// Passes at tick 179 alone, 178 in its class run.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 360)
	public void ensureToolBorrowsAFarStoreToolOverCrafting(GameTestHelper helper) {
		Container store = chest(helper, FAR, new ItemStack(Items.STONE_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 20, 1, 20);
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 3));
		run(helper, bot, IMPORT + """
			import lib.stock
			from lib.get import ensure_tool
			lib.stock.STORAGE = %s
			r = ensure_tool("pickaxe")
			print(r["ok"], held().item, count("oak_log"))
			""".formatted(pos(helper, FAR)));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True minecraft:stone_pickaxe 3");
			helper.assertTrue(store.isEmpty(), "the store is not empty");
		});
	}

	/** get("oak_planks", 16) with planks in the store and the nearer chest (#1019): takes the store's, for good. */
	@GameTest(maxTicks = 60)
	public void getTakesMaterialsFromTheColonyStoreForGood(GameTestHelper helper) {
		Container near = chest(helper, NEAR, new ItemStack(Items.OAK_PLANKS, 16));
		Container store = chest(helper, FAR, new ItemStack(Items.OAK_PLANKS, 16));
		AntBot bot = colonyBot(helper);
		run(helper, bot, IMPORT + STORE.formatted(pos(helper, FAR)) + """
			from lib.get import get
			from lib.borrow import give_back
			r = get("oak_planks", 16)
			give_back()
			print(r["ok"], count("oak_planks"), borrowed())
			""");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 16 []");
			helper.assertTrue(count(near, Items.OAK_PLANKS) == 16, "planks in the near chest " + count(near, Items.OAK_PLANKS));
			helper.assertTrue(count(store, Items.OAK_PLANKS) == 0, "planks in the store " + count(store, Items.OAK_PLANKS));
		});
	}

	/**
	 * A bot in no colony with lib.stock.STORAGE set (#1019) finds the store by a scan around it: the chest at (1, 1, 1),
	 * which lib.find.WHERE leaves out and which lies past get's radius of 8 from the bot at (12, 1, 12). It takes the
	 * store's planks, for good, before the 4 in the chest beside it.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 200)
	public void aStorageOutsideWhereAndRadiusIsFoundByAScan(GameTestHelper helper) {
		Container near = chest(helper, new BlockPos(13, 1, 12), new ItemStack(Items.OAK_PLANKS, 4));
		Container store = chest(helper, FAR, new ItemStack(Items.OAK_PLANKS, 16));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 12, 1, 12);
		run(helper, bot, IMPORT + """
			import lib.find
			import lib.stock
			from lib.get import get
			from lib.borrow import give_back
			far = %s
			lib.stock.STORAGE = far
			lib.find.WHERE = lambda p: p != far
			print(chests(8)[0] == far, len(chests(8)))
			r = get("oak_planks", 16, 8)
			give_back()
			print(r["ok"], count("oak_planks"), borrowed())
			""".formatted(pos(helper, FAR)));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			printed(helper, bot, "True 2", "True 16 []");
			helper.assertTrue(count(near, Items.OAK_PLANKS) == 4, "planks in the near chest " + count(near, Items.OAK_PLANKS));
			helper.assertTrue(count(store, Items.OAK_PLANKS) == 0, "planks in the store " + count(store, Items.OAK_PLANKS));
		});
	}

	/** Publishes the chest at far (formatted in) as the colony store, as lib.depot.publish_store would (#1018). */
	private static final String STORE = """
		far = %s
		colony_memory["depots"] = {"me/store": {"chests": [["tools", far]], "kind": "store"}}
		colony_memory["storage"] = "me/store"
		""";

	/** A bot at (5, 1, 5) in a colony whose core is at (2, 1, 6). */
	private static AntBot colonyBot(GameTestHelper helper) {
		helper.setBlock(2, 1, 6, ModBlocks.COLONY_CORE);
		ColonyManager colonies = ColonyManager.get(helper.getLevel());
		Colony colony = colonies.byCore(helper.absolutePos(new BlockPos(2, 1, 6))).orElseThrow();
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		colonies.join(bot, colony);
		return bot;
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
