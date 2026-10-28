package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
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

/**
 * {@code lib/mine.py} (#372): a bot at the dug landing of a level mines its hall and branches, the ore veins they uncover
 * into the landing chest, and seals the fluid it meets. The area is stone up to y 4. The shaft is a small one: its top at
 * (1, 6, 3), its one level at y 1 with the landing at (2, 1, 3) and the chest north of it, a hall of 3 east to (5, 1, 3)
 * and from its end a branch of 2 north and one of 2 south, so all of it lies inside the area.
 */
public class MineGameTest implements CustomTestMethodInvoker {
	private static final List<BlockPos> HALL = List.of(new BlockPos(3, 1, 3), new BlockPos(4, 1, 3), new BlockPos(5, 1, 3));
	private static final List<BlockPos> NORTH = List.of(new BlockPos(5, 1, 2), new BlockPos(5, 1, 1));
	private static final List<BlockPos> SOUTH = List.of(new BlockPos(5, 1, 4), new BlockPos(5, 1, 5));
	private static final BlockPos LANDING = new BlockPos(2, 1, 3);
	private static final BlockPos CHEST = LANDING.north();
	/** About twice the slowest pass measured (383 ticks). */
	private static final int MAX_TICKS = 800;
	private static final String SCRIPT = """
		import lib.mine
		y = pos().y
		memory["shaft"] = {"top": pos() + (-1, 5, 0), "levels": [y], "branches": 1, "length": 2}
		r = lib.mine.mine_level(y)
		print(r["ok"], r["reason"], len(r["sealed"]))
		""";

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		helper.setBlock(LANDING, Blocks.AIR);
		helper.setBlock(LANDING.above(), Blocks.AIR);
		helper.setBlock(CHEST, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
		method.invoke(this, helper);
	}

	/**
	 * Starts {@link #SCRIPT} on a bot with a stone pickaxe at the landing, as {@code /antcraft bot run} does, and fails at
	 * once when it fails or prints other than {@code printed}.
	 */
	private static AntBot mine(GameTestHelper helper, String printed) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, LANDING.getX(), LANDING.getY(), LANDING.getZ());
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
		String name = BotScripts.libraryScript(helper, "mine_", SCRIPT);
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			if (!log.isEmpty() && !log.equals(List.of(printed))) {
				helper.fail("printed " + log + ", " + describe(helper, bot));
			}
		});
		return bot;
	}

	/** Fails unless each of {@code cells} and the block above it is air. */
	private static void assertDug(GameTestHelper helper, List<BlockPos> cells, String what) {
		for (BlockPos cell : cells) {
			helper.assertTrue(helper.getBlockState(cell).isAir() && helper.getBlockState(cell.above()).isAir(),
				what + " not dug at " + cell.toShortString());
		}
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

	/** The bot's log and the blocks of the area that are neither stone nor air, for a failure. */
	private static String describe(GameTestHelper helper, AntBot bot) {
		List<String> blocks = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 4, 7)) {
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
			assertDug(helper, HALL, "hall");
			assertDug(helper, NORTH, "north branch");
			assertDug(helper, SOUTH, "south branch");
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
			assertDug(helper, HALL, "hall");
			assertDug(helper, NORTH, "north branch");
			assertDug(helper, SOUTH, "south branch");
			helper.assertTrue(helper.getBlockState(lava).is(Blocks.COBBLESTONE), "not sealed: " + describe(helper, bot));
			for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 4, 7)) {
				helper.assertTrue(!helper.getBlockState(pos).getFluidState().is(Fluids.LAVA)
					&& !helper.getBlockState(pos).getFluidState().is(Fluids.FLOWING_LAVA), "lava at " + pos.toShortString());
			}
		});
	}
}
