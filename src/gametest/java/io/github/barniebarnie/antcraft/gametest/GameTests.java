package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRespawns;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helpers shared by the game tests: how long each one took, cleaning up after one, and sending a bot somewhere. */
public final class GameTests {
	private static final Logger LOGGER = LoggerFactory.getLogger("AntCraft game tests");

	private GameTests() {
	}

	/**
	 * Call first in {@code invokeTestMethod}. Logs the tick the test passes or fails on, as
	 * {@code Game test <id> passed at tick <n> of <maxTicks>}, so {@code maxTicks} can be set from measured runs. Lays the
	 * stone floor at y=0 that CLAUDE.md promises every test, across the whole area (#558): the framework leaves y=0 air.
	 */
	public static void start(GameTestHelper helper) {
		GameTestInfo info = info(helper);
		info.addListener(new GameTestListener() {
			@Override
			public void testStructureLoaded(GameTestInfo test) {
			}

			@Override
			public void testPassed(GameTestInfo test, GameTestRunner runner) {
				LOGGER.info("Game test {} passed at tick {} of {}", test.id(), test.getTick(), test.getTimeoutTicks());
			}

			@Override
			public void testFailed(GameTestInfo test, GameTestRunner runner) {
				LOGGER.info("Game test {} failed at tick {} of {}", test.id(), test.getTick(), test.getTimeoutTicks());
			}

			@Override
			public void testAddedForRerun(GameTestInfo old, GameTestInfo next, GameTestRunner runner) {
			}
		});
		AABB bounds = helper.getBounds();
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, (int) bounds.getXsize() - 1, 0, (int) bounds.getZsize() - 1)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		// A bot that died in the test would otherwise come back at its home in the test's area after the test, into
		// whatever test runs there next. Every level: a bot waits in the level of its home, not of where it died.
		onEnd(helper, () -> forgetRespawns(helper.getLevel().getServer(), bounds));
		Area area = new Area(helper.getLevel(), bounds);
		AREAS.add(area);
		onEnd(helper, () -> AREAS.remove(area));
	}

	/** A test's area, in absolute positions, and its level. */
	private record Area(Level level, AABB box) {
	}

	/** The areas of the tests that {@link #start} started and that have not ended. */
	private static final Set<Area> AREAS = ConcurrentHashMap.newKeySet();

	/**
	 * Whether a scan by {@code bot} leaves out the block at the absolute {@code pos}: any block beside the area of the
	 * bot's test, the running test's area in its level that holds the bot, else the nearest (#598, #618). So a bot never
	 * finds a neighbouring test's chests, trees or stone, whatever radius a test passes. Blocks above or below the area
	 * are no other test's, so they are left in, such as the top of a tree taller than the area. See ScanAreaMixin.
	 */
	public static boolean hides(Entity bot, BlockPos pos) {
		AABB area = area(bot);
		return area != null && (pos.getX() < area.minX || pos.getX() >= area.maxX || pos.getZ() < area.minZ || pos.getZ() >= area.maxZ);
	}

	/**
	 * {@code radius} cut down to the farthest block of the bot's test area from {@code center} (#618), so a scan of
	 * radius 32 reads its few thousand blocks and not some 137,000 of the scan budget all bots share.
	 */
	public static int scanRadius(Entity bot, long x, long y, long z, int radius) {
		AABB area = area(bot);
		if (area == null) {
			return radius;
		}
		double dx = Math.max(Math.abs(x - area.minX), Math.abs(area.maxX - 1 - x));
		double dy = Math.max(Math.abs(y - area.minY), Math.abs(area.maxY - 1 - y));
		double dz = Math.max(Math.abs(z - area.minZ), Math.abs(area.maxZ - 1 - z));
		return (int) Math.min(radius, Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz)));
	}

	/** The bots whose scans are not kept to their test's area: {@link #scansFreely}. */
	private static final Set<UUID> FREE = ConcurrentHashMap.newKeySet();

	/**
	 * Lets {@code bot}'s scans read their whole radius and find blocks outside its test's area until the test ends: for a
	 * test of what a scan reads or costs, or of a bot that works away from its area (a portal far off).
	 */
	public static void scansFreely(GameTestHelper helper, Entity bot) {
		FREE.add(bot.getUUID());
		onEnd(helper, () -> FREE.remove(bot.getUUID()));
	}

	/**
	 * The area of {@code bot}'s test: the running test's area in its level that holds it, else the nearest; null when
	 * none runs there or the bot scans freely.
	 */
	private static @Nullable AABB area(Entity bot) {
		if (FREE.contains(bot.getUUID())) {
			return null;
		}
		Vec3 at = bot.position();
		return AREAS.stream().filter(a -> a.level() == bot.level()).map(Area::box)
			.min(Comparator.comparingDouble(a -> a.contains(at) ? -1 : a.distanceToSqr(at))).orElse(null);
	}

	/** Forgets every bot waiting to respawn at a home in {@code bounds}, in every level: what {@link #start} does at a test's end. */
	static void forgetRespawns(MinecraftServer server, AABB bounds) {
		server.getAllLevels().forEach(level -> BotRespawns.get(level).forget(server, home -> bounds.contains(Vec3.atCenterOf(home))));
	}

	/** The shortest tick {@link #waitAtMost} lets the server run: 5 ms. */
	private static final long PACED_TICK_NANOS = 5_000_000L;

	/** How long {@link #waitAtMost} lets a wait last: 15 s. */
	public static final long WAIT_SECONDS = 15;

	/** The ticks a wait of {@link #WAIT_SECONDS} lasts at most at the paced 5 ms a tick: a test's {@code maxTicks} adds its own to it. */
	public static final int PACED_WAIT_TICKS = 3000;

	/**
	 * Gives a wait on something that happens in the background, such as a chunk's entities loading from disk, a budget in
	 * wall-clock time rather than in ticks (#355, #356). Game tests run at a new spot each run, so their chunks are new, and
	 * the IO worker that loads their entities can take seconds while other tests' chunks are saved, as the idle server runs
	 * thousands of ticks. Paces the server to at least {@link #PACED_TICK_NANOS} a tick, and fails the test at once once
	 * {@code waiting} has held for {@link #WAIT_SECONDS} on end, with {@code failure}. So a test whose {@code maxTicks} is
	 * {@link #PACED_WAIT_TICKS} plus its own ticks never times out on the wait first. Registers an {@code onEachTick}
	 * listener: call it up front.
	 */
	public static void waitAtMost(GameTestHelper helper, BooleanSupplier waiting, Supplier<String> failure) {
		long[] lastTick = {System.nanoTime()};
		long[] since = {-1};
		helper.onEachTick(() -> {
			long wait = lastTick[0] + PACED_TICK_NANOS - System.nanoTime();
			if (wait > 0) {
				LockSupport.parkNanos(wait);
			}
			lastTick[0] = System.nanoTime();
			if (!waiting.getAsBoolean()) {
				since[0] = -1;
			} else if (since[0] < 0) {
				since[0] = lastTick[0];
			} else if (lastTick[0] - since[0] > WAIT_SECONDS * 1_000_000_000L) {
				helper.fail(failure.get() + " after " + WAIT_SECONDS + " s");
			}
		});
	}

	/** Runs {@code cleanup} once the test passed or failed, such as to remove a player or file it added. */
	public static void onEnd(GameTestHelper helper, Runnable cleanup) {
		info(helper).addListener(new GameTestListener() {
			@Override
			public void testStructureLoaded(GameTestInfo test) {
			}

			@Override
			public void testPassed(GameTestInfo test, GameTestRunner runner) {
				cleanup.run();
			}

			@Override
			public void testFailed(GameTestInfo test, GameTestRunner runner) {
				cleanup.run();
			}

			@Override
			public void testAddedForRerun(GameTestInfo old, GameTestInfo next, GameTestRunner runner) {
			}
		});
	}

	/** The blocks, in absolute positions, that are under spawn protection (see SpawnProtectionMixin). */
	private static final Set<BlockPos> SPAWN_PROTECTED = ConcurrentHashMap.newKeySet();

	/**
	 * Puts the block at {@code pos} of the test area under the server's spawn protection until the test ends, for every
	 * player but an operator, as a dedicated server with {@code spawn-protection} does near spawn. The game test server
	 * protects nothing by itself.
	 */
	public static void protect(GameTestHelper helper, BlockPos pos) {
		BlockPos absolute = helper.absolutePos(pos);
		SPAWN_PROTECTED.add(absolute);
		onEnd(helper, () -> SPAWN_PROTECTED.remove(absolute));
	}

	/** Whether {@link #protect} put the block at the absolute {@code pos} under spawn protection. */
	public static boolean isSpawnProtected(BlockPos pos) {
		return SPAWN_PROTECTED.contains(pos);
	}

	// ponytail: GameTestHelper has no getter for its test; reflection beats a mixin for test-only code.
	private static GameTestInfo info(GameTestHelper helper) {
		try {
			Field field = GameTestHelper.class.getDeclaredField("testInfo");
			field.setAccessible(true);
			return (GameTestInfo) field.get(helper);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Sends {@code bot} to within a block of {@code target}, as {@code goto(target, near=1)} in a script that then waits
	 * there, so it stays rather than going home; {@link #walked} tells when the walk ended. Starts on the bot's next tick.
	 */
	public static void walkTo(AntBot bot, BlockPos target) {
		walkTo(bot, target, 1);
	}

	/** Like {@link #walkTo(AntBot, BlockPos)}, to within {@code near} blocks of {@code target}. */
	public static void walkTo(AntBot bot, BlockPos target, int near) {
		BotScripts.run(bot, "print(goto((" + target.getX() + ", " + target.getY() + ", " + target.getZ() + "), near=" + near + ").ok)\n"
			+ "while True:\n    wait(1000)\n");
	}

	/**
	 * Runs {@code setup} then {@code measured} on {@code bot}, both script source, and succeeds once the script finished
	 * having printed {@code expected} after a line "measure" that comes between the two, with {@code measured} run within
	 * {@code limit} instructions; fails at once past that. Logs what it cost, as {@code Script cost <what>: <n> instructions
	 * over <t> ticks}, to the antcraft logger. The count starts on the tick "measure" is first seen, so it may leave out up to
	 * a tick's budget run before it. Registers an {@code onEachTick} listener: call it up front.
	 */
	public static void cost(GameTestHelper helper, AntBot bot, String setup, String measured, List<String> expected, String what, long limit) {
		ScriptRun run = BotScripts.run(bot, setup + "print('measure')\n" + measured);
		List<String> want = new ArrayList<>(List.of("measure"));
		want.addAll(expected);
		long[] start = {-1, 0};
		helper.onEachTick(() -> {
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			if (run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
			if (start[0] < 0 && !printed.isEmpty()) {
				start[0] = run.executed();
				start[1] = helper.getTick();
			}
			if (start[0] >= 0 && run.executed() - start[0] > limit) {
				helper.fail(what + " ran more than " + limit + " instructions");
			}
			if (run.state() == ScriptRun.State.FINISHED) {
				if (!printed.equals(want)) {
					helper.fail("printed " + printed + ", expected " + want);
				}
				COST.info("Script cost {}: {} instructions over {} ticks", what, run.executed() - start[0], helper.getTick() - start[1]);
				helper.succeed();
			}
		});
	}

	private static final Logger COST = LoggerFactory.getLogger("antcraft");

	/** Whether the walk {@link #walkTo} started has ended, there or given up. */
	public static boolean walked(AntBot bot) {
		return !BotScripts.log(bot, Output.Level.PRINT).isEmpty();
	}

	/** How many of item the bot carries, in its main hand too: the bot holds the tool or block it last used there. */
	public static int carried(AntBot bot, Item item) {
		ItemStack hand = bot.getMainHandItem();
		return bot.getInventory().countItem(item) + (hand.is(item) ? hand.getCount() : 0);
	}
}
