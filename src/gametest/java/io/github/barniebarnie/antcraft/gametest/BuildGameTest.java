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
 * {@code lib/build.py} and the job {@code jobs/build.py} (#571): get what a blueprint's site lacks, build it, give back
 * what was borrowed.
 */
public class BuildGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * The one-cobblestone test blueprint {@code two_palettes}, an empty bot, stone and a chest with a wooden pickaxe: it
	 * borrows the pickaxe, mines a cobblestone, builds, and puts the pickaxe back.
	 */
	// Measured: passes at tick 68.
	@GameTest(maxTicks = 150)
	public void buildsWithWhatItGetsAndGivesBack(GameTestHelper helper) {
		for (int x = 5; x <= 6; x++) {
			helper.setBlock(x, 1, 6, Blocks.STONE);
		}
		BlockPos at = new BlockPos(1, 1, 6);
		helper.setBlock(at, Blocks.CHEST);
		Container chest = helper.getBlockEntity(at, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		run(helper, bot, "antcraft-gametest:two_palettes " + coords(helper, 4, 1, 3) + " 0 8", "built antcraft-gametest:two_palettes", () -> {
			helper.assertBlockPresent(Blocks.COBBLESTONE, 4, 1, 3);
			helper.assertTrue(chest.countItem(Items.WOODEN_PICKAXE) == 1, "the pickaxe is not back");
			helper.assertTrue(GameTests.carried(bot, Items.WOODEN_PICKAXE) == 0, "the bot kept the pickaxe");
		});
	}

	/**
	 * The cobble step with its two cobblestone in a chest and no wood for its oak stairs (#585): build_here borrows the
	 * cobblestone, fails to get the stairs, builds what it has (the two cobblestone of the bottom layer) and fails with
	 * the stairs in missing. The cobblestone is used up, so nothing is left to give back.
	 */
	// Measured: passes at tick 25.
	@GameTest(maxTicks = 60)
	public void anUnobtainableItemFailsWithItInMissing(GameTestHelper helper) {
		BlockPos at = new BlockPos(1, 1, 6);
		helper.setBlock(at, Blocks.CHEST);
		Container chest = helper.getBlockEntity(at, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		String name = BotScripts.libraryScript(helper, "build_", """
			from lib.build import build_here, materials
			from lib.borrow import borrowed
			print(materials("antcraft:cobble_step", %s))
			r = build_here("antcraft:cobble_step", %s)
			print(r["ok"], r["reason"], r["missing"], borrowed(), count("cobblestone"))
			""".formatted(at(helper, 4, 1, 3), at(helper, 4, 1, 3)));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of(
				"{'minecraft:cobblestone': 2, 'minecraft:oak_stairs': 1}",
				"False no_logs {'minecraft:oak_stairs': 1} [] 0"), "printed");
			helper.assertBlockPresent(Blocks.COBBLESTONE, 4, 1, 3);
			helper.assertBlockPresent(Blocks.COBBLESTONE, 5, 1, 3);
			helper.assertBlockNotPresent(Blocks.OAK_STAIRS, 4, 2, 3);
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 0, "cobblestone in the chest " + chest.countItem(Items.COBBLESTONE));
		});
	}

	/**
	 * A blueprint of cobblestone, oak stairs (no logs anywhere, so its get fails) and dirt on top, both blocks in a
	 * chest: build_here gets only what comes before the stairs, never the dirt it could not place this round (#599),
	 * builds the cobblestone and fails with the stairs and the dirt in missing.
	 */
	// Measured: passes at tick 19.
	@GameTest(maxTicks = 40)
	public void itemsAfterTheFirstItemItCannotGetAreNotGot(GameTestHelper helper) {
		BlockPos at = new BlockPos(1, 1, 6);
		helper.setBlock(at, Blocks.CHEST);
		Container chest = helper.getBlockEntity(at, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		chest.setItem(1, new ItemStack(Items.DIRT, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		String name = BotScripts.libraryScript(helper, "build_", """
			import lib.get
			import lib.build
			real = lib.get.get
			got = []
			def recorded(item, n, radius):
			    got.append(item)
			    return real(item, n, radius)
			lib.get.get = recorded
			r = lib.build.build_here("antcraft-gametest:capped_step", %s)
			print(r["ok"], r["reason"], r["missing"], "minecraft:cobblestone" in got, "minecraft:dirt" in got)
			""".formatted(at(helper, 4, 1, 3)));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of(
				"False no_logs {'minecraft:oak_stairs': 1, 'minecraft:dirt': 1} True False"),
				"printed");
			helper.assertBlockPresent(Blocks.COBBLESTONE, 4, 1, 3);
			helper.assertTrue(chest.countItem(Items.DIRT) == 1, "dirt in the chest " + chest.countItem(Items.DIRT));
		});
	}

	/**
	 * The test blueprint cobble_between, cobblestone, oak stairs (no logs anywhere, so its get fails) and cobblestone on
	 * top, two cobblestone in a chest: build_here gets the one cobblestone the step before the stairs takes, not the one
	 * after them, builds it and fails with the stairs and a cobblestone in missing (#620).
	 */
	// Measured: passes at tick 22.
	@GameTest(maxTicks = 40)
	public void anItemUsedAfterTheFirstItemItCannotGetIsGotOnlyForTheStepsBefore(GameTestHelper helper) {
		BlockPos at = new BlockPos(1, 1, 6);
		helper.setBlock(at, Blocks.CHEST);
		Container chest = helper.getBlockEntity(at, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		String name = BotScripts.libraryScript(helper, "build_", """
			import lib.get
			import lib.build
			real = lib.get.get
			most = [0]
			def recorded(item, n, radius):
			    if item == "minecraft:cobblestone":
			        most[0] = max(most[0], n)
			    return real(item, n, radius)
			lib.get.get = recorded
			r = lib.build.build_here("antcraft-gametest:cobble_between", %s)
			print(r["ok"], r["reason"], r["missing"], most[0])
			""".formatted(at(helper, 4, 1, 3)));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of(
				"False no_logs {'minecraft:oak_stairs': 1, 'minecraft:cobblestone': 1} 1"), "printed");
			helper.assertBlockPresent(Blocks.COBBLESTONE, 4, 1, 3);
		});
	}

	/**
	 * fetch on the steps a, b, a, c with a get that always succeeds: once b is got it gets the second a straight away,
	 * in the same trip, not after c (#626). A failed get stops it with that get's reason.
	 */
	// Measured: passes at tick 2.
	@GameTest(maxTicks = 20)
	public void fetchGetsTheRestOfAnItemOnceTheItemItWaitedOnIsGot(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		String name = BotScripts.libraryScript(helper, "build_", """
			from lib.build import fetch
			calls = []
			def get(item, n, radius):
			    calls.append((item, n))
			    return {"ok": item != "x", "reason": None if item != "x" else "no_x"}
			print(fetch(["a", "b", "a", "c"], 8, get), calls)
			calls = []
			print(fetch(["a", "x", "a"], 8, get), calls)
			""");
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT), List.of(
			"None [('a', 1), ('b', 1), ('a', 2), ('c', 1)]",
			"no_x [('a', 1), ('x', 1)]"), "printed"));
	}

	/** A blueprint id the server does not know: build_here fails at once with no_blueprint, at at, nothing missing. */
	// Measured: passes at tick 2.
	@GameTest(maxTicks = 20)
	public void anUnknownBlueprintFailsWithNoBlueprint(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		String name = BotScripts.libraryScript(helper, "build_", """
			from lib.build import build_here
			r = build_here("antcraft-gametest:no_such_blueprint", %s)
			print(r["ok"], r["reason"], r["at"] == %s, r["missing"])
			""".formatted(at(helper, 4, 1, 3), at(helper, 4, 1, 3)));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT),
			List.of("False no_blueprint True {}"), "printed"));
	}

	/**
	 * A build that loses what the bot got (the test's build puts the cobblestone back in its chest before building, as
	 * a thief would take it): the round places nothing, so the next would lack as much, and build_here fails with
	 * no_progress, the cobblestone in missing.
	 */
	// Measured: passes at tick 15.
	@GameTest(maxTicks = 40)
	public void aRoundThatPlacesNothingFailsWithNoProgress(GameTestHelper helper) {
		BlockPos at = new BlockPos(1, 1, 6);
		helper.setBlock(at, Blocks.CHEST);
		Container chest = helper.getBlockEntity(at, ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		String name = BotScripts.libraryScript(helper, "build_", """
			import lib.build
			real = build
			builds = []
			def robbed(id, at, turn, mirror):
			    builds.append(id)
			    put(%s, "cobblestone", 64)
			    return real(id, at, turn, mirror)
			lib.build.build = robbed
			r = lib.build.build_here("antcraft-gametest:two_palettes", %s)
			print(r["ok"], r["reason"], r["missing"], len(builds))
			""".formatted(at(helper, 1, 1, 6), at(helper, 4, 1, 3)));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT),
				List.of("False no_progress {'minecraft:cobblestone': 1} 1"), "printed");
			helper.assertBlockNotPresent(Blocks.COBBLESTONE, 4, 1, 3);
			helper.assertTrue(chest.countItem(Items.COBBLESTONE) == 1, "cobblestone in the chest " + chest.countItem(Items.COBBLESTONE));
		});
	}

	private static String at(GameTestHelper helper, int x, int y, int z) {
		BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
		return "Pos(%d, %d, %d)".formatted(p.getX(), p.getY(), p.getZ());
	}

	private static String coords(GameTestHelper helper, int x, int y, int z) {
		BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	private static void failOnError(GameTestHelper helper, AntBot bot) {
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
	}

	/** Runs jobs.build with args by the command; succeeds once the bot said {@code says} and {@code then} holds. */
	private static void run(GameTestHelper helper, AntBot bot, String args, String says, Runnable then) {
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.build " + args);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[jobs.build, 1]")), "run answered " + started);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
			List<String> said = BotScripts.log(bot, Output.Level.PRINT);
			if (!said.isEmpty() && !said.equals(List.of(says))) {
				helper.fail("said " + said + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of(says)), "has not said " + says);
			then.run();
		});
	}
}
