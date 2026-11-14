package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.at;
import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.expect;

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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code lib/terrain.py} and {@code jobs/flatten.py} (#573; docs/reference/modules/lib/terrain.md). The plot is the 4 by 4
 * from (1, 1, 1) to (4, 2, 4), two blocks of dirt on the stone floor, to be levelled at y 2: a bump of two dirt on
 * (2, 2, 2), a stone on (1, 2, 1) and a hole two deep at (3, 2, 3) down to the floor.
 */
public class TerrainGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 1; x <= 4; x++) {
			for (int z = 1; z <= 4; z++) {
				helper.setBlock(x, 1, z, Blocks.DIRT);
				helper.setBlock(x, 2, z, Blocks.DIRT);
			}
		}
		helper.setBlock(2, 3, 2, Blocks.DIRT);
		helper.setBlock(2, 4, 2, Blocks.DIRT);
		helper.setBlock(1, 3, 1, Blocks.STONE);
		helper.setBlock(3, 2, 3, Blocks.AIR);
		helper.setBlock(3, 1, 3, Blocks.AIR);
		method.invoke(this, helper);
	}

	private static String header(GameTestHelper helper) {
		return "import lib.terrain\no = Pos(" + at(helper, 0, 0, 0) + ")\ny = o.y + 2\n";
	}

	/** needs counts the two holes to fill and the three blocks to dig, by the tool each is mineable with. */
	// Measured: passes at tick 3.
	@GameTest(maxTicks = 10)
	public void needsCountsTheFillAndTheDigging(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 6, 1, 6);
		expect(helper, bot, header(helper) + "print(lib.terrain.needs(o + (1, 1, 1), o + (4, 4, 4), y))\n",
			List.of("{'fill': 2, 'dig': 3, 'tools': {'pickaxe': 1, 'shovel': 2}}"));
	}

	/**
	 * flatten, from a bot standing on the plot, digs the bump and the stone and fills the hole with the dirt it dug: the
	 * plot's top is level at y 2.
	 */
	// Measured: passes at tick 62.
	@GameTest(maxTicks = 130)
	public void flattenLevelsABumpAndAHole(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 3, 4);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		expect(helper, bot, header(helper) + """
				r = lib.terrain.flatten(o + (1, 1, 1), o + (4, 4, 4), y)
				print(r["ok"], count("dirt"), count("cobblestone"))
				""", List.of("True 0 1"), () -> level(helper));
	}

	/**
	 * jobs.flatten, from a bot standing on the plot, with no y levels the plot at its middle height, 2, with the tools the
	 * bot carries. The area has sky access, as the job reads the ground with surface.
	 */
	// Measured: passes at tick 53.
	@GameTest(maxTicks = 130, skyAccess = true)
	public void theJobLevelsThePlotAtItsMiddleHeight(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 3, 4);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_SHOVEL));
		BlockPos low = helper.absolutePos(new BlockPos(1, 0, 1));
		BlockPos high = helper.absolutePos(new BlockPos(4, 0, 4));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.flatten " + low.getX() + " " + low.getZ() + " "
			+ high.getX() + " " + high.getZ());
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED || !BotScripts.log(bot, Output.Level.WARN).isEmpty()) {
				helper.fail("failed or warned: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScript() != null && bot.getScript().state() == ScriptRun.State.FINISHED, "not finished: "
				+ (bot.getScript() == null ? "no script" : bot.getScript().state()) + " " + BotScripts.log(bot, Output.Level.DEBUG));
			level(helper);
		});
	}

	/** Every column of the plot is solid at y 1 and 2 and air above. */
	private static void level(GameTestHelper helper) {
		for (int x = 1; x <= 4; x++) {
			for (int z = 1; z <= 4; z++) {
				for (int y = 1; y <= 4; y++) {
					boolean air = helper.getBlockState(new BlockPos(x, y, z)).isAir();
					helper.assertTrue(air == y > 2, (air ? "air" : "a block") + " at " + x + " " + y + " " + z);
				}
			}
		}
	}
}
