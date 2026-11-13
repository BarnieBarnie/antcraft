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

/** {@code lib/result.py} (#504): the result() every library doer gives, also still importable from {@code lib.shaft}. */
public class ResultGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** lib.result and lib.shaft give the same dict, for success and for a failure with where and what is missing. */
	@GameTest(maxTicks = 10)
	public void resultFromLibResultAndLibShaftAgree(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		String name = BotScripts.libraryScript(helper, "result_", """
			from lib.result import result
			import lib.shaft
			ok = result()
			bad = result("no_tool", Pos(1, 2, 3), {"minecraft:stick": 2})
			print(ok == lib.shaft.result(), bad == lib.shaft.result("no_tool", Pos(1, 2, 3), {"minecraft:stick": 2}))
			print(ok["ok"], ok["reason"], ok["at"], len(ok["missing"]), bad["ok"], bad["reason"], bad["missing"]["minecraft:stick"])
			""");
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(log.equals(List.of("True True", "True None None 0 False no_tool 2")), "printed " + log);
		});
	}
}
