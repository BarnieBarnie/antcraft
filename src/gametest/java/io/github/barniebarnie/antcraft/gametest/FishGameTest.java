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
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@code fish} (#962; docs/reference/blocks/fish.md) and {@code jobs/fish.py} (docs/cookbook/fish.md). Each test's
 * ground is stone up to y 1, the bot walking at y 2; the pool is water at y 1 over x 3 to 5, z 3 to 5. The rod has
 * Lure III, so a bite comes within some 400 ticks, and the sky is open, as fish bite half as often under a roof.
 */
public class FishGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** The ground, with the pool where {@code pool}, and a bot at (1, 2, 4). */
	private static AntBot ground(GameTestHelper helper, boolean pool) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean water = pool && x >= 3 && x <= 5 && z >= 3 && z <= 5;
				helper.setBlock(x, 1, z, water ? Blocks.WATER : Blocks.STONE);
			}
		}
		return helper.spawn(ModEntities.ANT_BOT, 1, 2, 4);
	}

	private static ItemStack rod(GameTestHelper helper) {
		ItemStack rod = new ItemStack(Items.FISHING_ROD);
		rod.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LURE), 3);
		return rod;
	}

	/** How many items the bot carries but the one in its hand, its rod. */
	private static int caught(AntBot bot) {
		int n = 0;
		for (ItemStack stack : bot.getInventory().getItems()) {
			n += stack.getCount();
		}
		return n;
	}

	/** Runs {@code source} and succeeds once it printed {@code expected}, no action ever started: it fails at once. */
	private static void refuses(GameTestHelper helper, AntBot bot, String expected) {
		ScriptRun run = BotScripts.run(bot, "r = fish()\nprint(r.ok, r.reason)\n");
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			if (run.action() != null) {
				helper.fail(run.action() + " started instead of failing at once");
			}
		});
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of(expected), "printed"));
	}

	/** A bot with a rod beside the pool casts, reels in on a bite and has the catch, its rod worn by one. */
	// Measured: passes at tick 85 to 307 (red: name 'fish' is not defined); a bite takes up to some 380 ticks with Lure III.
	@GameTest(maxTicks = 900, skyAccess = true)
	public void aBotWithARodBesideWaterCatchesSomething(GameTestHelper helper) {
		AntBot bot = ground(helper, true);
		bot.getInventory().addItem(rod(helper));
		ScriptRun run = BotScripts.run(bot, "r = fish()\nprint(r.ok, r.value)\n");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True 1"), "printed");
			helper.assertTrue(caught(bot) >= 1, "carries nothing caught");
			helper.assertValueEqual(bot.getMainHandItem().getDamageValue(), 1, "the rod's damage");
		});
	}

	/** A bot with no room for its catch counts none of it and stops with no_space; the catch lies by the pool (#969). */
	// Measured: passes at tick 166 to 240 (red: printed True None 1); a bite takes up to some 380 ticks with Lure III.
	@GameTest(maxTicks = 900, skyAccess = true)
	public void aFullBotCountsNoCatchItHasNoRoomFor(GameTestHelper helper) {
		AntBot bot = ground(helper, true);
		bot.setItemSlot(EquipmentSlot.MAINHAND, rod(helper));
		for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
			bot.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
		}
		int full = caught(bot);
		ScriptRun run = BotScripts.run(bot, "r = fish()\nprint(r.ok, r.reason, r.value)\n");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("False no_space 0"), "printed");
			helper.assertValueEqual(caught(bot), full, "items carried");
		});
	}

	/** equip of a carried rod's Stack holds that rod, and fish fishes with it, not the plain rod held before (#993). */
	// Measured: passes at tick 265 (red: a selector is a str or a list of str, not 'Stack'); a bite takes up to some 380 ticks.
	@GameTest(maxTicks = 900, skyAccess = true)
	public void fishFishesWithTheRodEquipped(GameTestHelper helper) {
		AntBot bot = ground(helper, true);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.FISHING_ROD));
		bot.getInventory().addItem(rod(helper));
		ScriptRun run = BotScripts.run(bot, "s = [x for x in inventory() if x.enchantments][0]\nprint(equip(s).ok)\nprint(fish().ok)\n");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of("True", "True"), "printed");
			ItemStack hand = bot.getMainHandItem();
			helper.assertTrue(hand.isEnchanted(), "the bot holds the plain rod");
			helper.assertValueEqual(hand.getDamageValue(), 1, "the enchanted rod's damage");
			ItemStack plain = bot.getInventory().getItems().stream().filter(s -> s.is(Items.FISHING_ROD)).findFirst().orElseThrow();
			helper.assertValueEqual(plain.getDamageValue(), 0, "the plain rod's damage");
		});
	}

	@GameTest(maxTicks = 20)
	public void noRodIsNoRodAtOnce(GameTestHelper helper) {
		refuses(helper, ground(helper, true), "False no_rod");
	}

	@GameTest(maxTicks = 20)
	public void noWaterIsNoWaterAtOnce(GameTestHelper helper) {
		AntBot bot = ground(helper, false);
		bot.getInventory().addItem(rod(helper));
		refuses(helper, bot, "False no_water");
	}

	/** jobs.fish fishes the pool and puts the catch in a depot chest it places by the spot, keeping its rod (#995). */
	// Measured: passes at tick 85 to 340 (red: No module named 'jobs.fish'; #995 red: no chest has room for the catch).
	@GameTest(maxTicks = 1000, skyAccess = true)
	public void theJobStoresItsCatchInAChestBesideTheSpot(GameTestHelper helper) {
		AntBot bot = ground(helper, true);
		bot.getInventory().addItem(rod(helper));
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 3));
		BotScripts.run(bot, "import jobs.fish\njobs.fish.run()\n");
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(chests(helper, 8).stream().anyMatch(c -> !c.isEmpty()), "no chest holds the catch");
			helper.assertTrue(bot.getMainHandItem().is(Items.FISHING_ROD), "the bot does not hold its rod");
		});
	}

	/**
	 * jobs.fish started some 27 blocks from the pool, beyond fish()'s 16, walks to it and stores its catch in the chest
	 * in a depot chest by it (#994, #995). The wide area's ground is stone up to y 1; the pool is water at y 1 over x 20 to 22, z 20 to 22.
	 */
	// Measured: passes at tick 487 to 640 (red: warned fish: no_water); a bite takes up to some 380 ticks with Lure III.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1400, skyAccess = true)
	public void theJobWalksToWaterBeyondSixteenBlocks(GameTestHelper helper) {
		for (int x = 0; x < 24; x++) {
			for (int z = 0; z < 24; z++) {
				boolean water = x >= 20 && x <= 22 && z >= 20 && z <= 22;
				helper.setBlock(x, 1, z, water ? Blocks.WATER : Blocks.STONE);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 2, 1);
		bot.getInventory().addItem(rod(helper));
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 3));
		BotScripts.run(bot, "import jobs.fish\njobs.fish.run()\n");
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(chests(helper, 24).stream().anyMatch(c -> !c.isEmpty()), "no chest holds the catch");
		});
	}

	/**
	 * jobs.fish with fish stubbed: it prints the depot's chests and waits, and the test hands the bot a cod on the first
	 * call and an enchanted book on the second, as if caught. The cod goes to a new chest, the book to another new chest,
	 * not the fish chest (#995). The bot carries two chests for the depot.
	 */
	// Measured: passes at tick 17 to 18 (red: warned fish: no chest has room for ['minecraft:cod'], as no chest stood by).
	@GameTest(maxTicks = 40)
	public void theJobSortsItsCatchIntoAChestPerKind(GameTestHelper helper) {
		AntBot bot = ground(helper, true);
		bot.getInventory().addItem(rod(helper));
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 2));
		ScriptRun run = BotScripts.run(bot, STUB + "jobs.fish.run()\n");
		BotScripts.failOnWarning(helper, bot);
		int[] given = {0};
		helper.onEachTick(() -> {
			int calls = BotScripts.log(bot, Output.Level.PRINT).size();
			if (given[0] < calls && given[0] < 2) {
				ItemStack catchStack = given[0] == 0 ? new ItemStack(Items.COD) : new ItemStack(Items.ENCHANTED_BOOK);
				bot.getInventory().addItem(catchStack);
				given[0]++;
			}
		});
		helper.succeedWhen(() -> {
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(printed.size() >= 3, "printed " + printed);
			helper.assertValueEqual(printed.subList(0, 3), List.of("0", "1", "2"), "depot chests before each catch");
			List<ChestBlockEntity> chests = chests(helper, 8);
			helper.assertValueEqual(chests.size(), 2, "chests");
			ChestBlockEntity fish = chests.stream().filter(c -> c.countItem(Items.COD) == 1).findFirst().orElse(null);
			ChestBlockEntity books = chests.stream().filter(c -> c.countItem(Items.ENCHANTED_BOOK) == 1).findFirst().orElse(null);
			helper.assertTrue(fish != null && books != null && fish != books, "the cod and the book are not each in a chest of their own");
			helper.assertTrue(bot.getInventory().countItem(Items.FISHING_ROD) == 1, "the bot stored its rod");
			helper.assertTrue(run.state() != ScriptRun.State.FINISHED, "the job stopped");
		});
	}

	/** jobs.fish with fish stubbed and a cod caught but no chest, planks or logs: it warns and stops, keeping the cod (#995). */
	// Measured: passes at tick 16 (red: warned without the reason, fish: no chest has room for ['minecraft:cod']).
	@GameTest(maxTicks = 40)
	public void theJobWithNoChestToGetWarnsAndStops(GameTestHelper helper) {
		AntBot bot = ground(helper, true);
		bot.getInventory().addItem(rod(helper));
		ScriptRun run = BotScripts.run(bot, STUB + "jobs.fish.run()\n");
		BotScripts.failOnError(helper, bot);
		boolean[] given = {false};
		helper.onEachTick(() -> {
			if (!given[0] && !BotScripts.log(bot, Output.Level.PRINT).isEmpty()) {
				bot.getInventory().addItem(new ItemStack(Items.COD));
				given[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertValueEqual(BotScripts.warnings(bot), List.of("fish: no chest has room for ['minecraft:cod'] and none to get: no_logs"), "warned");
			helper.assertValueEqual(bot.getInventory().countItem(Items.COD), 1, "cod carried");
		});
	}

	/** jobs.fish with its fish stubbed: the stub prints how many chests the depot has, then waits 5 ticks for the test to hand over a catch. */
	private static final String STUB = """
		import jobs.fish
		import lib.depot
		def stub(n=1, at=None):
		    print(len(lib.depot.chests("fish")) if "fish" in memory.get("depots", {}) else 0)
		    return wait(5)
		jobs.fish.fish = stub
		""";

	/** The chests standing in the area, size blocks square, from y 2 up to y 3. */
	private static List<ChestBlockEntity> chests(GameTestHelper helper, int size) {
		List<ChestBlockEntity> chests = new java.util.ArrayList<>();
		for (int x = 0; x < size; x++) {
			for (int z = 0; z < size; z++) {
				for (int y = 2; y <= 3; y++) {
					if (helper.getBlockState(new BlockPos(x, y, z)).is(Blocks.CHEST)) {
						chests.add(helper.getBlockEntity(new BlockPos(x, y, z), ChestBlockEntity.class));
					}
				}
			}
		}
		return chests;
	}

	/** jobs.fish with no water within its radius warns and stops (#994). */
	// Measured: passes at tick 7 (red: warned fish: no_water).
	@GameTest(maxTicks = 20)
	public void theJobWithNoWaterWarnsAndStops(GameTestHelper helper) {
		AntBot bot = ground(helper, false);
		bot.getInventory().addItem(rod(helper));
		ScriptRun run = BotScripts.run(bot, "import jobs.fish\njobs.fish.run()\n");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertValueEqual(BotScripts.warnings(bot), List.of("fish: no water"), "warned");
		});
	}
}
