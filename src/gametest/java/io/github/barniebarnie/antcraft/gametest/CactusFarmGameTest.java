package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@code jobs/cactus_farm.py} (#830; docs/cookbook/cactus-farm.md): a cactus farm on lib.cellfarm, its cells 2 by 2 floor
 * blocks with cactus on sand at two opposite corners, a checkerboard. Each test's farm has its corner at (1, 1, 3), so cell
 * 1 is the floor from (1, 0, 3) to (2, 0, 4), its cacti over (1, 0, 3) and (2, 0, 4), and cell 2 the same at x 4 to 5; the
 * depot's first chest is at (1, 1, 1). Every test fails at once where a cactus hurts the bot.
 */
public class CactusFarmGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** A supply chest of what one cell takes, the bot by it, and jobs.cactus_farm run with size cells. */
	private static AntBot farm(GameTestHelper helper, int cactus, String size) {
		helper.setBlock(6, 1, 6, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(6, 1, 6), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.CACTUS, cactus));
		chest.setItem(1, new ItemStack(Items.SAND, 4));
		chest.setItem(2, new ItemStack(Items.STONE_PICKAXE));
		chest.setItem(3, new ItemStack(Items.CHEST, 3)); // the cobblestone over goes in a chest of its own
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 6);
		BlockPos at = helper.absolutePos(new BlockPos(1, 1, 3));
		ScriptRun run = BotScripts.run(bot, """
			import jobs.cactus_farm
			jobs.cactus_farm.run(Pos(%d, %d, %d), %s, 8)
			""".formatted(at.getX(), at.getY(), at.getZ(), size));
		BotScripts.failOnError(helper, bot);
		failOnCactus(helper, bot, run);
		return bot;
	}

	/** Fails the test at once where a cactus hurts the bot. */
	private static void failOnCactus(GameTestHelper helper, AntBot bot, ScriptRun run) {
		helper.onEachTick(() -> {
			DamageSource hurt = bot.getLastDamageSource();
			if (hurt != null && hurt.is(DamageTypes.CACTUS)) {
				// Where the bot stood in the test, not just its block, and the call it ran: which step touched the cactus (#846).
				helper.fail("a cactus hurt the bot at " + TestPositions.relative(helper, bot.position()) + ", running " + run.action()
					+ " on line " + run.line());
			}
		});
	}

	/** The cactus in the depot's chests, along its row east of (1, 1, 1). */
	private static int stored(GameTestHelper helper) {
		int n = 0;
		for (int x = 1; x < 5; x++) {
			if (helper.getBlockState(new BlockPos(x, 1, 1)).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(new BlockPos(x, 1, 1), ChestBlockEntity.class).countItem(Items.CACTUS);
			}
		}
		return n;
	}

	private static boolean said(AntBot bot, String what) {
		return BotScripts.log(bot, Output.Level.PRINT).stream().anyMatch(l -> l.contains(what));
	}

	/** Cactus on sand at the cell's two spots, and no block beside either, at its own height. */
	private static void cell(GameTestHelper helper, int x, int z) {
		for (BlockPos spot : new BlockPos[] {new BlockPos(x, 1, z), new BlockPos(x + 1, 1, z + 1)}) {
			helper.assertBlockPresent(Blocks.SAND, spot.below());
			helper.assertBlockPresent(Blocks.CACTUS, spot);
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				helper.assertBlockPresent(Blocks.AIR, spot.relative(dir));
			}
		}
	}

	/** On a stone floor: sand is put at the cell's two spots, cactus on it, with nothing beside either. */
	// Measured: passes at tick 56 (red: No module named 'jobs.cactus_farm').
	@GameTest(maxTicks = 120)
	public void aCellIsBuiltWithCactiOnSandNoneBesideABlock(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, "1");
		helper.succeedWhen(() -> {
			cell(helper, 1, 3);
			helper.assertTrue(said(bot, "built cell 1"), "said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/** A cactus three high in the built cell is cut to its bottom block, the two cut stored in the depot, the bot unhurt. */
	// Measured: passes at tick 191.
	// Full runs: passes at up to tick 240 (#861).
	@GameTest(maxTicks = 480)
	public void aCactusThreeHighIsCutToOneAndStored(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, "1");
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				helper.setBlock(1, 2, 3, Blocks.CACTUS);
				helper.setBlock(1, 3, 3, Blocks.CACTUS);
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(grown[0], "cell 1 not built; said " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertBlockPresent(Blocks.CACTUS, 1, 1, 3);
			helper.assertBlockNotPresent(Blocks.CACTUS, 1, 2, 3);
			int stored = stored(helper);
			helper.assertTrue(stored == 2, "cactus in the depot " + stored + "; said " + BotScripts.log(bot, Output.Level.PRINT));
		});
	}

	/**
	 * With cactus for one cell only, the farm builds cell 1, then lacks cactus for cell 2; cactus grown in cell 1 is
	 * harvested and stored, and cell 2 is planted from the depot's cactus, the other 2 left there.
	 */
	// Measured: passes at tick 258.
	@GameTest(maxTicks = 520)
	public void aSecondCellIsPlantedFromStoredCactus(GameTestHelper helper) {
		AntBot bot = farm(helper, 2, "2");
		boolean[] grown = new boolean[1];
		helper.onEachTick(() -> {
			if (!grown[0] && said(bot, "built cell 1")) {
				for (BlockPos spot : new BlockPos[] {new BlockPos(1, 2, 3), new BlockPos(2, 2, 4)}) {
					helper.setBlock(spot, Blocks.CACTUS);
					helper.setBlock(spot.above(), Blocks.CACTUS);
				}
				grown[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "built cell 2"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			cell(helper, 4, 3);
			cell(helper, 1, 3);
			int stored = stored(helper);
			helper.assertTrue(stored == 2, "cactus in the depot " + stored + " of the 4 cut, 2 planted");
		});
	}

	/**
	 * A round that harvests nothing walks the bot into the chunk of the farm's corner where it stands elsewhere
	 * (lib.cellfarm.corner, #814), to a block by the corner beside no cactus: the block diagonal into the cell from its
	 * corner, where it was sent before, is the cell's second cactus, so the walk failed as unreachable and left the bot
	 * where its search gave up, by the cactus or outside the chunk (#846). The cactus spec says hurts, so farm sends it
	 * to a spot touching no cell block, not even diagonally (#887): the block back on z it took before was diagonal to
	 * the cactus at the corner. The corner is 12 blocks into its chunk along x and 4 along z, so that spot, 2 back on z, is
	 * in the chunk; the bot 6 east of it, in the next chunk.
	 */
	// Measured: passes at tick 27 (red: the bot stopped at (14.34, 1, 5.5), beside the cactus at (13, 1, 5); in other
	// runs at (9.27, 1, 5.5), 6 from the corner in the next chunk, or (11.09, 1, 5.4), 3 from it).
	// With the spec's hurts (#887): passes at tick 60 (red without it: the walk ended diagonal to the cactus at the
	// corner, and once the script ended the bot left the chunk, the last failure, on tick 82).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 80)
	public void theCornerWalkEndsBesideNoCactus(GameTestHelper helper) {
		int x = Math.floorMod(12 - helper.absolutePos(BlockPos.ZERO).getX(), 16);
		int z = Math.floorMod(4 - helper.absolutePos(BlockPos.ZERO).getZ(), 16);
		BlockPos low = new BlockPos(x < 2 ? x + 16 : x, 0, z < 2 ? z + 16 : z);
		BlockPos high = low.offset(1, 0, 1);
		for (BlockPos spot : new BlockPos[] {low, high}) {
			helper.setBlock(spot, Blocks.SAND);
			helper.setBlock(spot.above(), Blocks.CACTUS);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, low.offset(6, 1, 1));
		BlockPos at = helper.absolutePos(low);
		ScriptRun run = BotScripts.run(bot, """
			import lib.cells
			import lib.cellfarm
			import jobs.cactus_farm
			lib.cells.layout("cactus_farm", Pos(%d, %d, %d), (2, 2))
			lib.cells.add("cactus_farm", 0)
			lib.cellfarm.corner("cactus_farm", {}, jobs.cactus_farm.spec().get("hurts", False))
			print("waits")
			""".formatted(at.getX(), at.getY(), at.getZ()));
		BotScripts.failOnError(helper, bot);
		failOnCactus(helper, bot, run);
		helper.succeedWhen(() -> {
			helper.assertTrue(said(bot, "waits"), "said " + BotScripts.log(bot, Output.Level.PRINT));
			BlockPos feet = bot.blockPosition();
			helper.assertTrue(feet.getX() >> 4 == at.getX() >> 4 && feet.getZ() >> 4 == at.getZ() >> 4,
				"the bot at " + TestPositions.relative(helper, bot.position()) + ", not in the corner's chunk");
			helper.assertTrue(Math.abs(feet.getX() - at.getX()) <= 1 && Math.abs(feet.getZ() - at.getZ()) <= 2,
				"the bot at " + TestPositions.relative(helper, bot.position()) + ", not by the corner " + low);
			for (BlockPos p : BlockPos.betweenClosed(feet.offset(-1, 0, -1), feet.offset(1, 0, 1))) {
				helper.assertFalse(bot.level().getBlockState(p).is(Blocks.CACTUS),
					"the bot at " + TestPositions.relative(helper, bot.position()) + ", touching a cactus");
			}
			helper.assertBlockPresent(Blocks.CACTUS, low.above());
			helper.assertBlockPresent(Blocks.CACTUS, high.above());
		});
	}
}
