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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * {@code jobs/ore_miner.py} (#1002; docs/cookbook/ore-miner.md). The bot's home is where it spawns, (3, 1, 6); stone is
 * filled under the floor down to y -4, and the job mines at y -2 in the area it is given in place of its home chunk:
 * stairs north from home to (3, -2, 3), a corridor along x there and branches north and south from it every 3 blocks.
 * The bot carries the MINER kit, three chests for its depot and two coal.
 */
public class OreMinerGameTest implements CustomTestMethodInvoker {
	private static final BlockPos HOME = new BlockPos(3, 1, 6);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (BlockPos pos : BlockPos.betweenClosed(0, -4, 0, 7, -1, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		method.invoke(this, helper);
	}

	/**
	 * Iron ore in the walls of the two columns of x 2 to 6 (x 3 and 6): the bot mines both, smelts the raw iron at home
	 * and ends there with the ingots in its depot, the grid dug.
	 */
	// Measured: passes at tick 1251 (red: No module named 'jobs.ore_miner').
	@GameTest(maxTicks = 2500)
	public void minesSmeltsAndStoresTheIronAtHome(GameTestHelper helper) {
		helper.setBlock(2, -2, 1, Blocks.IRON_ORE);
		helper.setBlock(5, -1, 1, Blocks.IRON_ORE);
		AntBot bot = miner(helper);
		ScriptRun run = run(helper, bot, 2, 0, 6, 7);
		helper.succeedWhen(() -> {
			ended(helper, run, bot);
			helper.assertValueEqual(stored(helper, Items.IRON_INGOT), 2, "iron ingots stored");
			helper.assertValueEqual(GameTests.carried(bot, Items.RAW_IRON), 0, "raw iron carried");
		});
	}

	/**
	 * A bot with 2 free slots goes home at the bottom of its stairs, before any branch, stores the dirt it carries and
	 * goes back down to dig the grid.
	 */
	// Measured: passes at tick 772 (red: No module named 'jobs.ore_miner'; with FREE 0, the dirt stored after the branches).
	@GameTest(maxTicks = 1600)
	public void aFullInventorySendsItHomeAndBackDown(GameTestHelper helper) {
		AntBot bot = miner(helper);
		for (int i = 0, free = 0; i < bot.getInventory().getContainerSize(); i++) {
			if (bot.getInventory().getItem(i).isEmpty() && free++ >= 2) {
				bot.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
			}
		}
		ScriptRun run = run(helper, bot, 2, 0, 6, 7);
		boolean[] early = {false, false};
		helper.onEachTick(() -> {
			if (!early[0] && stored(helper, Items.DIRT) > 0) {
				early[0] = true;
				early[1] = helper.getBlockState(new BlockPos(3, -2, 2)).is(Blocks.STONE);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(early[0], "no dirt stored");
			helper.assertTrue(early[1], "the dirt was stored after the first branch was dug");
			ended(helper, run, bot);
			helper.assertBlockPresent(Blocks.AIR, 6, -2, 0);
		});
	}

	/**
	 * The area x 2 to 4, z 2 to 6 has one column, x 3, whose north branch is one block long and whose south one would
	 * undermine the stairs: once the north one is dug the job ends with the bot at home.
	 */
	// Measured: passes at tick 212 (red: No module named 'jobs.ore_miner').
	@GameTest(maxTicks = 450)
	public void aDugGridEndsTheJobAtHome(GameTestHelper helper) {
		AntBot bot = miner(helper);
		ScriptRun run = run(helper, bot, 2, 2, 4, 6);
		helper.succeedWhen(() -> {
			ended(helper, run, bot);
			helper.assertBlockPresent(Blocks.AIR, 3, -2, 2);
			helper.assertBlockPresent(Blocks.STONE, 3, -2, 1);
			helper.assertBlockPresent(Blocks.STONE, 3, -2, 4);
		});
	}

	/**
	 * Stairs fenced into the 3x3 area x 2 to 4, z 4 to 6 down to y -10 spiral clockwise round its edge, six legs, and the
	 * grid at their bottom (4, -10, 4) is dug: one branch west at z 5.
	 */
	// Measured: passes at tick 824 (red: ore miner: outside at y -5, after four legs).
	@GameTest(maxTicks = 1700)
	public void stairsInASmallAreaSpiralDownPastFourLegs(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, -12, 0, 7, -5, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		AntBot bot = miner(helper);
		ScriptRun run = run(helper, bot, 1, -10, 2, 4, 4, 6);
		helper.succeedWhen(() -> {
			ended(helper, run, bot, HOME);
			helper.assertBlockPresent(Blocks.AIR, 4, -10, 4);
			helper.assertBlockPresent(Blocks.AIR, 2, -10, 5);
		});
	}

	/**
	 * A coal miner whose home is below coal's best y (96), run with no y, digs its stairs down to DEPTH (8) below home,
	 * y -7, in the 3x3 area of stairsInASmallAreaSpiralDownPastFourLegs, and mines there, not at home's level (#1006).
	 */
	// Measured: passes at tick 584 in a class run (red: the lowest y the bot stood at was 1).
	@GameTest(maxTicks = 1200)
	public void aCoalMinerBelowItsBestYMinesBelowHome(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, -10, 0, 7, -5, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		AntBot bot = miner(helper);
		BlockPos a = helper.absolutePos(new BlockPos(2, 0, 4));
		BlockPos b = helper.absolutePos(new BlockPos(4, 0, 6));
		ScriptRun run = BotScripts.run(bot, "import jobs.ore_miner\njobs.ore_miner.run(\"coal\", 1, 32, None, (%d, %d, %d, %d))\n"
			.formatted(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ())));
		BotScripts.failOnWarning(helper, bot);
		double[] lowest = {Double.MAX_VALUE};
		int floor = helper.absolutePos(BlockPos.ZERO).getY();
		helper.onEachTick(() -> {
			if (!bot.isRemoved()) {
				lowest[0] = Math.min(lowest[0], bot.getY() - floor);
			}
		});
		helper.succeedWhen(() -> {
			ended(helper, run, bot);
			helper.assertValueEqual((int) Math.floor(lowest[0]), -7, "the lowest y the bot stood at");
		});
	}

	/**
	 * A claim of 2 areas, x 2 to 6 and east of it x 7 to 11 (z 2 to 6): once the first is dug the bot tunnels east into
	 * the second at y -2 and digs its grid, a corridor along z through (7, -2, 3) and branches east, mining the iron ore
	 * there; then it is finished.
	 */
	// Measured: passes at tick 1305 (red: (7, -2, 3) still stone, only the first area dug).
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 2600)
	public void afterItsFirstChunkItMinesTheNextOfTheSpiral(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, -4, 0, 14, -1, 9)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		helper.setBlock(9, -2, 2, Blocks.IRON_ORE);
		AntBot bot = miner(helper);
		ScriptRun run = run(helper, bot, 2, -2, 2, 2, 6, 6);
		helper.succeedWhen(() -> {
			ended(helper, run, bot, HOME);
			helper.assertBlockPresent(Blocks.AIR, 7, -2, 3);
			helper.assertBlockPresent(Blocks.AIR, 11, -2, 3);
			helper.assertBlockPresent(Blocks.AIR, 11, -2, 6);
			helper.assertBlockPresent(Blocks.STONE, 12, -2, 3);
			helper.assertValueEqual(stored(helper, Items.IRON_INGOT), 1, "iron ingots stored");
		});
	}

	/**
	 * A claim of 3 areas, x 2 to 6, x 7 to 11 east of it and z 7 to 11 south of that: once the bot has tunnelled into the
	 * third it is filled up, so it goes home from there, stores the dirt and walks back by its waypoints (stairs, then
	 * each tunnel's entry) to the third, two tunnels from the stairs, digs its grid and comes home finished (#1007).
	 */
	// Measured: passes at tick 1673 in a class run; green before too (1681): goto found the 2-tunnel route in one go.
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 3400)
	public void aLaterTripReachesAChunkTwoTunnelsFromTheStairsAndComesHome(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(0, -4, 0, 14, -1, 14)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		AntBot bot = miner(helper);
		ScriptRun run = run(helper, bot, 3, -2, 2, 2, 6, 6);
		boolean[] filled = {false};
		helper.onEachTick(() -> {
			BlockPos at = bot.isRemoved() ? BlockPos.ZERO : TestPositions.relative(helper, bot.blockPosition());
			if (!filled[0] && at.getX() >= 7 && at.getZ() >= 7) {
				filled[0] = true;
				for (int i = 0, free = 0; i < bot.getInventory().getContainerSize(); i++) {
					if (bot.getInventory().getItem(i).isEmpty() && free++ >= 2) {
						bot.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
					}
				}
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(filled[0], "the bot never reached the third area");
			ended(helper, run, bot, HOME);
			helper.assertTrue(stored(helper, Items.DIRT) > 0, "no dirt stored");
			helper.assertBlockPresent(Blocks.AIR, 7, -2, 7);
			helper.assertBlockPresent(Blocks.AIR, 11, -2, 7);
			helper.assertBlockPresent(Blocks.AIR, 11, -2, 11);
		});
	}

	/**
	 * jobs.ore_miner.walk to (5, 1, 6), then (5, 1, 2), bedrock, then (1, 1, 1): it fails at the second waypoint, which its
	 * result gives as at (#1007).
	 */
	// Measured: passes at tick 83 in a class run (red: module 'jobs.ore_miner' has no attribute 'walk').
	@GameTest(maxTicks = 200)
	public void aFailedLegGivesTheWaypointItFailedAt(GameTestHelper helper) {
		helper.setBlock(5, 1, 2, Blocks.BEDROCK);
		helper.setBlock(5, 2, 2, Blocks.BEDROCK);
		AntBot bot = miner(helper);
		BlockPos a = helper.absolutePos(new BlockPos(5, 1, 6));
		BlockPos b = helper.absolutePos(new BlockPos(5, 1, 2));
		BlockPos c = helper.absolutePos(new BlockPos(1, 1, 1));
		ScriptRun run = BotScripts.run(bot, ("import jobs.ore_miner\nb = Pos(%d, %d, %d)\n"
			+ "r = jobs.ore_miner.walk([Pos(%d, %d, %d), b, Pos(%d, %d, %d)])\nprint(r[\"ok\"], r[\"at\"] == b)\n").formatted(
			b.getX(), b.getY(), b.getZ(), a.getX(), a.getY(), a.getZ(), c.getX(), c.getY(), c.getZ()));
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "the script " + run.state());
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(printed.equals(List.of("False True")), "printed " + printed);
		});
	}

	/**
	 * A claim of the one-column area x 2 to 4, z 2 to 6: once dug the bot is at home and says it is finished; run again,
	 * it says so once more and stays at home.
	 */
	// Measured: passes at tick 217 (red: said "ore miner: dug the chunk", not finished).
	@GameTest(maxTicks = 450)
	public void aClearedClaimEndsFinishedAndASecondRunDoesNotMine(GameTestHelper helper) {
		AntBot bot = miner(helper);
		ScriptRun first = run(helper, bot, 1, -2, 2, 2, 4, 6);
		String finished = "ore_miner raw_iron: finished my 1 chunks around " + home(helper, HOME) + ", move me or give me a new job";
		ScriptRun[] second = {null};
		double[] away = {0};
		helper.onEachTick(() -> {
			if (second[0] == null && first.state() == ScriptRun.State.FINISHED) {
				second[0] = BotScripts.run(bot, source(helper, 1, -2, 2, 2, 4, 6));
			} else if (second[0] != null) {
				away[0] = Math.max(away[0], Math.sqrt(bot.blockPosition().distSqr(helper.absolutePos(HOME))));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(second[0] != null, "the first run " + first.state());
			ended(helper, second[0], bot, HOME);
			List<String> said = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertValueEqual(said.stream().filter(finished::equals).count(), 2L, "times said finished in " + said);
			helper.assertTrue(away[0] <= 1, "the second run went " + away[0] + " blocks from home");
		});
	}

	/**
	 * A bot whose claim of x 2 to 4, z 2 to 6 is finished, given a new home (4, 1, 6) in it, claims it afresh: it digs
	 * new stairs from there to (4, -2, 3) and the branch south at x 3 it did not dig before.
	 */
	// Measured: passes at tick 512 (red: (4, -2, 3) still stone, the second run ended at once).
	@GameTest(maxTicks = 1000)
	public void aNewHomeStartsANewClaim(GameTestHelper helper) {
		AntBot bot = miner(helper);
		ScriptRun first = run(helper, bot, 1, -2, 2, 2, 4, 6);
		BlockPos moved = new BlockPos(4, 1, 6);
		ScriptRun[] second = {null};
		helper.onEachTick(() -> {
			if (second[0] == null && first.state() == ScriptRun.State.FINISHED) {
				helper.assertBlockPresent(Blocks.STONE, 3, -2, 6);
				bot.setHome(helper.absolutePos(moved));
				second[0] = BotScripts.run(bot, source(helper, 1, -2, 2, 2, 4, 6));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(second[0] != null, "the first run " + first.state());
			ended(helper, second[0], bot, moved);
			helper.assertBlockPresent(Blocks.AIR, 4, -2, 3);
			helper.assertBlockPresent(Blocks.AIR, 3, -2, 6);
		});
	}

	/**
	 * A Fortune III iron pickaxe in a chest at (0, 1, 4), near home: after its trip, in the one-column area of
	 * aDugGridEndsTheJobAtHome, the bot holds it and its old stone pickaxe is in its depot.
	 */
	// Measured: passes at tick 214 (red: the bot holds 1 minecraft:stone_pickaxe).
	@GameTest(maxTicks = 450)
	public void aBetterPickaxeInAHomeChestIsHeldAfterTheTrip(GameTestHelper helper) {
		ItemStack fortune = new ItemStack(Items.IRON_PICKAXE);
		enchant(helper, fortune, Enchantments.FORTUNE, 3);
		ChestBlockEntity chest = chest(helper, fortune);
		AntBot bot = miner(helper);
		ScriptRun run = run(helper, bot, 2, 2, 4, 6);
		helper.succeedWhen(() -> {
			ended(helper, run, bot);
			ItemStack hand = bot.getMainHandItem();
			helper.assertTrue(hand.is(Items.IRON_PICKAXE) && hand.isEnchanted(), "the bot holds " + hand);
			helper.assertValueEqual(chest.countItem(Items.IRON_PICKAXE), 0, "iron pickaxes left in the chest");
			helper.assertValueEqual(stored(helper, Items.STONE_PICKAXE), 1, "stone pickaxes stored");
		});
	}

	/**
	 * A worn plain stone pickaxe and a Fortune III golden one, below the stone that iron needs, in a chest near home:
	 * both are left there and the bot keeps its own stone pickaxe.
	 */
	// Measured: passes at tick 214 (green before #1004 too, as nothing took a pickaxe from a chest).
	@GameTest(maxTicks = 450)
	public void aWorsePickaxeInAHomeChestIsLeftThere(GameTestHelper helper) {
		ItemStack worn = new ItemStack(Items.STONE_PICKAXE);
		worn.setDamageValue(120);
		ItemStack golden = new ItemStack(Items.GOLDEN_PICKAXE);
		enchant(helper, golden, Enchantments.FORTUNE, 3);
		ChestBlockEntity chest = chest(helper, worn, golden);
		AntBot bot = miner(helper);
		ScriptRun run = run(helper, bot, 2, 2, 4, 6);
		helper.succeedWhen(() -> {
			ended(helper, run, bot);
			ItemStack hand = bot.getMainHandItem();
			helper.assertTrue(hand.is(Items.STONE_PICKAXE) && hand.getDamageValue() < 120, "the bot holds " + hand);
			helper.assertValueEqual(chest.countItem(Items.STONE_PICKAXE), 1, "stone pickaxes left in the chest");
			helper.assertValueEqual(chest.countItem(Items.GOLDEN_PICKAXE), 1, "golden pickaxes left in the chest");
		});
	}

	// One ore block at (3, -2, 2), dug by the one branch of the area of aDugGridEndsTheJobAtHome (#1005): the bot ends at
	// home with what it drops, smelted for copper and gold, in its depot. Iron-tier ores get an iron pickaxe in hand.

	// Measured: passes at tick 224 (red: ore miner: could not smelt coal: no_recipe).
	@GameTest(maxTicks = 450)
	public void aCoalMinerStoresItsCoal(GameTestHelper helper) {
		mines(helper, Blocks.COAL_ORE, "coal", Items.STONE_PICKAXE, Items.STONE_PICKAXE, Items.COAL, 3);
	}

	// Measured: passes at tick 654 with a drop of 2 to 5 raw copper, each smelted in 200 ticks (green
	// before #1005 too: raw copper was smelted already).
	@GameTest(maxTicks = 2400)
	public void aCopperMinerStoresCopperIngots(GameTestHelper helper) {
		mines(helper, Blocks.COPPER_ORE, "raw_copper", Items.STONE_PICKAXE, Items.STONE_PICKAXE, Items.COPPER_INGOT, 2);
	}

	// Measured: passes at tick 188 (red: ore miner: could not smelt redstone: no_recipe).
	@GameTest(maxTicks = 450)
	public void aRedstoneMinerStoresItsRedstone(GameTestHelper helper) {
		mines(helper, Blocks.REDSTONE_ORE, "redstone", Items.IRON_PICKAXE, Items.IRON_PICKAXE, Items.REDSTONE, 4);
	}

	// Measured: passes at tick 188 (red: ore miner: could not smelt diamond: no_recipe).
	@GameTest(maxTicks = 450)
	public void aDiamondMinerStoresItsDiamond(GameTestHelper helper) {
		mines(helper, Blocks.DIAMOND_ORE, "diamond", Items.IRON_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND, 1);
	}

	// Measured: passes at tick 224 (red: ore miner: could not smelt lapis_lazuli: no_recipe).
	@GameTest(maxTicks = 450)
	public void aLapisMinerStoresItsLapis(GameTestHelper helper) {
		mines(helper, Blocks.LAPIS_ORE, "lapis_lazuli", Items.STONE_PICKAXE, Items.STONE_PICKAXE, Items.LAPIS_LAZULI, 4);
	}

	/**
	 * A gold miner with only a stone pickaxe, and 3 iron ingots and 2 sticks in a chest near home, makes an iron pickaxe
	 * before it goes down (lib.get.ensure_tool), then mines, smelts and stores the gold.
	 */
	// Measured: passes at tick 528 (green before #1005 too: ensure_pickaxe asks for the tier gold needs).
	@GameTest(maxTicks = 900)
	public void aGoldMinerGetsAnIronPickaxeFirstAndStoresGoldIngots(GameTestHelper helper) {
		chest(helper, new ItemStack(Items.IRON_INGOT, 3), new ItemStack(Items.STICK, 2));
		mines(helper, Blocks.GOLD_ORE, "raw_gold", Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.GOLD_INGOT, 1);
	}

	/**
	 * A Fortune III iron pickaxe in a chest near home is taken before the first trip, not only on the way back: the bot
	 * holds it when it first goes below the floor, and its stone pickaxe ends in the depot.
	 */
	// Measured: passes at tick 183 (red: went down holding 1 minecraft:stone_pickaxe).
	@GameTest(maxTicks = 450)
	public void aBetterPickaxeInAHomeChestIsTakenBeforeTheFirstTrip(GameTestHelper helper) {
		ItemStack fortune = new ItemStack(Items.IRON_PICKAXE);
		enchant(helper, fortune, Enchantments.FORTUNE, 3);
		chest(helper, fortune);
		AntBot bot = miner(helper);
		ScriptRun run = run(helper, bot, 2, 2, 4, 6);
		ItemStack[] down = downWith(helper, bot);
		helper.succeedWhen(() -> {
			ended(helper, run, bot);
			helper.assertTrue(down[0] != null && down[0].is(Items.IRON_PICKAXE), "went down holding " + down[0]);
			helper.assertValueEqual(stored(helper, Items.STONE_PICKAXE), 1, "stone pickaxes stored");
		});
	}

	/**
	 * Has a bot holding pickaxe mine ore with block at (3, -2, 2) in the one-column area of aDugGridEndsTheJobAtHome;
	 * succeeds once it went down holding a pickaxe of item down and is home with at least n of stored in its depot and,
	 * for a raw ore, none of it stored. A bot that smelts carries a furnace, as the area gives too little cobblestone.
	 */
	private static void mines(GameTestHelper helper, Block block, String ore, Item pickaxe, Item down, Item stored, int n) {
		helper.setBlock(3, -2, 2, block);
		AntBot bot = miner(helper);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(pickaxe));
		Item raw = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(ore));
		if (raw != stored) {
			bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		}
		ScriptRun run = BotScripts.run(bot, source(helper, ore, 1, -2, 2, 2, 4, 6));
		BotScripts.failOnWarning(helper, bot);
		ItemStack[] held = downWith(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(held[0] != null && held[0].is(down), "went down holding " + held[0]);
			ended(helper, run, bot);
			helper.assertTrue(stored(helper, stored) >= n, stored(helper, stored) + " " + stored + " stored, " + n + " wanted");
			if (raw != stored) {
				helper.assertValueEqual(stored(helper, raw), 0, ore + " stored");
			}
		});
	}

	/** What bot holds when it first goes below the floor, null till then. */
	private static ItemStack[] downWith(GameTestHelper helper, AntBot bot) {
		ItemStack[] down = {null};
		int floor = helper.absolutePos(BlockPos.ZERO).getY();
		helper.onEachTick(() -> {
			if (down[0] == null && !bot.isRemoved() && bot.getY() < floor) {
				down[0] = bot.getMainHandItem().copy();
			}
		});
		return down;
	}

	/** A chest at (0, 1, 4), away from the stairs and the depot's row, holding stacks. */
	private static ChestBlockEntity chest(GameTestHelper helper, ItemStack... stacks) {
		helper.setBlock(0, 1, 4, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(new BlockPos(0, 1, 4), ChestBlockEntity.class);
		for (int i = 0; i < stacks.length; i++) {
			chest.setItem(i, stacks[i]);
		}
		return chest;
	}

	private static void enchant(GameTestHelper helper, ItemStack stack, ResourceKey<Enchantment> key, int level) {
		stack.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), level);
	}

	private static String home(GameTestHelper helper, BlockPos home) {
		BlockPos h = helper.absolutePos(home);
		return h.getX() + " " + h.getY() + " " + h.getZ();
	}

	private static AntBot miner(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, HOME.getX(), HOME.getY(), HOME.getZ());
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 16));
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 3));
		bot.getInventory().addItem(new ItemStack(Items.COAL, 2));
		return bot;
	}

	/** Runs jobs.ore_miner for raw iron at y -2 in the area x0..x1, z0..z1 (relative), failing at once on a warning. */
	private static ScriptRun run(GameTestHelper helper, AntBot bot, int x0, int z0, int x1, int z1) {
		return run(helper, bot, 1, -2, x0, z0, x1, z1);
	}

	/**
	 * Runs jobs.ore_miner for raw iron with a claim of chunks areas the size of x0..x1, z0..z1 (relative), the first of
	 * them, at y, failing at once on a warning.
	 */
	private static ScriptRun run(GameTestHelper helper, AntBot bot, int chunks, int y, int x0, int z0, int x1, int z1) {
		ScriptRun run = BotScripts.run(bot, source(helper, chunks, y, x0, z0, x1, z1));
		BotScripts.failOnWarning(helper, bot);
		return run;
	}

	private static String source(GameTestHelper helper, int chunks, int y, int x0, int z0, int x1, int z1) {
		return source(helper, "raw_iron", chunks, y, x0, z0, x1, z1);
	}

	private static String source(GameTestHelper helper, String ore, int chunks, int y, int x0, int z0, int x1, int z1) {
		BlockPos a = helper.absolutePos(new BlockPos(x0, y, z0));
		BlockPos b = helper.absolutePos(new BlockPos(x1, y, z1));
		return "import jobs.ore_miner\njobs.ore_miner.run(\"%s\", %d, 32, %d, (%d, %d, %d, %d))\n".formatted(ore, chunks, a.getY(),
			Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()));
	}

	/** The job finished with the bot at home. */
	private static void ended(GameTestHelper helper, ScriptRun run, AntBot bot) {
		ended(helper, run, bot, HOME);
	}

	/** The job finished with the bot at home, relative. */
	private static void ended(GameTestHelper helper, ScriptRun run, AntBot bot, BlockPos home) {
		helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "the job " + run.state());
		helper.assertTrue(bot.blockPosition().equals(helper.absolutePos(home)), "the bot is at " + TestPositions.relative(helper, bot.blockPosition()));
	}

	/** How many of item the chests standing in the area at y 1 to 2 hold. */
	private static int stored(GameTestHelper helper, Item item) {
		int n = 0;
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 2, 7)) {
			if (helper.getBlockState(pos).is(Blocks.CHEST)) {
				n += helper.getBlockEntity(pos.immutable(), ChestBlockEntity.class).countItem(item);
			}
		}
		return n;
	}
}
