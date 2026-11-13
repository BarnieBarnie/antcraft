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
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code lib/dig.py} and {@code lib/find.py} (#509): ore from an exposed vein, a staircase down and back up, and a 1x2
 * branch tunnel that takes the ore it uncovers and puts torches; a staircase up from a pocket with none remembered, and
 * ore's deep fallback under the floor (#523). Each bot holds a stone pickaxe.
 */
public class DigGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	private static AntBot miner(GameTestHelper helper, int x, int y, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, y, z);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
		return bot;
	}

	/** Fills the area with stone from the floor up to {@code top}. */
	private static void stone(GameTestHelper helper, int top) {
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, top, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
	}

	/** nearest takes a filter; exposed leaves out an ore walled in by stone. */
	@GameTest(maxTicks = 40)
	public void findNearestAndExposed(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.IRON_ORE);
		for (BlockPos pos : List.of(new BlockPos(1, 1, 2), new BlockPos(3, 1, 2), new BlockPos(2, 1, 1), new BlockPos(2, 1, 3),
			new BlockPos(2, 2, 2))) {
			helper.setBlock(pos, Blocks.STONE);
		}
		helper.setBlock(6, 1, 6, Blocks.IRON_ORE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 5);
		String buried = at(helper, 2, 1, 2);
		String open = at(helper, 6, 1, 6);
		expect(helper, bot, """
			import lib.find
			print(lib.find.nearest("iron_ore", 8) == Pos%s)
			print(lib.find.nearest("iron_ore", 8, lambda p: p.x > %d) == Pos%s)
			print(lib.find.exposed("#c:ores/iron", 8) == Pos%s)
			print(lib.find.exposed("diamond_ore", 8))
			""".formatted(buried, helper.absolutePos(new BlockPos(4, 0, 0)).getX(), open, open),
			List.of("True", "True", "True", "None"));
	}

	/** An exposed iron vein of two blocks gives two raw iron. */
	@GameTest(maxTicks = 150)
	public void oreFromAnExposedVein(GameTestHelper helper) {
		helper.setBlock(5, 1, 5, Blocks.IRON_ORE);
		helper.setBlock(5, 2, 5, Blocks.IRON_ORE);
		AntBot bot = miner(helper, 1, 1, 1);
		expect(helper, bot, """
			import lib.dig
			r = lib.dig.ore("raw_iron", 2)
			print(r["ok"], count("raw_iron"))
			""", List.of("True 2"),
			() -> {
				helper.assertBlockPresent(Blocks.AIR, 5, 1, 5);
				helper.assertBlockPresent(Blocks.AIR, 5, 2, 5);
			});
	}

	/** Four steps down east through stone and back up: each step has stone under it, no hole straight down. */
	@GameTest(maxTicks = 500)
	public void stairsDownFourAndBackUp(GameTestHelper helper) {
		stone(helper, 5);
		AntBot bot = miner(helper, 1, 6, 3);
		expect(helper, bot, """
			import lib.dig
			s = pos()
			r = lib.dig.stairs_to(s.y - 4, "east")
			print(r["ok"], pos().x - s.x, pos().y - s.y)
			r = lib.dig.stairs_up()
			print(r["ok"], pos().x - s.x, pos().y - s.y)
			""", List.of("True 4 -4", "True 0 0"),
			() -> {
				for (int k = 1; k <= 4; k++) {
					helper.assertBlockPresent(Blocks.AIR, 1 + k, 6 - k, 3);
					helper.assertBlockPresent(Blocks.STONE, 1 + k, 5 - k, 3);
				}
				helper.assertBlockPresent(Blocks.STONE, 1, 5, 3);
			});
	}

	/** A branch of 6 east, torches every 3, takes the two coal veins beside it (three ore). */
	@GameTest(maxTicks = 700)
	public void branchTakesTheCoalBesideItAndPutsTorches(GameTestHelper helper) {
		stone(helper, 4);
		helper.setBlock(1, 1, 3, Blocks.AIR);
		helper.setBlock(1, 2, 3, Blocks.AIR);
		helper.setBlock(4, 1, 2, Blocks.COAL_ORE);
		helper.setBlock(4, 2, 2, Blocks.COAL_ORE);
		helper.setBlock(5, 2, 4, Blocks.COAL_ORE);
		AntBot bot = miner(helper, 1, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 4));
		expect(helper, bot, """
			import lib.dig
			r = lib.dig.branch(6, 3, "east")
			print(r["ok"], count("coal"), count("torch"))
			""", List.of("True 3 2"),
			() -> {
				for (int x = 2; x <= 7; x++) {
					helper.assertBlockNotPresent(Blocks.STONE, x, 1, 3);
					helper.assertBlockPresent(Blocks.AIR, x, 2, 3);
				}
				helper.assertBlockPresent(Blocks.TORCH, 4, 1, 3);
				helper.assertBlockPresent(Blocks.TORCH, 7, 1, 3);
				helper.assertBlockPresent(Blocks.AIR, 4, 2, 2);
				helper.assertBlockPresent(Blocks.AIR, 5, 2, 4);
			});
	}

	/**
	 * From a pocket in stone with no staircase remembered, stairs_up digs a staircase east up to the open air. The area
	 * has sky access: no barrier ceiling over it.
	 */
	@GameTest(maxTicks = 500, skyAccess = true)
	public void stairsUpWithoutStairsDigsToTheSurface(GameTestHelper helper) {
		stone(helper, 5);
		helper.setBlock(1, 1, 3, Blocks.AIR);
		helper.setBlock(1, 2, 3, Blocks.AIR);
		AntBot bot = miner(helper, 1, 1, 3);
		expect(helper, bot, """
			import lib.dig
			s = pos()
			r = lib.dig.stairs_up("east")
			print(r["ok"], pos().x - s.x, pos().y - s.y)
			""", List.of("True 5 5"),
			() -> {
				for (int k = 1; k <= 5; k++) {
					helper.assertBlockPresent(Blocks.STONE, 1 + k, k, 3);
				}
			});
	}

	/**
	 * Out of a cave 17 blocks tall (#534): air ahead and 16 blocks of it overhead are not the surface while the cave's
	 * roof is over the bot. stairs_up bridges the first step with the cobblestone it carries and digs on east through a
	 * wall of stone 4 high, out from under the roof, to its top. The area has sky access; the roof is above it, at y=18.
	 */
	@GameTest(maxTicks = 350, skyAccess = true)
	public void stairsUpOutOfATallCaveDigsToTheRealSurface(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(3, 1, 0, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		for (BlockPos pos : BlockPos.betweenClosed(0, 18, 0, 2, 18, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		AntBot bot = miner(helper, 1, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 4));
		expect(helper, bot, """
			import lib.dig
			s = pos()
			r = lib.dig.stairs_up("east")
			print(r["ok"], pos().x - s.x, pos().y - s.y)
			""", List.of("True 4 4"),
			() -> {
				for (int k = 2; k <= 4; k++) {
					helper.assertBlockPresent(Blocks.STONE, 1 + k, k, 3);
				}
			});
	}

	/**
	 * Coal buried under the floor, none exposed: ore digs stairs down to y, branches north (4 blocks, so it stays under
	 * the area) past the coal and climbs back up to where it started. Stone is filled under the floor, y -4 to -1.
	 */
	@GameTest(maxTicks = 700)
	public void oreDigsDownForBuriedCoal(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, -4, 0, 7, -1, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		helper.setBlock(4, -2, 2, Blocks.COAL_ORE);
		AntBot bot = miner(helper, 3, 1, 7);
		expect(helper, bot, """
			import lib.dig
			s = pos()
			r = lib.dig.ore("coal", 1, 1, s.y - 3, length=4)
			print(r["ok"], count("coal"), pos() == s)
			""", List.of("True 1 True"),
			() -> helper.assertBlockPresent(Blocks.AIR, 4, -2, 2));
	}

	/** Emerald has no best_y (it is only in mountains): without y, and no exposed vein, ore gives no_ore at once. */
	@GameTest(maxTicks = 40)
	public void emeraldWithoutYGivesNoOre(GameTestHelper helper) {
		AntBot bot = miner(helper, 1, 1, 1);
		expect(helper, bot, """
			import lib.dig
			r = lib.dig.ore("emerald", 1, 1)
			print(r["reason"], r["missing"])
			""", List.of("no_ore {'minecraft:emerald': 1}"));
	}
}
