package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.SenseApi;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * Milestone 6's acceptance (#371): {@code lib/shaft.py} digs a ladder shaft down to two levels and the bot climbs back,
 * every time. The area is stone up to y 5, so the whole shaft lies inside it: the bot starts on the ground at (1, 6, 1),
 * {@code mineshaft} puts the top at (4, 6, 4), the column runs from the hatch at y 5 down to the levels 3 and 1, with the
 * landing chambers east of it at y 3 and west at y 1, and the stone floor at y 0 under the last.
 */
public class ShaftGameTest implements CustomTestMethodInvoker {
	/** The column of ladders, from the hatch down. */
	private static final List<BlockPos> COLUMN = List.of(new BlockPos(4, 5, 4), new BlockPos(4, 4, 4), new BlockPos(4, 3, 4), new BlockPos(4, 2, 4),
		new BlockPos(4, 1, 4));
	/** About twice the slowest pass measured (398 ticks). */
	private static final int MAX_TICKS = 800;
	private static final List<BlockPos> LANDINGS = List.of(new BlockPos(5, 3, 4), new BlockPos(3, 1, 4));
	/**
	 * Digs the shaft, retrying every second, and climbs out. The site is kept in memory up front, as {@code mineshaft} would
	 * pick it in plain stone: {@code mineshaft} checks the halls, which run into the areas of the tests beside this one,
	 * and their water or lava would move it (#387).
	 */
	private static final String SCRIPT = """
		import lib.shaft
		y = pos().y
		memory["shaft"] = {"top": pos() + (3, 0, 3), "levels": [y - 3, y - 5]}
		r = lib.shaft.dig_shaft([y - 3, y - 5])
		while not r["ok"]:
		    print(r["reason"], r["missing"])
		    wait(20)
		    r = lib.shaft.dig_shaft([y - 3, y - 5])
		print("dug", lib.shaft.to_surface().ok)
		""";

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, 7, 5, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		method.invoke(this, helper);
	}

	/** A bot on the ground with a stone pickaxe, {@code ladders} ladders, an oak trapdoor, 2 torches and 2 chests. */
	private static AntBot miner(GameTestHelper helper, int ladders) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 6, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.LADDER, ladders));
		bot.getInventory().addItem(new ItemStack(Items.OAK_TRAPDOOR));
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 2));
		bot.getInventory().addItem(new ItemStack(Items.CHEST, 2));
		return bot;
	}

	/** Starts {@link #SCRIPT} on {@code bot} from the world's script folder, as {@code /antcraft bot run} does. */
	private static void dig(GameTestHelper helper, AntBot bot) {
		run(helper, bot, SCRIPT);
	}

	private static void run(GameTestHelper helper, AntBot bot, String script) {
		String name = BotScripts.libraryScript(helper, "shaft_", script);
		List<String> started = BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.assertTrue(started.equals(List.of("commands.antcraft.bot.run[" + name + ", 1]")), "run answered " + started);
	}

	/** Fails at once when the script of the bot {@code bot} gives fails. */
	private static void failOnError(GameTestHelper helper, Supplier<AntBot> bot) {
		helper.onEachTick(() -> {
			ScriptRun run = bot.get().getScript();
			if (run != null && run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot.get(), Output.Level.DEBUG));
			}
		});
	}

	/**
	 * Succeeds once the script printed {@code printed}, the last line it prints, with a shut trapdoor in the hatch and a
	 * ladder in every block of the column under it, both facing north, a torch and a chest at each landing, and the bot on
	 * the ground.
	 */
	private static void succeedWhenDug(GameTestHelper helper, Supplier<AntBot> bot, List<String> printed) {
		helper.succeedWhen(() -> {
			String problem = problem(helper, bot.get(), printed);
			if (problem != null) {
				helper.fail(problem + "; " + describe(helper, bot.get()));
			}
		});
	}

	private static @Nullable String problem(GameTestHelper helper, AntBot bot, List<String> printed) {
		List<String> log = BotScripts.log(bot, Output.Level.PRINT);
		if (!log.equals(printed)) {
			return "printed " + log;
		}
		BlockState hatch = helper.getBlockState(COLUMN.getFirst());
		if (!hatch.is(BlockTags.WOODEN_TRAPDOORS) || hatch.getValue(TrapDoorBlock.OPEN) || hatch.getValue(TrapDoorBlock.FACING) != Direction.NORTH) {
			return "no shut hatch";
		}
		if (hatch.getValue(TrapDoorBlock.HALF) != Half.TOP) {
			return "the hatch is not flush with the ground";
		}
		for (BlockPos cell : COLUMN.subList(1, COLUMN.size())) {
			BlockState state = helper.getBlockState(cell);
			if (!state.is(Blocks.LADDER) || state.getValue(LadderBlock.FACING) != Direction.NORTH) {
				return "no ladder at " + cell.toShortString();
			}
		}
		for (BlockPos landing : LANDINGS) {
			BlockState torch = helper.getBlockState(landing);
			if (!torch.is(Blocks.TORCH) && !torch.is(Blocks.WALL_TORCH)) {
				return "no torch at " + landing.toShortString();
			}
			if (!helper.getBlockState(landing.north()).is(Blocks.CHEST)) {
				return "no chest at " + landing.north().toShortString();
			}
		}
		BlockPos at = TestPositions.relative(helper, bot.blockPosition());
		return at.getY() == 6 && !at.equals(new BlockPos(4, 6, 4)) ? null : "not on the ground";
	}

	/** The bot's log, where it is and what it carries, and the blocks round the shaft that are not stone, for a failure. */
	private static String describe(GameTestHelper helper, AntBot bot) {
		StringBuilder blocks = new StringBuilder();
		for (BlockPos pos : BlockPos.betweenClosed(2, 0, 2, 6, 6, 6)) {
			BlockState state = helper.getBlockState(pos);
			if (!state.is(Blocks.STONE) && !(pos.getY() == 6 && state.isAir())) {
				blocks.append(' ').append(pos.toShortString()).append('=').append(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
			}
		}
		List<String> items = new ArrayList<>();
		for (ItemStack stack : bot.getInventory().getItems()) {
			if (!stack.isEmpty()) {
				items.add(stack.toString());
			}
		}
		return "origin " + helper.absolutePos(BlockPos.ZERO).toShortString() + ", bot at " + TestPositions.relative(helper, bot.blockPosition()).toShortString()
			+ " holding " + bot.getMainHandItem() + " and " + items + ", blocks" + blocks + ", log " + BotScripts.log(bot, Output.Level.DEBUG);
	}

	/**
	 * Puts pockets into the stone round the shaft, from {@code seed}: gravel (two high below the ground), air, and a water
	 * source under the first level, where {@code mineshaft} does not look, at least two blocks from the other pockets so it
	 * stays where it is until the bot digs next to it.
	 */
	private static void pockets(GameTestHelper helper, long seed) {
		Random random = new Random(seed);
		BlockPos gravel = new BlockPos(2 + random.nextInt(5), 1 + random.nextInt(5), 2 + random.nextInt(5));
		BlockPos air = new BlockPos(2 + random.nextInt(5), 1 + random.nextInt(5), 2 + random.nextInt(5));
		List<BlockPos> dry = new ArrayList<>(List.of(gravel, air));
		helper.setBlock(gravel, Blocks.GRAVEL);
		if (gravel.getY() < 5) {
			helper.setBlock(gravel.above(), Blocks.GRAVEL);
			dry.add(gravel.above());
		}
		helper.setBlock(air, Blocks.AIR);
		BlockPos water;
		do {
			// Not at z 4, where the halls run, also through the areas of the tests beside this one: mineshaft would put
			// the shaft of a test whose hall runs into water elsewhere.
			water = new BlockPos(3 + random.nextInt(4), 1 + random.nextInt(2), 2 + random.nextInt(5));
		} while (water.getZ() == 4 || near(water, dry));
		helper.setBlock(water, Blocks.WATER);
	}

	private static boolean near(BlockPos pos, List<BlockPos> others) {
		return others.stream().anyMatch(other -> other.distManhattan(pos) < 2);
	}

	/** A bot digs the shaft past the pockets of {@code seed}, or in plain stone for seed 0, and climbs out. */
	private static void digsAShaft(GameTestHelper helper, long seed) {
		if (seed != 0) {
			pockets(helper, seed);
		}
		AntBot bot = miner(helper, COLUMN.size() - 1);
		dig(helper, bot);
		failOnError(helper, () -> bot);
		succeedWhenDug(helper, () -> bot, List.of("dug True"));
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftToTwoLevelsAndClimbsOut(GameTestHelper helper) {
		digsAShaft(helper, 0);
	}

	// The same 10 times over, each past pockets of its own (#371: every time).

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftPastPockets1(GameTestHelper helper) {
		digsAShaft(helper, 1);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftPastPockets2(GameTestHelper helper) {
		digsAShaft(helper, 2);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftPastPockets3(GameTestHelper helper) {
		digsAShaft(helper, 3);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftPastPockets4(GameTestHelper helper) {
		digsAShaft(helper, 4);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftPastPockets5(GameTestHelper helper) {
		digsAShaft(helper, 5);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftPastPockets6(GameTestHelper helper) {
		digsAShaft(helper, 6);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftPastPockets7(GameTestHelper helper) {
		digsAShaft(helper, 7);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftPastPockets8(GameTestHelper helper) {
		digsAShaft(helper, 8);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftPastPockets9(GameTestHelper helper) {
		digsAShaft(helper, 9);
	}

	@GameTest(maxTicks = MAX_TICKS)
	public void digsAShaftPastPockets10(GameTestHelper helper) {
		digsAShaft(helper, 10);
	}

	/** A bot saved once the column is past the first level resumes in the middle of its digging, and finishes. */
	@GameTest(maxTicks = MAX_TICKS)
	public void aBotSavedHalfWayDownResumesAndFinishes(GameTestHelper helper) {
		AntBot first = miner(helper, COLUMN.size() - 1);
		dig(helper, first);
		AntBot[] bot = {first};
		helper.onEachTick(() -> {
			if (bot[0] == first && helper.getBlockState(COLUMN.get(3)).isAir()) {
				helper.assertTrue(first.getScript().running(), "script " + first.getScript().state());
				bot[0] = ScriptGameTest.saveAndLoad(helper, first);
			}
		});
		failOnError(helper, () -> bot[0]);
		succeedWhenDug(helper, () -> {
			helper.assertTrue(bot[0] != first, "not saved yet");
			return bot[0];
		}, List.of("dug True"));
	}

	/**
	 * A bot with 2 ladders of the 4 the shaft takes stops before it digs, with what it lacks; given 2 more, it digs the
	 * shaft.
	 */
	@GameTest(maxTicks = MAX_TICKS)
	public void aBotWithoutEnoughLaddersSaysWhatItLacksAndGoesOnOnceGivenThem(GameTestHelper helper) {
		AntBot bot = miner(helper, 2);
		dig(helper, bot);
		String lacks = "missing_ingredients {'minecraft:ladder': 2}";
		boolean[] given = {false};
		helper.onEachTick(() -> {
			if (!given[0] && BotScripts.log(bot, Output.Level.PRINT).contains(lacks)) {
				helper.assertTrue(helper.getBlockState(COLUMN.getFirst()).is(Blocks.STONE), "dug before it had the ladders");
				bot.getInventory().addItem(new ItemStack(Items.LADDER, 2));
				given[0] = true;
			}
		});
		failOnError(helper, () -> bot);
		succeedWhenDug(helper, () -> bot, List.of(lacks, "dug True"));
	}

	/** A water source in the ground beside the hatch is walled off before the bot digs the hatch, and the shaft is dug (#387). */
	@GameTest(maxTicks = MAX_TICKS)
	public void aWaterSourceBesideTheHatchIsSealed(GameTestHelper helper) {
		helper.setBlock(4, 5, 5, Blocks.WATER);
		digsAShaftSealing(helper, new BlockPos(4, 5, 5));
	}

	/** Lava flowing towards the column is walled off once the column reaches it, and the shaft is dug (#387). */
	@GameTest(maxTicks = MAX_TICKS)
	public void lavaFlowingIntoTheColumnIsSealed(GameTestHelper helper) {
		helper.setBlock(4, 4, 6, Blocks.LAVA);
		helper.setBlock(4, 4, 5, Blocks.AIR);
		digsAShaftSealing(helper, new BlockPos(4, 4, 5));
	}

	/** A bot digs the shaft in plain stone but for a fluid that runs into {@code sealed}, which ends up walled off. */
	private static void digsAShaftSealing(GameTestHelper helper, BlockPos sealed) {
		AntBot bot = miner(helper, COLUMN.size() - 1);
		dig(helper, bot);
		failOnError(helper, () -> bot);
		succeedWhenDug(helper, () -> {
			helper.assertTrue(helper.getBlockState(sealed).getFluidState().isEmpty(), "no wall at " + sealed.toShortString());
			return bot;
		}, List.of("dug True"));
	}

	/**
	 * A husk in a cave the deepest landing opens up attacks the bot, and does not stop the dig for good (#387). The husk
	 * stands still and strikes as often as a husk does while the bot is in its reach: a husk of its own finds the bot or
	 * not, and walks into the column after it or not, by chance (#390).
	 */
	@GameTest(maxTicks = MAX_TICKS)
	public void aHuskInTheShaftDoesNotStopTheDig(GameTestHelper helper) {
		helper.setBlock(3, 1, 4, Blocks.AIR);
		helper.setBlock(3, 2, 4, Blocks.AIR);
		Husk husk = helper.spawn(EntityTypes.HUSK, 3, 1, 4);
		husk.setNoAi(true);
		AntBot bot = miner(helper, COLUMN.size() - 1);
		dig(helper, bot);
		failOnError(helper, () -> bot);
		int[] hits = {0};
		helper.onEachTick(() -> {
			if (helper.getTick() % 20 == 0 && husk.isWithinMeleeAttackRange(bot) && husk.hasLineOfSight(bot)
				&& husk.doHurtTarget(helper.getLevel(), bot)) {
				hits[0]++;
			}
		});
		succeedWhenDug(helper, () -> {
			helper.assertTrue(hits[0] > 0, "the husk never hit the bot");
			return bot;
		}, List.of("dug True"));
	}

	/**
	 * A cow standing for good where the deepest chest goes, in a cave beside the landing, stops the dig with a clear
	 * {@code blocked}, not a timeout; once it is gone the dig goes on and finishes (#392). Bots fight hostile mobs they see
	 * (their melee goal), so what stays in the way is a mob they leave alone.
	 */
	@GameTest(maxTicks = MAX_TICKS)
	public void aMobStandingInTheLandingIsReportedAndTheDigGoesOnOnceItLeaves(GameTestHelper helper) {
		helper.setBlock(3, 1, 3, Blocks.AIR);
		helper.setBlock(3, 2, 3, Blocks.AIR);
		Cow cow = helper.spawn(EntityTypes.COW, 3, 1, 3);
		cow.setNoAi(true);
		AntBot bot = miner(helper, COLUMN.size() - 1);
		dig(helper, bot);
		failOnError(helper, () -> bot);
		String blocked = "blocked {}";
		helper.onEachTick(() -> {
			if (cow.isAlive() && BotScripts.log(bot, Output.Level.PRINT).contains(blocked)) {
				cow.discard();
			}
		});
		succeedWhenDug(helper, () -> bot, List.of(blocked, "dug True"));
	}

	/** The bot climbs out through the shut hatch, back down to the deepest landing and out again; the hatch is shut after (#387). */
	@GameTest(maxTicks = MAX_TICKS)
	public void theBotPassesTheShutHatchBothWays(GameTestHelper helper) {
		AntBot bot = miner(helper, COLUMN.size() - 1);
		run(helper, bot, SCRIPT + """
			print("down", lib.shaft.to_level(y - 5).ok, pos().y == y - 5)
			print("up", lib.shaft.to_surface().ok)
			""");
		failOnError(helper, () -> bot);
		succeedWhenDug(helper, () -> bot, List.of("dug True", "down True True", "up True"));
	}

	/**
	 * A bot low on health climbing out of the shaft with a husk right below it in the column strikes back as it flees,
	 * rather than take the husk's blows all the way up (#395). The husk climbs after the bot (kept just below it, up to
	 * under the hatch) and strikes every second while the bot is in its reach, from the first tick: two blows kill the
	 * bot before it can climb out. The husk is nearly dead, so one blow of the bot's kills it.
	 */
	@GameTest(maxTicks = 120)
	public void aBotFleeingUpTheShaftStrikesTheMobBelowIt(GameTestHelper helper) {
		helper.setBlock(COLUMN.getFirst(), Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.NORTH)
			.setValue(TrapDoorBlock.HALF, Half.TOP));
		for (BlockPos cell : COLUMN.subList(1, COLUMN.size())) {
			helper.setBlock(cell, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 2, 4);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
		bot.setHealth(AntCraftConfig.botFleeHealth());
		Husk husk = helper.spawn(EntityTypes.HUSK, 4, 1, 4);
		husk.setNoAi(true);
		husk.setHealth(2);
		run(helper, bot, """
			import lib.shaft
			memory["shaft"] = {"top": pos() + (0, 4, 0), "levels": [pos().y + 1, pos().y - 1]}
			while not lib.shaft.to_surface():
			    wait(5)
			print("out")
			""");
		failOnError(helper, () -> bot);
		BlockPos bottom = helper.absolutePos(COLUMN.getLast());
		helper.onEachTick(() -> {
			if (!bot.isAlive()) {
				helper.fail("the bot died: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
			if (!husk.isAlive()) {
				return;
			}
			// Climbs after the bot, up to the cell under the hatch, which it cannot open.
			double y = Math.max(bottom.getY(), Math.min(bottom.getY() + 3, bot.getY() - 1));
			husk.teleportTo(bottom.getX() + 0.5, y, bottom.getZ() + 0.5);
			if (helper.getTick() % 20 == 1 && husk.isWithinMeleeAttackRange(bot) && husk.hasLineOfSight(bot)) {
				husk.doHurtTarget(helper.getLevel(), bot);
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(!husk.isAlive(), "the bot did not strike the husk");
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(log.equals(List.of("out")), "printed " + log);
			helper.assertTrue(TestPositions.relative(helper, bot.blockPosition()).getY() == 6, "not on the ground");
		});
	}

	/**
	 * As {@link #aBotFleeingUpTheShaftStrikesTheMobBelowIt}, with a husk at full health and its own AI, right below the
	 * bot on the ladders (#400): the bot keeps fleeing, striking it once as it goes, and gets away unhurt. A bot at the
	 * flee health stopping on the ladders to fight a husk on its heels would lose: the husk strikes once a second and kills
	 * it in two blows, the bot strikes every 13 ticks with a diamond sword and needs three. So it does not stop: fleeing already strikes back.
	 */
	// Measured: the bot is unhurt and the husk struck once (16 of 20 left) at tick 100.
	@GameTest(maxTicks = 120)
	public void aBotFleeingUpTheShaftGetsAwayFromAHuskAtFullHealth(GameTestHelper helper) {
		helper.setBlock(COLUMN.getFirst(), Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.NORTH)
			.setValue(TrapDoorBlock.HALF, Half.TOP));
		for (BlockPos cell : COLUMN.subList(1, COLUMN.size())) {
			helper.setBlock(cell, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 2, 4);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_PICKAXE));
		bot.setHealth(AntCraftConfig.botFleeHealth());
		Husk husk = helper.spawn(EntityTypes.HUSK, 4, 1, 4);
		run(helper, bot, """
			import lib.shaft
			memory["shaft"] = {"top": pos() + (0, 4, 0), "levels": [pos().y + 1, pos().y - 1]}
			while not lib.shaft.to_surface():
			    wait(5)
			""");
		failOnError(helper, () -> bot);
		helper.onEachTick(() -> {
			if (bot.isDeadOrDying()) { // not isAlive: the test's end removes the bot on the tick it succeeds
				helper.fail("the bot died: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.runAtTickTime(100, () -> {
			helper.assertTrue(bot.getFlightPlans() > 0, "the bot did not flee");
			helper.assertTrue(husk.getHealth() < husk.getMaxHealth(), "the bot did not strike the husk");
			helper.succeed();
		});
	}

	/**
	 * A reflex that takes over while {@code mineshaft} searches, over several ticks, does not end the search (#410):
	 * {@code shaft()} gets its site once the reflex lets go, rather than stopping with an error on {@code interrupted}. The
	 * test spends the tick's block search budget until the search runs, so it cannot finish at once, then lights fire on
	 * netherrack under the bot. Stone runs east of the area along the hall at level 3, so terrain there cannot move the site.
	 * The budget is the server's, so the test runs in an environment of its own ({@code alone}), which puts it in a batch
	 * of its own: in a shared batch it starved the block searches of the tests beside it.
	 */
	// Measured: passes at tick 7 alone, 11 in a full run.
	@GameTest(environment = "antcraft-gametest:alone", maxTicks = 25)
	public void aReflexDuringTheSearchDoesNotStopShaft(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(8, 1, 3, 24, 5, 5)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		helper.setBlock(1, 5, 1, Blocks.NETHERRACK);
		AntBot bot = miner(helper, 0);
		run(helper, bot, """
			import lib.shaft
			m = lib.shaft.shaft([pos().y - 3])
			print(m is not None and m["top"] == memory["shaft"]["top"])
			""");
		failOnError(helper, () -> bot);
		boolean[] fire = {false};
		helper.onEachTick(() -> {
			if (fire[0]) {
				return;
			}
			ScriptRun run = bot.getScript();
			String action = run == null ? null : run.action();
			if (action != null && action.startsWith("mineshaft")) {
				fire[0] = true;
				helper.setBlock(1, 6, 1, Blocks.FIRE);
			}
			// The bot's next tick finds no blocks left to search, whether its search started yet or not.
			SenseApi.SCAN_BUDGET.spend(helper.getLevel().getGameTime() + 1, SenseApi.BLOCKS_PER_TICK);
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(fire[0], "the search never ran over several ticks");
			List<String> log = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(log.equals(List.of("True")), "printed " + log);
			helper.assertFalse(helper.getLevel().getBlockState(bot.blockPosition()).is(BlockTags.FIRE), "bot still in fire");
		});
	}
}
