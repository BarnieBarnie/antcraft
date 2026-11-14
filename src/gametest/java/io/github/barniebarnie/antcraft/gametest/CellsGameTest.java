package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** {@code lib/cells.py} (#603): a grid of farm cells kept in memory, built one cell at a time. */
public class CellsGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/**
	 * The boxes of cells 0..5 of a 5x5 farm with rows of 3 and a gap of 1, row-major from the origin; the cells built
	 * and the next one survive a second run of the script.
	 */
	// Measured: passes at tick 2.
	@GameTest(maxTicks = 10)
	public void cellsAreBoxedAndBuiltCellsSurviveARerun(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		String name = BotScripts.libraryScript(helper, "cells_", """
			import lib.cells
			lib.cells.layout("f", Pos(10, 64, 20), (5, 5), 3, 1)
			boxes = [lib.cells.cell("f", i) for i in range(6)]
			print([(l.x, l.y, l.z, h.x, h.y, h.z) for l, h in boxes])
			print(lib.cells.built("f"), lib.cells.next("f"), lib.cells.count("f"))
			lib.cells.add("f", 0)
			lib.cells.add("f", 2)
			lib.cells.add("f", 0)
			""");
		String run = "antcraft bot " + bot.getStringUUID() + " run " + name;
		BotScripts.command(helper, run);
		boolean[] rerun = new boolean[1];
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			ScriptRun script = bot.getScript();
			if (!rerun[0] && script != null && script.state() == ScriptRun.State.FINISHED) {
				rerun[0] = true;
				BotScripts.command(helper, run);
			}
		});
		String boxes = "[(10, 64, 20, 14, 64, 24), (16, 64, 20, 20, 64, 24), (22, 64, 20, 26, 64, 24), "
			+ "(10, 64, 26, 14, 64, 30), (16, 64, 26, 20, 64, 30), (22, 64, 26, 26, 64, 30)]";
		helper.succeedWhen(() -> helper.assertValueEqual(BotScripts.log(bot, Output.Level.PRINT),
			List.of(boxes, "[] 0 0", boxes, "[0, 2] 1 2"), "printed"));
	}
}
