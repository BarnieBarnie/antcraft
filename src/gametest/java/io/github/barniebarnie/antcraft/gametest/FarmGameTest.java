package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

/**
 * {@code lib/farm.py} and {@code jobs/farm.py} (#570): harvest takes only the ripe crops and replants them, tend harvests,
 * tills and plants a small field, and the job does that with a hoe and seeds from a chest and stores the harvest there.
 * The field is the 3 by 3 of soil from (1, 0, 1) to (3, 0, 3) with water in its middle.
 */
public class FarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * Ripe wheat at (1, 0, 1), unripe wheat at (2, 0, 1) and ripe carrots at (3, 0, 1): ripe names the two ripe crops, and
	 * harvest, with no seeds carried, takes those two and replants them from what they dropped, leaving the unripe wheat.
	 */
	// Measured: passes at tick 5.
	@GameTest(maxTicks = 15)
	public void harvestTakesTheRipeCropsOnlyAndReplantsThem(GameTestHelper helper) {
		helper.setBlock(2, 0, 2, Blocks.WATER);
		for (int x = 1; x <= 3; x++) {
			helper.setBlock(x, 0, 1, Blocks.FARMLAND);
		}
		helper.setBlock(1, 1, 1, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
		helper.setBlock(2, 1, 1, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 3));
		helper.setBlock(3, 1, 1, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 4);
		run(helper, bot, """
			import lib.farm
			spots = [%s, %s, %s]
			print([lib.farm.ripe(s.up()) for s in spots])
			print(lib.farm.harvest(spots), count("wheat"))
			""".formatted(printed(helper, 1, 0, 1), printed(helper, 2, 0, 1), printed(helper, 3, 0, 1)));
		helper.succeedWhen(() -> {
			finished(helper, bot, List.of("['minecraft:wheat', None, 'minecraft:carrots']", "2 1"));
			for (BlockPos crop : List.of(new BlockPos(1, 1, 1), new BlockPos(3, 1, 1))) {
				helper.assertTrue(helper.getBlockState(crop).getValue(CropBlock.AGE) < 7, "not replanted at " + crop);
			}
			helper.assertBlockPresent(Blocks.WHEAT, 1, 1, 1);
			helper.assertBlockPresent(Blocks.CARROTS, 3, 1, 1);
			helper.assertBlockPresent(Blocks.WHEAT, 2, 1, 1);
		});
	}

	/**
	 * field over a box three blocks high on the field with a dirt block raised at (3, 1, 3) and stone at (2, 1, 1): it
	 * lists the top soil of each column, row by row, so (3, 1, 3) and not the dirt under it, and nothing under the stone.
	 */
	@GameTest(maxTicks = 15)
	public void fieldListsTheTopSoilOfEachColumn(GameTestHelper helper) {
		field(helper);
		helper.setBlock(3, 1, 3, Blocks.DIRT);
		helper.setBlock(2, 1, 1, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 5);
		run(helper, bot, """
			import lib.farm
			print(lib.farm.field(%s, %s))
			""".formatted(printed(helper, 1, 0, 1), printed(helper, 3, 2, 3)));
		String expected = "[" + String.join(", ", printed(helper, 1, 0, 1), printed(helper, 3, 0, 1), printed(helper, 1, 0, 2),
			printed(helper, 3, 0, 2), printed(helper, 1, 0, 3), printed(helper, 2, 0, 3), printed(helper, 3, 1, 3)) + "]";
		helper.succeedWhen(() -> finished(helper, bot, List.of(expected)));
	}

	/**
	 * A field of dirt with ripe wheat on farmland at (1, 0, 1): tend harvests it, tills the other seven spots and plants
	 * all eight with the bot's wheat seeds.
	 */
	// Measured: passes at tick 22.
	@GameTest(maxTicks = 50)
	public void tendHarvestsTillsAndPlantsASmallField(GameTestHelper helper) {
		List<BlockPos> spots = field(helper);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 5);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		bot.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 8));
		run(helper, bot, """
			import lib.farm
			r = lib.farm.tend(%s, %s)
			print(r["ok"], count("wheat"))
			""".formatted(printed(helper, 1, 0, 1), printed(helper, 3, 0, 3)));
		helper.succeedWhen(() -> {
			finished(helper, bot, List.of("True 1"));
			planted(helper, spots);
		});
	}

	/**
	 * jobs.farm over the field, with a wooden hoe and 8 wheat seeds in a chest at (6, 1, 6): it borrows the hoe and seeds,
	 * tends the field, stores the wheat in the chest and gives the hoe back.
	 */
	// Measured: passes at tick 133.
	@GameTest(maxTicks = 270)
	public void theJobTendsTheFieldAndStoresTheHarvest(GameTestHelper helper) {
		List<BlockPos> spots = field(helper);
		Container chest = chest(helper, 6, 1, 6, new ItemStack(Items.WOODEN_HOE), new ItemStack(Items.WHEAT_SEEDS, 8));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 5);
		ScriptRun job = runJob(helper, bot, "wheat");
		helper.succeedWhen(() -> {
			jobFinished(helper, bot, job);
			planted(helper, spots);
			helper.assertTrue(chest.countItem(Items.WHEAT) == 1, "wheat in the chest: " + chest.countItem(Items.WHEAT));
			helper.assertTrue(chest.countItem(Items.WOODEN_HOE) == 1, "no hoe in the chest");
		});
	}

	/**
	 * jobs.farm with 2 rounds over the field, a hoe and seeds in the chest: once the first round has planted the field and
	 * stored its wheat, the test ripens every crop, and the job waits for that, tends the field again and stores the 8
	 * wheat of the second harvest too.
	 */
	// Measured: passes at tick 210.
	@GameTest(maxTicks = 420)
	public void theJobTendsAgainOnceTheCropsRipen(GameTestHelper helper) {
		List<BlockPos> spots = field(helper);
		Container chest = chest(helper, 6, 1, 6, new ItemStack(Items.WOODEN_HOE), new ItemStack(Items.WHEAT_SEEDS, 8));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 5);
		ScriptRun job = runJob(helper, bot, "wheat 2");
		boolean[] ripened = {false};
		helper.onEachTick(() -> {
			// Ripens the field once the first round planted it and stored its harvest (one listener, registered up front).
			if (!ripened[0] && chest.countItem(Items.WHEAT) == 1 && spots.stream().allMatch(s -> helper.getBlockState(s.above()).is(Blocks.WHEAT))) {
				ripened[0] = true;
				for (BlockPos spot : spots) {
					helper.setBlock(spot.above(), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
				}
			}
		});
		helper.succeedWhen(() -> {
			jobFinished(helper, bot, job);
			planted(helper, spots);
			helper.assertTrue(chest.countItem(Items.WHEAT) == 9, "wheat in the chest: " + chest.countItem(Items.WHEAT));
		});
	}

	/**
	 * A field of ripe carrots on farmland but for bare farmland at (3, 0, 3), 5 carrots in a chest at (6, 1, 6) and an
	 * empty chest at (1, 1, 5), nearer the field: jobs.farm borrows a carrot for the bare spot, harvests and replants, and
	 * gives that carrot back to the chest it came from before it stores the harvest in the nearest chest.
	 */
	// Measured: passes at tick 56.
	@GameTest(maxTicks = 120)
	public void theJobGivesBorrowedCarrotsBackToTheirChest(GameTestHelper helper) {
		List<BlockPos> spots = field(helper);
		for (BlockPos spot : spots) {
			helper.setBlock(spot, Blocks.FARMLAND);
			if (!spot.equals(new BlockPos(3, 0, 3))) {
				helper.setBlock(spot.above(), Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7));
			}
		}
		Container source = chest(helper, 6, 1, 6, new ItemStack(Items.CARROT, 5));
		Container near = chest(helper, 1, 1, 5);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 4);
		ScriptRun job = runJob(helper, bot, "carrot");
		helper.succeedWhen(() -> {
			jobFinished(helper, bot, job);
			for (BlockPos spot : spots) {
				helper.assertBlockPresent(Blocks.CARROTS, spot.above());
			}
			helper.assertTrue(source.countItem(Items.CARROT) == 5, "carrots in their chest: " + source.countItem(Items.CARROT)
				+ ", in the near one: " + near.countItem(Items.CARROT) + ", on the bot: " + bot.getInventory().countItem(Items.CARROT));
			helper.assertTrue(bot.getInventory().countItem(Items.CARROT) == 0, "carrots left on the bot");
		});
	}

	/** The job finished (the bot runs idle.py once it has). */
	private static void jobFinished(GameTestHelper helper, AntBot bot, ScriptRun job) {
		helper.assertTrue(job.state() == ScriptRun.State.FINISHED, "not finished: " + job.state() + " " + BotScripts.log(bot, Output.Level.DEBUG));
	}

	/** A chest at (x, y, z) holding items. */
	private static Container chest(GameTestHelper helper, int x, int y, int z, ItemStack... items) {
		helper.setBlock(x, y, z, Blocks.CHEST);
		Container chest = (Container) Objects.requireNonNull(helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(x, y, z))));
		for (int i = 0; i < items.length; i++) {
			chest.setItem(i, items[i]);
		}
		return chest;
	}

	/**
	 * Runs jobs.farm over the field from (1, 0, 1) to (3, 0, 3) with args after the corners, and fails on an error or
	 * warning. Gives the job's run.
	 */
	private static ScriptRun runJob(GameTestHelper helper, AntBot bot, String args) {
		BlockPos low = helper.absolutePos(new BlockPos(1, 0, 1));
		BlockPos high = helper.absolutePos(new BlockPos(3, 0, 3));
		List<String> said = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.farm " + low.getX() + " "
			+ low.getY() + " " + low.getZ() + " " + high.getX() + " " + high.getY() + " " + high.getZ() + " " + args);
		ScriptRun job = bot.getScript();
		helper.assertTrue(job != null, "not run: " + said);
		failOnError(helper, bot);
		helper.onEachTick(() -> {
			if (!BotScripts.warnings(bot).isEmpty()) {
				helper.fail("warned: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		return job;
	}

	/** Lays the field: dirt from (1, 0, 1) to (3, 0, 3), water in its middle and ripe wheat on farmland at (1, 0, 1). */
	private static List<BlockPos> field(GameTestHelper helper) {
		List<BlockPos> spots = new java.util.ArrayList<>();
		for (int z = 1; z <= 3; z++) {
			for (int x = 1; x <= 3; x++) {
				if (x == 2 && z == 2) {
					helper.setBlock(x, 0, z, Blocks.WATER);
				} else {
					spots.add(new BlockPos(x, 0, z));
					helper.setBlock(x, 0, z, Blocks.DIRT);
				}
			}
		}
		helper.setBlock(1, 0, 1, Blocks.FARMLAND);
		helper.setBlock(1, 1, 1, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
		return spots;
	}

	/** Every spot is farmland with unripe wheat on it. */
	private static void planted(GameTestHelper helper, List<BlockPos> spots) {
		for (BlockPos spot : spots) {
			helper.assertBlockPresent(Blocks.FARMLAND, spot);
			helper.assertBlockPresent(Blocks.WHEAT, spot.above());
			helper.assertTrue(helper.getBlockState(spot.above()).getValue(CropBlock.AGE) < 7, "ripe wheat left at " + spot);
		}
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

	private static void finished(GameTestHelper helper, AntBot bot, List<String> expected) {
		helper.assertTrue(bot.getScript() != null && bot.getScript().state() == ScriptRun.State.FINISHED, "not finished");
		List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(printed.equals(expected), "printed " + printed + ", expected " + expected);
	}

	/** The position {@code (x, y, z)} of the test area as a script prints it. */
	private static String printed(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "Pos(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}
}
