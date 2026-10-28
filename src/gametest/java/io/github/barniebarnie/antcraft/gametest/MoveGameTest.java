package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotChunks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.BotLog;
import io.github.barniebarnie.antcraft.script.DefaultScripts;
import io.github.barniebarnie.antcraft.script.Pos;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.portal.PortalShape;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

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

	/**
	 * An idle bot loads chunks on its way home as any script's {@code goto} does (#327): far from every other test, on a
	 * bridge high in the air, its home lies in the fourth chunk on, which nothing loads until its walk does.
	 */
	@GameTest(maxTicks = 600)
	public void anIdleBotWalksHomeIntoAnUnloadedChunk(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int chunkX = 1875;
		int chunkZ = 1875 + 16 * FAR_SPOTS.getAndIncrement();
		int y = 300;
		BlockPos start = new BlockPos(SectionPos.sectionToBlockCoord(chunkX) + 15, y, SectionPos.sectionToBlockCoord(chunkZ) + 8);
		BlockPos home = start.east(50);
		int homeChunk = SectionPos.blockToSectionCoord(home.getX());
		for (int cx = chunkX - 3; cx <= chunkX + 3; cx++) {
			for (int cz = chunkZ - 3; cz <= chunkZ + 3; cz++) {
				level.getChunk(cx, cz);
			}
		}
		for (int x = start.getX() - 1; x < SectionPos.sectionToBlockCoord(homeChunk) - 1; x++) {
			level.setBlock(new BlockPos(x, y - 1, start.getZ()), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
		}
		helper.assertTrue(level.getChunkSource().getChunkNow(homeChunk, chunkZ) == null, "the home's chunk is loaded before the walk");

		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		GameTests.onEnd(helper, bot::discard);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 8));
		bot.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
		bot.setHome(home);
		BotChunks.keep(bot);
		failOnError(helper, bot);

		helper.succeedWhen(() -> {
			ScriptRun run = bot.getScript();
			helper.assertTrue(run != null && run.name().equals(DefaultScripts.IDLE), "bot runs " + (run == null ? "nothing" : run.name()));
			helper.assertValueEqual(bot.blockPosition(), home, "bot");
		});
	}

	/** Whether a bot's own chunk ticket ({@link BotChunks#BOT}) sits on {@code chunk}. */
	private static boolean hasBotTicket(ServerLevel level, ChunkPos chunk) {
		TicketStorage tickets = level.getChunkSource().getDataStorage().computeIfAbsent(TicketStorage.TYPE);
		return tickets.getTickets(chunk.pack()).stream().anyMatch(ticket -> ticket.getType() == BotChunks.BOT);
	}

	/**
	 * A bot idle at home, far from every player and other test, holds no chunk ticket (#327): its chunk unloads, and the
	 * bot with it. Once the chunk loads again, as when a player comes near, the bot carries on with {@code idle.py}: it
	 * walks to the home set while it was away. The bot's entities load from disk in the background, through the IO worker,
	 * both when it is first put on the far spot and when its chunk loads again; while other tests' chunks are saved that
	 * can take seconds, and the server runs hundreds of ticks meanwhile. So those waits have a budget in wall-clock time
	 * ({@link GameTests#waitAtMost}).
	 */
	@GameTest(maxTicks = GameTests.PACED_WAIT_TICKS + 240)
	public void anIdleBotLetsItsChunkUnloadAndCarriesOnWhenItLoads(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		int chunkX = -1875;
		int chunkZ = 1875 + 16 * FAR_SPOTS.getAndIncrement();
		int y = 300;
		BlockPos start = new BlockPos(SectionPos.sectionToBlockCoord(chunkX) + 4, y, SectionPos.sectionToBlockCoord(chunkZ) + 8);
		BlockPos newHome = start.east(3);
		ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
		// The chunks the bot's ticket loads, loaded now: generating them takes longer than the ticket lasts.
		for (int cx = chunkX - 3; cx <= chunkX + 3; cx++) {
			for (int cz = chunkZ - 3; cz <= chunkZ + 3; cz++) {
				level.getChunk(cx, cz);
			}
		}
		for (int x = start.getX() - 1; x <= newHome.getX() + 1; x++) {
			for (int z = start.getZ() - 1; z <= start.getZ() + 1; z++) {
				level.setBlock(new BlockPos(x, y - 1, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		bot.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
		bot.setHome(start);
		// As a bot running a script does each tick, so it ticks on its new spot and starts idle.py there.
		BotChunks.keep(bot);
		failOnError(helper, bot);
		boolean[] idled = {false};
		boolean[] unloaded = {false};
		GameTests.onEnd(helper, () -> {
			if (level.getEntity(id) instanceof AntBot reloaded) {
				reloaded.discard();
			}
			bot.discard();
		});
		GameTests.waitAtMost(helper, () -> unloaded[0] ? !(level.getEntity(id) instanceof AntBot reloaded && reloaded != bot)
			: !idled[0] && !level.isPositionEntityTicking(start), () -> unloaded[0] ? "bot not reloaded" : "bot not ticking on its spot");
		helper.onEachTick(() -> {
			if (!unloaded[0]) {
				ScriptRun run = bot.getScript();
				idled[0] |= run != null && run.running() && run.name().equals(DefaultScripts.IDLE);
				if (idled[0] && bot.isRemoved() && !hasBotTicket(level, chunk)) {
					unloaded[0] = true;
				}
				return;
			}
			// A player near: the chunk ticks again. The ticket's level is a bot's, not the bot's own.
			level.getChunkSource().addTicketWithRadius(BotChunks.BOT, chunk, 3);
			if (level.getEntity(id) instanceof AntBot reloaded && reloaded != bot && !reloaded.isRemoved()) {
				reloaded.setHome(newHome);
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(idled[0], "bot never ran idle.py");
			helper.assertTrue(unloaded[0], "bot's chunk not unloaded: bot removed " + bot.isRemoved() + ", ticket " + hasBotTicket(level, chunk));
			helper.assertTrue(level.getEntity(id) instanceof AntBot reloaded && reloaded.blockPosition().equals(newHome)
				&& reloaded.getScript() != null && reloaded.getScript().name().equals(DefaultScripts.IDLE),
				"reloaded bot not at its new home running idle.py: " + level.getEntity(id));
		});
	}

	/**
	 * A lit nether portal two wide and three high across x, its frame's lower west corner at {@code corner}, on a stone
	 * floor with air around it, two blocks deep on either side. Gives the portal's lower west block.
	 */
	private static BlockPos buildPortal(ServerLevel level, BlockPos corner) {
		for (int x = -1; x <= 4; x++) {
			for (int z = -2; z <= 2; z++) {
				level.setBlockAndUpdate(corner.offset(x, 0, z), Blocks.STONE.defaultBlockState());
				for (int y = 1; y <= 5; y++) {
					level.setBlockAndUpdate(corner.offset(x, y, z), Blocks.AIR.defaultBlockState());
				}
			}
		}
		for (int i = 0; i <= 3; i++) {
			level.setBlockAndUpdate(corner.offset(i, 0, 0), Blocks.OBSIDIAN.defaultBlockState());
			level.setBlockAndUpdate(corner.offset(i, 4, 0), Blocks.OBSIDIAN.defaultBlockState());
		}
		for (int y = 1; y <= 3; y++) {
			level.setBlockAndUpdate(corner.offset(0, y, 0), Blocks.OBSIDIAN.defaultBlockState());
			level.setBlockAndUpdate(corner.offset(3, y, 0), Blocks.OBSIDIAN.defaultBlockState());
		}
		BlockPos inside = corner.offset(1, 1, 0);
		PortalShape.findEmptyPortalShape(level, inside, Direction.Axis.X).orElseThrow().createPortalBlocks(level);
		return inside;
	}

	/** A {@code goto} whose straight way crosses a lit nether portal walks round it and stays in the Overworld (#351). */
	@GameTest(maxTicks = 100)
	public void gotoWalksRoundANetherPortalInTheWay(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		buildPortal(level, helper.absolutePos(new BlockPos(2, 0, 4)));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 2);
		UUID id = bot.getUUID();
		ServerLevel nether = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER));
		GameTests.onEnd(helper, () -> {
			if (nether.getEntity(id) instanceof AntBot there) {
				there.discard();
			}
		});
		BlockPos target = helper.absolutePos(new BlockPos(3, 1, 6));
		BotScripts.run(bot, "r = goto(" + pos(target) + ")\nprint(r.ok, dimension())\nwait(1000)\n");
		failOnError(helper, bot);
		helper.onEachTick(() -> {
			if (level.getBlockState(bot.blockPosition()).is(Blocks.NETHER_PORTAL) || bot.isRemoved()) {
				helper.fail("bot walked into the portal");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(printed(bot).contains("True minecraft:overworld"), "printed " + printed(bot));
			helper.assertTrue(bot.blockPosition().equals(target), "bot at " + bot.blockPosition());
		});
	}

	/**
	 * The lower west corner of a portal frame at a far spot of its own in the Overworld, with the chunks a bot's ticket
	 * would load loaded. The spots lie 1024 blocks apart, 128 in the Nether, so no portal test comes out of another's
	 * portal in either dimension, as the tests in a batch would in the Nether a few blocks apart (#359).
	 */
	private static BlockPos portalSpot(ServerLevel level) {
		ChunkPos spot = new ChunkPos(3750, 64 * FAR_SPOTS.getAndIncrement());
		for (int cx = spot.x() - 3; cx <= spot.x() + 3; cx++) {
			for (int cz = spot.z() - 3; cz <= spot.z() + 3; cz++) {
				level.getChunk(cx, cz);
			}
		}
		return new BlockPos(spot.getMinBlockX() + 4, 300, spot.getMinBlockZ() + 8);
	}

	/**
	 * A bot next to a lit nether portal goes through it with {@code travel} and arrives in the Nether, its script going on
	 * there (#350). At a {@link #portalSpot}. The bot travels once it ticks on that spot and the Nether chunk it comes out
	 * in ticks entities: both chunks are new, and their entities load in the background (see {@link GameTests#waitAtMost}).
	 */
	@GameTest(maxTicks = GameTests.PACED_WAIT_TICKS + 240)
	public void travelTakesTheBotThroughANetherPortal(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos corner = portalSpot(level);
		buildPortal(level, corner);
		BlockPos start = corner.offset(1, 1, -2);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
		UUID id = bot.getUUID();
		ServerLevel nether = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER));
		GameTests.onEnd(helper, () -> {
			if (nether.getEntity(id) instanceof AntBot there) {
				there.discard();
			}
			bot.discard();
		});
		failOnError(helper, bot);
		// Where the portal leads, an eighth of the way from the Nether's origin.
		BlockPos there = new BlockPos(Math.floorDiv(start.getX(), 8), 64, Math.floorDiv(start.getZ(), 8));
		boolean[] started = {false};
		GameTests.waitAtMost(helper, () -> !started[0], () -> "chunks not ticking entities: Overworld "
			+ level.isPositionEntityTicking(start) + ", Nether " + nether.isPositionEntityTicking(there));
		helper.onEachTick(() -> {
			BotChunks.keep(level, ChunkPos.containing(start));
			BotChunks.keep(nether, ChunkPos.containing(there));
			if (!started[0] && level.isPositionEntityTicking(start) && nether.isPositionEntityTicking(there)) {
				BotScripts.run(bot, "r = travel(find_nearest('nether_portal'))\nprint(r.ok, dimension())\nwait(1000)\n");
				started[0] = true;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(nether.getEntity(id) instanceof AntBot, "bot not in the Nether");
			AntBot through = (AntBot) Objects.requireNonNull(nether.getEntity(id));
			List<String> printed = printed(through);
			helper.assertTrue(printed.contains("True minecraft:the_nether"), "printed " + printed);
			// It remembers both ends of the portal, the end it came out of first, and they survive a save and load (#354).
			List<Pos> portals = through.getPortals();
			helper.assertTrue(portals.size() == 2 && "minecraft:the_nether".equals(portals.get(0).dimension())
				&& "minecraft:overworld".equals(portals.get(1).dimension()), "portals " + portals);
			helper.assertTrue(isPortal(nether, portals.get(0)) && isPortal(helper.getLevel(), portals.get(1)), "not portal blocks: " + portals);
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, nether.registryAccess());
			through.saveWithoutId(output);
			AntBot loaded = new AntBot(ModEntities.ANT_BOT, nether);
			loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, nether.registryAccess(), output.buildResult()));
			helper.assertTrue(describe(loaded.getPortals()).equals(describe(portals)), "loaded portals " + describe(loaded.getPortals()));
		});
	}

	private static boolean isPortal(ServerLevel level, Pos pos) {
		return level.getBlockState(new BlockPos((int) pos.x(), (int) pos.y(), (int) pos.z())).is(Blocks.NETHER_PORTAL);
	}

	/** Positions with their dimensions, which {@link Pos#equals} leaves out. */
	private static List<String> describe(List<Pos> portals) {
		return portals.stream().map(portal -> portal + " " + portal.dimension()).toList();
	}

	/**
	 * A bot in the Nether whose home is in the Overworld goes home through a portal next to it: its {@code idle.py} finds
	 * the portal, travels, and walks home from the portal it comes out of, which is the one next to its home (#350). At a
	 * {@link #portalSpot}, waiting for its chunks as {@link #travelTakesTheBotThroughANetherPortal} does.
	 */
	@GameTest(maxTicks = GameTests.PACED_WAIT_TICKS + 320)
	public void anIdleBotInTheNetherGoesHomeThroughAPortal(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		// The Overworld end, which the portal in the Nether leads to: nearer its way out than any other portal.
		BlockPos overworldCorner = portalSpot(level);
		buildPortal(level, overworldCorner);
		BlockPos home = overworldCorner.offset(4, 1, -2);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
		UUID id = bot.getUUID();
		bot.setHome(home);
		ServerLevel nether = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER));
		BlockPos corner = new BlockPos(Math.floorDiv(home.getX(), 8) - 1, 100, Math.floorDiv(home.getZ(), 8));
		GameTests.onEnd(helper, () -> {
			for (ServerLevel in : List.of(level, nether)) {
				if (in.getEntity(id) instanceof AntBot there) {
					there.discard();
				}
			}
		});
		// Once both chunks tick entities, the portal goes up in the Nether and the bot is put next to it.
		boolean[] sent = {false};
		GameTests.waitAtMost(helper, () -> !sent[0], () -> "chunks not ticking entities: Overworld "
			+ level.isPositionEntityTicking(home) + ", Nether " + nether.isPositionEntityTicking(corner));
		helper.onEachTick(() -> {
			BotChunks.keep(level, ChunkPos.containing(home));
			BotChunks.keep(nether, ChunkPos.containing(corner));
			if (sent[0] || !level.isPositionEntityTicking(home) || !nether.isPositionEntityTicking(corner)) {
				return;
			}
			buildPortal(nether, corner);
			bot.teleportTo(nether, corner.getX() + 1.5, corner.getY() + 1, corner.getZ() - 0.5, Set.of(), 0, 0, false);
			sent[0] = true;
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(sent[0] && nether.getEntity(id) == null, "bot not through the portal yet");
			helper.assertTrue(level.getEntity(id) instanceof AntBot back && back != bot && back.blockPosition().equals(home),
				"bot not home: " + level.getEntity(id));
		});
	}

	/**
	 * A bot that went into the Nether through a portal and is then more than 32 blocks from it, at the end of a tunnel,
	 * goes home through the portal it remembers: its {@code idle.py} finds no portal near and travels to the remembered
	 * one (#354). Far from the test area and the other portal tests, so their portals are not the nearest.
	 */
	@GameTest(maxTicks = 700)
	public void anIdleBotFarFromThePortalItCameThroughGoesHomeThroughIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ChunkPos spot = new ChunkPos(1875, -1875 - 16 * FAR_SPOTS.getAndIncrement());
		for (int cx = spot.x() - 3; cx <= spot.x() + 3; cx++) {
			for (int cz = spot.z() - 3; cz <= spot.z() + 3; cz++) {
				level.getChunk(cx, cz);
			}
		}
		BlockPos overworldCorner = new BlockPos(spot.getMinBlockX() + 4, 300, spot.getMinBlockZ() + 8);
		buildPortal(level, overworldCorner);
		BlockPos home = overworldCorner.offset(1, 1, -2);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
		bot.setHome(home);
		UUID id = bot.getUUID();
		ServerLevel nether = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER));
		// The portal the Overworld one leads to, and a closed tunnel from in front of it to the east.
		BlockPos corner = new BlockPos(Math.floorDiv(overworldCorner.getX(), 8), 100, Math.floorDiv(overworldCorner.getZ(), 8));
		int length = 42;
		BlockPos far = corner.offset(4 + length, 1, -1);
		GameTests.onEnd(helper, () -> {
			for (ServerLevel in : List.of(level, nether)) {
				if (in.getEntity(id) instanceof AntBot there) {
					there.discard();
				}
			}
		});
		failOnError(helper, bot);
		// 0: waiting for the chunks; 1: the bot travels; 2: the bot, through and remembering the portal, was put at the far
		// end of the tunnel and idles.
		int[] phase = {0};
		helper.onEachTick(() -> {
			BotChunks.keep(level, spot);
			boolean ticking = level.isPositionEntityTicking(home);
			for (int x = corner.getX() - 1; x <= far.getX() + 8; x += 8) {
				BlockPos at = new BlockPos(x, corner.getY(), corner.getZ());
				BotChunks.keep(nether, ChunkPos.containing(at));
				ticking &= nether.isPositionEntityTicking(at);
			}
			if (phase[0] == 0 && ticking) {
				buildPortal(nether, corner);
				for (int x = 5; x <= 5 + length; x++) {
					for (int z = -3; z <= 0; z++) {
						for (int y = 0; y <= 4; y++) {
							boolean inside = z >= -2 && z <= -1 && y >= 1 && y <= 3 && x < 5 + length;
							nether.setBlockAndUpdate(corner.offset(x, y, z), (inside ? Blocks.AIR : Blocks.STONE).defaultBlockState());
						}
					}
				}
				BotScripts.run(bot, "travel(find_nearest('nether_portal'))\nwait(100000)\n");
				phase[0] = 1;
			} else if (phase[0] == 1 && nether.getEntity(id) instanceof AntBot there && there.getPortals().size() == 2) {
				there.teleportTo(nether, far.getX() + 0.5, far.getY(), far.getZ() + 0.5, Set.of(), 0, 0, false);
				there.stopScript();
				phase[0] = 2;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(phase[0] == 2 && nether.getEntity(id) == null, "bot not back through the portal yet: phase " + phase[0]);
			helper.assertTrue(level.getEntity(id) instanceof AntBot back && back.blockPosition().equals(home),
				"bot not home: " + level.getEntity(id));
		});
	}

	/**
	 * An idle bot whose home is in the Nether and whose nearest remembered portal was broken travels there, finds it gone
	 * ({@code no_portal}), forgets it, and goes through the next portal it remembers, more than 32 blocks off on the other
	 * side (#358). In the Overworld, far from the test area so no other test's portal is near, and without waiting for a
	 * Nether chunk: the test ends as the bot goes through. The bot's entities still load in the background once it is put
	 * on the far spot, so that wait has a budget in wall-clock time ({@link GameTests#waitAtMost}).
	 */
	@GameTest(maxTicks = GameTests.PACED_WAIT_TICKS + 480)
	public void anIdleBotWhoseNearestRememberedPortalIsGoneGoesThroughTheNext(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		ChunkPos spot = new ChunkPos(-1875, -1875 - 16 * FAR_SPOTS.getAndIncrement());
		for (int cx = spot.x() - 3; cx <= spot.x() + 3; cx++) {
			for (int cz = spot.z() - 3; cz <= spot.z() + 3; cz++) {
				level.getChunk(cx, cz);
			}
		}
		// The gone portal to the west, the next one to the east: neither within 32 blocks of the other or the start.
		BlockPos start = new BlockPos(spot.getMinBlockX() + 2, 300, spot.getMinBlockZ() + 8);
		for (int x = -11; x <= 40; x++) {
			for (int z = -2; z <= 2; z++) {
				level.setBlockAndUpdate(start.offset(x, -1, z), Blocks.STONE.defaultBlockState());
			}
		}
		BlockPos next = buildPortal(level, start.offset(38, -1, 0));
		Pos gone = new Pos(start.getX() - 10, start.getY(), start.getZ()).in("minecraft:overworld");
		Pos kept = new Pos(next.getX(), next.getY(), next.getZ()).in("minecraft:overworld");
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		bot.teleportTo(start.getX() + 0.5, start.getY(), start.getZ() + 0.5);
		bot.setHome(new BlockPos(Math.floorDiv(start.getX(), 8), 100, Math.floorDiv(start.getZ(), 8)), Level.NETHER);
		bot.setPortals(List.of(gone, kept));
		ServerLevel nether = Objects.requireNonNull(level.getServer().getLevel(Level.NETHER));
		GameTests.onEnd(helper, () -> {
			if (nether.getEntity(id) instanceof AntBot there) {
				there.discard();
			}
			bot.discard();
		});
		failOnError(helper, bot);
		GameTests.waitAtMost(helper, () -> !bot.isRemoved() && !level.isPositionEntityTicking(bot.blockPosition()), () -> "bot not ticking on its spot");
		helper.onEachTick(() -> BotChunks.keep(level, spot));
		helper.succeedWhen(() -> {
			helper.assertTrue(bot.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION,
				"bot not through a portal yet: at " + bot.blockPosition() + ", portals " + describe(bot.getPortals()));
			helper.assertTrue(describe(bot.getPortals()).equals(describe(List.of(kept))), "portals " + describe(bot.getPortals()));
		});
	}
}
