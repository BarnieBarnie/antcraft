package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.Memory;
import io.github.barniebarnie.antcraft.script.Pos;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Colony scripts (docs/scripting.md, milestone 7, #365): the sense {@code colony()}, the shared {@code colony_memory},
 * and the Colony Core's storage through {@code put} and {@code take}.
 */
public class ColonyGameTest implements CustomTestMethodInvoker {
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

	/** Places a Colony Core at {@code (x, y, z)} of the test area, which founds a colony there. */
	private static Colony colonyAt(GameTestHelper helper, int x, int y, int z) {
		helper.setBlock(x, y, z, ModBlocks.COLONY_CORE);
		return ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(x, y, z))).orElseThrow();
	}

	private static AntBot member(GameTestHelper helper, Colony colony, int x, int y, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, y, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	private static List<String> printed(AntBot bot) {
		return BotScripts.log(bot, Output.Level.PRINT);
	}

	/** Fails at once when the script of {@code bot} fails. */
	private static void failOnError(GameTestHelper helper, AntBot... bots) {
		helper.onEachTick(() -> {
			for (AntBot bot : bots) {
				if (bot.getScript() != null && bot.getScript().state() == ScriptRun.State.FAILED) {
					helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
				}
			}
		});
	}

	/**
	 * What one member stores in {@code colony_memory} the other reads at once, and {@code colony()} tells both their
	 * colony; a bot in no colony gets {@code None} for both.
	 */
	@GameTest(maxTicks = 20)
	public void membersShareColonyMemoryAndABotOutsideGetsNone(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 1, 1, 1);
		AntBot writer = member(helper, colony, 3, 1, 3);
		AntBot reader = member(helper, colony, 5, 1, 5);
		AntBot outside = helper.spawn(ModEntities.ANT_BOT, 6, 1, 1);
		BlockPos core = helper.absolutePos(new BlockPos(1, 1, 1));
		String corePos = "(" + core.getX() + ", " + core.getY() + ", " + core.getZ() + ")";
		BotScripts.run(writer, "wait(3)\ncolony_memory['chests'] = [pos()]\ncolony_memory['n'] = 42\n");
		BotScripts.run(reader, """
			while 'n' not in colony_memory:
			    wait(1)
			c = colony()
			print(colony_memory['n'], len(colony_memory['chests']), c['radius'], sorted(c['members']) == sorted(MEMBERS))
			print((c['core'].x, c['core'].y, c['core'].z) == CORE, c['core'].dimension, c['owner'])
			""".replace("MEMBERS", "['" + writer.getStringUUID() + "', '" + reader.getStringUUID() + "']").replace("CORE", corePos));
		BotScripts.run(outside, "wait(5)\nprint(colony(), colony_memory)\n");
		failOnError(helper, writer, reader, outside);
		helper.succeedWhen(() -> {
			helper.assertTrue(printed(reader).equals(List.of("42 1 32 True", "True minecraft:overworld None")), "reader printed " + printed(reader));
			helper.assertTrue(printed(outside).equals(List.of("None None")), "outside printed " + printed(outside));
			helper.assertTrue(Values.repr(colony.memory().get("n")).equals("42"), "colony memory " + Values.repr(colony.memory()));
		});
	}

	/**
	 * A member that went to the Nether stays a member (#353): its script there sees its colony, the core with the
	 * Overworld as its dimension, and the colony's memory; {@code put} at the core fails with {@code wrong_dimension}.
	 * The bot is moved as a portal moves it, but not added to the Nether: its script is ticked here, so the test needs no
	 * Nether chunks.
	 */
	@GameTest(maxTicks = 20)
	public void aMemberInTheNetherSeesItsColonyWithTheCoreInTheOverworld(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 1, 1, 1);
		colony.memory().put("chest", "by the core");
		AntBot bot = member(helper, colony, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 4));
		ServerLevel nether = Objects.requireNonNull(helper.getLevel().getServer().getLevel(Level.NETHER));
		AntBot moved = Objects.requireNonNull(ModEntities.ANT_BOT.create(nether, EntitySpawnReason.DIMENSION_TRAVEL));
		moved.restoreFrom(bot);
		moved.snapTo(Vec3.atBottomCenterOf(bot.blockPosition().atY(100)), 0, 0);
		bot.remove(Entity.RemovalReason.CHANGED_DIMENSION);
		BlockPos core = helper.absolutePos(new BlockPos(1, 1, 1));
		ScriptRun run = BotScripts.run(moved, """
			c = colony()
			print(dimension(), c['core'].dimension, (c['core'].x, c['core'].y, c['core'].z) == CORE, colony_memory['chest'], len(c['members']))
			print(put(c['core'], 'dirt').reason)
			""".replace("CORE", "(" + core.getX() + ", " + core.getY() + ", " + core.getZ() + ")"));
		helper.onEachTick(() -> run.tick(AntCraftConfig.scriptInstructionsPerTick()));
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state() + ": " + BotScripts.log(moved, Output.Level.DEBUG));
			helper.assertTrue(printed(moved).equals(List.of("minecraft:the_nether minecraft:overworld True by the core 1", "wrong_dimension")),
				"printed " + printed(moved));
		});
	}

	/** A member that leaves from another dimension than its core's leaves no stale entry in the colony's members (#353). */
	@GameTest(maxTicks = 20)
	public void aMemberLeavingFromAnotherDimensionIsNoLongerListed(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 1, 1, 1);
		AntBot bot = member(helper, colony, 3, 1, 3);
		ServerLevel nether = Objects.requireNonNull(helper.getLevel().getServer().getLevel(Level.NETHER));
		AntBot moved = Objects.requireNonNull(ModEntities.ANT_BOT.create(nether, EntitySpawnReason.DIMENSION_TRAVEL));
		moved.restoreFrom(bot);
		bot.remove(Entity.RemovalReason.CHANGED_DIMENSION);
		helper.assertTrue(colony.id().equals(moved.getColonyId()), "not a member after the move");
		ColonyManager.get(nether).leave(moved);
		helper.assertTrue(moved.getColonyId() == null, "still has colony " + moved.getColonyId());
		helper.assertFalse(colony.members().contains(moved.getUUID()), "still listed: " + colony.members());
		helper.succeed();
	}

	/** The Colony Core stores items like a chest: a script {@code put}s into it, reads it with {@code container} and {@code take}s back. */
	@GameTest(maxTicks = 60)
	public void aScriptPutsIntoAndTakesFromTheColonyCore(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 6, 1, 6);
		AntBot bot = member(helper, colony, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 10));
		BotScripts.run(bot, """
			c = colony()['core']
			r = put(c, 'dirt', 6)
			print(r.ok, r.value, [(s.item, s.count) for s in container(c)])
			r = take(c, 'dirt', 2)
			print(r.ok, r.value, count('dirt'), [(s.item, s.count) for s in container(c)])
			""");
		failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(printed(bot).equals(List.of("True 6 [('minecraft:dirt', 6)]",
			"True 2 6 [('minecraft:dirt', 4)]")), "printed " + printed(bot)));
	}

	/**
	 * A member saved in the middle of its script keeps no copy of {@code colony_memory}: loaded again, the script goes on
	 * with the colony's live dict, its own reference to it included, and sees what was stored there meanwhile.
	 */
	@GameTest(maxTicks = 30)
	public void aMemberSavedAndLoadedGoesOnWithTheColonysMemory(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 1, 1, 1);
		AntBot bot = member(helper, colony, 3, 1, 3);
		ScriptRun run = BotScripts.run(bot, "cm = colony_memory\ncm['a'] = 1\nwait(10)\ncm['b'] = cm['a']\n");
		failOnError(helper, bot);
		AntBot[] copy = {null};
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(run.action() != null && run.action().startsWith("wait"), "not waiting: " + run.action());
			// restoreFrom saves the bot and loads the copy from that save, as a portal does, resuming its script.
			copy[0] = Objects.requireNonNull(ModEntities.ANT_BOT.create(helper.getLevel(), EntitySpawnReason.LOAD));
			copy[0].restoreFrom(bot);
			bot.discard();
			colony.memory().put("a", 7L); // another member stores after the save
			helper.getLevel().addFreshEntity(copy[0]);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(copy[0] != null, "not saved yet");
			helper.assertTrue(Values.repr(colony.memory()).equals("{'a': 7, 'b': 7}"), "colony memory " + Values.repr(colony.memory()));
		});
	}

	/** The takers of the colony's jobs, oldest job first, null for an open one. */
	private static List<String> takers(Colony colony) {
		return colony.jobs().jobs(null).stream().map(job -> job.taker().map(UUID::toString).orElse(null)).toList();
	}

	/**
	 * The job board (#368): one member posts two jobs, and two others running the same script each take a different
	 * one, which {@code jobs()} lists with its taker.
	 */
	@GameTest(maxTicks = 30)
	public void twoBotsRunningTheSameScriptTakeDifferentJobs(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 1, 1, 1);
		AntBot poster = member(helper, colony, 3, 1, 3);
		AntBot first = member(helper, colony, 5, 1, 5);
		AntBot second = member(helper, colony, 6, 1, 3);
		BotScripts.run(poster, "wait(2)\nprint(post_job('haul', {'n': 1}), post_job('haul', [2, 'logs']))\n");
		String worker = """
			j = None
			while j is None:
			    j = take_job('haul')
			    wait(1)
			print(j['id'], j['kind'], j['data'], j['poster'] == POSTER, len(jobs('haul')), len(jobs('build')))
			wait(100)
			""".replace("POSTER", "'" + poster.getStringUUID() + "'");
		BotScripts.run(first, worker);
		BotScripts.run(second, worker);
		failOnError(helper, poster, first, second);
		helper.succeedWhen(() -> {
			helper.assertTrue(printed(poster).equals(List.of("1 2")), "poster printed " + printed(poster));
			List<String> both = new ArrayList<>(printed(first));
			both.addAll(printed(second));
			helper.assertTrue(both.stream().sorted().toList().equals(List.of("1 haul {'n': 1} True 2 0", "2 haul [2, 'logs'] True 2 0")),
				"workers printed " + printed(first) + " and " + printed(second));
			helper.assertTrue(Set.copyOf(takers(colony)).equals(Set.of(first.getStringUUID(), second.getStringUUID())),
				"takers " + takers(colony));
		});
	}

	/** A bot whose script is stopped gives back its job, and another member takes it (#368). */
	@GameTest(maxTicks = 30)
	public void aStoppedBotsJobGoesBackAndAnotherTakesIt(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 1, 1, 1);
		AntBot stopped = member(helper, colony, 3, 1, 3);
		AntBot other = member(helper, colony, 5, 1, 5);
		colony.jobs().post("haul", Memory.encode("logs"), stopped.getUUID());
		ScriptRun run = BotScripts.run(stopped, "print(take_job()['data'])\nwait(100)\n");
		failOnError(helper, stopped, other);
		boolean[] started = {false};
		helper.onEachTick(() -> {
			if (!started[0] && printed(stopped).equals(List.of("logs"))) {
				started[0] = true;
				helper.assertTrue(takers(colony).equals(List.of(stopped.getStringUUID())), "takers " + takers(colony));
				stopped.stopScript(); // as /antcraft bot stop does
				helper.assertTrue(takers(colony).equals(Collections.singletonList(null)), "not given back: " + takers(colony));
				BotScripts.run(other, "print(take_job('haul')['data'], take_job())\nwait(100)\n");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.STOPPED, "script " + run.state());
			helper.assertTrue(printed(other).equals(List.of("logs None")), "other printed " + printed(other));
			helper.assertTrue(takers(colony).equals(List.of(other.getStringUUID())), "takers " + takers(colony));
		});
	}

	// the default colony scripts (#369)

	/** Starts the bundled script {@code script} on {@code bot}, as a player does with {@code /antcraft bot run}. */
	private static void runDefault(GameTestHelper helper, AntBot bot, String script) {
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + script);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + script + ", 1]")), "run answered " + started);
	}

	/** Fails at once when a bot logs a warning or worse, or says something: the colony scripts do only when stuck. */
	private static void failOnWarning(GameTestHelper helper, AntBot... bots) {
		helper.onEachTick(() -> {
			for (AntBot bot : bots) {
				if (!BotScripts.log(bot, Output.Level.WARN).isEmpty()) {
					StringBuilder at = new StringBuilder();
					for (AntBot each : bots) {
						at.append(' ').append(TestPositions.relative(helper, each.blockPosition()).toShortString());
					}
					helper.fail(bot.getScript().name() + " stuck: " + BotScripts.log(bot, Output.Level.DEBUG) + "; bots at" + at);
				}
			}
		});
	}

	private static Container container(GameTestHelper helper, int x, int y, int z) {
		return (Container) Objects.requireNonNull(helper.getBlockEntity(new BlockPos(x, y, z), BlockEntity.class));
	}

	private static Pos pos(GameTestHelper helper, int x, int y, int z) {
		BlockPos at = helper.absolutePos(new BlockPos(x, y, z));
		return new Pos(at.getX(), at.getY(), at.getZ());
	}

	/**
	 * Milestone 7's acceptance: a builder takes a posted build of the cobble step, finds the core empty and asks for its
	 * materials with haul jobs; a hauler brings them from a chest in the colony to the core, and the builder fetches them
	 * there and builds.
	 */
	@GameTest(maxTicks = 200)
	public void aBuilderAndAHaulerBuildAPostedCobbleStepFromAChest(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 6, 1, 1);
		colony.setRadius(7);
		helper.setBlock(1, 1, 1, Blocks.CHEST);
		Container chest = container(helper, 1, 1, 1);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		chest.setItem(1, new ItemStack(Items.OAK_STAIRS));
		AntBot builder = member(helper, colony, 4, 1, 6);
		AntBot hauler = member(helper, colony, 6, 1, 4);
		PyDict data = new PyDict();
		data.put("blueprint", "antcraft:cobble_step");
		data.put("at", pos(helper, 3, 1, 3));
		colony.jobs().post("build", Memory.encode(data), builder.getUUID());
		runDefault(helper, builder, "colony.builder");
		runDefault(helper, hauler, "colony.hauler");
		failOnWarning(helper, builder, hauler);
		helper.succeedWhen(() -> {
			WorkGameTest.assertCobbleStep(helper, 0);
			helper.assertTrue(colony.jobs().jobs(null).isEmpty(), "jobs left: " + colony.jobs().jobs(null));
			helper.assertTrue(chest.isEmpty(), "the chest still holds " + chest.getItem(0) + ", " + chest.getItem(1));
		});
	}

	/** A haul job's item is a selector: one for {@code #minecraft:logs} brings birch logs from a chest to the core (#376). */
	@GameTest(maxTicks = 80)
	public void aHaulJobForATagBringsMatchingItemsToTheCore(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 6, 1, 1);
		colony.setRadius(7);
		helper.setBlock(1, 1, 1, Blocks.CHEST);
		Container chest = container(helper, 1, 1, 1);
		chest.setItem(0, new ItemStack(Items.DIRT, 2));
		chest.setItem(1, new ItemStack(Items.BIRCH_LOG, 3));
		Container core = container(helper, 6, 1, 1);
		AntBot hauler = member(helper, colony, 6, 1, 4);
		PyDict data = new PyDict();
		data.put("item", "#minecraft:logs");
		colony.jobs().post("haul", Memory.encode(data), hauler.getUUID());
		runDefault(helper, hauler, "colony.hauler");
		failOnWarning(helper, hauler);
		helper.succeedWhen(() -> {
			helper.assertTrue(core.countItem(Items.BIRCH_LOG) == 3, "birch logs in the core: " + core.countItem(Items.BIRCH_LOG));
			helper.assertTrue(colony.jobs().jobs(null).isEmpty(), "jobs left: " + colony.jobs().jobs(null));
			helper.assertTrue(chest.countItem(Items.DIRT) == 2, "dirt in the chest: " + chest.countItem(Items.DIRT));
		});
	}

	/** A lumberjack alone fells the colony's tree, plants its sapling where it stood and puts the logs into the core. */
	@GameTest(maxTicks = 600)
	public void aLumberjackFellsATreeReplantsAndStoresTheLogs(GameTestHelper helper) {
		helper.setBlock(5, 0, 5, Blocks.DIRT);
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(5, y, 5, Blocks.OAK_LOG);
		}
		for (int y = 3; y <= 5; y++) {
			int r = y == 5 ? 1 : 2;
			for (int x = 5 - r; x <= 5 + r; x++) {
				for (int z = 5 - r; z <= 5 + r; z++) {
					boolean corner = Math.abs(x - 5) == r && Math.abs(z - 5) == r;
					if (!corner && helper.getBlockState(new BlockPos(x, y, z)).isAir()) {
						helper.setBlock(x, y, z, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
					}
				}
			}
		}
		Colony colony = colonyAt(helper, 1, 1, 6);
		colony.setRadius(7);
		Container core = container(helper, 1, 1, 6);
		AntBot lumberjack = member(helper, colony, 1, 1, 1);
		lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(5, 1, 5));
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 4, "logs in the core: " + core.countItem(Items.OAK_LOG));
		});
	}

	/**
	 * A farmer alone farms the farm in colony_memory["buildings"]: harvests the ripe wheat, tills the dirt, plants every work
	 * spot and stores the wheat in the core.
	 */
	@GameTest(maxTicks = 340)
	public void aFarmerTillsPlantsAndHarvestsAFarm(GameTestHelper helper) {
		List<BlockPos> spots = farm(helper, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), 2,
				new ItemStack(Items.WHEAT_SEEDS, 24));
		Container core = container(helper, 7, 1, 7);
		helper.succeedWhen(() -> {
			for (BlockPos spot : spots) {
				helper.assertBlockPresent(Blocks.FARMLAND, spot);
				helper.assertBlockPresent(Blocks.WHEAT, spot.above());
			}
			helper.assertTrue(core.countItem(Items.WHEAT) == 2, "wheat in the core: " + core.countItem(Items.WHEAT));
		});
	}

	/**
	 * With carrots in the core the farmer plants carrots, harvests the ripe ones and stores what it does not replant (#377).
	 * The core's 16 carrots all go into the 16 bare spots, so carrots back in the core came from the harvest.
	 */
	@GameTest(maxTicks = 240)
	public void aFarmerGrowsCarrotsFromTheCore(GameTestHelper helper) {
		List<BlockPos> spots = farm(helper, Blocks.CARROTS.defaultBlockState().setValue(CropBlock.AGE, 7), 8,
				new ItemStack(Items.CARROT, 16));
		Container core = container(helper, 7, 1, 7);
		helper.succeedWhen(() -> {
			for (BlockPos spot : spots) {
				helper.assertBlockPresent(Blocks.FARMLAND, spot);
				helper.assertBlockPresent(Blocks.CARROTS, spot.above());
				helper.assertTrue(helper.getBlockState(spot.above()).getValue(CropBlock.AGE) < 7, "ripe carrots left at " + spot);
			}
			helper.assertTrue(core.countItem(Items.CARROT) > 0, "carrots in the core: " + core.countItem(Items.CARROT));
		});
	}

	/** A farmer with no seeds harvests ripe potatoes and replants them with potatoes from the harvest (#377). */
	@GameTest(maxTicks = 100)
	public void aFarmerReplantsHarvestedPotatoes(GameTestHelper helper) {
		farm(helper, Blocks.POTATOES.defaultBlockState().setValue(CropBlock.AGE, 7), 2);
		helper.succeedWhen(() -> {
			for (BlockPos ripe : List.of(new BlockPos(1, 1, 1), new BlockPos(2, 1, 1))) {
				helper.assertBlockPresent(Blocks.POTATOES, ripe);
				helper.assertTrue(helper.getBlockState(ripe).getValue(CropBlock.AGE) < 7, "not harvested at " + ripe);
			}
		});
	}

	/** A farmer with no seeds harvests ripe beetroot and replants it with the seeds from the harvest (#382). */
	@GameTest(maxTicks = 180)
	public void aFarmerReplantsHarvestedBeetroot(GameTestHelper helper) {
		farm(helper, Blocks.BEETROOTS.defaultBlockState().setValue(BeetrootBlock.AGE, 3), 2);
		Container core = container(helper, 7, 1, 7);
		helper.succeedWhen(() -> {
			for (BlockPos ripe : List.of(new BlockPos(1, 1, 1), new BlockPos(2, 1, 1))) {
				helper.assertBlockPresent(Blocks.BEETROOTS, ripe);
				helper.assertTrue(helper.getBlockState(ripe).getValue(BeetrootBlock.AGE) < 3, "not harvested at " + ripe);
			}
			helper.assertTrue(core.countItem(Items.BEETROOT) == 2, "beetroot in the core: " + core.countItem(Items.BEETROOT));
		});
	}

	/**
	 * After a harvest the farmer keeps no seeds the bare spots do not need and no poisonous potato: it stores them in the
	 * core (#382). Poisonous potatoes drop at random, so the bot starts with one, and with beetroot seeds it never plants.
	 */
	@GameTest(maxTicks = 340)
	public void aFarmerStoresSpareSeedsAndPoisonousPotatoes(GameTestHelper helper) {
		List<BlockPos> spots = farm(helper, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), 2,
				new ItemStack(Items.WHEAT_SEEDS, 24));
		AntBot farmer = helper.findOneEntity(ModEntities.ANT_BOT);
		farmer.getInventory().addItem(new ItemStack(Items.POISONOUS_POTATO));
		farmer.getInventory().addItem(new ItemStack(Items.BEETROOT_SEEDS, 10));
		Container core = container(helper, 7, 1, 7);
		helper.succeedWhen(() -> {
			for (BlockPos spot : spots) {
				helper.assertBlockPresent(Blocks.WHEAT, spot.above());
			}
			helper.assertTrue(core.countItem(Items.WHEAT) == 2, "wheat in the core: " + core.countItem(Items.WHEAT));
			for (var item : List.of(Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.POISONOUS_POTATO)) {
				helper.assertTrue(farmer.getInventory().countItem(item) == 0, item + " kept: " + farmer.getInventory().countItem(item));
			}
			helper.assertTrue(core.countItem(Items.POISONOUS_POTATO) == 1, "no poisonous potato in the core");
			helper.assertTrue(core.countItem(Items.BEETROOT_SEEDS) == 10, "beetroot seeds in the core: " + core.countItem(Items.BEETROOT_SEEDS));
		});
	}

	/**
	 * Lays a 5 by 5 farm of dirt with water in its middle at (1, 0, 1), with {@code ripe} on farmland on its first
	 * {@code ripeSpots} work spots (row by row from (1, 0, 1)), notes it in colony_memory["buildings"] of a colony with its
	 * core at (7, 1, 7), puts a wooden hoe and {@code items} in the core and starts colony.farmer; gives the work spots.
	 */
	private static List<BlockPos> farm(GameTestHelper helper, BlockState ripe, int ripeSpots, ItemStack... items) {
		List<BlockPos> spots = new ArrayList<>();
		for (int z = 1; z <= 5; z++) {
			for (int x = 1; x <= 5; x++) {
				if (x == 3 && z == 3) {
					helper.setBlock(x, 0, z, Blocks.WATER);
				} else {
					spots.add(new BlockPos(x, 0, z));
					helper.setBlock(x, 0, z, Blocks.DIRT);
				}
			}
		}
		for (BlockPos spot : spots.subList(0, ripeSpots)) {
			helper.setBlock(spot, Blocks.FARMLAND);
			helper.setBlock(spot.above(), ripe);
		}
		Colony colony = colonyAt(helper, 7, 1, 7);
		colony.setRadius(8);
		PyDict farm = new PyDict();
		farm.put("blueprint", "antcraft:farm");
		farm.put("at", pos(helper, 1, 0, 1));
		PyList buildings = new PyList();
		buildings.items.add(farm);
		colony.memory().put("buildings", buildings);
		Container core = container(helper, 7, 1, 7);
		core.setItem(0, new ItemStack(Items.WOODEN_HOE));
		for (int i = 0; i < items.length; i++) {
			core.setItem(i + 1, items[i]);
		}
		AntBot farmer = member(helper, colony, 0, 1, 7);
		runDefault(helper, farmer, "colony.farmer");
		failOnWarning(helper, farmer);
		return spots;
	}

	/** Outside a colony there is no job board: {@code take_job} and {@code jobs} give None and [], {@code post_job} fails at its line. */
	@GameTest(maxTicks = 10)
	public void aBotOutsideAColonyHasNoJobBoard(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun run = BotScripts.run(bot, "print(take_job(), jobs(), finish_job(1), drop_job(1))\npost_job('haul')\n");
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FAILED, "script " + run.state());
			helper.assertTrue(printed(bot).equals(List.of("None [] False False")), "printed " + printed(bot));
			List<String> log = BotScripts.log(bot, Output.Level.ERROR);
			helper.assertTrue(log.getLast().equals("test.py:2: post_job() needs the bot to be in a colony"), "log " + log);
		});
	}
}
