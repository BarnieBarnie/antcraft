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
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScript() != null && bot.getScript().state() == ScriptRun.State.FINISHED, "not finished: "
				+ (bot.getScript() == null ? "no script" : bot.getScript().state()) + " " + BotScripts.log(bot, Output.Level.DEBUG));
			level(helper);
		});
	}

	/**
	 * flatten fills a hole of water: a source at the top of the hole, (3, 2, 3), flowing down to (3, 1, 3), is filled
	 * level like a hole of air (#586).
	 */
	// Measured: passes at tick 61.
	@GameTest(maxTicks = 130)
	public void flattenFillsAWaterHole(GameTestHelper helper) {
		helper.setBlock(3, 2, 3, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 3, 4);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		expect(helper, bot, header(helper) + """
				r = lib.terrain.flatten(o + (1, 1, 1), o + (4, 4, 4), y)
				print(r["ok"], count("dirt"))
				""", List.of("True 0"), () -> level(helper));
	}

	/** flatten fills a hole of lava, a source at (3, 2, 3) flowing down to (3, 1, 3), level like one of water (#597). */
	// Measured: passes at tick 61.
	@GameTest(maxTicks = 130)
	public void flattenFillsALavaHole(GameTestHelper helper) {
		helper.setBlock(3, 2, 3, Blocks.LAVA);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 3, 4);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		expect(helper, bot, header(helper) + """
				r = lib.terrain.flatten(o + (1, 1, 1), o + (4, 4, 4), y)
				print(r["ok"], count("dirt"))
				""", List.of("True 0"), () -> level(helper));
	}

	/**
	 * jobs.flatten with no fill fills the hole with the cobblestone the bot carries, though it carries no dirt, rather than
	 * getting dirt (#597).
	 */
	// Measured: passes at tick 7.
	@GameTest(maxTicks = 30, skyAccess = true)
	public void theJobFillsWithTheCobblestoneItCarries(GameTestHelper helper) {
		helper.setBlock(2, 3, 2, Blocks.AIR);
		helper.setBlock(2, 4, 2, Blocks.AIR);
		helper.setBlock(1, 3, 1, Blocks.AIR);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 3, 4);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 2));
		runJob(helper, bot, " 2", () -> {
			helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(3, 1, 3));
			helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(3, 2, 3));
		});
	}

	/** jobs.flatten with no fill fills the hole with the cobbled deepslate the bot carries, as a deep plot gives (#620). */
	// Measured: passes at tick 7.
	@GameTest(maxTicks = 30, skyAccess = true)
	public void theJobFillsWithTheCobbledDeepslateItCarries(GameTestHelper helper) {
		helper.setBlock(2, 3, 2, Blocks.AIR);
		helper.setBlock(2, 4, 2, Blocks.AIR);
		helper.setBlock(1, 3, 1, Blocks.AIR);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 3, 4);
		bot.getInventory().addItem(new ItemStack(Items.COBBLED_DEEPSLATE, 2));
		runJob(helper, bot, " 2", () -> {
			helper.assertBlockPresent(Blocks.COBBLED_DEEPSLATE, new BlockPos(3, 1, 3));
			helper.assertBlockPresent(Blocks.COBBLED_DEEPSLATE, new BlockPos(3, 2, 3));
		});
	}

	/**
	 * jobs.flatten on a plot with a hole and nothing to dig, from a bot that carries no dirt, gets the two dirt it lacks
	 * from a chest by the plot and flattens again (#586). The chest stands on a step of dirt one lower than the plot, so the
	 * bot can climb back onto it.
	 */
	// Measured: passes at tick 24; the chest scan waits on a budget all bots share.
	@GameTest(maxTicks = 50, skyAccess = true)
	public void theJobGetsTheFillItLacksAndFlattensAgain(GameTestHelper helper) {
		helper.setBlock(2, 3, 2, Blocks.AIR);
		helper.setBlock(2, 4, 2, Blocks.AIR);
		helper.setBlock(1, 3, 1, Blocks.AIR);
		for (int x = 5; x <= 7; x++) {
			for (int z = 0; z <= 7; z++) {
				helper.setBlock(x, 1, z, Blocks.DIRT);
			}
		}
		helper.setBlock(6, 2, 1, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(6, 2, 1), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.DIRT, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 3, 4);
		runJob(helper, bot, "", () -> helper.assertTrue(chest.countItem(Items.DIRT) == 3, "dirt in the chest: " + chest.countItem(Items.DIRT)));
	}

	/** jobs.flatten with a fill after the corners fills the hole with it, here the cobblestone the bot carries (#586). */
	// Measured: passes at tick 6.
	@GameTest(maxTicks = 30, skyAccess = true)
	public void theJobFillsWithTheFillItIsGiven(GameTestHelper helper) {
		helper.setBlock(2, 3, 2, Blocks.AIR);
		helper.setBlock(2, 4, 2, Blocks.AIR);
		helper.setBlock(1, 3, 1, Blocks.AIR);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 3, 4);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 2));
		runJob(helper, bot, " 2 cobblestone", () -> {
			helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(3, 1, 3));
			helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(3, 2, 3));
		});
	}

	/**
	 * Runs jobs.flatten on the plot with args after its corners (a leading " 2" is y 2), failing at once on a warning, and
	 * succeeds once it finished with the plot level and then holds.
	 */
	private static void runJob(GameTestHelper helper, AntBot bot, String args, Runnable then) {
		BlockPos low = helper.absolutePos(new BlockPos(1, 0, 1));
		BlockPos high = helper.absolutePos(new BlockPos(4, 0, 4));
		String y = args.startsWith(" 2") ? " " + (low.getY() + 2) + args.substring(2) : args;
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.flatten " + low.getX() + " " + low.getZ() + " "
			+ high.getX() + " " + high.getZ() + y);
		BotScripts.failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getScript() != null && bot.getScript().state() == ScriptRun.State.FINISHED, "not finished: "
				+ (bot.getScript() == null ? "no script" : bot.getScript().state()) + " " + BotScripts.log(bot, Output.Level.DEBUG));
			level(helper);
			then.run();
		});
	}

	/** Every column of the plot is solid (neither air nor fluid) at y 1 and 2 and air above. */
	private static void level(GameTestHelper helper) {
		for (int x = 1; x <= 4; x++) {
			for (int z = 1; z <= 4; z++) {
				for (int y = 1; y <= 4; y++) {
					BlockState state = helper.getBlockState(new BlockPos(x, y, z));
					boolean solid = !state.isAir() && state.getFluidState().isEmpty();
					helper.assertTrue(solid == y <= 2, state + " at " + x + " " + y + " " + z);
				}
			}
		}
	}
}
