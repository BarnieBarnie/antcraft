package io.github.barniebarnie.antcraft.gametest;

import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.at;
import static io.github.barniebarnie.antcraft.gametest.WorkGameTest.expect;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The library module {@code lib/area.py} (#451; docs/reference/modules/lib/area.md): {@code box}, {@code clear} and
 * {@code fill} on cuboids inside the test area, run as short scripts on a bot beside them.
 */
public class AreaGameTest implements CustomTestMethodInvoker {
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

	/** {@code import lib.area} and {@code rel(p)}, a position as a tuple relative to the test area, or None. */
	private static String header(GameTestHelper helper) {
		return "import lib.area\no = Pos(" + at(helper, 0, 0, 0) + ")\n"
			+ "def rel(p):\n    return None if p is None else (p.x - o.x, p.y - o.y, p.z - o.z)\n";
	}

	private static void cuboid(GameTestHelper helper, Block block, int x0, int y0, int z0, int x1, int y1, int z1) {
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					helper.setBlock(x, y, z, block);
				}
			}
		}
	}

	/** Corners in any order: bottom up, each layer in rows along x, one row after the other along z, every position once. */
	@GameTest(maxTicks = 20)
	public void boxListsEveryPositionOnceBottomUpInRows(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		expect(helper, bot, header(helper) + "print([rel(p) for p in lib.area.box(o + (2, 2, 1), o + (1, 1, 2))])\n",
			List.of("[(1, 1, 1), (2, 1, 1), (1, 1, 2), (2, 1, 2), (1, 2, 1), (2, 2, 1), (1, 2, 2), (2, 2, 2)]"));
	}

	/** A 3x3x3 of dirt is mined away, and the bot carries the 27 dirt. */
	@GameTest(maxTicks = 1000)
	public void clearEmptiesACubeOfDirtAndTheBotCarriesIt(GameTestHelper helper) {
		cuboid(helper, Blocks.DIRT, 1, 1, 1, 3, 3, 3);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 2);
		expect(helper, bot, header(helper) + "r = lib.area.clear(o + (1, 1, 1), o + (3, 3, 3))\nprint(r['ok'], r['reason'], count('dirt'))\n",
			List.of("True None 27"), () -> {
				for (int x = 1; x <= 3; x++) {
					for (int y = 1; y <= 3; y++) {
						for (int z = 1; z <= 3; z++) {
							helper.assertBlockPresent(Blocks.AIR, x, y, z);
						}
					}
				}
			});
	}

	/** Stone without a pickaxe: it stops there with no_tool, having mined the dirt before it, top down. */
	@GameTest(maxTicks = 100)
	public void clearStopsWithTheReasonAndWhereItCouldNotMine(GameTestHelper helper) {
		cuboid(helper, Blocks.DIRT, 1, 1, 1, 2, 2, 1);
		helper.setBlock(1, 1, 1, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 1);
		expect(helper, bot, header(helper) + "r = lib.area.clear(o + (1, 1, 1), o + (2, 2, 1))\nprint(r['ok'], r['reason'], rel(r['at']), count('dirt'))\n",
			List.of("False no_tool (1, 1, 1) 3"), () -> helper.assertBlockPresent(Blocks.STONE, 1, 1, 1));
	}

	/** A 3x1x3 floor of dirt is laid with the 9 dirt the bot carries. */
	@GameTest(maxTicks = 40)
	public void fillLaysAFloor(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 2);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 9));
		expect(helper, bot, header(helper) + "r = lib.area.fill(o + (1, 1, 1), o + (3, 1, 3), 'dirt')\nprint(r['ok'], r['reason'], count('dirt'))\n",
			List.of("True None 0"), () -> {
				for (int x = 1; x <= 3; x++) {
					for (int z = 1; z <= 3; z++) {
						helper.assertBlockPresent(Blocks.DIRT, x, 1, z);
					}
				}
			});
	}

	/** With 5 dirt for 9 positions it places 5 and stops with missing_ingredients where the sixth goes, missing 4. */
	@GameTest(maxTicks = 40)
	public void fillWithTooFewBlocksStopsWithMissingIngredients(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 2);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 5));
		expect(helper, bot, header(helper) + "r = lib.area.fill(o + (1, 1, 1), o + (3, 1, 3), 'dirt')\nprint(r['ok'], r['reason'], rel(r['at']), r['missing'])\n",
			List.of("False missing_ingredients (3, 1, 2) {'dirt': 4}"), () -> helper.assertBlockPresent(Blocks.DIRT, 2, 1, 2));
	}
}
