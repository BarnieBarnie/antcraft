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
import io.github.barniebarnie.antcraft.script.SenseApi;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import io.github.barniebarnie.antcraft.script.lang.PyList;
import io.github.barniebarnie.antcraft.script.lang.PyNone;
import io.github.barniebarnie.antcraft.script.lang.Values;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.Item;
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
 * Colony scripts (docs/design/index.md, milestone 7, #365): the sense {@code colony()}, the shared {@code colony_memory},
 * and the Colony Core's storage through {@code put} and {@code take}.
 */
public class ColonyGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
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
	@GameTest(maxTicks = 20)
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
	@GameTest(maxTicks = 20)
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

	private static String contents(Container container) {
		StringJoiner out = new StringJoiner(", ", "[", "]");
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (!container.getItem(i).isEmpty()) {
				out.add(container.getItem(i).toString());
			}
		}
		return out.toString();
	}

	private static Pos pos(GameTestHelper helper, int x, int y, int z) {
		BlockPos at = helper.absolutePos(new BlockPos(x, y, z));
		return new Pos(at.getX(), at.getY(), at.getZ());
	}

	/**
	 * Milestone 7's acceptance: a builder takes a posted build of the cobble step and finds the core empty; the materials
	 * are in a chest in the colony, which the builder borrows them from with lib.get (#543) or a hauler brings to the
	 * core, and the builder builds.
	 */
	@GameTest(maxTicks = 160)
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

	/**
	 * An idle bot homed where the cobble step's second cobblestone goes, standing over the edge where the first goes, is
	 * asked to make way once (#388): the builder finds one cobblestone in the core, stops with missing_ingredients, fails
	 * to get the second with lib.get (no tree for a pickaxe) and posts a haul job for it (#543), builds on once the second
	 * is put there, cancelling the haul job no hauler took, and the idle bot keeps off the site in between instead of going
	 * back home.
	 */
	@GameTest(maxTicks = 180)
	public void anIdleBotMakesWayOnceForABuildThatGoesOnAfterMissingIngredients(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 6, 1, 1);
		colony.setRadius(7);
		Container core = container(helper, 6, 1, 1);
		core.setItem(0, new ItemStack(Items.COBBLESTONE));
		core.setItem(1, new ItemStack(Items.OAK_STAIRS));
		AntBot idle = helper.spawn(ModEntities.ANT_BOT, new Vec3(4.05, 1, 3.5));
		AntBot builder = member(helper, colony, 1, 1, 6);
		PyDict data = new PyDict();
		data.put("blueprint", "antcraft:cobble_step");
		data.put("at", pos(helper, 3, 1, 3));
		colony.jobs().post("build", Memory.encode(data), builder.getUUID());
		runDefault(helper, builder, "colony.builder");
		failOnWarning(helper, builder);
		boolean[] given = {false};
		helper.onEachTick(() -> {
			if (!given[0] && !colony.jobs().jobs("haul").isEmpty()) {
				given[0] = true;
				core.setItem(2, new ItemStack(Items.COBBLESTONE));
			}
		});
		helper.succeedWhen(() -> {
			WorkGameTest.assertCobbleStep(helper, 0);
			helper.assertValueEqual(idle.getWaysMade(), 1, "times the idle bot made way");
			// The haul job no hauler took is cancelled once the build is done (#557).
			helper.assertTrue(colony.jobs().jobs(null).isEmpty(), "jobs left: " + colony.jobs().jobs(null));
		});
	}

	/**
	 * As {@link #anIdleBotMakesWayOnceForABuildThatGoesOnAfterMissingIngredients}, with a builder whose fetch takes 300
	 * ticks, longer than the hold after its last build: the idle bot keeps off the site while the builder holds its build
	 * job (#391), and is asked once.
	 */
	@GameTest(maxTicks = 500)
	public void anIdleBotKeepsOffWhileTheBuilderHoldsItsBuildJob(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 6, 1, 1);
		colony.setRadius(7);
		AntBot idle = helper.spawn(ModEntities.ANT_BOT, new Vec3(4.05, 1, 3.5));
		AntBot builder = member(helper, colony, 1, 1, 6);
		WorkGameTest.carryCobbleStep(builder, 1);
		PyDict data = new PyDict();
		data.put("blueprint", "antcraft:cobble_step");
		colony.jobs().post("build", Memory.encode(data), builder.getUUID());
		String build = "build('antcraft:cobble_step', " + WorkGameTest.at(helper, 3, 1, 3) + ")";
		BotScripts.run(builder, "job = take_job('build')\nprint(" + build + ".reason)\nwait(300)\nprint(" + build + ".reason)\nfinish_job(job['id'])\n");
		boolean[] given = {false};
		helper.onEachTick(() -> {
			if (!given[0] && printed(builder).size() == 1) {
				given[0] = true;
				builder.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertValueEqual(printed(builder), List.of("missing_ingredients", "None"), "printed");
			WorkGameTest.assertCobbleStep(helper, 0);
			helper.assertValueEqual(idle.getWaysMade(), 1, "times the idle bot made way");
		});
	}

	/**
	 * As {@link #anIdleBotKeepsOffWhileTheBuilderHoldsItsBuildJob}, with a builder holding two build jobs that gives up the
	 * first right after missing_ingredients and holds on to the second: the hold follows the job passed to {@code build},
	 * so the idle bot goes back home once that job is done, though the builder still holds a build job (#396), and makes
	 * way only once: the build's spots go with the hold, not a second after the last ask (#400).
	 */
	// Measured: passes at tick 11 (with the spots kept a second after the hold, the idle bot made way twice); with any build job holding, it never goes back while the second one is held.
	@GameTest(maxTicks = 30)
	public void anIdleBotGoesBackOnceTheBuildsOwnJobIsDone(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 6, 1, 1);
		colony.setRadius(7);
		AntBot idle = helper.spawn(ModEntities.ANT_BOT, new Vec3(4.05, 1, 3.5));
		AntBot builder = member(helper, colony, 1, 1, 6);
		WorkGameTest.carryCobbleStep(builder, 1);
		PyDict data = new PyDict();
		data.put("blueprint", "antcraft:cobble_step");
		colony.jobs().post("build", Memory.encode(data), builder.getUUID());
		colony.jobs().post("build", Memory.encode(data), builder.getUUID());
		BotScripts.run(builder, "a = take_job('build')\nb = take_job('build')\nprint(build('antcraft:cobble_step', "
			+ WorkGameTest.at(helper, 3, 1, 3) + ", job=a['id']).reason)\nfinish_job(a['id'])\nwait(300)\nprint('end')\n");
		failOnError(helper, builder);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(printed(builder), List.of("missing_ingredients"), "printed");
			helper.assertValueEqual(idle.blockPosition(), helper.absolutePos(new BlockPos(4, 1, 3)), "where the idle bot stands");
			helper.assertValueEqual(idle.getWaysMade(), 1, "times the idle bot made way");
		});
	}

	/**
	 * A lone builder with only the stairs in the core gets the cobble step's cobblestone itself with lib.get (#543): logs
	 * from the oak of {@link TreesGameTest}, a wooden pickaxe, then the stone; no haul job is posted.
	 */
	@GameTest(maxTicks = 1100)
	public void aLoneBuilderGathersTheCobblestoneTheCoreLacks(GameTestHelper helper) {
		TreesGameTest.plantTree(helper);
		for (int z = 4; z <= 6; z++) {
			helper.setBlock(1, 1, z, Blocks.STONE);
		}
		Colony colony = colonyAt(helper, 6, 1, 1);
		colony.setRadius(7);
		container(helper, 6, 1, 1).setItem(0, new ItemStack(Items.OAK_STAIRS));
		AntBot builder = member(helper, colony, 2, 1, 1);
		PyDict data = new PyDict();
		data.put("blueprint", "antcraft:cobble_step");
		data.put("at", pos(helper, 3, 1, 3));
		colony.jobs().post("build", Memory.encode(data), builder.getUUID());
		runDefault(helper, builder, "colony.builder");
		failOnWarning(helper, builder);
		helper.onEachTick(() -> helper.assertTrue(colony.jobs().jobs("haul").isEmpty(), "haul jobs: " + colony.jobs().jobs("haul")));
		helper.succeedWhen(() -> {
			WorkGameTest.assertCobbleStep(helper, 0);
			helper.assertTrue(colony.jobs().jobs(null).isEmpty(), "jobs left: " + colony.jobs().jobs(null));
		});
	}

	/**
	 * A builder with an empty core and nothing in the colony to make the cobble step from (no tree for a pickaxe or
	 * planks) asks a hauler for both its materials at once (#554): lib.get fails fast, not after costing every tool.
	 */
	// Measured: both posted by tick 33 alone, 50 in the colony group; before #554 neither by tick 60 alone (the
	// cobblestone's get took some 160 ticks), so maxTicks is the deadline.
	@GameTest(maxTicks = 100)
	public void aBuilderAsksAHaulerAtOnceWhereNothingCanBeGot(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 6, 1, 1);
		colony.setRadius(7);
		AntBot builder = member(helper, colony, 4, 1, 5);
		PyDict data = new PyDict();
		data.put("blueprint", "antcraft:cobble_step");
		data.put("at", pos(helper, 3, 1, 3));
		colony.jobs().post("build", Memory.encode(data), builder.getUUID());
		runDefault(helper, builder, "colony.builder");
		failOnWarning(helper, builder);
		helper.succeedWhen(() -> helper.assertValueEqual(hauls(colony), List.of("minecraft:cobblestone 2", "minecraft:oak_stairs 1"), "haul jobs"));
	}

	/**
	 * A half-built cobble step (one of its two cobblestones in place) gets and hauls only what is left (#554): the haul
	 * job asks for one cobblestone, not the blueprint's two.
	 */
	// Measured: tick 35 alone, 44 in the colony group.
	@GameTest(maxTicks = 100)
	public void aHalfBuiltBlueprintAsksOnlyForWhatIsLeft(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 6, 1, 1);
		colony.setRadius(7);
		helper.setBlock(3, 1, 3, Blocks.COBBLESTONE);
		container(helper, 6, 1, 1).setItem(0, new ItemStack(Items.OAK_STAIRS));
		AntBot builder = member(helper, colony, 4, 1, 5);
		PyDict data = new PyDict();
		data.put("blueprint", "antcraft:cobble_step");
		data.put("at", pos(helper, 3, 1, 3));
		colony.jobs().post("build", Memory.encode(data), builder.getUUID());
		runDefault(helper, builder, "colony.builder");
		failOnWarning(helper, builder);
		helper.succeedWhen(() -> helper.assertValueEqual(hauls(colony), List.of("minecraft:cobblestone 1"), "haul jobs"));
	}

	/** The colony's haul jobs as "item n", in the order posted. */
	private static List<String> hauls(Colony colony) {
		return colony.jobs().jobs("haul").stream().map(job -> {
			PyDict data = (PyDict) Memory.decode(job.data());
			return data.get("item") + " " + data.get("n");
		}).toList();
	}

	/** A haul job's item is a selector: one for {@code #minecraft:logs} brings birch logs from a chest to the core (#376). */
	@GameTest(maxTicks = 110)
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

	/**
	 * A haul job for a build that is done is moot (#557): the builder got the cobblestone another way and finished, so
	 * the hauler finds none in the colony and finishes the job without a warning, instead of reporting itself stuck.
	 */
	@GameTest(maxTicks = 140)
	public void aHaulJobForADoneBuildIsFinishedQuietly(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 6, 1, 1);
		colony.setRadius(7);
		AntBot hauler = member(helper, colony, 6, 1, 4);
		long build = colony.jobs().post("build", Memory.encode(new PyDict()), hauler.getUUID());
		colony.jobs().finish(build, hauler.getUUID());
		PyDict data = new PyDict();
		data.put("item", "minecraft:cobblestone");
		data.put("n", 2L);
		data.put("for", build);
		colony.jobs().post("haul", Memory.encode(data), hauler.getUUID());
		runDefault(helper, hauler, "colony.hauler");
		failOnWarning(helper, hauler);
		helper.succeedWhen(() -> helper.assertTrue(colony.jobs().jobs(null).isEmpty(), "jobs left: " + colony.jobs().jobs(null)));
	}

	/** A haul that finds nothing says why its take failed (#566): from an empty chest, no_item. */
	@GameTest(maxTicks = 40)
	public void aHaulThatFindsNothingWarnsWithTheReason(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 6, 1, 1);
		colony.setRadius(7);
		helper.setBlock(1, 1, 1, Blocks.CHEST);
		BlockPos chest = helper.absolutePos(new BlockPos(1, 1, 1));
		AntBot hauler = member(helper, colony, 6, 1, 4);
		PyDict data = new PyDict();
		data.put("item", "minecraft:cobblestone");
		data.put("from", new Pos(chest.getX(), chest.getY(), chest.getZ()));
		colony.jobs().post("haul", Memory.encode(data), hauler.getUUID());
		runDefault(helper, hauler, "colony.hauler");
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.warnings(hauler),
			List.of("no minecraft:cobblestone to haul in the colony: no_item"), "warnings"));
	}

	/**
	 * Importing {@code colony.common} keeps the library within the colony (#540): {@code lib.find.WHERE} is
	 * {@code in_colony}, so {@code lib.stock.chests} gives the core but not a chest just outside the colony's radius.
	 */
	@GameTest(maxTicks = 20)
	public void colonyCommonKeepsTheLibraryWithinTheColony(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 3, 1, 1);
		colony.setRadius(3);
		helper.setBlock(6, 1, 5, Blocks.CHEST);
		AntBot bot = member(helper, colony, 3, 1, 3);
		BotScripts.run(bot, """
			from colony.common import core
			import lib.stock
			print(lib.stock.chests(8) == [core()])
			""");
		failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(printed(bot).equals(List.of("True")), "printed " + printed(bot)));
	}

	/**
	 * The colony's edge is a column (#576): {@code in_colony} measures across, so a block 40 below the core within the
	 * radius is in the colony, and one beside it just past the radius is not, however high.
	 */
	@GameTest(maxTicks = 20)
	public void inColonyMeasuresTheEdgeAsAColumn(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 3, 1, 1);
		colony.setRadius(3);
		AntBot bot = member(helper, colony, 3, 1, 3);
		BotScripts.run(bot, """
			from colony.common import core, in_colony
			c = core()
			print(in_colony(c.down(40) + (2, 0, 2)), in_colony(c.down(40) + (3, 0, 0)), in_colony(c + (3, 0, 1)), in_colony(c.up(40) + (0, 0, 4)))
			""");
		failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(printed(bot).equals(List.of("True True False False")), "printed " + printed(bot)));
	}

	/** {@code fetch} takes a selector: one for {@code #minecraft:planks} takes birch planks from the core and leaves the dirt (#383). */
	@GameTest(maxTicks = 20)
	public void fetchForATagTakesMatchingItemsFromTheCore(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 3, 1, 1);
		Container core = container(helper, 3, 1, 1);
		core.setItem(0, new ItemStack(Items.DIRT, 2));
		core.setItem(1, new ItemStack(Items.BIRCH_PLANKS, 5));
		AntBot bot = member(helper, colony, 1, 1, 1);
		BotScripts.run(bot, """
			from colony.common import fetch
			print(fetch('#minecraft:planks', 3), count('birch_planks'), count('dirt'))
			""");
		failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(printed(bot).equals(List.of("True 3 0")), "printed " + printed(bot)));
	}

	/**
	 * Each default colony script on a bot in no colony says so at once and stops (#452): no error, such as the miner's
	 * {@code colony_memory.setdefault} on None, and no endless wait for a job, as the hauler and builder did.
	 */
	@GameTest(maxTicks = 20)
	public void eachColonyScriptOnABotInNoColonySaysSoAndStops(GameTestHelper helper) {
		String[] scripts = {"colony.miner", "colony.hauler", "colony.builder", "colony.farmer", "colony.lumberjack"};
		AntBot[] bots = new AntBot[scripts.length];
		for (int i = 0; i < scripts.length; i++) {
			bots[i] = helper.spawn(ModEntities.ANT_BOT, 1 + i, 1, 1);
			runDefault(helper, bots[i], scripts[i]);
		}
		failOnError(helper, bots);
		helper.succeedWhen(() -> {
			for (int i = 0; i < scripts.length; i++) {
				helper.assertTrue(printed(bots[i]).equals(List.of("I am in no colony: place a Colony Core and let me join it")),
					scripts[i] + " printed " + printed(bots[i]));
				helper.assertTrue(!bots[i].getScript().running(), scripts[i] + " still runs");
			}
		});
	}

	/**
	 * A lumberjack alone fells the colony's tree, plants a sapling where it stood and puts the logs, and all else it carries
	 * but its saplings, into the core (lib.inv's store_all, #457): those it had, less the one it plants, and those it picked
	 * up, a sapling dropped by the tree and any of the leaves it breaks. Passes at about tick 430, alone or with the other
	 * colony tests: 600 before ensure_tool stopped costing a diamond and an iron axe for its few logs (#582).
	 */
	@GameTest(maxTicks = 860)
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
		lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING, 2));
		lumberjack.getInventory().addItem(new ItemStack(Items.DIRT));
		// A sapling lying by the tree, as a leaf the bot breaks drops one at random: it picks that up and keeps it too.
		helper.spawnItem(Items.OAK_SAPLING, new BlockPos(4, 1, 6));
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		// The saplings it picks up, that one and those of the leaves it breaks to stand in them (1 in 20 each, #565).
		int[] gained = {0};
		int[] last = {2};
		helper.onEachTick(() -> {
			int now = lumberjack.getInventory().countItem(Items.OAK_SAPLING)
				+ (lumberjack.getMainHandItem().is(Items.OAK_SAPLING) ? lumberjack.getMainHandItem().getCount() : 0);
			gained[0] += Math.max(0, now - last[0]);
			last[0] = now;
		});
		helper.succeedWhen(() -> {
			helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(5, 1, 5));
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 4, "logs in the core: " + core.countItem(Items.OAK_LOG));
			helper.assertTrue(core.countItem(Items.DIRT) == 1, "dirt in the core: " + core.countItem(Items.DIRT));
			int saplings = lumberjack.getInventory().countItem(Items.OAK_SAPLING)
				+ (lumberjack.getMainHandItem().is(Items.OAK_SAPLING) ? lumberjack.getMainHandItem().getCount() : 0);
			helper.assertTrue(saplings == 2 + gained[0] - 1, "saplings the lumberjack keeps: " + saplings + ", picked up " + gained[0]);
		});
	}

	/**
	 * An empty-handed lumberjack, no axe in the core, by the giant spruce of {@link TreesGameTest#plantGiant}: it makes an
	 * axe from the logs of an oak of four logs at (6, 1, 6), not the giant's, then fells the giant whole with dirt and
	 * saplings from the core, takes back its scaffold, replants four saplings and stores the 26 logs (#541). Passes at about tick 1700.
	 */
	@GameTest(maxTicks = 3400)
	public void aLumberjackMakesAnAxeAndFellsAGiantTree(GameTestHelper helper) {
		TreesGameTest.plantGiant(helper);
		TreesGameTest.crownGiant(helper);
		helper.setBlock(6, 0, 6, Blocks.DIRT);
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(6, y, 6, Blocks.OAK_LOG);
		}
		helper.setBlock(6, 5, 6, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		Colony colony = colonyAt(helper, 1, 1, 6);
		colony.setRadius(7);
		Container core = container(helper, 1, 1, 6);
		core.setItem(0, new ItemStack(Items.DIRT, 6));
		core.setItem(1, new ItemStack(Items.SPRUCE_SAPLING, 4));
		AntBot lumberjack = member(helper, colony, 1, 1, 1);
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		failOnError(helper, lumberjack);
		helper.succeedWhen(() -> {
			TreesGameTest.assertGiantFelled(helper);
			helper.assertTrue(core.countItem(Items.SPRUCE_LOG) == 26, "spruce logs in the core: " + core.countItem(Items.SPRUCE_LOG));
			helper.assertTrue(lumberjack.getMainHandItem().is(ItemTags.AXES) || lumberjack.getInventory().hasAnyMatching(s -> s.is(ItemTags.AXES)),
				"the lumberjack has no axe");
		});
	}

	/**
	 * A lumberjack with a stone axe fells an oak of six logs, taller than it reaches from the ground, whole: it climbs on
	 * dirt from the core, takes the dirt back, replants the sapling from the core and stores the logs and the dirt (#541). Passes at about tick 225, alone or with the other colony tests.
	 */
	@GameTest(maxTicks = 450)
	public void aLumberjackFellsATreeTallerThanItReaches(GameTestHelper helper) {
		helper.setBlock(4, 0, 4, Blocks.DIRT);
		for (int y = 1; y <= 6; y++) {
			helper.setBlock(4, y, 4, Blocks.OAK_LOG);
		}
		helper.setBlock(4, 7, 4, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		Colony colony = colonyAt(helper, 1, 1, 6);
		colony.setRadius(7);
		Container core = container(helper, 1, 1, 6);
		core.setItem(0, new ItemStack(Items.DIRT, 6));
		core.setItem(1, new ItemStack(Items.OAK_SAPLING));
		AntBot lumberjack = member(helper, colony, 1, 1, 1);
		lumberjack.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		failOnError(helper, lumberjack);
		helper.succeedWhen(() -> {
			for (int x = 0; x < 8; x++) {
				for (int z = 0; z < 8; z++) {
					for (int y = 1; y < 8; y++) {
						helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(x, y, z));
						helper.assertBlockNotPresent(Blocks.DIRT, new BlockPos(x, y, z));
					}
				}
			}
			helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(4, 1, 4));
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 6, "logs in the core: " + core.countItem(Items.OAK_LOG));
			helper.assertTrue(core.countItem(Items.DIRT) == 6, "dirt in the core: " + core.countItem(Items.DIRT));
		});
	}

	/**
	 * A lumberjack with a stone axe by an oak of six logs, the core holding three dirt and three cobblestone: it climbs on
	 * the core's blocks, not on dirt dug from the colony's ground (four exposed dirt blocks in the floor), and puts them
	 * back (#553). Passes at about tick 225, alone or with the other colony tests.
	 */
	@GameTest(maxTicks = 450)
	public void aLumberjackClimbsOnTheCoresBlocksNotTheGround(GameTestHelper helper) {
		helper.setBlock(4, 0, 4, Blocks.DIRT);
		for (int y = 1; y <= 6; y++) {
			helper.setBlock(4, y, 4, Blocks.OAK_LOG);
		}
		helper.setBlock(4, 7, 4, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		BlockPos[] ground = {new BlockPos(6, 0, 1), new BlockPos(7, 0, 1), new BlockPos(6, 0, 2), new BlockPos(7, 0, 2)};
		for (BlockPos p : ground) {
			helper.setBlock(p, Blocks.DIRT);
		}
		Colony colony = colonyAt(helper, 1, 1, 6);
		colony.setRadius(7);
		Container core = container(helper, 1, 1, 6);
		core.setItem(0, new ItemStack(Items.DIRT, 3));
		core.setItem(1, new ItemStack(Items.COBBLESTONE, 3));
		core.setItem(2, new ItemStack(Items.OAK_SAPLING));
		AntBot lumberjack = member(helper, colony, 1, 1, 1);
		lumberjack.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		failOnError(helper, lumberjack);
		helper.onEachTick(() -> {
			for (BlockPos p : ground) {
				if (!helper.getBlockState(p).is(Blocks.DIRT)) {
					helper.fail("the lumberjack dug the colony's ground at " + p);
				}
			}
		});
		helper.succeedWhen(() -> {
			for (int y = 1; y < 8; y++) {
				helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(4, y, 4));
			}
			helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(4, 1, 4));
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 6, "logs in the core: " + core.countItem(Items.OAK_LOG));
			helper.assertTrue(core.countItem(Items.DIRT) == 3 && core.countItem(Items.COBBLESTONE) == 3,
				"dirt and cobblestone in the core: " + core.countItem(Items.DIRT) + " " + core.countItem(Items.COBBLESTONE));
		});
	}

	/**
	 * An empty-handed lumberjack, no axe anywhere, by three oaks of three logs: one tree is cheaper felled by hand, but the
	 * nine logs together are cheaper with a wooden axe, so it makes one, and a crafting table, from three of them and fells the rest with it (#553). Passes at about tick 860, 1130 with the other colony tests.
	 */
	@GameTest(maxTicks = 2260)
	public void aLumberjackMakesAnAxeForSeveralSmallTrees(GameTestHelper helper) {
		for (BlockPos base : List.of(new BlockPos(3, 1, 3), new BlockPos(6, 1, 2), new BlockPos(5, 1, 6))) {
			helper.setBlock(base.below(), Blocks.DIRT);
			for (int y = 0; y < 3; y++) {
				helper.setBlock(base.above(y), Blocks.OAK_LOG);
			}
			helper.setBlock(base.above(3), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		}
		Colony colony = colonyAt(helper, 1, 1, 6);
		colony.setRadius(7);
		Container core = container(helper, 1, 1, 6);
		AntBot lumberjack = member(helper, colony, 1, 1, 1);
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		failOnError(helper, lumberjack);
		helper.succeedWhen(() -> {
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 6, "logs in the core: " + core.countItem(Items.OAK_LOG));
			helper.assertTrue(lumberjack.getMainHandItem().is(ItemTags.AXES) || lumberjack.getInventory().hasAnyMatching(s -> s.is(ItemTags.AXES)),
				"the lumberjack has no axe");
		});
	}

	/**
	 * A lumberjack with a stone axe and a warped fungus by a huge crimson fungus ({@link #hugeFungus}), the core holding a
	 * crimson fungus: it is a tree, so the lumberjack fells it, plants the core's fungus on the nylium, stores the four
	 * stems and keeps the warped fungus, as it keeps saplings (#563, #582). Passes at about tick 185.
	 */
	@GameTest(maxTicks = 400)
	public void aLumberjackFellsAHugeFungusAndReplantsIt(GameTestHelper helper) {
		hugeFungus(helper);
		Colony colony = colonyAt(helper, 1, 1, 6);
		colony.setRadius(7);
		Container core = container(helper, 1, 1, 6);
		core.setItem(0, new ItemStack(Items.CRIMSON_FUNGUS));
		AntBot lumberjack = member(helper, colony, 1, 1, 1);
		lumberjack.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		lumberjack.getInventory().addItem(new ItemStack(Items.WARPED_FUNGUS));
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		failOnError(helper, lumberjack);
		helper.succeedWhen(() -> {
			if (!helper.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.CRIMSON_FUNGUS) || core.countItem(Items.CRIMSON_STEM) != 4
				|| core.countItem(Items.WARPED_FUNGUS) != 0 || carried(lumberjack, Items.WARPED_FUNGUS) != 1) {
				helper.fail(fungusState(helper, core, lumberjack));
			}
		});
	}

	/**
	 * A lumberjack with a stone axe, an oak sapling and a warped fungus by a huge crimson fungus ({@link #hugeFungus}), no
	 * crimson fungus anywhere: no sapling grows on nylium, so it plants the warped fungus there and keeps the sapling (#582).
	 * Passes at about tick 185.
	 */
	@GameTest(maxTicks = 400)
	public void aLumberjackPlantsAnyFungusNotASaplingOnNylium(GameTestHelper helper) {
		hugeFungus(helper);
		Colony colony = colonyAt(helper, 1, 1, 6);
		colony.setRadius(7);
		Container core = container(helper, 1, 1, 6);
		AntBot lumberjack = member(helper, colony, 1, 1, 1);
		lumberjack.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		lumberjack.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
		lumberjack.getInventory().addItem(new ItemStack(Items.WARPED_FUNGUS));
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		failOnError(helper, lumberjack);
		helper.succeedWhen(() -> {
			if (!helper.getBlockState(new BlockPos(5, 1, 5)).is(Blocks.WARPED_FUNGUS) || core.countItem(Items.CRIMSON_STEM) != 4
				|| carried(lumberjack, Items.OAK_SAPLING) != 1) {
				helper.fail(fungusState(helper, core, lumberjack));
			}
		});
	}

	/** How many of item the bot carries, in its hand too. */
	private static int carried(AntBot bot, Item item) {
		return bot.getInventory().countItem(item) + (bot.getMainHandItem().is(item) ? bot.getMainHandItem().getCount() : 0);
	}

	/**
	 * A huge crimson fungus at (5, 1, 5): four stems on crimson nylium under nether wart blocks. It keeps the nylium from
	 * turning to netherrack under the stem: nylium under a block that keeps out all light, the bottom stem, does so on a
	 * random tick (NyliumBlock.randomTick), in about 1 run in 10 before the lumberjack mines that stem, and no fungus grows
	 * on netherrack (#575). A stem on netherrack TreesGameTest aStemOnNetherrackIsATree checks (#582).
	 */
	private static void hugeFungus(GameTestHelper helper) {
		helper.setBlock(5, 0, 5, Blocks.CRIMSON_NYLIUM);
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(5, y, 5, Blocks.CRIMSON_STEM);
		}
		helper.setBlock(5, 5, 5, Blocks.NETHER_WART_BLOCK);
		for (Direction d : Direction.Plane.HORIZONTAL) {
			helper.setBlock(new BlockPos(5, 4, 5).relative(d), Blocks.NETHER_WART_BLOCK);
		}
		helper.onEachTick(() -> {
			if (helper.getBlockState(new BlockPos(5, 0, 5)).is(Blocks.NETHERRACK)) {
				helper.setBlock(5, 0, 5, Blocks.CRIMSON_NYLIUM);
			}
		});
	}

	/** What a huge fungus test fails with: the block at (5, 1, 5) and its ground, the core, the bot and its log. */
	private static String fungusState(GameTestHelper helper, Container core, AntBot lumberjack) {
		return "at (5, 1, 5): " + helper.getBlockState(new BlockPos(5, 1, 5)) + " on " + helper.getBlockState(new BlockPos(5, 0, 5))
			+ ", core: " + contents(core)
			+ ", bot at " + TestPositions.relative(helper, lumberjack.blockPosition()).toShortString() + " holding "
			+ lumberjack.getMainHandItem() + ", inventory " + contents(lumberjack.getInventory())
			+ ", log: " + BotScripts.log(lumberjack, Output.Level.DEBUG);
	}

	/**
	 * A lumberjack with a stone axe by what is left of an oak whose bottom logs were taken: three logs at (4, 5..7, 4) over
	 * air, leaves just above the area, the core holding six dirt. The top log is out of reach from the ground, so it climbs
	 * on the core's dirt, counted from the ground rather than from the remnant's lowest log, fells all three, takes the
	 * dirt back and stores the logs and the dirt (#582). Passes at about tick 270.
	 */
	@GameTest(maxTicks = 540)
	public void aLumberjackClimbsToFellARemnant(GameTestHelper helper) {
		for (int y = 5; y <= 7; y++) {
			helper.setBlock(4, y, 4, Blocks.OAK_LOG);
		}
		helper.setBlock(4, 8, 4, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		Colony colony = colonyAt(helper, 1, 1, 6);
		colony.setRadius(7);
		Container core = container(helper, 1, 1, 6);
		core.setItem(0, new ItemStack(Items.DIRT, 6));
		AntBot lumberjack = member(helper, colony, 1, 1, 1);
		lumberjack.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		failOnError(helper, lumberjack);
		helper.succeedWhen(() -> {
			for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
				helper.assertBlockNotPresent(Blocks.OAK_LOG, p);
				helper.assertBlockNotPresent(Blocks.DIRT, p);
			}
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 3 && core.countItem(Items.DIRT) == 6,
				"logs and dirt in the core: " + core.countItem(Items.OAK_LOG) + " " + core.countItem(Items.DIRT));
		});
	}

	/**
	 * A lumberjack with a stone axe by an oak shaped like a U, in the tall area: two trunks of twelve logs at x 1 and 6,
	 * joined by four logs along the floor, only (1, 1, 4) on dirt, so that is the base. Each trunk's top is out of reach
	 * from the ground and from the other trunk, so it climbs at each on its own pillar; the core holds 20 dirt. ready()
	 * gets scaffold for both pillars, not the highest only, so fell_whole fells the whole tree in one go: no log may reach
	 * the core while one still stands (#596). With the highest pillar's scaffold only it stored 27 logs and left the top of
	 * the trunk it climbed second. All 20 dirt come back to the core, also one its walks placed while it took the scaffold
	 * back (#614). With 4 stands, not 14, it passes at about tick 960-980 (#614), 1745 before.
	 */
	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 1900)
	public void aLumberjackClimbsOnAPillarAtEachOfTwoStands(GameTestHelper helper) {
		helper.setBlock(1, 0, 4, Blocks.DIRT);
		for (int y = 1; y <= 12; y++) {
			helper.setBlock(1, y, 4, Blocks.OAK_LOG);
			helper.setBlock(6, y, 4, Blocks.OAK_LOG);
		}
		for (int x = 2; x <= 5; x++) {
			helper.setBlock(x, 1, 4, Blocks.OAK_LOG);
		}
		helper.setBlock(1, 13, 4, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		helper.setBlock(6, 13, 4, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		Colony colony = colonyAt(helper, 1, 1, 6);
		colony.setRadius(7);
		Container core = container(helper, 1, 1, 6);
		core.setItem(0, new ItemStack(Items.DIRT, 20));
		AntBot lumberjack = member(helper, colony, 1, 1, 1);
		lumberjack.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		failOnError(helper, lumberjack);
		helper.onEachTick(() -> {
			if (core.countItem(Items.OAK_LOG) > 0) {
				for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 7, 15, 7)) {
					if (helper.getBlockState(p).is(Blocks.OAK_LOG)) {
						helper.fail("logs in the core while the log at " + p.toShortString() + " stands: "
							+ core.countItem(Items.OAK_LOG) + ", log: " + BotScripts.log(lumberjack, Output.Level.DEBUG));
					}
				}
			}
		});
		helper.succeedWhen(() -> {
			for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 7, 15, 7)) {
				helper.assertBlockNotPresent(Blocks.OAK_LOG, p);
			}
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 28, "logs in the core: " + core.countItem(Items.OAK_LOG));
			// Every block of scaffold comes back, those its own walks placed while it took the scaffold back too (#614).
			StringBuilder left = new StringBuilder();
			for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 7, 15, 7)) {
				if (helper.getBlockState(p).is(Blocks.DIRT)) {
					left.append(' ').append(p.toShortString());
				}
			}
			helper.assertTrue(core.countItem(Items.DIRT) == 20, "dirt in the core: " + core.countItem(Items.DIRT) + ", left at" + left);
		});
	}

	/**
	 * A lumberjack with a stone axe by an oak of seven logs, the top one out of reach from the ground (its leaves just above
	 * the area), the core holding no blocks to climb on and a chest by the lumberjack six dirt: it borrows the chest's dirt to climb on, takes it back from around the tree and gives it back to
	 * the chest, not the core (#563). Passes at about tick 280.
	 */
	@GameTest(maxTicks = 600)
	public void aLumberjackBorrowsBlocksToClimbOnFromAChest(GameTestHelper helper) {
		helper.setBlock(4, 0, 4, Blocks.DIRT);
		for (int y = 1; y <= 7; y++) {
			helper.setBlock(4, y, 4, Blocks.OAK_LOG);
		}
		helper.setBlock(4, 8, 4, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		Colony colony = colonyAt(helper, 1, 1, 6);
		colony.setRadius(7);
		Container core = container(helper, 1, 1, 6);
		core.setItem(0, new ItemStack(Items.OAK_SAPLING));
		helper.setBlock(4, 1, 1, Blocks.CHEST);
		Container chest = container(helper, 4, 1, 1);
		chest.setItem(0, new ItemStack(Items.DIRT, 6));
		AntBot lumberjack = member(helper, colony, 1, 1, 1);
		lumberjack.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		failOnError(helper, lumberjack);
		helper.succeedWhen(() -> {
			for (int y = 1; y <= 7; y++) {
				helper.assertBlockNotPresent(Blocks.OAK_LOG, new BlockPos(4, y, 4));
			}
			helper.assertBlockPresent(Blocks.OAK_SAPLING, new BlockPos(4, 1, 4));
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 7, "logs in the core: " + core.countItem(Items.OAK_LOG));
			helper.assertTrue(chest.countItem(Items.DIRT) == 6 && core.countItem(Items.DIRT) == 0,
				"dirt in the chest and the core: " + chest.countItem(Items.DIRT) + " " + core.countItem(Items.DIRT));
		});
	}

	/**
	 * A lumberjack with a stone axe, in a wide area, by an acacia as the game grows one: a trunk of three logs at
	 * (12, 1..3, 12) on dirt that bends, its logs touching only at a corner, east up to (15, 6, 12) and north-west up to
	 * (10, 5, 10), leaves only around those two ends. The core holds six dirt and an acacia sapling: it fells all eight
	 * logs, takes its scaffold back, replants the sapling at the trunk's base and stores the logs (#563). Passes at about tick 380, 420 with the other colony tests.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 900)
	public void aLumberjackFellsAWideAcacia(GameTestHelper helper) {
		helper.setBlock(12, 0, 12, Blocks.DIRT);
		List<BlockPos> logs = List.of(new BlockPos(12, 1, 12), new BlockPos(12, 2, 12), new BlockPos(12, 3, 12),
			new BlockPos(13, 4, 12), new BlockPos(14, 5, 12), new BlockPos(15, 6, 12), new BlockPos(11, 4, 11), new BlockPos(10, 5, 10));
		for (BlockPos p : logs) {
			helper.setBlock(p, Blocks.ACACIA_LOG);
		}
		for (BlockPos end : List.of(new BlockPos(15, 6, 12), new BlockPos(10, 5, 10))) {
			for (BlockPos p : BlockPos.betweenClosed(end.offset(-2, 0, -2), end.offset(2, 1, 2))) {
				boolean far = Math.abs(p.getX() - end.getX()) == 2 || Math.abs(p.getZ() - end.getZ()) == 2;
				if (!(far && p.getY() == end.getY()) && helper.getBlockState(p).isAir()) {
					helper.setBlock(p, Blocks.ACACIA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
				}
			}
		}
		Colony colony = colonyAt(helper, 4, 1, 12);
		colony.setRadius(15);
		Container core = container(helper, 4, 1, 12);
		core.setItem(0, new ItemStack(Items.DIRT, 6));
		core.setItem(1, new ItemStack(Items.ACACIA_SAPLING));
		AntBot lumberjack = member(helper, colony, 2, 1, 12);
		lumberjack.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		runDefault(helper, lumberjack, "colony.lumberjack");
		failOnWarning(helper, lumberjack);
		failOnError(helper, lumberjack);
		helper.succeedWhen(() -> {
			for (BlockPos p : logs) {
				helper.assertBlockNotPresent(Blocks.ACACIA_LOG, p);
			}
			for (BlockPos p : BlockPos.betweenClosed(0, 1, 0, 23, 7, 23)) {
				helper.assertBlockNotPresent(Blocks.DIRT, p);
			}
			helper.assertBlockPresent(Blocks.ACACIA_SAPLING, new BlockPos(12, 1, 12));
			helper.assertTrue(core.countItem(Items.ACACIA_LOG) == 8, "logs in the core: " + core.countItem(Items.ACACIA_LOG));
			helper.assertTrue(core.countItem(Items.DIRT) == 6, "dirt in the core: " + core.countItem(Items.DIRT));
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
	 * With no hoe in the core, a farmer by a small oak fells it, makes a hoe with lib.get and tills and plants its farm
	 * (#544).
	 */
	@GameTest(maxTicks = 1100)
	public void aFarmerWithNoHoeMakesOne(GameTestHelper helper) {
		helper.setBlock(7, 0, 1, Blocks.DIRT);
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(7, y, 1, Blocks.OAK_LOG);
		}
		helper.setBlock(7, 4, 1, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		List<BlockPos> spots = farm(helper, false, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7), 2,
				new ItemStack(Items.WHEAT_SEEDS, 24));
		AntBot farmer = helper.findOneEntity(ModEntities.ANT_BOT);
		helper.succeedWhen(() -> {
			for (BlockPos spot : spots) {
				helper.assertBlockPresent(Blocks.FARMLAND, spot);
				helper.assertBlockPresent(Blocks.WHEAT, spot.above());
			}
			helper.assertTrue(farmer.getInventory().countItem(Items.WOODEN_HOE) + (farmer.getMainHandItem().is(Items.WOODEN_HOE) ? 1 : 0) > 0,
					"the farmer has no wooden hoe");
		});
	}

	private static List<BlockPos> farm(GameTestHelper helper, BlockState ripe, int ripeSpots, ItemStack... items) {
		return farm(helper, true, ripe, ripeSpots, items);
	}

	/**
	 * Lays a 5 by 5 farm of dirt with water in its middle at (1, 0, 1), with {@code ripe} on farmland on its first
	 * {@code ripeSpots} work spots (row by row from (1, 0, 1)), notes it in colony_memory["buildings"] of a colony with its
	 * core at (7, 1, 7), puts a wooden hoe (where {@code hoe}) and {@code items} in the core and starts colony.farmer; gives
	 * the work spots.
	 */
	private static List<BlockPos> farm(GameTestHelper helper, boolean hoe, BlockState ripe, int ripeSpots, ItemStack... items) {
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
		if (hoe) {
			core.setItem(0, new ItemStack(Items.WOODEN_HOE));
		}
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
	// colony.miner (#373). The area is stone up to y 5 and the core stands on it at (0, 6, 0); mineshaft puts the shaft's top
	// at (3, 6, 3), the first spot beside the core, its column down from the hatch at y 5 to its one level at y 1, with the
	// landing at (4, 1, 3), the chest north of it, a hall east to (7, 1, 3) and from its end a branch of 2 north and south.

	/** About twice the slowest pass measured (618 ticks). */
	private static final int MINER_TICKS = 1300;
	private static final List<BlockPos> MINER_IRON = List.of(new BlockPos(6, 1, 4), new BlockPos(5, 2, 2));

	/**
	 * Fills the area with stone up to y 5, plants {@link #MINER_IRON} in the walls of the hall, founds a colony at (0, 6, 0)
	 * whose colony_memory["mineshaft"] asks for a small shaft (one level at y 1, a branch each side of 2), with a stone pickaxe
	 * for each of {@code miners} positions, ladders, a trapdoor, torches and a chest in the core, and runs colony.miner on a member at each.
	 */
	private static List<AntBot> miners(GameTestHelper helper, Colony colony, BlockPos... at) {
		Container core = (Container) Objects.requireNonNull(helper.getLevel().getBlockEntity(colony.core()));
		core.setItem(0, new ItemStack(Items.LADDER, 8));
		core.setItem(1, new ItemStack(Items.TORCH, 8));
		core.setItem(2, new ItemStack(Items.CHEST, 2));
		core.setItem(3, new ItemStack(Items.OAK_TRAPDOOR));
		for (int i = 0; i < at.length; i++) {
			core.setItem(4 + i, new ItemStack(Items.STONE_PICKAXE));
		}
		List<AntBot> bots = new ArrayList<>();
		for (BlockPos pos : at) {
			bots.add(member(helper, colony, pos.getX(), pos.getY(), pos.getZ()));
		}
		bots.forEach(bot -> runDefault(helper, bot, "colony.miner"));
		failOnWarning(helper, bots.toArray(AntBot[]::new));
		return bots;
	}

	private static Colony mineColony(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 5, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		MINER_IRON.forEach(pos -> helper.setBlock(pos, Blocks.IRON_ORE));
		return mineColony(helper, 0, 1);
	}

	/**
	 * A colony at (o, 6, o) whose colony_memory["mineshaft"] asks for a level at each of {@code levels} (test-area heights).
	 * Its radius of 10 holds the whole 8x8 area from the core at its corner, so the veins of the halls and branches are in
	 * the colony (lib.mine keeps to its edge, #576).
	 */
	private static Colony mineColony(GameTestHelper helper, int o, int... levels) {
		Colony colony = colonyAt(helper, o, 6, o);
		colony.setRadius(10);
		PyDict shaft = new PyDict();
		PyList heights = new PyList();
		for (int y : levels) {
			heights.items.add((long) helper.absolutePos(new BlockPos(0, y, 0)).getY());
		}
		shaft.put("levels", heights);
		shaft.put("branches", 1L);
		shaft.put("length", 2L);
		colony.memory().put("mineshaft", shaft);
		return colony;
	}

	/** The top of the colony's shaft in colony_memory["mineshaft"], or null before one is picked. */
	private static @Nullable String sharedTop(Colony colony) {
		Object top = colony.memory().get("mineshaft") instanceof PyDict shaft ? shaft.get("top") : null;
		return top == null ? null : Values.repr(top);
	}

	/** Succeeds once the shaft at (3, 6, 3) is dug to y 1, laddered and shut, its hall mined and the iron in the core. */
	private static void succeedWhenMined(GameTestHelper helper, Colony colony, Runnable more) {
		Container core = container(helper, 0, 6, 0);
		helper.succeedWhen(() -> {
			helper.assertTrue(Values.repr(pos(helper, 3, 6, 3)).equals(sharedTop(colony)), "shaft top " + sharedTop(colony));
			for (int y = 1; y <= 4; y++) {
				helper.assertBlockPresent(Blocks.LADDER, new BlockPos(3, y, 3));
			}
			helper.assertBlockPresent(Blocks.OAK_TRAPDOOR, new BlockPos(3, 5, 3));
			for (int x = 5; x <= 7; x++) {
				helper.assertTrue(helper.getBlockState(new BlockPos(x, 2, 3)).isAir(), "hall not dug at x " + x);
			}
			helper.assertTrue(core.countItem(Items.RAW_IRON) == MINER_IRON.size(), "raw iron in the core: " + core.countItem(Items.RAW_IRON));
			more.run();
		});
	}

	/** A miner alone digs the colony's shaft with what the core holds, mines its hall and brings the iron to the core. */
	@GameTest(maxTicks = MINER_TICKS)
	public void aMinerDigsTheShaftMinesTheHallAndBringsTheIronToTheCore(GameTestHelper helper) {
		Colony colony = mineColony(helper);
		miners(helper, colony, new BlockPos(1, 6, 1));
		succeedWhenMined(helper, colony, () -> { });
	}

	/**
	 * A miner with an empty core and no pickaxe gets one and a torch with lib.get (#542): an oak on the stone and a coal
	 * ore in it give the planks, sticks and coal, the stone the cobblestone. It carries the ladders, trapdoor and chest, so
	 * what it gets is the pickaxe and the torch; it digs the shaft at (3, 6, 3) down to its level at y 1, the torch on the
	 * landing at (4, 1, 3) and the chest north of it.
	 */
	@GameTest(maxTicks = 2800)
	public void aMinerWithAnEmptyCoreGetsAPickaxeAndTorchesAndDigsTheShaft(GameTestHelper helper) {
		Colony colony = mineColony(helper);
		TreesGameTest.plantTree(helper, new BlockPos(1, 6, 5), Blocks.OAK_LOG, Blocks.OAK_LEAVES);
		helper.setBlock(6, 5, 1, Blocks.COAL_ORE);
		AntBot bot = member(helper, colony, 1, 6, 1);
		bot.getInventory().addItem(new ItemStack(Items.LADDER, 4));
		bot.getInventory().addItem(new ItemStack(Items.OAK_TRAPDOOR));
		bot.getInventory().addItem(new ItemStack(Items.CHEST));
		runDefault(helper, bot, "colony.miner");
		failOnWarning(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(Values.repr(pos(helper, 3, 6, 3)).equals(sharedTop(colony)), "shaft top " + sharedTop(colony));
			for (int y = 1; y <= 4; y++) {
				helper.assertBlockPresent(Blocks.LADDER, new BlockPos(3, y, 3));
			}
			helper.assertBlockPresent(Blocks.OAK_TRAPDOOR, new BlockPos(3, 5, 3));
			helper.assertBlockPresent(Blocks.TORCH, new BlockPos(4, 1, 3));
			helper.assertBlockPresent(Blocks.CHEST, new BlockPos(4, 1, 2));
		});
	}

	/**
	 * A miner mines a level with the pickaxe tier its ores need (#559): colony_memory["mineshaft"]["tiers"] says the level
	 * at y 1 takes iron, as lib.mine.tier_at says of a deep level, and lodestone, which the game test tags put in
	 * needs_iron_tool and c:ores, stands in the south branch at (7, 2, 4). The core holds a stone pickaxe, which the shaft
	 * takes, and an iron one after it, which the level takes; the lodestone is mined. About twice its pass (854 ticks).
	 */
	@GameTest(maxTicks = 1700)
	public void aMinerMinesADeepLevelWithThePickaxeItsOresNeed(GameTestHelper helper) {
		Colony colony = mineColony(helper);
		helper.setBlock(7, 2, 4, Blocks.LODESTONE);
		PyDict tiers = new PyDict();
		tiers.put((long) helper.absolutePos(new BlockPos(0, 1, 0)).getY(), "iron");
		((PyDict) colony.memory().get("mineshaft")).put("tiers", tiers);
		AntBot bot = miners(helper, colony, new BlockPos(1, 6, 1)).getFirst();
		container(helper, 0, 6, 0).setItem(5, new ItemStack(Items.IRON_PICKAXE));
		succeedWhenMined(helper, colony, () -> {
			helper.assertTrue(helper.getBlockState(new BlockPos(7, 2, 4)).isAir(), "lodestone left in the branch");
			helper.assertTrue(bot.getInventory().countItem(Items.IRON_PICKAXE) == 1, "no iron pickaxe carried");
		});
	}

	/**
	 * With spruce logs in the core and no trapdoor, the miner makes the hatch a spruce trapdoor (#559), not an oak one,
	 * which it has no wood for. About twice its pass (362 ticks).
	 */
	@GameTest(maxTicks = 750)
	public void aMinersHatchIsOfTheWoodTheColonyHas(GameTestHelper helper) {
		Colony colony = mineColony(helper);
		miners(helper, colony, new BlockPos(1, 6, 1));
		container(helper, 0, 6, 0).setItem(3, new ItemStack(Items.SPRUCE_LOG, 4));
		helper.succeedWhen(() -> helper.assertBlockPresent(Blocks.SPRUCE_TRAPDOOR, new BlockPos(3, 5, 3)));
	}

	/**
	 * Two miners started on the same tick share the colony's one shaft (#393): neither picks a second site, one shaft job
	 * is posted, and with a level each both take a mine job and mine a round. In a wide area, as the two halls go opposite
	 * ways: stone up to y 5 from (0, 0, 0) to (15, 5, 15), the core at (4, 6, 4), the shaft's top at (7, 6, 7), the hall of
	 * level 3 east from (9, 3, 7) to (11, 3, 7) and that of level 1 west from (5, 1, 7) to (3, 1, 7).
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 1000)
	public void twoMinersShareOneShaft(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 15, 5, 15)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		Colony colony = mineColony(helper, 4, 1, 3);
		List<AntBot> bots = miners(helper, colony, new BlockPos(5, 6, 5), new BlockPos(5, 6, 10));
		String[] first = new String[1];
		Set<Long> shaftJobs = new HashSet<>();
		Set<UUID> mined = new HashSet<>();
		helper.onEachTick(() -> {
			colony.jobs().jobs("shaft").forEach(job -> shaftJobs.add(job.id()));
			colony.jobs().jobs("mine").forEach(job -> job.taker().ifPresent(mined::add));
			if (shaftJobs.size() > 1) {
				helper.fail("shaft jobs posted: " + shaftJobs);
			}
			String top = sharedTop(colony);
			if (first[0] == null) {
				first[0] = top;
			} else if (!first[0].equals(top)) {
				helper.fail("a second site was picked: " + first[0] + ", then " + top);
			}
			for (AntBot bot : bots) {
				if (bot.getMemory().get("shaft") instanceof PyDict kept && !Values.repr(kept.get("top")).equals(first[0])) {
					helper.fail("a miner keeps another shaft: " + Values.repr(kept));
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(Values.repr(pos(helper, 7, 6, 7)).equals(sharedTop(colony)), "shaft top " + sharedTop(colony));
			for (int x = 3; x <= 5; x++) {
				helper.assertTrue(helper.getBlockState(new BlockPos(x, 2, 7)).isAir(), "level 1 hall not dug at x " + x);
				helper.assertTrue(helper.getBlockState(new BlockPos(x + 6, 4, 7)).isAir(), "level 3 hall not dug at x " + (x + 6));
			}
			helper.assertTrue(shaftJobs.size() == 1, "shaft jobs posted: " + shaftJobs);
			for (AntBot bot : bots) {
				helper.assertTrue(mined.contains(bot.getUUID()), "a miner never took a mine job");
			}
		});
	}

	/**
	 * A miner does not search for a site while another member holds the colony's "shaft_site" claim (#393), and picks it
	 * once the claim goes back to the board.
	 */
	@GameTest(maxTicks = 130)
	public void aMinerWaitsWhileAnotherClaimsTheSite(GameTestHelper helper) {
		Colony colony = mineColony(helper);
		// The holder's claim goes back to the board when its script ends.
		AntBot holder = member(helper, colony, 6, 6, 6);
		BotScripts.run(holder, "wait(50)\n");
		colony.jobs().post("shaft_site", Memory.encode(PyNone.NONE), holder.getUUID());
		colony.jobs().take("shaft_site", holder.getUUID());
		miners(helper, colony, new BlockPos(1, 6, 1));
		helper.onEachTick(() -> {
			if (helper.getTick() == 45) {
				helper.assertTrue(sharedTop(colony) == null, "picked while claimed: " + sharedTop(colony));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(helper.getTick() > 45, "claimed");
			helper.assertTrue(Values.repr(pos(helper, 3, 6, 3)).equals(sharedTop(colony)), "shaft top " + sharedTop(colony));
			helper.assertTrue(colony.jobs().jobs("shaft_site").isEmpty(), "claims left " + colony.jobs().jobs("shaft_site"));
			helper.assertTrue(colony.jobs().jobs("shaft").size() == 1, "shaft jobs " + colony.jobs().jobs("shaft"));
		});
	}

	/**
	 * A reflex that takes over while the miner's {@code mineshaft} search runs over several ticks pauses it (#410, #419):
	 * the miner still gets its site and digs the shaft, rather than stopping on an {@code interrupted} result. As
	 * ShaftGameTest.aReflexDuringTheSearchDoesNotStopShaft, the test spends the tick's block search budget until the
	 * search runs, then lights fire on netherrack under the bot; the budget is the server's, so it runs {@code alone}.
	 */
	// Measured: passes at tick 242 alone.
	@GameTest(environment = "antcraft-gametest:alone", maxTicks = 500)
	public void aReflexDuringTheMinersSiteSearchDoesNotStopTheShaft(GameTestHelper helper) {
		Colony colony = mineColony(helper);
		helper.setBlock(1, 5, 1, Blocks.NETHERRACK);
		AntBot bot = miners(helper, colony, new BlockPos(1, 6, 1)).getFirst();
		failOnError(helper, bot);
		boolean[] fire = {false};
		helper.onEachTick(() -> {
			if (fire[0]) {
				return;
			}
			ScriptRun run = bot.getScript();
			String action = run == null ? null : run.action();
			if (action != null && action.startsWith("mineshaft")) {
				fire[0] = true;
				helper.setBlock(1, 6, 1, Blocks.FIRE);
			}
			// The bot's next tick finds no blocks left to search, whether its search started yet or not.
			SenseApi.SCAN_BUDGET.spend(helper.getLevel().getGameTime() + 1, SenseApi.BLOCKS_PER_TICK);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(fire[0], "the search never ran over several ticks");
			helper.assertTrue(Values.repr(pos(helper, 3, 6, 3)).equals(sharedTop(colony)), "shaft top " + sharedTop(colony));
			for (int y = 1; y <= 4; y++) {
				helper.assertBlockPresent(Blocks.LADDER, new BlockPos(3, y, 3));
			}
			helper.assertBlockPresent(Blocks.OAK_TRAPDOOR, new BlockPos(3, 5, 3));
			helper.assertTrue(colony.jobs().jobs("shaft").isEmpty(), "shaft jobs " + colony.jobs().jobs("shaft"));
		});
	}

	/** {@code x y z} of {@code pos} in the test's area, as a command takes it. */
	private static String at(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return pos.getX() + " " + pos.getY() + " " + pos.getZ();
	}

	/** {@code /antcraft bot join} by its owner (#459) makes a bot in no colony a member: {@code colony()} then gives it. */
	@GameTest(maxTicks = 20)
	public void aBotInNoColonyJoinsOneByCommand(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 1, 1, 1);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);
		colony.setOwner(owner.getUUID());

		List<String> answer = BotScripts.commandAsPlayer(helper, owner, "antcraft bot " + bot.getStringUUID() + " join " + at(helper, 1, 1, 1));
		helper.assertTrue(answer.equals(List.of("commands.antcraft.bot.join[1, " + colony.core().toShortString() + "]")), "answer " + answer);
		helper.assertTrue(colony.members().contains(bot.getUUID()), "not a member: " + colony.members());
		BotScripts.run(bot, "c = colony()['core']\nprint((c.x, c.y, c.z) == (" + at(helper, 1, 1, 1).replace(" ", ", ") + "))\n");
		failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertTrue(printed(bot).equals(List.of("True")), "printed " + printed(bot)));
	}

	/** A member moved to another colony by {@code join} (#459) leaves the first, where its taken job is open again. */
	@GameTest(maxTicks = 20)
	public void aMemberJoiningAnotherColonyLeavesTheFirstAndItsJobsOpen(GameTestHelper helper) {
		Colony first = colonyAt(helper, 1, 1, 1);
		Colony second = colonyAt(helper, 6, 1, 6);
		AntBot bot = member(helper, first, 3, 1, 3);
		UUID id = bot.getUUID();
		long job = first.jobs().post("haul", new CompoundTag(), id);
		helper.assertTrue(first.jobs().take("haul", id).isPresent(), "job not taken");

		BotScripts.command(helper, "antcraft bot " + id + " join " + at(helper, 6, 1, 6));
		helper.assertTrue(second.id().equals(bot.getColonyId()) && second.members().contains(id), "not in the second colony");
		helper.assertFalse(first.members().contains(id), "still in the first: " + first.members());
		helper.assertTrue(first.jobs().jobs("haul").stream().anyMatch(j -> j.id() == job && j.taker().isEmpty()),
			"job not open: " + first.jobs().jobs("haul"));
		helper.succeed();
	}

	/**
	 * A bot in the Nether joins a colony whose core is in the Overworld by naming the dimension after the core's position
	 * (#465); without it, {@code join} looks in the Nether, the command's level, and finds no core. The bot is moved as a
	 * portal moves it but not added to the Nether, so it runs the command as {@code @s}.
	 */
	@GameTest(maxTicks = 20)
	public void aBotInTheNetherJoinsAColonyInTheOverworldByNamingTheDimension(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 1, 1, 1);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ServerLevel nether = Objects.requireNonNull(helper.getLevel().getServer().getLevel(Level.NETHER));
		AntBot moved = Objects.requireNonNull(ModEntities.ANT_BOT.create(nether, EntitySpawnReason.DIMENSION_TRAVEL));
		moved.restoreFrom(bot);
		bot.remove(Entity.RemovalReason.CHANGED_DIMENSION);

		List<String> here = BotScripts.command(helper, moved, "execute in minecraft:the_nether run antcraft bot @s join " + at(helper, 1, 1, 1));
		helper.assertTrue(moved.getColonyId() == null, "joined without the dimension");
		helper.assertTrue(here.equals(List.of("No Colony Core at " + colony.core().toShortString())), "answer " + here);
		List<String> answer = BotScripts.command(helper, moved,
			"execute in minecraft:the_nether run antcraft bot @s join " + at(helper, 1, 1, 1) + " minecraft:overworld");
		helper.assertTrue(answer.equals(List.of("commands.antcraft.bot.join[1, " + colony.core().toShortString() + "]")), "answer " + answer);
		helper.assertTrue(colony.id().equals(moved.getColonyId()) && colony.members().contains(moved.getUUID()), "not a member: " + colony.members());
		helper.succeed();
	}

	/** {@code /antcraft bot leave} (#459) takes a bot out of its colony. */
	@GameTest(maxTicks = 20)
	public void leaveTakesABotOutOfItsColony(GameTestHelper helper) {
		Colony colony = colonyAt(helper, 1, 1, 1);
		AntBot bot = member(helper, colony, 3, 1, 3);
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);

		List<String> answer = BotScripts.commandAsPlayer(helper, owner, "antcraft bot " + bot.getStringUUID() + " leave");
		helper.assertTrue(answer.equals(List.of("commands.antcraft.bot.leave[1]")), "answer " + answer);
		helper.assertTrue(bot.getColonyId() == null, "still has colony " + bot.getColonyId());
		helper.assertFalse(colony.members().contains(bot.getUUID()), "still listed: " + colony.members());
		helper.succeed();
	}

	/**
	 * {@code join} (#459) is refused for a colony the player may not change, for another player's bot, and for a position
	 * with no Colony Core; {@code leave} for another player's bot.
	 */
	@GameTest(maxTicks = 20)
	public void joinAndLeaveAreRefusedWhereThePlayerMayNot(GameTestHelper helper) {
		Colony theirs = colonyAt(helper, 1, 1, 1);
		theirs.setOwner(UUID.randomUUID());
		Colony mine = colonyAt(helper, 6, 1, 6);
		AntBot own = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BotScripts.RecordingPlayer player = BotScripts.owner(helper, own);
		mine.setOwner(player.getUUID());
		AntBot other = member(helper, theirs, 5, 1, 1);
		other.setOwner(UUID.randomUUID());

		List<String> notTheirs = BotScripts.commandAsPlayer(helper, player, "antcraft bot " + own.getStringUUID() + " join " + at(helper, 1, 1, 1));
		helper.assertTrue(own.getColonyId() == null, "joined a colony the player may not change");
		helper.assertTrue(notTheirs.equals(List.of("You may not change the colony at " + theirs.core().toShortString())),
			"answer " + notTheirs);
		List<String> notOwn = BotScripts.commandAsPlayer(helper, player, "antcraft bot " + other.getStringUUID() + " join " + at(helper, 6, 1, 6));
		helper.assertTrue(theirs.id().equals(other.getColonyId()), "moved another player's bot");
		helper.assertTrue(notOwn.size() == 1 && notOwn.getFirst().endsWith(" is not your bot"), "answer " + notOwn);
		List<String> notOwnLeave = BotScripts.commandAsPlayer(helper, player, "antcraft bot " + other.getStringUUID() + " leave");
		helper.assertTrue(theirs.id().equals(other.getColonyId()), "took another player's bot out");
		helper.assertTrue(notOwnLeave.size() == 1 && notOwnLeave.getFirst().endsWith(" is not your bot"), "answer " + notOwnLeave);
		List<String> noCore = BotScripts.commandAsPlayer(helper, player, "antcraft bot " + own.getStringUUID() + " join " + at(helper, 4, 1, 4));
		helper.assertTrue(own.getColonyId() == null, "joined at no core");
		helper.assertTrue(noCore.equals(List.of("No Colony Core at " + helper.absolutePos(new BlockPos(4, 1, 4)).toShortString())),
			"answer " + noCore);
		helper.succeed();
	}
}
