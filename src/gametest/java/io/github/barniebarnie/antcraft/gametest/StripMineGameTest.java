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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code jobs/strip_mine.py} (#514): a bot at the south edge of the area stairs three blocks
 * down north, through stone filled under the floor, and branches north under the area, past its north edge, for the coal
 * seeded beside the tunnel; then it climbs back up. The bot carries the MINER kit (a stone pickaxe, a crafting table and
 * 16 torches) so the run does not bootstrap one, and each run passes radius 8 so a neighbouring test's chest is not one
 * of its own.
 */
public class StripMineGameTest implements CustomTestMethodInvoker {
	private static final BlockPos START = new BlockPos(3, 1, 7);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		// Stone under the floor for the stairs and a branch of 8 north from z=4 to z=-4, past the area's north edge.
		for (BlockPos pos : BlockPos.betweenClosed(0, -4, -6, 7, -1, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		// Three coal ore beside the branch, at y=-2 (feet) and y=-1 (head).
		helper.setBlock(4, -2, 2, Blocks.COAL_ORE);
		helper.setBlock(4, -1, 2, Blocks.COAL_ORE);
		helper.setBlock(2, -1, 0, Blocks.COAL_ORE);
		method.invoke(this, helper);
	}

	/** The bot with the kit, y passed: it ends back at the surface with the 3 coal. */
	@GameTest(maxTicks = 1000)
	public void minesTheSeededCoalAndComesBackUp(GameTestHelper helper) {
		AntBot bot = miner(helper, START, new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, "coal 3 %d 8");
		helper.succeedWhen(() -> mined(helper, bot, START, 3));
	}

	/**
	 * Facing east from the west edge (#523): the stairs go down east and the branch runs east, past the area's east edge
	 * through stone filled under it, beside the two coal at x=4; the coal north of the branch's line is left.
	 */
	@GameTest(maxTicks = 1000)
	public void facingEastDigsEast(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(8, -4, 2, 12, -1, 4)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		BlockPos start = new BlockPos(0, 1, 3);
		AntBot bot = miner(helper, start, new ItemStack(Items.STONE_PICKAXE));
		run(helper, bot, "coal 2 %d 8 east");
		helper.succeedWhen(() -> {
			mined(helper, bot, start, 2);
			helper.assertBlockPresent(Blocks.AIR, 4, -2, 2);
			helper.assertBlockPresent(Blocks.COAL_ORE, 2, -1, 0);
		});
	}

	/**
	 * The kit's pickaxe has 10 uses left, enough for the stairs but not for a branch: before the branch the job makes a
	 * new stone one from the cobblestone the stairs gave and the two sticks it carries, and the old one breaks.
	 */
	@GameTest(maxTicks = 1200)
	public void aNearlyBrokenPickaxeIsReplaced(GameTestHelper helper) {
		ItemStack worn = new ItemStack(Items.STONE_PICKAXE);
		worn.setDamageValue(worn.getMaxDamage() - 10);
		AntBot bot = miner(helper, START, worn);
		bot.getInventory().addItem(new ItemStack(Items.STICK, 2));
		run(helper, bot, "coal 3 %d 8");
		helper.succeedWhen(() -> {
			mined(helper, bot, START, 3);
			helper.assertTrue(carried(bot, Items.STONE_PICKAXE) == 1, "carries " + carried(bot, Items.STONE_PICKAXE)
				+ " stone pickaxes: " + bot.getMainHandItem() + " in hand, " + bot.getInventory().getItems());
			helper.assertTrue(carried(bot, Items.STICK) == 0, "the sticks are left: no pickaxe was made");
		});
	}

	private static AntBot miner(GameTestHelper helper, BlockPos start, ItemStack pickaxe) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, start.getX(), start.getY(), start.getZ());
		bot.setItemSlot(EquipmentSlot.MAINHAND, pickaxe);
		bot.getInventory().addItem(new ItemStack(Items.CRAFTING_TABLE));
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 16));
		return bot;
	}

	/** The job finished and said so, the bot stands back at start, the top of its stairs, and carries n coal. */
	private static void mined(GameTestHelper helper, AntBot bot, BlockPos start, int n) {
		ScriptRun run = bot.getScript();
		List<String> log = BotScripts.log(bot, Output.Level.PRINT);
		helper.assertTrue(log.equals(List.of("strip mined " + n + " coal")), "printed " + log);
		helper.assertTrue(run != null && run.state() == ScriptRun.State.FINISHED, "the job " + (run == null ? null : run.state()));
		helper.assertTrue(bot.blockPosition().equals(helper.absolutePos(start)), "the bot is at " + helper.relativePos(bot.blockPosition()));
		helper.assertTrue(carried(bot, Items.COAL) == n, "carries " + carried(bot, Items.COAL) + " coal");
	}

	/**
	 * Runs jobs.strip_mine with args, %d in them the absolute y of y=-2, and fails the test at once when it fails or
	 * warns.
	 */
	private static void run(GameTestHelper helper, AntBot bot, String args) {
		int y = helper.absolutePos(new BlockPos(0, -2, 0)).getY();
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run jobs.strip_mine " + args.formatted(y));
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[jobs.strip_mine, 1]")), "run answered " + started);
		helper.onEachTick(() -> {
			ScriptRun run = bot.getScript();
			List<String> log = BotScripts.log(bot, Output.Level.DEBUG);
			if (run != null && run.state() == ScriptRun.State.FAILED || log.stream().anyMatch(l -> l.startsWith("strip mine:"))) {
				helper.fail("job failed: " + log + ", carries " + bot.getInventory().getItems() + " and " + bot.getMainHandItem());
			}
		});
	}

	/** How many of item the bot carries, in its hand too. */
	private static int carried(AntBot bot, Item item) {
		ItemStack hand = bot.getMainHandItem();
		return bot.getInventory().countItem(item) + (hand.is(item) ? hand.getCount() : 0);
	}
}
