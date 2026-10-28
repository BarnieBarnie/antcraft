package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.pathing.PathBudget;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * The sense {@code reachable(pos, near=0)} (docs/scripting.md, Senses): it plans a walk without taking it. Each test has a
 * pocket two high at (5, 1, 5) walled in by bedrock: its floor, the eight columns around it and a lid.
 */
public class ReachableGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		for (int x = 4; x <= 6; x++) {
			for (int z = 4; z <= 6; z++) {
				boolean centre = x == 5 && z == 5;
				helper.setBlock(x, 0, z, Blocks.BEDROCK);
				helper.setBlock(x, 1, z, centre ? Blocks.AIR : Blocks.BEDROCK);
				helper.setBlock(x, 2, z, centre ? Blocks.AIR : Blocks.BEDROCK);
			}
		}
		helper.setBlock(5, 3, 5, Blocks.BEDROCK);
		method.invoke(this, helper);
	}

	/** The position {@code (x, y, z)} of the test area as a script tuple. */
	private static String at(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	/** Runs {@code source} on a bot at (1, 1, 1), failing the test as soon as the script fails. */
	private static ScriptRun run(GameTestHelper helper, AntBot bot, String source) {
		ScriptRun run = BotScripts.run(bot, source);
		helper.onEachTick(() -> {
			if (run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		return run;
	}

	private static void assertPrinted(GameTestHelper helper, AntBot bot, ScriptRun run, List<String> expected) {
		helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
		List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(printed.equals(expected), "printed " + printed + ", expected " + expected);
	}

	/** Spots across the area are reachable, the walled-in pocket is not, and the bot stays where it is. */
	@GameTest(maxTicks = 20)
	public void reachableIsTrueForAWalkableSpotAndFalseForOneWalledOffByBedrock(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		ScriptRun run = run(helper, bot, "print(reachable(" + at(helper, 6, 1, 1) + "), reachable(" + at(helper, 1, 1, 6) + "))\n"
			+ "print(reachable(" + at(helper, 5, 1, 5) + "))\n");
		helper.succeedWhen(() -> {
			assertPrinted(helper, bot, run, List.of("True True", "False"));
			helper.assertValueEqual(bot.blockPosition(), helper.absolutePos(new BlockPos(1, 1, 1)), "bot");
		});
	}

	/**
	 * Within 1 of the pocket lie only bedrock and the pocket itself; within 2 lies the floor beside its wall, which the
	 * bot can walk to.
	 */
	@GameTest(maxTicks = 20)
	public void reachableWithNearIsTrueWhenASpotNearAWalledInTargetIsReachable(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		String pocket = at(helper, 5, 1, 5);
		ScriptRun run = run(helper, bot, "print(reachable(" + pocket + ", near=1), reachable(" + pocket + ", near=2))\n");
		helper.succeedWhen(() -> assertPrinted(helper, bot, run, List.of("False True")));
	}

	@GameTest(maxTicks = 10)
	public void anUnloadedPositionIsNotReachable(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		ScriptRun run = run(helper, bot, "print(reachable((29000000, 64, 29000000)))\n");
		helper.succeedWhen(() -> assertPrinted(helper, bot, run, List.of("False")));
	}

	/**
	 * A plan the server's path budget cannot finish this tick goes on over the next ticks, pausing the script, and costs
	 * the script an instruction per node. The test area is walled in, so no plan in it runs long: the test spends the
	 * budget of the bot's first tick itself. With blocks to climb and bridge with, the plan to the pocket searches some
	 * 400 nodes before it gives up.
	 */
	@GameTest(maxTicks = 20)
	public void aPlanTheBudgetCannotFinishPausesTheScript(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 64));
		ScriptRun run = run(helper, bot, "print(reachable(" + at(helper, 5, 1, 5) + "))\n");
		long firstTick = helper.getLevel().getServer().getTickCount() + 1;
		PathBudget.SERVER.spend(firstTick, PathBudget.NODES_PER_TICK);
		int[] planning = {0};
		helper.onEachTick(() -> {
			String action = run.action();
			if (action != null && action.startsWith("reachable")) {
				planning[0]++;
			}
		});
		helper.succeedWhen(() -> {
			assertPrinted(helper, bot, run, List.of("False"));
			helper.assertTrue(planning[0] >= 1, "planned during " + planning[0] + " ticks after the first");
			helper.assertTrue(run.executed() >= 200, run.executed() + " instructions");
		});
	}
}
