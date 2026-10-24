package io.github.barniebarnie.antcraft.gametest;

import com.mojang.datafixers.util.Either;
import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.colony.BlueprintPlacement;
import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.PlacedBlueprint;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bot benchmark: a colony of {@link #BOTS} bots in a 48x48 area doing mixed work (felling trees, building a farm
 * and a warehouse, digging the mineshaft and mining it, farming, hauling), with a player watching its ghosts, for
 * {@link #MEASURED_TICKS} ticks. Logs the average and worst server tick and where the time went (see
 * {@link TickProfiler}), and fails if the average tick reaches {@link #TICK_BUDGET_MS}. The layout is fixed, ores
 * included (seeded), so runs compare. Manual-only: {@code ./gradlew runGameTest -Pbenchmark} runs it.
 */
public class BenchmarkGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("AntCraft game tests");
	static final int BOTS = 30;
	/** How long the colony settles in before measuring: the bots spawn and the first survey runs. */
	private static final int WARM_UP_TICKS = 20;
	/** Three minutes of server ticks. */
	static final int MEASURED_TICKS = 3600;
	/** One server tick of the 20 a second. */
	private static final double TICK_BUDGET_MS = 50;
	/** Height of the ground's top block; stone below it, with ores. */
	private static final int GROUND = 12;
	private static final int SIZE = 48;
	private static final BlockPos CORE = new BlockPos(24, GROUND + 1, 24);

	@GameTest(structure = "antcraft-gametest:benchmark", maxTicks = WARM_UP_TICKS + MEASURED_TICKS + 20, manualOnly = true)
	public void thirtyBotsDoingMixedWork(GameTestHelper helper) {
		GameTests.start(helper);
		int y0 = helper.absolutePos(BlockPos.ZERO).getY();
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64, List.of(y0 + 8, y0 + 3), false, AntCraftConfig.replantMatchWaitTicks()));
		ground(helper);
		for (int[] tree : new int[][] {{4, 4}, {12, 3}, {20, 4}, {30, 3}, {43, 5}, {44, 16}, {43, 28}, {3, 20}, {4, 42}, {16, 44}, {28, 44}, {42, 43}}) {
			tree(helper, tree[0], tree[1]);
		}
		wheat(helper);
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE)).orElseThrow();
		colony.setRadius(SIZE / 2 - 1);
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		BlockPos watch = helper.absolutePos(CORE.offset(0, 0, -3));
		player.setPos(watch.getX() + 0.5, watch.getY(), watch.getZ() + 0.5);
		// As a player's placing does: the starter projects (tools, the mineshaft) start.
		ModBlocks.COLONY_CORE.setPlacedBy(helper.getLevel(), helper.absolutePos(CORE), helper.getBlockState(CORE), player, new ItemStack(ModItems.COLONY_CORE));
		place(helper, colony, "farm", new BlockPos(8, GROUND + 1, 30));
		place(helper, colony, "warehouse", new BlockPos(32, GROUND + 1, 10));
		Container core = (Container) helper.getBlockEntity(CORE, BlockEntity.class);
		List<ItemStack> supplies = List.of(new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.OAK_LOG, 64), new ItemStack(Items.COBBLESTONE, 64),
			new ItemStack(Items.COBBLESTONE, 64), new ItemStack(Items.WHEAT_SEEDS, 32), new ItemStack(Items.TORCH, 32), new ItemStack(Items.COAL, 16),
			new ItemStack(Items.CHEST, 10), new ItemStack(Items.DIRT, 64));
		for (int i = 0; i < supplies.size(); i++) {
			core.setItem(i, supplies.get(i));
		}
		List<Item> tools = List.of(Items.STONE_PICKAXE, Items.STONE_AXE, Items.STONE_HOE);
		List<AntBot> bots = new ArrayList<>();
		for (int i = 0; i < BOTS; i++) {
			AntBot bot = helper.spawn(ModEntities.ANT_BOT, 19 + 2 * (i % 6), GROUND + 1, 18 + 2 * (i / 6));
			bot.getInventory().addItem(new ItemStack(tools.get(i % tools.size())));
			ColonyManager.get(helper.getLevel()).join(bot, colony);
			bots.add(bot);
		}
		// What the bots are at, sampled every 100 ticks: their job type, or upkeep or idle.
		Map<String, Integer> doing = new TreeMap<>();
		helper.onEachTick(() -> {
			long tick = helper.getTick();
			if (tick > WARM_UP_TICKS && tick % 100 == 0) {
				for (AntBot bot : bots) {
					String what = !bot.isAlive() ? "dead" : bot.getJobType().map(type -> type.getSerializedName()).orElse(bot.isWorking() ? "upkeep" : "idle");
					doing.merge(what, 1, Integer::sum);
				}
			}
		});
		helper.runAfterDelay(WARM_UP_TICKS, TickProfiler.SERVER::start);
		helper.runAfterDelay(WARM_UP_TICKS + MEASURED_TICKS, () -> {
			TickProfiler.SERVER.stop();
			TickProfiler.Report report = TickProfiler.SERVER.report();
			LOGGER.info("Benchmark: {} bots, {}", BOTS, String.join("\n  ", report.lines()));
			LOGGER.info("Benchmark: bots at (samples every 100 ticks) {}; jobs on the board {}", doing, colony.board().jobs().size());
			helper.assertTrue(report.averageTickMs() < TICK_BUDGET_MS, "average tick " + report.averageTickMs() + " ms");
			helper.succeed();
		});
	}

	/** Stone with seeded ores up to below {@link #GROUND}, dirt under grass at it, over the whole area. */
	private static void ground(GameTestHelper helper) {
		Random random = new Random(308);
		for (BlockPos pos : BlockPos.betweenClosed(0, 0, 0, SIZE - 1, GROUND, SIZE - 1)) {
			BlockState state;
			if (pos.getY() == GROUND) {
				state = Blocks.GRASS_BLOCK.defaultBlockState();
			} else if (pos.getY() >= GROUND - 2) {
				state = Blocks.DIRT.defaultBlockState();
			} else {
				double ore = random.nextDouble();
				state = ore < 0.02 ? Blocks.COAL_ORE.defaultBlockState() : ore < 0.03 ? Blocks.IRON_ORE.defaultBlockState() : Blocks.STONE.defaultBlockState();
			}
			helper.setBlock(pos, state);
		}
	}

	/** A field of ripe wheat, 9 by 7 round a water source, for the bots to harvest. */
	private static void wheat(GameTestHelper helper) {
		for (int x = 30; x <= 38; x++) {
			for (int z = 30; z <= 36; z++) {
				if (x == 34 && z == 33) {
					helper.setBlock(x, GROUND, z, Blocks.WATER);
					continue;
				}
				helper.setBlock(x, GROUND, z, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, FarmlandBlock.MAX_MOISTURE));
				helper.setBlock(x, GROUND + 1, z, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, CropBlock.MAX_AGE));
			}
		}
	}

	private static void tree(GameTestHelper helper, int x, int z) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
		int top = GROUND + 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				helper.setBlock(x + dx, top, z + dz, leaves);
			}
		}
		helper.setBlock(x, top + 1, z, leaves);
		for (int y = GROUND + 1; y <= top; y++) {
			helper.setBlock(x, y, z, Blocks.OAK_LOG);
		}
	}

	private static void place(GameTestHelper helper, Colony colony, String blueprint, BlockPos at) {
		Either<BlueprintPlacement.Refusal, PlacedBlueprint> placed = BlueprintPlacement.place(helper.getLevel(), colony, true, AntCraft.id(blueprint),
			helper.absolutePos(at), Rotation.NONE, Mirror.NONE);
		helper.assertTrue(placed.right().isPresent(), blueprint + " not placed: " + placed.left());
	}
}
