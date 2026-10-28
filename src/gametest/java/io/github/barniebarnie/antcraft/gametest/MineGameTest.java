package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;

/**
 * {@code lib/mine.py} (#372, #389): a bot at the dug landing of a level mines its hall and branches, the ore veins they
 * uncover into the landing chest, and seals the fluid it meets. The area is stone up to y 4. The shaft is a small one: its
 * top at (1, 6, 3), its one level at y 1 with the landing at (2, 1, 3) and the chest north of it, a hall of 3 east to
 * (5, 1, 3) and from its end a branch of 2 north and one of 2 south, so all of it lies inside the area.
 */
public class MineGameTest implements CustomTestMethodInvoker {
	private static final List<BlockPos> HALL = List.of(new BlockPos(3, 1, 3), new BlockPos(4, 1, 3), new BlockPos(5, 1, 3));
	private static final List<BlockPos> NORTH = List.of(new BlockPos(5, 1, 2), new BlockPos(5, 1, 1));
	private static final List<BlockPos> SOUTH = List.of(new BlockPos(5, 1, 4), new BlockPos(5, 1, 5));
	private static final BlockPos LANDING = new BlockPos(2, 1, 3);
	private static final BlockPos CHEST = LANDING.north();
	/** About twice the slowest pass measured (383 ticks). */
	private static final int MAX_TICKS = 800;
	/** Mines as many rounds (the format argument) of the level the bot stands on. */
	private static final String SCRIPT = """
		import lib.mine
		y = pos().y
		memory["shaft"] = {"top": pos() + (-1, 5, 0), "levels": [y], "branches": 1, "length": 2}
		r = lib.mine.mine_level(y, rounds=%d)
		print(r["ok"], r["reason"], len(r["sealed"]))
		""";

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		AABB bounds = helper.getBounds();
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, (int) bounds.getXsize() - 1, 4, (int) bounds.getZsize() - 1)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		method.invoke(this, helper);
	}

	/**
	 * Digs the landing at {@code landing} with its chest north of it, and starts {@link #SCRIPT} for {@code rounds} on a bot
	 * with a stone pickaxe there, as {@code /antcraft bot run} does.
	 */
	private static AntBot mine(GameTestHelper helper, BlockPos landing, int rounds) {
		helper.setBlock(landing, Blocks.AIR);
		helper.setBlock(landing.above(), Blocks.AIR);
		helper.setBlock(landing.north(), Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, landing.getX(), landing.getY(), landing.getZ());
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
		String name = BotScripts.libraryScript(helper, "mine_", SCRIPT.formatted(rounds));
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
		return bot;
	}

	/** One round at {@link #LANDING}, failing at once when the script fails or prints other than {@code printed}. */
	private static AntBot mine(GameTestHelper helper, String printed) {
		AntBot bot = mine(helper, LANDING, 1);
		failOnError(helper, () -> bot, printed);
		return bot;
	}

	/** Fails at once when the script of the bot {@code bot} gives fails or prints other than {@code printed}. */
	private static void failOnError(GameTestHelper helper, Supplier<AntBot> bot, String printed) {
		helper.onEachTick(() -> {
			ScriptRun run = bot.get().getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot.get(), Output.Level.DEBUG));
			}
			List<String> log = BotScripts.log(bot.get(), Output.Level.PRINT);
			if (!log.isEmpty() && !log.equals(List.of(printed))) {
				helper.fail("printed " + log + ", " + describe(helper, bot.get()));
			}
		});
	}

	/** Fails unless each of {@code cells} and the block above it is air. */
	private static void assertDug(GameTestHelper helper, List<BlockPos> cells, String what) {
		for (BlockPos cell : cells) {
			helper.assertTrue(helper.getBlockState(cell).isAir() && helper.getBlockState(cell.above()).isAir(),
				what + " not dug at " + cell.toShortString());
		}
	}

	/** Fails unless the hall and both branches of the small shaft are dug. */
	private static void assertLevelDug(GameTestHelper helper) {
		assertDug(helper, HALL, "hall");
		assertDug(helper, NORTH, "north branch");
		assertDug(helper, SOUTH, "south branch");
	}

	private static int inChest(GameTestHelper helper, Item item) {
		Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		int n = 0;
		for (int i = 0; i < chest.getContainerSize(); i++) {
			if (chest.getItem(i).is(item)) {
				n += chest.getItem(i).getCount();
			}
		}
		return n;
	}

	/** {@code stacks} full stacks of {@code item} into the bot's inventory. */
	private static void give(AntBot bot, Item item, int stacks) {
		for (int i = 0; i < stacks; i++) {
			bot.getInventory().addItem(new ItemStack(item, item.getDefaultMaxStackSize()));
		}
	}

	/** The bot's log and the blocks of the area that are neither stone nor air, for a failure. */
	private static String describe(GameTestHelper helper, AntBot bot) {
		List<String> blocks = new ArrayList<>();
		AABB bounds = helper.getBounds();
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, (int) bounds.getXsize() - 1, 4, (int) bounds.getZsize() - 1)) {
			BlockState state = helper.getBlockState(pos);
			if (!state.is(Blocks.STONE) && !state.isAir()) {
				blocks.add(pos.toShortString() + "=" + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
			}
		}
		return "bot at " + TestPositions.relative(helper, bot.blockPosition()).toShortString() + ", blocks " + blocks + ", log "
			+ BotScripts.log(bot, Output.Level.DEBUG);
	}

	/** An iron vein of 4 beside the end of the south branch, the last block two out, is mined whole into the chest. */
	@GameTest(maxTicks = MAX_TICKS)
	public void minesAHallItsBranchesAndAnIronVeinIntoTheChest(GameTestHelper helper) {
		List<BlockPos> vein = List.of(new BlockPos(6, 1, 5), new BlockPos(6, 2, 5), new BlockPos(7, 2, 5), new BlockPos(6, 2, 6));
		vein.forEach(pos -> helper.setBlock(pos, Blocks.IRON_ORE));
		AntBot bot = mine(helper, "True None 0");
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("True None 0")), "not done");
			assertLevelDug(helper);
			vein.forEach(pos -> helper.assertTrue(helper.getBlockState(pos).isAir(), "ore left at " + pos.toShortString()));
			helper.assertTrue(inChest(helper, Items.RAW_IRON) == 4, "raw iron in the chest: " + inChest(helper, Items.RAW_IRON));
		});
	}

	/**
	 * Lava past the end of the south branch is sealed with a block the bot dug once the branch's end is dug, not dug into,
	 * and the bot goes on with the north branch.
	 */
	@GameTest(maxTicks = MAX_TICKS)
	public void aLavaPocketAtABranchEndIsSealedAndTheBotGoesOn(GameTestHelper helper) {
		BlockPos lava = new BlockPos(5, 1, 6);
		helper.setBlock(lava, Blocks.LAVA);
		AntBot bot = mine(helper, "True None 1");
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("True None 1")), "not done");
			assertLevelDug(helper);
			helper.assertTrue(helper.getBlockState(lava).is(Blocks.COBBLESTONE), "not sealed: " + describe(helper, bot));
			for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 4, 7)) {
				helper.assertTrue(!helper.getBlockState(pos).getFluidState().is(Fluids.LAVA)
					&& !helper.getBlockState(pos).getFluidState().is(Fluids.FLOWING_LAVA), "lava at " + pos.toShortString());
			}
		});
	}

	/**
	 * In a wide area, the landing at (10, 1, 10): mine_level(level, rounds=2) digs the second round of branches on from the
	 * ends of the first, 2 more north and south, and puts torches at its torch spots, the fourth block of each branch.
	 */
	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 900)
	public void aSecondRoundOfBranchesIsDugOnAndLitAtItsTorchSpots(GameTestHelper helper) {
		AntBot bot = mine(helper, new BlockPos(10, 1, 10), 2);
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 2));
		failOnError(helper, () -> bot, "True None 0");
		List<BlockPos> north = List.of(new BlockPos(13, 1, 9), new BlockPos(13, 1, 8), new BlockPos(13, 1, 7), new BlockPos(13, 1, 6));
		List<BlockPos> south = List.of(new BlockPos(13, 1, 11), new BlockPos(13, 1, 12), new BlockPos(13, 1, 13), new BlockPos(13, 1, 14));
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("True None 0")), "not done");
			for (BlockPos cell : Stream.concat(north.stream(), south.stream()).toList()) {
				boolean torch = cell.equals(north.getLast()) || cell.equals(south.getLast());
				BlockState feet = helper.getBlockState(cell);
				helper.assertTrue((torch ? feet.is(Blocks.TORCH) || feet.is(Blocks.WALL_TORCH) : feet.isAir()) && helper.getBlockState(cell.above()).isAir(),
					"branch not dug or lit at " + cell.toShortString() + ": " + describe(helper, bot));
			}
		});
	}

	/**
	 * A bot with 3 slots free, the rest junk (gravel, flint, granite) and cobblestone, puts the junk and the cobblestone
	 * beyond 64 into the landing chest before it digs, and goes on to mine the level.
	 */
	@GameTest(maxTicks = MAX_TICKS)
	public void aNearlyFullBotEmptiesItsJunkIntoTheChestAndGoesOn(GameTestHelper helper) {
		AntBot bot = mine(helper, "True None 0");
		give(bot, Items.GRAVEL, 8);
		give(bot, Items.FLINT, 8);
		give(bot, Items.GRANITE, 4);
		give(bot, Items.COBBLESTONE, 4);
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("True None 0")), "not done");
			assertLevelDug(helper);
			helper.assertTrue(inChest(helper, Items.GRAVEL) == 512 && inChest(helper, Items.FLINT) == 512 && inChest(helper, Items.GRANITE) == 256,
				"junk in the chest: " + inChest(helper, Items.GRAVEL) + " gravel, " + inChest(helper, Items.FLINT) + " flint, "
					+ inChest(helper, Items.GRANITE) + " granite");
			helper.assertTrue(bot.getInventory().countItem(Items.COBBLESTONE) == 64, "kept " + bot.getInventory().countItem(Items.COBBLESTONE));
		});
	}

	/** A bot with 3 slots free, the rest gravel, whose landing chest is full stops with no_space before it digs. */
	@GameTest(maxTicks = 20)
	public void aNearlyFullBotWithAFullChestGivesNoSpace(GameTestHelper helper) {
		AntBot bot = mine(helper, "False no_space 0");
		Container chest = helper.getBlockEntity(CHEST, ChestBlockEntity.class);
		for (int i = 0; i < chest.getContainerSize(); i++) {
			chest.setItem(i, new ItemStack(Items.STONE, 64));
		}
		give(bot, Items.GRAVEL, 24);
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("False no_space 0")), "not done");
			helper.assertTrue(helper.getBlockState(HALL.getFirst()).is(Blocks.STONE), "dug with no room");
		});
	}

	/**
	 * On a granite level, a bot with 3 slots free, the rest granite (junk), keeps a stock of it when it empties itself
	 * into the chest, and seals the water in the first block of the hall with it (#392).
	 */
	@GameTest(maxTicks = 40)
	public void aBotOnAGraniteLevelKeepsBlocksToSealAWaterPocket(GameTestHelper helper) {
		AABB bounds = helper.getBounds();
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, (int) bounds.getXsize() - 1, 4, (int) bounds.getZsize() - 1)) {
			helper.setBlock(pos, Blocks.GRANITE);
		}
		helper.setBlock(HALL.getFirst(), Blocks.WATER);
		AntBot bot = mine(helper, "True None 1");
		give(bot, Items.GRANITE, 24);
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("True None 1")), "not done");
			helper.assertTrue(helper.getBlockState(HALL.getFirst()).is(Blocks.GRANITE), "not sealed: " + describe(helper, bot));
			helper.assertTrue(inChest(helper, Items.GRANITE) == 24 * 64 - 16, "granite in the chest: " + inChest(helper, Items.GRANITE));
		});
	}

	/** A bot with 3 slots free, the rest bread, which is neither ore nor junk, stops with no_space before it digs (#392). */
	@GameTest(maxTicks = 20)
	public void aBotFullOfFoodGivesNoSpace(GameTestHelper helper) {
		AntBot bot = mine(helper, "False no_space 0");
		give(bot, Items.BREAD, 24);
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("False no_space 0")), "not done");
			helper.assertTrue(helper.getBlockState(HALL.getFirst()).is(Blocks.STONE), "dug with no room");
		});
	}

	/** A bot saved once it dug into the south branch resumes in the middle of the branch, and finishes the level. */
	@GameTest(maxTicks = MAX_TICKS)
	public void aBotSavedMidBranchResumesAndFinishesTheLevel(GameTestHelper helper) {
		AntBot first = mine(helper, LANDING, 1);
		AntBot[] bot = {first};
		helper.onEachTick(() -> {
			if (bot[0] == first && helper.getBlockState(SOUTH.getFirst()).isAir() && !helper.getBlockState(SOUTH.getLast()).isAir()) {
				helper.assertTrue(first.getScript().running(), "script " + first.getScript().state());
				bot[0] = ScriptGameTest.saveAndLoad(helper, first);
			}
		});
		failOnError(helper, () -> bot[0], "True None 0");
		helper.succeedWhen(() -> {
			helper.assertTrue(bot[0] != first, "not saved yet");
			helper.assertTrue(BotScripts.log(bot[0], Output.Level.PRINT).equals(List.of("True None 0")), "not done");
			assertLevelDug(helper);
		});
	}
}
