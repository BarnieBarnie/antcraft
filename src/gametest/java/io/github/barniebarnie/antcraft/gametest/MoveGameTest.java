package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotChunks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.BotLog;
import io.github.barniebarnie.antcraft.script.DefaultScripts;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Scripted bots moving (docs/scripting.md, milestone 2): {@code goto}, chunk loading, {@code home}, {@code set_home} and {@code idle.py}. */
public class MoveGameTest implements CustomTestMethodInvoker {
	/** Each far-away test gets its own spot, so one run after another in a server finds its chunks unloaded. */
	private static final AtomicInteger FAR_SPOTS = new AtomicInteger();

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
		method.invoke(this, helper);
	}

	private static String pos(BlockPos pos) {
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	private static List<String> printed(AntBot bot) {
		return BotScripts.log(bot, Output.Level.PRINT);
	}

	/** Fails at once when the script fails, rather than waiting out the test. */
	private static void failOnError(GameTestHelper helper, AntBot bot) {
		helper.onEachTick(() -> {
			List<String> errors = bot.getLog().last(BotLog.CAPACITY, Output.Level.ERROR).stream()
				.filter(entry -> entry.level() == Output.Level.ERROR).map(BotLog.Entry::text).toList();
			if (!errors.isEmpty()) {
				helper.fail("script error: " + errors);
			}
		});
	}

	/**
	 * A wall across the area, open only at its far end, then a trench two deep across the whole area: the bot goes round
	 * the wall and bridges the trench with the cobblestone it carries.
	 */
	@GameTest(maxTicks = 150)
	public void gotoReachesATargetBehindAWallAndOverAGap(GameTestHelper helper) {
		for (int z = 0; z < 7; z++) {
			for (int y = 1; y <= 3; y++) {
				helper.setBlock(3, y, z, Blocks.STONE);
			}
		}
		for (int z = -1; z <= 8; z++) {
			for (int y = -1; y <= 3; y++) {
				helper.setBlock(5, y, z, Blocks.AIR);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 1));
		ScriptRun run = BotScripts.run(bot, "r = goto(" + pos(target) + ")\nprint(r.ok, r.reason)\n");
		failOnError(helper, bot);
		helper.onEachTick(() -> {
			if (printed(bot).contains("False unreachable")) {
				helper.fail("goto gave up, bot at " + TestPositions.relative(helper, bot.blockPosition()).toShortString());
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(printed(bot).equals(List.of("True None")), "printed " + printed(bot) + ", script " + run.state());
			helper.assertValueEqual(bot.blockPosition(), target, "bot");
		});
	}

	/** A spot in mid-air, and no blocks to climb with. */
	@GameTest(maxTicks = 100)
	public void gotoASpotThatCannotBeReachedIsUnreachable(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos target = helper.absolutePos(new BlockPos(4, 6, 4));
		BotScripts.run(bot, "r = goto(" + pos(target) + ")\nprint(r.ok, r.reason, bool(r))\n");
		failOnError(helper, bot);

		helper.succeedWhen(() -> helper.assertTrue(printed(bot).equals(List.of("False unreachable False")), "printed " + printed(bot)));
	}

	/**
	 * Far from every other test, on a bridge high in the air: the bot's own chunk ticket keeps the three chunks around it
	 * loaded, and the target lies in the fourth chunk on, which nothing loads until {@code goto} does. The bot crosses the
	 * last three blocks on cobblestone of its own.
	 */
	@GameTest(maxTicks = 600)
	public void gotoIntoAnUnloadedChunkLoadsItAndArrives(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int chunkX = 1875;
		int chunkZ = 1875 + 16 * FAR_SPOTS.getAndIncrement();
		int y = 300;
		BlockPos start = new BlockPos(SectionPos.sectionToBlockCoord(chunkX) + 15, y, SectionPos.sectionToBlockCoord(chunkZ) + 8);
		BlockPos target = start.east(50);
		int targetChunk = SectionPos.blockToSectionCoord(target.getX());
		helper.assertTrue(targetChunk == chunkX + 4, "target in chunk " + targetChunk);
		// The chunks the bot's ticket loads, loaded now: generating them takes the server a while, and game tests tick fast.
		for (int cx = chunkX - 3; cx <= chunkX + 3; cx++) {
			for (int cz = chunkZ - 3; cz <= chunkZ + 3; cz++) {
				level.getChunk(cx, cz);
			}
		}
		// The bridge, nearly to the end of the third chunk on. Without neighbour updates, and short of the last block, which
		// would look into the target's chunk and load it.
		for (int x = start.getX() - 1; x < SectionPos.sectionToBlockCoord(targetChunk) - 1; x++) {
			level.setBlock(new BlockPos(x, y - 1, start.getZ()), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		helper.assertTrue(level.getChunkSource().getChunkNow(targetChunk, chunkZ) == null, "the target's chunk is loaded before goto");

		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		GameTests.onEnd(helper, bot::discard);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		ScriptRun run = BotScripts.run(bot, "r = goto(" + pos(target) + ")\nprint(r.ok, r.reason)\n");
		bot.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
		// As the bot does each tick while it runs a script, from the first tick on its new spot.
		BotChunks.keep(bot);
		failOnError(helper, bot);
		helper.onEachTick(() -> {
			if (printed(bot).stream().anyMatch(line -> line.startsWith("False"))) {
				helper.fail("goto failed: " + printed(bot) + ", bot at " + bot.blockPosition().toShortString());
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(printed(bot).equals(List.of("True None")), "printed " + printed(bot) + ", script " + run.state()
				+ ", bot at " + bot.blockPosition().toShortString());
			helper.assertValueEqual(bot.blockPosition(), target, "bot");
		});
	}

	/**
	 * Once its script ends, a bot runs {@code idle.py}: it walks home, where it was spawned, and stays. After a script sets
	 * a new home, it walks there.
	 */
	@GameTest(maxTicks = 330)
	public void aBotWhoseScriptEndsWalksHomeAndStays(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos spawn = helper.absolutePos(new BlockPos(1, 1, 1));
		BlockPos away = helper.absolutePos(new BlockPos(6, 1, 6));
		BlockPos newHome = helper.absolutePos(new BlockPos(6, 1, 1));
		BotScripts.run(bot, "print(goto(" + pos(away) + ").ok)\n");
		failOnError(helper, bot);
		int[] homeTicks = {0};
		boolean[] moved = {false};
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (moved[0] || run == null || !run.name().equals(DefaultScripts.IDLE)) {
				return;
			}
			helper.assertTrue(printed(bot).equals(List.of("True")), "first script printed " + printed(bot));
			homeTicks[0] = bot.blockPosition().equals(spawn) ? homeTicks[0] + 1 : 0;
			if (homeTicks[0] >= 30) {
				moved[0] = true;
				BotScripts.run(bot, "set_home(" + pos(newHome) + ")\n");
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(moved[0], "bot has not stayed home 30 ticks, at " + TestPositions.relative(helper, bot.blockPosition()).toShortString());
			ScriptRun run = bot.getScript();
			helper.assertTrue(run != null && run.name().equals(DefaultScripts.IDLE), "bot runs " + (run == null ? "nothing" : run.name()));
			helper.assertValueEqual(bot.blockPosition(), newHome, "bot");
		});
	}
}
