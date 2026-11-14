package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** {@code lib/project.py} (#569): a list of steps kept in memory, so a stopped project resumes at the step it was on. */
public class ProjectGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** Stopped in step 3 and run again, the project skips steps 1 and 2, which it has done, and finishes step 3. */
	// Measured: passes at tick 22.
	@GameTest(maxTicks = 50)
	public void aStoppedProjectResumesAtItsStep(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		String name = BotScripts.libraryScript(helper, "project_", """
			import lib.project
			from lib.project import Step
			from lib.result import result
			def one():
			    print("ran 1")
			def two():
			    print("ran 2")
			    return result()
			def three(t):
			    print("starts 3")
			    wait(t)
			    print("ran 3")
			    return result()
			r = lib.project.run("p", [Step("one", one), Step("two", two), Step("three", three, (20,))])
			print("end", r["ok"], r["missing"], lib.project.status("p")["done"])
			""");
		String run = "antcraft bot " + bot.getStringUUID() + " run " + name;
		BotScripts.command(helper, run);
		boolean[] rerun = new boolean[1];
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			if (!rerun[0] && ran(bot).contains("starts 3")) {
				rerun[0] = true;
				bot.stopScript();
				BotScripts.command(helper, run);
			}
		});
		helper.succeedWhen(() -> helper.assertValueEqual(ran(bot),
			List.of("ran 1", "ran 2", "starts 3", "starts 3", "ran 3", "end True [] ['one', 'two', 'three']"), "printed"));
	}

	/**
	 * An optional step that fails is noted and passed; a step with tries is tried again on a passing reason; a required
	 * step that fails stops the project with its reason, and the steps not done are its missing.
	 */
	// Measured: passes at tick 9.
	@GameTest(maxTicks = 20)
	public void anOptionalStepFailsOnARequiredOneStops(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		String name = BotScripts.libraryScript(helper, "project_fail_", """
			import lib.project
			from lib.project import Step
			from lib.result import result
			calls = []
			def flaky():
			    calls.append(1)
			    return result("blocked") if len(calls) < 3 else result()
			def never():
			    print("ran never")
			steps = [Step("logs", result, ("no_logs",), optional=True), Step("flaky", flaky, tries=3),
			         Step("pick", equip, ("diamond",)), Step("never", never)]
			r = lib.project.run("q", steps)
			s = lib.project.status("q")
			print(r["ok"], r["reason"], r["at"], r["missing"], len(calls))
			print(s["done"], s["failed"])
			lib.project.reset("q")
			print(lib.project.status("q"))
			""");
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT).stream()
				.filter(line -> !line.startsWith("q ")).toList(),
			List.of("False no_item pick ['logs', 'pick', 'never'] 3", "['flaky'] {'logs': 'no_logs', 'pick': 'no_item'}",
				"{'done': [], 'failed': {}}"), "printed"));
	}

	/**
	 * A tool a step borrowed from a chest is put back when the project ends: after a project that is done, and after one
	 * that a required step stopped.
	 */
	// Measured: passes at tick 5.
	@GameTest(maxTicks = 20)
	public void runGivesBackABorrowedTool(GameTestHelper helper) {
		helper.setBlock(5, 1, 4, Blocks.CHEST);
		Container chest = helper.getBlockEntity(new BlockPos(5, 1, 4), ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.STONE_PICKAXE));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		String name = BotScripts.libraryScript(helper, "project_give_back_", """
			import lib.project
			from lib.borrow import borrow
			from lib.project import Step
			from lib.result import result
			chest = %s
			def tool():
			    r = borrow(chest, "stone_pickaxe")
			    print("borrowed", count("stone_pickaxe"))
			    return r
			for name, last in [("done", Step("fine", result)), ("stopped", Step("stuck", result, ("blocked",)))]:
			    r = lib.project.run(name, [Step("tool", tool), last])
			    print(name, r["ok"], count("stone_pickaxe"))
			""".formatted(WorkGameTest.at(helper, 5, 1, 4)));
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT).stream()
					.filter(line -> !line.matches(".* \\d+/\\d+: .*")).toList(),
				List.of("borrowed 1", "done True 0", "borrowed 1", "stopped False 0"), "printed");
			helper.assertValueEqual(chest.countItem(Items.STONE_PICKAXE), 1, "pickaxes in the chest");
		});
	}

	/** The printed lines of {@code bot}, without the project's progress lines (said as "p ..."). */
	private static List<String> ran(AntBot bot) {
		return BotScripts.log(bot, Output.Level.PRINT).stream().filter(line -> !line.startsWith("p ")).toList();
	}
}
