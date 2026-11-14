package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.SenseApi;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * The senses of the bot API (docs/reference/index.md) and {@code memory}, each run as a short script on a bot in
 * the test area. Searches keep their radius inside the area and look for blocks the area has nothing else of, as tests
 * run side by side a few blocks apart.
 */
public class SenseGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** The position {@code (x, y, z)} of the test area as a script tuple. */
	private static String at(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	/** The position {@code (x, y, z)} of the test area as a script prints it. */
	private static String printed(GameTestHelper helper, int x, int y, int z) {
		return "Pos" + at(helper, x, y, z);
	}

	/** Runs {@code source} on a bot at (3, 1, 3), failing the test as soon as the script fails. */
	private static ScriptRun run(GameTestHelper helper, AntBot bot, String source) {
		ScriptRun run = BotScripts.run(bot, source);
		BotScripts.failOnError(helper, bot);
		return run;
	}

	/** Succeeds once the script finished having printed exactly {@code expected}. */
	private static void succeedWhenPrinted(GameTestHelper helper, AntBot bot, ScriptRun run, List<String> expected) {
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			helper.assertTrue(printed.equals(expected), "printed " + printed + ", expected " + expected);
		});
	}

	@GameTest(maxTicks = 10)
	public void inventoryCountHasAndHeldSeeWhatTheBotCarries(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 5));
		bot.getInventory().addItem(new ItemStack(Items.BIRCH_LOG, 3));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 2));
		ScriptRun run = run(helper, bot, """
			print(inventory())
			print(count('#minecraft:logs'), count('oak_log'), count(['dirt', 'minecraft:birch_log']), count('stone'))
			print(has('dirt', 2), has('dirt', 3), has('#logs'), has('stone'))
			print(held())
			""");
		succeedWhenPrinted(helper, bot, run, List.of(
			"[Stack('minecraft:iron_pickaxe', 1), Stack('minecraft:oak_log', 5), Stack('minecraft:birch_log', 3), Stack('minecraft:dirt', 2)]",
			"8 5 5 0",
			"True False True False",
			"Stack('minecraft:iron_pickaxe', 1)"));
	}

	@GameTest(maxTicks = 10)
	public void heldIsNoneWithAnEmptyHand(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun run = run(helper, bot, "print(held(), inventory())\n");
		succeedWhenPrinted(helper, bot, run, List.of("None []"));
	}

	@GameTest(maxTicks = 10)
	public void blockReadsABlockAndNoneWhereNothingIsLoaded(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		helper.setBlock(5, 1, 5, Blocks.OAK_LOG);
		helper.setBlock(1, 1, 1, Blocks.WATER);
		ScriptRun run = run(helper, bot, "b = block(" + at(helper, 5, 1, 5) + ")\n"
			+ "print(b, b.id, b.pos, b.is_air, b.is_fluid, '#minecraft:logs' in b.tags)\n"
			+ "w = block(" + at(helper, 1, 1, 1) + ")\n"
			+ "a = block(" + at(helper, 5, 2, 5) + ")\n"
			+ "print(w.id, w.is_fluid, a.id, a.is_air)\n"
			+ "print(block((29000000, 64, 29000000)))\n");
		String log = printed(helper, 5, 1, 5);
		succeedWhenPrinted(helper, bot, run, List.of(
			"Block('minecraft:oak_log', " + log + ") minecraft:oak_log " + log + " False False True",
			"minecraft:water True minecraft:air True",
			"None"));
	}

	/** A block's state: ints, bools and names by property, so a script tells ripe wheat (#369). */
	@GameTest(maxTicks = 10)
	public void blockStateGivesTheBlocksProperties(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		helper.setBlock(5, 0, 5, Blocks.FARMLAND);
		helper.setBlock(5, 1, 5, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
		helper.setBlock(1, 1, 1, Blocks.OAK_LOG);
		ScriptRun run = run(helper, bot, "print(block(" + at(helper, 5, 1, 5) + ").state, block(" + at(helper, 1, 1, 1) + ").state, block("
			+ at(helper, 1, 2, 1) + ").state)\n");
		succeedWhenPrinted(helper, bot, run, List.of("{'age': 7} {'axis': 'y'} {}"));
	}

	@GameTest(maxTicks = 10)
	public void findNearestFindsTheNearestMatchingBlockOrNone(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		helper.setBlock(6, 1, 6, Blocks.EMERALD_BLOCK);
		helper.setBlock(3, 1, 5, Blocks.EMERALD_BLOCK);
		helper.setBlock(1, 1, 3, Blocks.LAPIS_BLOCK);
		ScriptRun run = run(helper, bot, """
			print(find_nearest('emerald_block', radius=4))
			print(find_nearest(['lapis_block', 'emerald_block'], 4))
			print(find_nearest('diamond_block', radius=4))
			print(find_nearest('emerald_block', radius=1))
			""");
		succeedWhenPrinted(helper, bot, run, List.of(printed(helper, 3, 1, 5), printed(helper, 1, 1, 3), "None", "None"));
	}

	@GameTest(maxTicks = 10)
	public void scanGivesTheNearestFirstAndStopsAtTheLimit(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		helper.setBlock(6, 1, 6, Blocks.GOLD_BLOCK);
		helper.setBlock(3, 2, 4, Blocks.GOLD_BLOCK);
		helper.setBlock(1, 1, 3, Blocks.GOLD_BLOCK);
		helper.setBlock(3, 1, 0, Blocks.GOLD_BLOCK);
		String all = "[" + String.join(", ", printed(helper, 3, 2, 4), printed(helper, 1, 1, 3), printed(helper, 3, 1, 0),
			printed(helper, 6, 1, 6)) + "]";
		ScriptRun run = run(helper, bot, """
			print(scan('gold_block', radius=5))
			print(scan('gold_block', radius=5, limit=2))
			print(scan('gold_block', 2), scan('gold_block', limit=0), scan('#minecraft:beacon_base_blocks', 3, 1))
			""");
		succeedWhenPrinted(helper, bot, run, List.of(all,
			"[" + printed(helper, 3, 2, 4) + ", " + printed(helper, 1, 1, 3) + "]",
			"[" + printed(helper, 3, 2, 4) + ", " + printed(helper, 1, 1, 3) + "] [] [" + printed(helper, 3, 2, 4) + "]"));
	}

	/** {@code around} searches around a position instead of the bot, nearest to it first (#479). */
	@GameTest(maxTicks = 10)
	public void scanAndFindNearestSearchAroundAPosition(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 0, 1, 0);
		helper.setBlock(0, 1, 1, Blocks.COAL_BLOCK);
		helper.setBlock(4, 3, 5, Blocks.COAL_BLOCK);
		helper.setBlock(2, 1, 4, Blocks.COAL_BLOCK);
		helper.setBlock(4, 1, 5, Blocks.COAL_BLOCK);
		String p = at(helper, 4, 1, 4);
		ScriptRun run = run(helper, bot, "p = " + p + "\n"
			+ "print(scan('coal_block', radius=3, around=p))\n"
			+ "print(find_nearest('coal_block', radius=3, around=p), find_nearest('coal_block', around=p, radius=0))\n"
			+ "print(find_nearest('coal_block', 2), scan('coal_block', 3, 1, p))\n");
		succeedWhenPrinted(helper, bot, run, List.of(
			"[" + String.join(", ", printed(helper, 4, 1, 5), printed(helper, 2, 1, 4), printed(helper, 4, 3, 5)) + "]",
			printed(helper, 4, 1, 5) + " None",
			printed(helper, 0, 1, 1) + " [" + printed(helper, 4, 1, 5) + "]"));
	}

	/**
	 * A search of radius 32 reads some 137,000 blocks, more than the server's scan budget for one tick: it goes on over
	 * the next ticks, pausing the script, and charges the script an instruction per few blocks read.
	 */
	@GameTest(maxTicks = 30)
	public void aBigScanSpreadsOverTicksAndCostsInstructions(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		GameTests.scansFreely(helper, bot);
		ScriptRun run = run(helper, bot, "found = scan('sponge', radius=32)\nprint(isinstance(found, list))\n");
		int[] scanning = {0};
		helper.onEachTick(() -> {
			String action = run.action();
			if (action != null && action.startsWith("scan")) {
				scanning[0]++;
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("True")), "printed " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertTrue(scanning[0] >= 3, "scanned during " + scanning[0] + " ticks");
			helper.assertTrue(run.executed() >= 130_000 / SenseApi.BLOCKS_PER_INSTRUCTION, run.executed() + " instructions");
		});
	}

	/**
	 * In a game test a scan of radius 32 reads only as far as the test's area reaches (GameTests.scanRadius, #618): a
	 * few thousand blocks, done in the tick it starts, not some 137,000 of the scan budget all bots share.
	 */
	@GameTest(maxTicks = 10)
	public void aScanReadsNoFurtherThanTheTestArea(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun run = run(helper, bot, "found = scan('sponge', radius=32)\nprint(found)\n");
		helper.succeedWhen(() -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).equals(List.of("[]")), "printed " + BotScripts.log(bot, Output.Level.PRINT));
			helper.assertTrue(run.executed() < 10_000 / SenseApi.BLOCKS_PER_INSTRUCTION, run.executed() + " instructions");
		});
	}

	/**
	 * One bot's whole-sphere scan does not hold up another bot's small search: the bots searching share each tick's
	 * scan budget, so the small one is done while the big one still reads (#527).
	 */
	@GameTest(maxTicks = 10)
	public void aSmallSearchIsNotHeldUpByAnotherBotsBigScan(GameTestHelper helper) {
		AntBot big = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		GameTests.scansFreely(helper, big);
		AntBot small = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		helper.setBlock(5, 1, 6, Blocks.EMERALD_BLOCK);
		ScriptRun scan = run(helper, big, "print(len(scan('sponge', radius=32)))\n");
		ScriptRun find = run(helper, small, "print(find_nearest('emerald_block', radius=1))\n");
		helper.onEachTick(() -> {
			String action = scan.action();
			if (helper.getTick() >= 2 && (action == null || !action.startsWith("scan"))) {
				helper.fail("the big scan was done first; the small search: " + find.state() + " " + find.action());
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(find.state() == ScriptRun.State.FINISHED, "find_nearest " + find.state() + " " + find.action());
			helper.assertTrue(BotScripts.log(small, Output.Level.PRINT).equals(List.of(printed(helper, 5, 1, 6))),
				"printed " + BotScripts.log(small, Output.Level.PRINT));
		});
	}

	@GameTest(maxTicks = 10)
	public void aBadSelectorFailsTheScriptAtItsLine(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun run = BotScripts.run(bot, "x = 1\nfind_nearest('emerald_blok')\n");
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FAILED, "script " + run.state());
			List<String> log = BotScripts.log(bot, Output.Level.ERROR);
			helper.assertTrue(log.equals(List.of("test.py:2: unknown block 'minecraft:emerald_blok'")), "log " + log);
		});
	}

	@GameTest(maxTicks = 25)
	public void entitiesListsEntitiesNearestFirstAndTheyTurnInvalidOnceGone(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		Cow cow = helper.spawnWithNoFreeWill(EntityTypes.COW, 5, 1, 3);
		helper.spawnWithNoFreeWill(EntityTypes.PIG, 0, 1, 6);
		ScriptRun run = run(helper, bot, """
			print([e.type for e in entities(radius=6)])
			print([e.type for e in entities('#minecraft:can_breathe_under_water', radius=6)])
			cow = entities('cow', radius=6)[0]
			print(cow.type, cow.valid, cow.health == 10.0, cow.item, cow == entities('minecraft:cow', 6)[0])
			wait(10)
			print(cow.valid, entities('cow', radius=6))
			""");
		helper.runAfterDelay(5, cow::discard);
		succeedWhenPrinted(helper, bot, run, List.of("['minecraft:cow', 'minecraft:pig']", "[]",
			"minecraft:cow True True None True", "False []"));
	}

	@GameTest(maxTicks = 10)
	public void itemsNearListsDroppedItems(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ItemEntity apples = helper.spawnItem(Items.APPLE, new BlockPos(6, 1, 6));
		apples.setItem(new ItemStack(Items.APPLE, 3));
		apples.setNeverPickUp();
		helper.spawnItem(Items.STICK, new BlockPos(0, 1, 6)).setNeverPickUp();
		ScriptRun run = run(helper, bot, """
			items = items_near('apple', radius=6)
			print(len(items), items[0].item, items[0].type)
			print(len(items_near(radius=6)), items_near('dirt', 6))
			""");
		succeedWhenPrinted(helper, bot, run, List.of("1 Stack('minecraft:apple', 3) minecraft:item", "2 []"));
	}

	@GameTest(maxTicks = 10)
	public void containerListsTheStacksInAChestAndNoneForOtherBlocks(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		helper.setBlock(5, 1, 3, Blocks.CHEST);
		Container chest = (Container) helper.getBlockEntity(new BlockPos(5, 1, 3), net.minecraft.world.level.block.entity.ChestBlockEntity.class);
		chest.setItem(0, new ItemStack(Items.COBBLESTONE, 4));
		chest.setItem(5, new ItemStack(Items.TORCH, 1));
		ScriptRun run = run(helper, bot, "print(container(" + at(helper, 5, 1, 3) + "))\n"
			+ "print(container(" + at(helper, 5, 0, 3) + "))\n");
		succeedWhenPrinted(helper, bot, run, List.of("[Stack('minecraft:cobblestone', 4), Stack('minecraft:torch', 1)]", "None"));
	}

	@GameTest(maxTicks = 10)
	public void stacksGiveTheDamageAndMaxDamageOfATool(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ItemStack pickaxe = new ItemStack(Items.STONE_PICKAXE);
		pickaxe.setDamageValue(10);
		bot.setItemSlot(EquipmentSlot.MAINHAND, pickaxe);
		helper.setBlock(5, 1, 3, Blocks.CHEST);
		Container chest = (Container) helper.getBlockEntity(new BlockPos(5, 1, 3), net.minecraft.world.level.block.entity.ChestBlockEntity.class);
		ItemStack axe = new ItemStack(Items.WOODEN_AXE);
		axe.setDamageValue(3);
		chest.setItem(0, axe);
		chest.setItem(1, new ItemStack(Items.OAK_LOG, 2));
		ScriptRun run = run(helper, bot, "print(held().damage, held().max_damage)\n"
			+ "print([(s.item, s.damage, s.max_damage) for s in container(" + at(helper, 5, 1, 3) + ")])\n");
		succeedWhenPrinted(helper, bot, run, List.of("10 131",
			"[('minecraft:wooden_axe', 3, 59), ('minecraft:oak_log', 0, 0)]"));
	}

	@GameTest(maxTicks = 10)
	public void tagItemsListsTheItemsOfTheModsScaffoldTag(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun run = run(helper, bot, "print(tag_items('#antcraft:scaffold_blocks'))\n");
		succeedWhenPrinted(helper, bot, run, List.of("['minecraft:dirt', 'minecraft:cobblestone', 'minecraft:cobbled_deepslate', "
			+ "'minecraft:netherrack', 'minecraft:stone', 'minecraft:andesite', 'minecraft:diorite', 'minecraft:granite', 'minecraft:tuff']"));
	}

	/**
	 * surface(pos) is the lowest spot of pos's column with nothing that blocks motion above it: on top of a stone pillar,
	 * on the floor, and under leaves, which it leaves out (#534). The area has sky access: no barrier ceiling over it.
	 */
	@GameTest(maxTicks = 10, skyAccess = true)
	public void surfaceIsAboveTheHighestBlockOfAColumn(GameTestHelper helper) {
		for (int y = 1; y <= 3; y++) {
			helper.setBlock(2, y, 2, Blocks.STONE);
		}
		helper.setBlock(6, 3, 6, Blocks.OAK_LEAVES);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 4);
		ScriptRun run = run(helper, bot, "print(surface(%s))\nprint(surface(%s))\nprint(surface(%s))\n"
			.formatted(at(helper, 2, 1, 2), at(helper, 5, 6, 5), at(helper, 6, 1, 6)));
		succeedWhenPrinted(helper, bot, run, List.of(printed(helper, 2, 4, 2), printed(helper, 5, 1, 5), printed(helper, 6, 1, 6)));
	}

	/** biome() is the biome at the bot's feet, biome(pos) the one at pos, and None where pos is not loaded (#567). */
	@GameTest(maxTicks = 10)
	public void biomeIsTheBiomeAtAPosOrNoneWhereNotLoaded(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		BlockPos far = helper.absolutePos(new BlockPos(3, 1, 3)).offset(1_000_000, 0, 0);
		ScriptRun run = run(helper, bot, "print(biome())\nprint(biome(%s))\nprint(biome((%d, %d, %d)))\n"
			.formatted(at(helper, 6, 1, 6), far.getX(), far.getY(), far.getZ()));
		String biome = helper.getLevel().getBiome(helper.absolutePos(new BlockPos(3, 1, 3))).getRegisteredName();
		succeedWhenPrinted(helper, bot, run, List.of(biome, biome, "None"));
	}

	/**
	 * structures(radius) lists the structure starts whose box is within radius of the bot (#567): a plains village start
	 * put in the area's chunks, its box from (5, 1, 5) to (7, 3, 7), some 5.7 blocks from the bot at (1, 1, 1), is not
	 * within 4 but is within 16. The start is taken out again when the test ends.
	 */
	@GameTest(maxTicks = 10)
	public void structuresListsTheStartsWithinRadius(GameTestHelper helper) {
		village(helper, new BlockPos(5, 1, 5), new BlockPos(7, 3, 7));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		ScriptRun run = run(helper, bot, "s = structures(16)\nprint(len(structures(4)), len(s))\n"
			+ "print(s[0]['id'], s[0]['at'], s[0]['low'], s[0]['high'])\n");
		succeedWhenPrinted(helper, bot, run, List.of("0 1",
			"minecraft:village_plains " + printed(helper, 6, 2, 6) + " " + printed(helper, 5, 1, 5) + " " + printed(helper, 7, 3, 7)));
	}

	/**
	 * structures costs by the chunks it reads (#588): radius 128 reads 17 by 17 chunks, radius 0 the bot's one, so the
	 * first costs 288 chunks' charge more than the second.
	 */
	@GameTest(maxTicks = 10)
	public void structuresCostsByTheChunksItReads(GameTestHelper helper) {
		ScriptRun near = run(helper, helper.spawn(ModEntities.ANT_BOT, 1, 1, 1), "structures(0)\n");
		ScriptRun far = run(helper, helper.spawn(ModEntities.ANT_BOT, 1, 1, 1), "structures(128)\n");
		helper.succeedWhen(() -> {
			helper.assertTrue(near.state() == ScriptRun.State.FINISHED && far.state() == ScriptRun.State.FINISHED, "scripts " + near.state() + " " + far.state());
			helper.assertValueEqual(far.executed() - near.executed(), 288L * SenseApi.STRUCTURE_CHUNK_COST, "instructions more for radius 128");
		});
	}

	/**
	 * block_tags costs its measured 2 µs a call, 100 instructions at 20 ns (#589): it runs that many more than len, a
	 * native of the same shape that costs nothing besides the call.
	 */
	@GameTest(maxTicks = 10)
	public void blockTagsCostsItsMeasuredTime(GameTestHelper helper) {
		ScriptRun free = run(helper, helper.spawn(ModEntities.ANT_BOT, 1, 1, 1), "len(\"minecraft:stone\")\n");
		ScriptRun tags = run(helper, helper.spawn(ModEntities.ANT_BOT, 1, 1, 1), "block_tags(\"minecraft:stone\")\n");
		helper.succeedWhen(() -> {
			helper.assertTrue(free.state() == ScriptRun.State.FINISHED && tags.state() == ScriptRun.State.FINISHED, "scripts " + free.state() + " " + tags.state());
			helper.assertValueEqual(tags.executed() - free.executed(), 100L, "instructions more for block_tags");
		});
	}

	/**
	 * Puts a plains village start with one piece from {@code low} to {@code high} (in the area) in the area's chunks, with
	 * the references the chunks it reaches keep to it, as world generation would; takes it out again when the test ends.
	 * A chunk keeps one start per structure type, so two tests that run at once (one batch) must not put a village in the
	 * same chunk: the second replaces the first's, and the first one's cleanup takes both out. Give such tests an
	 * environment of their own, as ExploreGameTest does with {@code antcraft-gametest:structures}.
	 */
	static void village(GameTestHelper helper, BlockPos low, BlockPos high) {
		ServerLevel level = helper.getLevel();
		BoundingBox box = BoundingBox.fromCorners(helper.absolutePos(low), helper.absolutePos(high));
		Structure village = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValueOrThrow(BuiltinStructures.VILLAGE_PLAINS);
		StructurePiece piece = new StructurePiece(StructurePieceType.JIGSAW, 0, box) {
			@Override
			protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
			}

			@Override
			public void postProcess(WorldGenLevel world, StructureManager structures, ChunkGenerator generator, RandomSource random,
				BoundingBox chunkBox, ChunkPos chunk, BlockPos pivot) {
			}
		};
		ChunkPos start = ChunkPos.containing(helper.absolutePos(low));
		level.getChunk(start.x(), start.z()).setStartForStructure(village, new StructureStart(village, start, 0, new PiecesContainer(List.of(piece))));
		box.intersectingChunks().forEach(chunk -> level.getChunk(chunk.x(), chunk.z()).addReferenceForStructure(village, start.pack()));
		GameTests.onEnd(helper, () -> {
			LevelChunk startChunk = level.getChunk(start.x(), start.z());
			Map<Structure, StructureStart> starts = new HashMap<>(startChunk.getAllStarts());
			starts.remove(village);
			startChunk.setAllStarts(starts);
			box.intersectingChunks().forEach(chunk -> level.getChunk(chunk.x(), chunk.z()).getReferencesForStructure(village).remove(start.pack()));
		});
	}

	@GameTest(maxTicks = 10)
	public void timeAndIsDayReadTheWorldsTime(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun run = run(helper, bot, "t = time()\nprint(isinstance(t, int) and 0 <= t < 24000, is_day())\n");
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			List<String> printed = BotScripts.log(bot, Output.Level.PRINT);
			String expected = "True " + (helper.getLevel().isBrightOutside() ? "True" : "False");
			helper.assertTrue(printed.equals(List.of(expected)), "printed " + printed + ", expected " + expected);
		});
	}

	@GameTest(maxTicks = 10)
	public void memorySurvivesAScriptRestartAndASaveAndLoad(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun first = BotScripts.run(bot, "memory['n'] = 40\nmemory['home'] = pos()\n");
		AntBot[] copy = {null};
		ScriptRun[] second = {null};
		ScriptRun[] third = {null};
		BotScripts.failOnError(helper, () -> copy[0] == null ? bot : copy[0]);
		// One listener for every step: a listener added while the listeners run breaks the game test framework.
		helper.onEachTick(() -> {
			if (first.state() == ScriptRun.State.FINISHED && second[0] == null) {
				second[0] = BotScripts.run(bot, "memory['n'] += 1\n");
			} else if (second[0] != null && second[0].state() == ScriptRun.State.FINISHED && copy[0] == null) {
				TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
				bot.saveWithoutId(output);
				bot.discard();
				copy[0] = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
				copy[0].load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
				third[0] = BotScripts.run(copy[0], "memory['n'] += 1\nprint(memory['n'], memory['home'] == pos())\n");
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(third[0] != null && third[0].state() == ScriptRun.State.FINISHED, "not restarted and reloaded yet");
			List<String> printed = BotScripts.log(copy[0], Output.Level.PRINT);
			helper.assertTrue(printed.equals(List.of("42 True")), "printed " + printed);
		});
	}

	/**
	 * mineshaft(levels) picks the nearest diggable site beside the bot, its top on the ground the bot stands on, with a
	 * landing at the level; mineshaft_at gives the same shaft back from its top. Test areas stand a few blocks above
	 * world terrain at any height (y 7 in one run), so the level is a few blocks under the floor rather than y 20, and
	 * the column and hall are stone, not whatever terrain is there.
	 */
	// Each mineshaft() waits while the tick's block search budget is spent, as by the big scan beside it (#380).
	@GameTest(maxTicks = 30)
	public void mineshaftGivesASiteWithALandingAtTheLevel(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(5, -4, 5, 20, -1, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun run = run(helper, bot, "y = pos().y - 3\nm = mineshaft([y])\n"
			+ "print(m['top'], m['top'].y == pos().y, block(m['hatch']).id, m['level'][y]['landing'], m['levels'] == [y])\n"
			+ "print(mineshaft_at(m['top'], m['levels']) == m)\n"
			+ "print(mineshaft([y], branches=1, length=2) == mineshaft_at(m['top'], m['levels'], branches=1, length=2))\n");
		succeedWhenPrinted(helper, bot, run, List.of(printed(helper, 6, 1, 6) + " True minecraft:stone " + printed(helper, 7, -2, 6) + " True", "True",
			"True"));
	}
	/**
	 * mineshaft(levels, near) puts the top on the ground of its column, whatever height near is at: a block above the
	 * ground and a block into it (a dip) give the same top as near on the ground.
	 */
	// Each mineshaft() waits while the tick's block search budget is spent, as by the big scan beside it (#380).
	@GameTest(maxTicks = 30)
	public void mineshaftPutsTheTopOnTheGround(GameTestHelper helper) {
		for (BlockPos pos : BlockPos.betweenClosed(5, -4, 5, 20, -1, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		ScriptRun run = run(helper, bot, "y = pos().y - 3\n"
			+ "print(mineshaft([y], pos().up())['top'], mineshaft([y], pos().down())['top'])\n");
		succeedWhenPrinted(helper, bot, run, List.of(printed(helper, 6, 1, 6) + " " + printed(helper, 6, 1, 6)));
	}
}
