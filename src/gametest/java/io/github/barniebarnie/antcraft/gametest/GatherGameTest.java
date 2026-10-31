package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.expect;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code lib/gather.py} (#482): a bot with an empty inventory beside the oak of {@link TreesGameTest} and three stone
 * blocks at the level of its feet makes a stone axe from nothing; a raw item it cannot gather fails with no_recipe.
 */
public class GatherGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		TreesGameTest.plantTree(helper);
		for (int z = 4; z <= 6; z++) {
			helper.setBlock(1, 1, z, Blocks.STONE);
		}
		method.invoke(this, helper);
	}

	/** Logs from the oak, a wooden pickaxe, cobblestone from the stone, then the stone axe. */
	@GameTest(maxTicks = 1600)
	public void makeStoneAxeFromAnEmptyInventory(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		expect(helper, bot, "import lib.gather\nr = lib.gather.make('stone_axe')\nprint(r['ok'], count('stone_axe'))\n",
			List.of("True 1"));
	}

	/** An item it does not know how to gather, raw or in a recipe, fails at once with the item missing. */
	@GameTest(maxTicks = 20)
	public void unknownRawItemFailsWithNoRecipe(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 1);
		expect(helper, bot, """
			import lib.gather
			r = lib.gather.gather('minecraft:diamond', 2)
			print(r['ok'], r['reason'], r['missing'])
			r = lib.gather.make('elytra')
			print(r['ok'], r['reason'], r['missing'])
			""", List.of("False no_recipe {'minecraft:diamond': 2}", "False no_recipe {'minecraft:elytra': 1}"));
	}
}
