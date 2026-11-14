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
import net.minecraft.world.level.block.Blocks;

/**
 * {@code lib/explore.py} and {@code jobs/explore.py} (#568): the waypoints of the spiral, what look() records, and an
 * explore that stops where it sees what it looks for. The test that puts a village in the area runs in an environment of
 * its own ({@code structures}), so no test beside it lists the village among its structures.
 */
public class ExploreGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A square spiral from the centre out, east first and then clockwise, a ring of 8 * r waypoints at a time. */
	@GameTest(maxTicks = 10)
	public void spiralGivesTheWaypointsFromTheCentreOut(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		run(helper, bot, """
			import lib.explore
			w = lib.explore.spiral(Pos(0, 64, 0), 2, 1)
			print([(p.x, p.z) for p in w], all([p.y == 64 for p in w]))
			print(len(lib.explore.spiral(Pos(0, 64, 0))), lib.explore.spiral(Pos(0, 64, 0))[-1])
			""");
		succeedWhenPrinted(helper, bot, List.of("[(0, 0), (2, 0), (2, 2), (0, 2), (-2, 2), (-2, 0), (-2, -2), (0, -2), (2, -2)] True",
			"81 Pos(128, 64, -128)"));
	}

	/** look() records the biome at the bot once per place, however often it looks there. */
	@GameTest(maxTicks = 10)
	public void lookRecordsTheBiomeOncePerPlace(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		run(helper, bot, """
			import lib.explore
			for _ in range(2):
			    lib.explore.look(4, 0)
			    s = memory["seen"]
			    print(s["biomes"], s["structures"])
			""");
		String biome = helper.getLevel().getBiome(helper.absolutePos(new BlockPos(3, 1, 3))).getRegisteredName();
		String line = "{'" + biome + "': [" + printed(helper, 3, 1, 3) + "]} {}";
		succeedWhenPrinted(helper, bot, List.of(line, line));
	}

	/** seen("#minecraft:is_forest") gives the remembered biomes of that biome tag (biome_tags), not the others. */
	@GameTest(maxTicks = 10)
	public void seenTakesABiomeTag(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		run(helper, bot, """
			import lib.explore
			memory["seen"] = {"biomes": {"minecraft:forest": [Pos(1, 64, 1)], "minecraft:plains": [Pos(2, 64, 2)],
			    "minecraft:birch_forest": [Pos(3, 64, 3)]}, "structures": {}}
			print(sorted([p.x for p in lib.explore.seen("#minecraft:is_forest")]), lib.explore.seen("#minecraft:is_ocean"))
			print("#minecraft:is_forest" in biome_tags("minecraft:forest"), biome_tags("minecraft:nothing"))
			""");
		succeedWhenPrinted(helper, bot, List.of("[1, 3] []", "True []"));
	}

	/**
	 * A waypoint whose column is not loaded yet (surface() gives None the first time it is asked about each column) goes
	 * to the surface once its chunk loads: the third waypoint, (5, 1, 5), is a dirt block, so the bot stands on it at y 2
	 * rather than digging it out to stand at the centre's y.
	 */
	// Measured: passes at tick 39.
	@GameTest(maxTicks = 80)
	public void aWaypointNotLoadedYetGoesToTheSurfaceOnceItLoads(GameTestHelper helper) {
		helper.setBlock(5, 1, 5, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		run(helper, bot, """
			import lib.explore
			asked = {}
			real = surface
			def first_none(p):
			    k = (p.x, p.z)
			    n = asked.get(k, 0)
			    asked[k] = n + 1
			    return None if n == 0 else real(p)
			lib.explore.surface = first_none
			at = []
			def until(s):
			    at.append(pos())
			    return len(at) == 3
			lib.explore.explore(1, 2, until)
			print(at[2], block(at[2] + (0, -1, 0)).id)
			""");
		// Not succeedWhenPrinted: the script ends away from home, so the bot's idle.py takes over at once.
		List<String> expected = List.of(printed(helper, 5, 2, 5) + " minecraft:dirt");
		helper.succeedWhen(() -> {
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(printed.equals(expected), "printed " + printed + ", expected " + expected);
		});
	}

	/**
	 * A village from (6, 1, 6) to (7, 3, 7); the bot at (3, 1, 3) explores a spiral of step 2: its structures(2) sees the
	 * village from the third waypoint, (5, 1, 5), where it stops. Looking again there keeps the one position.
	 */
	// Measured: passes at tick 26.
	@GameTest(environment = "antcraft-gametest:structures", maxTicks = 60)
	public void exploreStopsAtTheFirstWaypointThatSeesAMatch(GameTestHelper helper) {
		SenseGameTest.village(helper, new BlockPos(6, 1, 6), new BlockPos(7, 3, 7));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		run(helper, bot, """
			import lib.explore
			r = lib.explore.explore(1, 2, lambda s: "minecraft:village_plains" in s["structures"])
			print(r["ok"], r["at"])
			lib.explore.look(2, 1)
			print(lib.explore.seen("village"), lib.explore.seen("minecraft:village_plains"))
			""");
		succeedWhenPrinted(helper, bot, List.of("True " + printed(helper, 5, 1, 5),
			"[" + printed(helper, 7, 2, 7) + "] [" + printed(helper, 7, 2, 7) + "]"));
	}

	/**
	 * jobs.explore nothing 1 2 walks the whole spiral of 2-block steps, as nothing matches "nothing", goes home, reports
	 * the biome it saw and warns not_found. (A village here too would share a chunk with the one of the test above,
	 * which runs beside it, and a chunk keeps one start per structure.)
	 */
	// Measured: passes at tick 116.
	@GameTest(maxTicks = 240)
	public void theJobWalksTheSpiralGoesHomeAndReports(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.explore nothing 1 2");
		failOnError(helper, bot);
		String biome = helper.getLevel().getBiome(helper.absolutePos(new BlockPos(3, 1, 3))).getRegisteredName();
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScript() != null && bot.getScript().state() == ScriptRun.State.FINISHED, "not finished");
			helper.assertTrue(bot.blockPosition().equals(helper.absolutePos(new BlockPos(3, 1, 3))), "not home: " + bot.blockPosition());
			List<String> printed = BotScripts.log(bot, Output.Level.WARN);
			helper.assertTrue(printed.get(0).equals(biome + " at " + printed(helper, 3, 1, 3)), "printed " + printed);
			helper.assertTrue(printed.get(printed.size() - 1).equals("explore: nothing not_found"), "printed " + printed);
		});
	}

	private static void run(GameTestHelper helper, AntBot bot, String source) {
		BotScripts.run(bot, source);
		failOnError(helper, bot);
	}

	/** Fails the test as soon as the bot's script fails. */
	private static void failOnError(GameTestHelper helper, AntBot bot) {
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
	}

	private static void succeedWhenPrinted(GameTestHelper helper, AntBot bot, List<String> expected) {
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScript() != null && bot.getScript().state() == ScriptRun.State.FINISHED, "not finished");
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(printed.equals(expected), "printed " + printed + ", expected " + expected);
		});
	}

	/** The position {@code (x, y, z)} of the test area as a script prints it. */
	private static String printed(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "Pos(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}
}
