package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.Pos;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.SenseApi;
import io.github.barniebarnie.antcraft.script.lang.NativeCall;
import io.github.barniebarnie.antcraft.script.lang.NativeFunction;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Times senses against each other in one JVM (#588): each native is called straight from Java many times, in rounds that
 * take turns, and its median time a call is logged with the cost it implies at the time {@code block_tags} (its registered cost) takes
 * an instruction, and at 20 ns an instruction (#380). Ratios, not absolute times, as other builds may share the machine.
 * Manual-only, as the benchmark is: {@code scripts/check.sh runGameTest -Pbenchmark -PgameTest='antcraft-gametest:sense_cost_game_test*'},
 * then {@code grep -A20 'Sense costs' build/check.log}.
 */
public class SenseCostGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("AntCraft game tests");
	private static final int CALLS = 20_000;
	private static final int ROUNDS = 7;

	@GameTest(maxTicks = 200, manualOnly = true)
	public void timesTheSenses(GameTestHelper helper) {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		ServerLevel level = helper.getLevel();
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BlockPos chest = helper.absolutePos(new BlockPos(5, 1, 5));
		helper.setBlock(5, 1, 5, Blocks.CHEST);
		((ChestBlockEntity) level.getBlockEntity(chest)).setItem(0, new ItemStack(Items.STONE, 20));
		SenseGameTest.village(helper, new BlockPos(5, 1, 5), new BlockPos(7, 3, 7));
		helper.setBlock(1, 1, 1, ModBlocks.COLONY_CORE);
		ColonyManager colonies = ColonyManager.get(level);
		colonies.join(bot, colonies.byCore(helper.absolutePos(new BlockPos(1, 1, 1))).orElseThrow());
		ScriptRun run = BotScripts.run(bot, "wait(1000)\n");
		helper.runAfterDelay(2, () -> {
			// Every chunk structures(128) reads loaded, its worst case.
			BlockPos at = bot.blockPosition();
			for (int cx = SectionPos.blockToSectionCoord(at.getX() - 128); cx <= SectionPos.blockToSectionCoord(at.getX() + 128); cx++) {
				for (int cz = SectionPos.blockToSectionCoord(at.getZ() - 128); cz <= SectionPos.blockToSectionCoord(at.getZ() + 128); cz++) {
					level.getChunk(cx, cz);
				}
			}
			Natives natives = new Natives();
			SenseApi.register(natives, run);
			Pos feet = new Pos(at.getX(), at.getY(), at.getZ());
			Pos box = new Pos(chest.getX(), chest.getY(), chest.getZ());
			Map<String, NativeCall> calls = new LinkedHashMap<>();
			calls.put("block_tags", call("block_tags", "minecraft:stone"));
			calls.put("block", call("block", feet));
			calls.put("container", call("container", box));
			calls.put("biome", call("biome"));
			calls.put("biome pos", call("biome", feet));
			calls.put("biome_tags", call("biome_tags", "minecraft:plains"));
			calls.put("structure_tags", call("structure_tags", "minecraft:village_plains"));
			calls.put("room", call("room", box, "stone"));
			calls.put("colony", call("colony"));
			calls.put("structures 16", call("structures", 16L));
			calls.put("structures 64", call("structures", 64L));
			calls.put("structures 128", call("structures", 128L));
			Map<String, long[]> times = new LinkedHashMap<>();
			calls.keySet().forEach(name -> times.put(name, new long[ROUNDS]));
			for (int round = -1; round < ROUNDS; round++) { // round -1 warms up
				for (Map.Entry<String, NativeCall> entry : calls.entrySet()) {
					NativeFunction function = natives.function(entry.getValue().name());
					long begin = System.nanoTime();
					for (int i = 0; i < CALLS; i++) {
						function.call(entry.getValue());
					}
					if (round >= 0) {
						times.get(entry.getKey())[round] = System.nanoTime() - begin;
					}
				}
			}
			double base = median(times.get("block_tags")) / natives.cost("block_tags");
			StringBuilder log = new StringBuilder();
			times.forEach((name, t) -> log.append(String.format(Locale.ROOT, "\n  %-15s %8.3f µs a call (rounds %s), cost %5.1f at block_tags' rate, %5.1f at 20 ns",
				name, median(t) / 1000.0, Arrays.toString(Arrays.stream(t).map(n -> n / CALLS).toArray()), median(t) / base, median(t) / 20.0)));
			LOGGER.info("Sense costs, {} calls a round, median of {} rounds:{}", CALLS, ROUNDS, log);
			helper.succeed();
		});
	}

	private static NativeCall call(String name, Object... args) {
		return new NativeCall(name, List.of(args), Map.of());
	}

	/** The median time of one call, in ns. */
	private static double median(long[] rounds) {
		long[] sorted = rounds.clone();
		Arrays.sort(sorted);
		return (double) sorted[sorted.length / 2] / CALLS;
	}
}
