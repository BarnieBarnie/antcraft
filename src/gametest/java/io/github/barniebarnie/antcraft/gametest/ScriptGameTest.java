package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.SayBubble;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.BotLog;
import io.github.barniebarnie.antcraft.script.DefaultScripts;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.ServerScripts;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Bots running scripts (docs/scripting.md, milestones 2 and 4): output, budget, errors, reloading, and idle.py for every bot without a script. */
public class ScriptGameTest implements CustomTestMethodInvoker {
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

	/** The messages {@code owner} got from {@code bot}, leaving out what other tests' players and bots sent. */
	private static List<BotScripts.Message> fromBot(BotScripts.RecordingPlayer owner, AntBot bot) {
		String name = bot.getName().getString();
		return owner.messages().stream().filter(m -> m.text().startsWith("<" + name + "> ") || m.text().startsWith("[" + name + "] ")).toList();
	}

	@GameTest(maxTicks = 50)
	public void saysWaitsAndPrintsAtTheRightTicks(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);
		ScriptRun run = BotScripts.run(bot, "say('hi')\nwait(20)\nprint(pos())\n");
		String name = bot.getName().getString();

		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			List<BotLog.Entry> lines = bot.getLog().last(10, Output.Level.PRINT);
			helper.assertTrue(lines.size() == 2, "log " + lines);
			BlockPos feet = bot.blockPosition();
			String pos = "Pos(" + feet.getX() + ", " + feet.getY() + ", " + feet.getZ() + ")";
			helper.assertTrue(lines.get(0).text().equals("hi") && lines.get(1).text().equals(pos), "log " + lines + ", bot at " + pos);
			long start = lines.get(0).tick();
			helper.assertTrue(lines.get(1).tick() == start + 20, "said hi at " + start + ", printed at " + lines.get(1).tick());
			List<BotScripts.Message> chat = fromBot(owner, bot);
			helper.assertTrue(chat.equals(List.of(new BotScripts.Message(start, "<" + name + "> hi"), new BotScripts.Message(start + 20, "[" + name + "] " + pos))),
				"owner got " + chat);
		});
	}

	/** What {@code say} shows above the bot: a text display around its head, or null. */
	private static Display.@Nullable TextDisplay sayDisplay(AntBot bot) {
		return bot.level().getEntitiesOfClass(Display.TextDisplay.class, bot.getBoundingBox().inflate(0.5)).stream().findFirst().orElse(null);
	}

	/** {@code say} shows its text above the bot for {@link SayBubble#TICKS} ticks; then it is gone from the world. */
	@GameTest(maxTicks = SayBubble.TICKS + 40)
	public void sayShowsTheTextAboveTheBotForAWhile(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BotScripts.run(bot, "say('hello there')\nwhile True:\n    wait(10)\n");
		failOnError(helper, bot);
		long[] shown = {-1};
		Display.TextDisplay[] display = {null};
		helper.onEachTick(() -> {
			Display.TextDisplay current = sayDisplay(bot);
			if (shown[0] < 0 && current != null) {
				helper.assertTrue(current.getText().getString().equals("hello there"), "shows " + current.getText().getString());
				shown[0] = helper.getLevel().getGameTime();
				display[0] = current;
			}
		});

		helper.succeedWhen(() -> {
			helper.assertTrue(shown[0] >= 0, "nothing shown above the bot");
			helper.assertTrue(sayDisplay(bot) == null, "still shown");
			long ticks = helper.getLevel().getGameTime() - shown[0];
			helper.assertTrue(ticks >= SayBubble.TICKS - 1, "gone after " + ticks + " ticks");
			helper.assertTrue(display[0].isRemoved(), "text display left in the world");
		});
	}

	/** A bot showing what it said can still be pushed: a pig standing in it moves it aside (bots do not push each other). */
	@GameTest(maxTicks = 40)
	public void aBotShowingWhatItSaidCanBePushed(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BotScripts.run(bot, "say('hello there')\nwhile True:\n    wait(10)\n");
		failOnError(helper, bot);
		Vec3 start = bot.position();
		helper.spawnWithNoFreeWill(EntityTypes.PIG, new Vec3(3.6, 1, 3.5));
		helper.succeedWhen(() -> {
			double moved = bot.position().subtract(start).horizontalDistance();
			helper.assertTrue(moved > 0.2, "pushed " + moved + " blocks");
			Display.TextDisplay display = sayDisplay(bot);
			helper.assertTrue(display != null, "nothing shown above the bot");
			helper.assertTrue(display.position().distanceTo(bot.position().add(0, bot.getBbHeight(), 0)) < 0.01,
				"text at " + display.position() + ", bot at " + bot.position());
		});
	}

	/** {@code /antcraft bot status} shows the last action, with its reason when it failed. */
	@GameTest(maxTicks = 80)
	public void statusShowsTheLastActionsResult(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos target = helper.absolutePos(new BlockPos(4, 6, 4));
		String xyz = target.getX() + ", " + target.getY() + ", " + target.getZ();
		BotScripts.run(bot, "goto((" + xyz + "))\nwhile True:\n    wait(10)\n");
		failOnError(helper, bot);

		helper.succeedWhen(() -> {
			List<String> status = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " status");
			helper.assertTrue(status.size() == 1 && status.getFirst().contains(", goto(Pos(" + xyz + ")) -> unreachable, "), "status " + status);
		});
	}

	/** The last action's result is saved with the bot, so {@code /antcraft bot status} still shows it after a reload. */
	@GameTest(maxTicks = 80)
	public void theLastResultSurvivesASaveAndLoad(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos target = helper.absolutePos(new BlockPos(4, 6, 4));
		String xyz = target.getX() + ", " + target.getY() + ", " + target.getZ();
		runLibraryScript(helper, bot, "save_result_", "goto((" + xyz + "))\nwait(1000)\n");
		AntBot[] loaded = new AntBot[1];
		helper.onEachTick(() -> {
			if (loaded[0] == null && "wait(1000)".equals(bot.getScript().action())) {
				loaded[0] = saveAndLoad(helper, bot);
			}
		});
		failOnError(helper, () -> loaded[0]);
		helper.succeedWhen(() -> {
			helper.assertTrue(loaded[0] != null, "not saved yet");
			List<String> status = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " status");
			helper.assertTrue(status.size() == 1 && status.getFirst().contains(", goto(Pos(" + xyz + ")) -> unreachable, "), "status " + status);
		});
	}

	/** The bot's log is saved with it, so {@code /antcraft bot log} still shows it after a reload. */
	@GameTest(maxTicks = 20)
	public void theLogSurvivesASaveAndLoad(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getLog().add(5, Output.Level.PRINT, "before the reload");
		bot.getLog().add(6, Output.Level.ERROR, "test.py:1: oops");
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		bot.saveWithoutId(output);
		bot.discard();
		AntBot copy = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
		List<String> log = copy.getLog().last(10, Output.Level.DEBUG).stream().map(BotLog.Entry::format).toList();
		helper.assertTrue(log.contains("[5 print] before the reload") && log.contains("[6 error] test.py:1: oops"), "log " + log);
		helper.succeed();
	}

	@GameTest(maxTicks = 60)
	public void anEndlessLoopKeepsTheServerTickingAndShowsAsRunning(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun run = BotScripts.run(bot, "n = 0\nwhile True:\n    n += 1\n");

		// The test only gets this far if the server ticks on: the script pauses when its budget for the tick is used up.
		helper.runAfterDelay(20, () -> {
			helper.assertTrue(run.state() == ScriptRun.State.RUNNING, "script " + run.state());
			long budget = AntCraftConfig.scriptInstructionsPerTick();
			helper.assertTrue(run.executed() >= 18 * budget && run.executed() <= 21 * budget,
				run.executed() + " instructions in 20 ticks at " + budget + " a tick");
			List<String> status = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " status");
			helper.assertTrue(status.size() == 1 && status.getFirst().startsWith("commands.antcraft.bot.status[")
				&& status.getFirst().contains(", test, running, "), "status " + status);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = 20)
	public void aRuntimeErrorReachesTheOwnerAndTheLogAndStopsTheScript(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);
		ScriptRun run = BotScripts.run(bot, "x = None\nprint(x.y)\nprint('after')\n");
		String error = "test.py:2: 'NoneType' object has no attribute 'y'";

		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FAILED, "script " + run.state());
			helper.assertTrue(BotScripts.log(bot, Output.Level.WARN).equals(List.of(error)), "log " + BotScripts.log(bot, Output.Level.DEBUG));
			List<String> chat = fromBot(owner, bot).stream().map(BotScripts.Message::text).toList();
			helper.assertTrue(chat.equals(List.of("[" + bot.getName().getString() + "] " + error)), "owner got " + chat);
			helper.assertFalse(bot.isRunningScript(), "bot still runs its script");
		});
	}

	/**
	 * {@code /antcraft script reload} after a script file changed starts the new version on the bot running it. The test
	 * writes its own script into the world script folder, under a name of its own.
	 */
	@GameTest(maxTicks = 40)
	public void reloadAfterAFileChangeRestartsTheBotsScript(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		String name = "reload_test_" + UUID.randomUUID().toString().substring(0, 8);
		Path file = ServerScripts.worldFolder(helper.getLevel().getServer()).resolve(name + ".py");
		GameTests.onEnd(helper, () -> {
			try {
				Files.deleteIfExists(file);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		write(file, "print('one')\nwhile True:\n    wait(1)\n");
		BotScripts.command(helper, "antcraft script reload");
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name + " a b");
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);

		helper.runAfterDelay(5, () -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("one")), "log " + BotScripts.log(bot, Output.Level.DEBUG));
			write(file, "print('two', ARGS)\nwhile True:\n    wait(1)\n");
			List<String> reloaded = BotScripts.command(helper, "antcraft script reload");
			helper.assertTrue(reloaded.size() == 1 && reloaded.getFirst().endsWith(", 1]"), "reload answered " + reloaded);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("one", "two ['a', 'b']")), "log " + BotScripts.log(bot, Output.Level.DEBUG));
			helper.assertTrue(bot.isRunningScript(), "script " + bot.getScript().state());
		});
	}

	/**
	 * A script imports a module of the world script folder, and {@code /antcraft script reload} after only that module
	 * changed starts the script again with the new version. The test writes its own script and module, under names of its own.
	 */
	@GameTest(maxTicks = 40)
	public void importsALibraryModuleAndRestartsWhenOnlyTheModuleChanged(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		String id = UUID.randomUUID().toString().substring(0, 8);
		String name = "import_test_" + id;
		String library = "import_lib_" + id;
		Path folder = ServerScripts.worldFolder(helper.getLevel().getServer());
		Path script = folder.resolve(name + ".py");
		Path module = folder.resolve(library).resolve("words.py");
		GameTests.onEnd(helper, () -> {
			try {
				Files.deleteIfExists(script);
				Files.deleteIfExists(module);
				Files.deleteIfExists(module.getParent());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		write(module, "def word():\n    return 'one'\n");
		write(script, "from " + library + ".words import word\nprint(word())\nwhile True:\n    wait(1)\n");
		BotScripts.command(helper, "antcraft script reload");
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);

		helper.runAfterDelay(5, () -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("one")), "log " + BotScripts.log(bot, Output.Level.DEBUG));
			write(module, "def word():\n    return 'two'\n");
			List<String> reloaded = BotScripts.command(helper, "antcraft script reload");
			helper.assertTrue(reloaded.size() == 1 && reloaded.getFirst().endsWith(", 1]"), "reload answered " + reloaded);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("one", "two")), "log " + BotScripts.log(bot, Output.Level.DEBUG));
			helper.assertTrue(bot.isRunningScript(), "script " + bot.getScript().state());
		});
	}

	/**
	 * Writes {@code source} as a script of the world script folder under a name of its own, reloads the library and starts
	 * it on {@code bot}; gives the script's name. The file is deleted when the test ends.
	 */
	private static String runLibraryScript(GameTestHelper helper, AntBot bot, String prefix, String source) {
		String name = prefix + UUID.randomUUID().toString().substring(0, 8);
		Path script = ServerScripts.worldFolder(helper.getLevel().getServer()).resolve(name + ".py");
		GameTests.onEnd(helper, () -> {
			try {
				Files.deleteIfExists(script);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		write(script, source);
		BotScripts.command(helper, "antcraft script reload");
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
		return name;
	}

	/** Saves {@code bot} as a world save does, removes it and loads the save as a new bot, as loading the world does. */
	static AntBot saveAndLoad(GameTestHelper helper, AntBot bot) {
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		bot.saveWithoutId(output);
		bot.discard();
		// Loaded before it joins the level, as from a chunk, so the level looks it up by the UUID it loaded.
		AntBot copy = Objects.requireNonNull(ModEntities.ANT_BOT.create(helper.getLevel(), EntitySpawnReason.LOAD));
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
		helper.getLevel().addFreshEntity(copy);
		return copy;
	}

	/** The log lines of {@code bot} at print level, with the tick of each. */
	private static List<BotLog.Entry> prints(AntBot bot) {
		return bot.getLog().last(BotLog.CAPACITY, Output.Level.PRINT).stream().filter(e -> e.level() == Output.Level.PRINT).toList();
	}

	/**
	 * A bot saved in the middle of a {@code wait} and loaded again goes on with the ticks it had left (docs/scripting.md,
	 * Save and resume): it prints once before and once after, 30 ticks apart, as without the save.
	 */
	@GameTest(maxTicks = 60)
	public void aBotSavedInAWaitResumesItWithTheTicksItHadLeft(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		runLibraryScript(helper, bot, "save_wait_", "n = 2\nprint('before', n)\nwait(30)\nprint('after', n)\n");
		AntBot[] loaded = new AntBot[1];
		helper.runAfterDelay(10, () -> {
			helper.assertTrue("wait(30)".equals(bot.getScript().action()), "action " + bot.getScript().action());
			loaded[0] = saveAndLoad(helper, bot);
		});
		failOnError(helper, () -> loaded[0]);
		helper.succeedWhen(() -> {
			helper.assertTrue(loaded[0] != null, "not saved yet");
			List<BotLog.Entry> prints = prints(loaded[0]);
			helper.assertTrue(prints.size() == 2 && prints.get(0).text().equals("before 2") && prints.get(1).text().equals("after 2"),
				"log " + BotScripts.log(loaded[0], Output.Level.DEBUG));
			helper.assertTrue(prints.get(1).tick() == prints.get(0).tick() + 30, "printed at " + prints.get(0).tick() + " and " + prints.get(1).tick());
			helper.assertTrue(loaded[0].getScript() != null && loaded[0].getScript().state() == ScriptRun.State.FINISHED, "script not finished");
		});
	}

	/**
	 * A bot saved half-way through a {@code build} and loaded again builds the rest, the blocks already placed counting as
	 * done and in its value.
	 */
	@GameTest(maxTicks = 60)
	public void aBotSavedInABuildFinishesIt(GameTestHelper helper) {
		helper.setBlock(4, 2, 3, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		WorkGameTest.carryCobbleStep(bot, 2);
		runLibraryScript(helper, bot, "save_build_", "r = build('antcraft:cobble_step', " + WorkGameTest.at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value)\n");
		AntBot[] loaded = new AntBot[1];
		helper.onEachTick(() -> {
			if (loaded[0] == null && helper.getBlockState(new BlockPos(3, 1, 3)).is(Blocks.COBBLESTONE)) {
				helper.assertTrue(bot.getScript().action().startsWith("build("), "action " + bot.getScript().action());
				loaded[0] = saveAndLoad(helper, bot);
			}
		});
		failOnError(helper, () -> loaded[0]);
		helper.succeedWhen(() -> {
			helper.assertTrue(loaded[0] != null, "not saved yet");
			List<String> prints = prints(loaded[0]).stream().map(BotLog.Entry::text).toList();
			helper.assertValueEqual(prints, List.of("True None 3"), "printed");
			WorkGameTest.assertCobbleStep(helper, 0);
		});
	}

	/**
	 * A bot saved once it pillared up with dirt and placed the high block, before the pillar is down, takes the whole
	 * pillar down when loaded again: the scaffold it placed is saved with the build's progress.
	 */
	@GameTest(maxTicks = 110)
	public void aBotSavedInABuildTakesItsScaffoldDown(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		runLibraryScript(helper, bot, "save_scaffold_", "r = build('antcraft-gametest:two_palettes', " + WorkGameTest.at(helper, 3, 7, 3)
			+ ")\nprint(r.ok, r.reason, r.value, count('dirt'))\n");
		AntBot[] loaded = new AntBot[1];
		helper.onEachTick(() -> {
			if (loaded[0] == null && helper.getBlockState(new BlockPos(3, 7, 3)).is(Blocks.COBBLESTONE)) {
				loaded[0] = saveAndLoad(helper, bot);
			}
		});
		failOnError(helper, () -> loaded[0]);
		helper.succeedWhen(() -> {
			helper.assertTrue(loaded[0] != null, "not saved yet");
			List<String> prints = prints(loaded[0]).stream().map(BotLog.Entry::text).toList();
			helper.assertValueEqual(prints, List.of("True None 1 16"), "printed");
			for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 6, 7)) {
				helper.assertFalse(helper.getBlockState(pos).is(Blocks.DIRT), "dirt left at " + pos);
			}
		});
	}

	/**
	 * A build stopped once it pillared up with dirt and placed the high block, before the pillar is down, leaves the
	 * pillar with the bot, which is saved with it: the same build called again on the loaded bot takes the pillar down.
	 */
	@GameTest(maxTicks = 110)
	public void aStoppedBuildCalledAgainTakesItsScaffoldDown(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		String build = "r = build('antcraft-gametest:two_palettes', " + WorkGameTest.at(helper, 3, 7, 3) + ")\n";
		BotScripts.run(bot, build);
		AntBot[] loaded = new AntBot[1];
		ScriptRun[] again = new ScriptRun[1];
		helper.onEachTick(() -> {
			if (loaded[0] == null && helper.getBlockState(new BlockPos(3, 7, 3)).is(Blocks.COBBLESTONE)) {
				bot.stopScript();
				loaded[0] = saveAndLoad(helper, bot);
				again[0] = BotScripts.run(loaded[0], build + "print(r.ok, r.reason, r.value, count('dirt'))\n");
			}
			if (again[0] != null && again[0].state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(loaded[0], Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(again[0] != null && again[0].state() == ScriptRun.State.FINISHED, "not built again yet");
			List<String> printed = prints(loaded[0]).stream().map(BotLog.Entry::text).toList();
			helper.assertValueEqual(printed, List.of("True None 0 16"), "printed");
			for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 6, 7)) {
				helper.assertFalse(helper.getBlockState(pos).is(Blocks.DIRT), "dirt left at " + pos);
			}
		});
	}

	/**
	 * A bot saved in the middle of a {@code goto} and loaded again walks on to the same target, and the script goes on
	 * after the call with its result, without starting again.
	 */
	@GameTest(maxTicks = 120)
	public void aBotSavedInAGotoResumesItAndArrives(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos target = helper.absolutePos(new BlockPos(6, 1, 6));
		runLibraryScript(helper, bot, "save_goto_", "print('start')\nr = goto((" + target.getX() + ", " + target.getY() + ", " + target.getZ()
			+ "))\nprint(r.ok, pos())\n");
		AntBot[] loaded = new AntBot[1];
		helper.runAfterDelay(8, () -> {
			String action = bot.getScript().action();
			helper.assertTrue(action != null && action.startsWith("goto("), "action " + action);
			helper.assertFalse(bot.blockPosition().equals(target), "already there");
			loaded[0] = saveAndLoad(helper, bot);
		});
		failOnError(helper, () -> loaded[0]);
		helper.succeedWhen(() -> {
			helper.assertTrue(loaded[0] != null, "not saved yet");
			String arrived = "True Pos(" + target.getX() + ", " + target.getY() + ", " + target.getZ() + ")";
			List<String> prints = prints(loaded[0]).stream().map(BotLog.Entry::text).toList();
			helper.assertTrue(prints.equals(List.of("start", arrived)), "log " + BotScripts.log(loaded[0], Output.Level.DEBUG));
		});
	}

	private static void write(Path file, String source) {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, source);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Fails at once when {@code bot}'s script fails, rather than waiting out the test. */
	private static void failOnError(GameTestHelper helper, AntBot bot) {
		failOnError(helper, () -> bot);
	}

	/** Fails at once when the script of the bot {@code bot} gives fails; it gives null while there is none yet. */
	private static void failOnError(GameTestHelper helper, Supplier<@Nullable AntBot> bots) {
		helper.onEachTick(() -> {
			AntBot bot = bots.get();
			if (bot == null) {
				return;
			}
			List<String> errors = bot.getLog().last(BotLog.CAPACITY, Output.Level.ERROR).stream()
				.filter(entry -> entry.level() == Output.Level.ERROR).map(BotLog.Entry::text).toList();
			if (!errors.isEmpty()) {
				helper.fail("script error: " + errors);
			}
		});
	}

	/** Succeeds once {@code bot} runs {@code idle.py} and has stood at {@code home} for 20 ticks. */
	private static void succeedWhenIdleAtHome(GameTestHelper helper, AntBot bot, BlockPos home) {
		int[] homeTicks = {0};
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			boolean idle = run != null && run.name().equals(DefaultScripts.IDLE) && run.running();
			homeTicks[0] = idle && bot.blockPosition().equals(home) ? homeTicks[0] + 1 : 0;
		});
		helper.succeedWhen(() -> {
			ScriptRun run = bot.getScript();
			helper.assertTrue(run != null && run.name().equals(DefaultScripts.IDLE), "bot runs " + (run == null ? "nothing" : run.name()));
			helper.assertTrue(homeTicks[0] >= 20, "bot not home 20 ticks, at " + TestPositions.relative(helper, bot.blockPosition()).toShortString());
		});
	}

	/** A bot that never ran a script runs {@code idle.py}: it walks home and waits there. */
	@GameTest(maxTicks = 120)
	public void aBotThatNeverRanAScriptWalksHomeAndWaits(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos home = helper.absolutePos(new BlockPos(6, 1, 6));
		bot.setHome(home);
		failOnError(helper, bot);
		succeedWhenIdleAtHome(helper, bot, home);
	}

	/** A colony member whose script is stopped with {@code /antcraft bot stop} runs {@code idle.py} and walks home. */
	@GameTest(maxTicks = 140)
	public void aColonyBotWhoseScriptIsStoppedRunsIdle(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(1, 1, 1))).orElseThrow();
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BlockPos home = helper.absolutePos(new BlockPos(6, 1, 6));
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setHome(home);
		BotScripts.run(bot, "while True:\n    wait(10)\n");
		failOnError(helper, bot);
		helper.runAfterDelay(10, () -> {
			helper.assertTrue(bot.isRunningScript() && bot.getScript().name().equals("test"), "bot runs " + bot.getScript().name());
			BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " stop");
		});
		succeedWhenIdleAtHome(helper, bot, home);
	}

	/**
	 * A bot saved by an older version, in a colony and with a job's role, its scaffold and a walk target, loads and runs
	 * {@code idle.py}; the fields it no longer knows are ignored.
	 */
	@GameTest(maxTicks = 120)
	public void aBotSavedWithColonyWorkLoadsAndRunsIdle(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BlockPos home = helper.absolutePos(new BlockPos(6, 1, 6));
		bot.setHome(home);
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
		bot.saveWithoutId(output);
		bot.discard();
		CompoundTag saved = output.buildResult();
		saved.store("Colony", UUIDUtil.CODEC, UUID.randomUUID());
		saved.putString("Role", "miner");
		saved.putBoolean("RoleSetByPlayer", true);
		saved.store("WalkTarget", BlockPos.CODEC, helper.absolutePos(new BlockPos(1, 1, 6)));
		saved.store("Scaffold", BlockPos.CODEC.listOf(), List.of(helper.absolutePos(new BlockPos(3, 1, 3))));
		AntBot copy = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		copy.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
		failOnError(helper, copy);
		succeedWhenIdleAtHome(helper, copy, home);
	}
}
