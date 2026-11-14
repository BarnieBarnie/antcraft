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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

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

	/**
	 * A copper pickaxe is of the copper tier, between stone and iron (#578): best picks it over a stone one, it drops
	 * iron ore at (1, 1, 1) as the game's incorrect_for_copper_tool leaves needs_stone_tool out, and lib.cost has the
	 * game's 190 uses and its mining speed 5 (stone 1.5 hardness: 9 ticks, between stone's 12 and iron's 8).
	 */
	@GameTest(maxTicks = 10)
	public void aCopperPickaxeIsOfTheCopperTier(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		bot.getInventory().addItem(new ItemStack(Items.STONE_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.COPPER_PICKAXE));
		String name = BotScripts.libraryScript(helper, "tools_", """
			import lib.tools
			import lib.cost
			b = lib.tools.best("pickaxe")
			print(b.item, lib.tools.tier(b), lib.tools.drops(Pos%s), lib.tools.TIERS.index("stone") > lib.tools.TIERS.index("copper") > lib.tools.TIERS.index("iron"))
			print(lib.tools.hold("pickaxe")["ok"], held().item, lib.cost.USES["copper"], lib.cost.BREAK["stone"]["copper"])
			""".formatted(at(helper, 1, 1, 1)));
		run(helper, bot, name);
		helper.succeedWhen(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(log.equals(List.of("minecraft:copper_pickaxe copper True True",
				"True minecraft:copper_pickaxe 190 9")), "printed " + log);
		});
	}

	/**
	 * tier_for of a position reads the block's needs_*_tool tags (#515): a lodestone, which the game test
	 * data pack adds to #minecraft:needs_iron_tool and lib.tools.NEEDS leaves out, gives "iron" there, and by id too, as
	 * tier_for of an id NEEDS leaves out reads the block's tags (#566).
	 */
	@GameTest(maxTicks = 20)
	public void tierForAPositionReadsTheBlocksTags(GameTestHelper helper) {
		helper.setBlock(1, 1, 1, Blocks.LODESTONE);
		helper.setBlock(1, 1, 2, Blocks.OBSIDIAN);
		helper.setBlock(1, 1, 3, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		String name = BotScripts.libraryScript(helper, "tools_", """
			import lib.tools
			print(lib.tools.tier_for(Pos%s), lib.tools.tier_for(Pos%s), lib.tools.tier_for(Pos%s))
			print(lib.tools.tier_for("lodestone"))
			""".formatted(at(helper, 1, 1, 1), at(helper, 1, 1, 2), at(helper, 1, 1, 3)));
		run(helper, bot, name);
		helper.succeedWhen(() -> {
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(log.equals(List.of("iron diamond wooden", "iron")), "printed " + log);
		});
	}

	/** The position {@code (x, y, z)} of the test area as a script tuple. */
	private static String at(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	private static void run(GameTestHelper helper, AntBot bot, String name) {
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
		BotScripts.failOnError(helper, bot);
	}
}
