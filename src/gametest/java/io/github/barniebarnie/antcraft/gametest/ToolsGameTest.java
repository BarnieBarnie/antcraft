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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** {@code lib/tools.py} and {@code lib/cost.py} (#507): carried tools, their uses left and effort estimates. */
public class ToolsGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** enough counts the uses left of the carried tools: a fresh stone axe lasts 50 blocks, one at damage 100 does not. */
	@GameTest(maxTicks = 10)
	public void enoughCountsTheUsesLeft(GameTestHelper helper) {
		AntBot fresh = helper.spawn(ModEntities.ANT_BOT, 2, 1, 2);
		fresh.getInventory().addItem(new ItemStack(Items.STONE_AXE));
		AntBot worn = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		ItemStack axe = new ItemStack(Items.STONE_AXE);
		axe.setDamageValue(100);
		worn.getInventory().addItem(axe);
		String name = BotScripts.libraryScript(helper, "tools_", """
			from lib.tools import enough, uses_left, best
			print(enough("axe", 50), uses_left(best("axe")), enough("pickaxe", 1))
			""");
		run(helper, fresh, name);
		run(helper, worn, name);
		helper.succeedWhen(() -> {
			List<String> a = BotScripts.log(fresh, Output.Level.PRINT);
			List<String> b = BotScripts.log(worn, Output.Level.PRINT);
			helper.assertTrue(a.equals(List.of("True 131 False")), "fresh printed " + a);
			helper.assertTrue(b.equals(List.of("False 31 False")), "worn printed " + b);
		});
	}

	/** best picks iron over stone, hold (also through lib.craft) equips it, tier_for and lib.cost give their estimates. */
	@GameTest(maxTicks = 10)
	public void bestPicksIronOverStoneAndTierForDiamondOreIsIron(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.IRON_PICKAXE));
		String name = BotScripts.libraryScript(helper, "tools_", """
			import lib.tools
			import lib.craft
			import lib.cost
			print(lib.tools.best("pickaxe").item, lib.tools.best("axe"))
			print(lib.tools.tier_for("minecraft:diamond_ore"), lib.tools.tier_for("iron_ore"), lib.tools.tier_for("minecraft:stone"), lib.tools.tier_for("obsidian"))
			print(lib.tools.hold("pickaxe")["ok"], held().item)
			equip("stone_pickaxe")
			print(lib.craft.hold("pickaxe")["ok"], held().item)
			print(lib.cost.USES["stone"], lib.cost.walk(Pos(0, 0, 0), Pos(3, 1, -4)) == 8 * lib.cost.WALK)
			print(lib.cost.breaking(10, "stone", "stone") == 10 * lib.cost.BREAK["stone"]["stone"], lib.cost.breaking(1, "stone", "iron") < lib.cost.breaking(1, "stone", "wooden"))
			""");
		run(helper, bot, name);
		helper.succeedWhen(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(log.equals(List.of("minecraft:iron_pickaxe None", "iron stone wooden diamond",
				"True minecraft:iron_pickaxe", "True minecraft:iron_pickaxe", "131 True", "True True")), "printed " + log);
		});
	}

	private static void run(GameTestHelper helper, AntBot bot, String name) {
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
	}
}
