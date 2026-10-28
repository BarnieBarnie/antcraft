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
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
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

	/** What {@code say} shows above the bot: the text display riding it, or null. */
	private static Display.@Nullable TextDisplay sayDisplay(AntBot bot) {
		return bot.getPassengers().stream().filter(e -> e instanceof Display.TextDisplay).map(e -> (Display.TextDisplay) e).findFirst().orElse(null);
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
		helper.onEachTick(() -> {
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
