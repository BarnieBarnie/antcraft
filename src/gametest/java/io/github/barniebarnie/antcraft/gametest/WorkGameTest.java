package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.AntCraft;
import io.github.barniebarnie.antcraft.blueprint.Blueprint;
import io.github.barniebarnie.antcraft.blueprint.BlueprintSummary;
import io.github.barniebarnie.antcraft.blueprint.Blueprints;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.work.BuildPlan;
import io.github.barniebarnie.antcraft.work.RegionPlan;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotHands;
import io.github.barniebarnie.antcraft.pathing.PathBudget;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.BotApi;
import io.github.barniebarnie.antcraft.script.MoveApi;
import io.github.barniebarnie.antcraft.script.Pos;
import io.github.barniebarnie.antcraft.script.Result;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.network.BlueprintNetworking;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.material.Fluids;

/**
 * The block actions of the bot API (docs/reference/index.md, Working blocks): {@code mine}, {@code place}, {@code pick_up},
 * {@code equip}, {@code use} and {@code drop}, each run as a short script on a bot in the test area: one test where the
 * action succeeds, and one per reason it can fail with.
 */
public class WorkGameTest implements CustomTestMethodInvoker {
	/** The bot's inventory size, filled up for the {@code no_space} tests. */
	private static final int INVENTORY_SLOTS = 27;

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		method.invoke(this, helper);
	}

	/** The position {@code (x, y, z)} of the test area as a script tuple. */
	static String at(GameTestHelper helper, int x, int y, int z) {
		BlockPos pos = helper.absolutePos(new BlockPos(x, y, z));
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	/** A position far above the world's build height, over the test area. */
	private static String aboveTheWorld(GameTestHelper helper) {
		BlockPos pos = helper.absolutePos(new BlockPos(4, 0, 4));
		return "(" + pos.getX() + ", " + (helper.getLevel().getMaxY() + 10) + ", " + pos.getZ() + ")";
	}

	static List<String> printed(AntBot bot) {
		return BotScripts.log(bot, Output.Level.PRINT);
	}

	/**
	 * Runs {@code source} on {@code bot} and succeeds once it finished having printed exactly {@code expected} and
	 * {@code world} holds; fails at once when the script fails or finishes having printed something else.
	 */
	static void expect(GameTestHelper helper, AntBot bot, String source, List<String> expected, Runnable world) {
		ScriptRun run = BotScripts.run(bot, source);
		BotScripts.failOnError(helper, bot);
		helper.onEachTick(() -> {
			if (run.state() == ScriptRun.State.FINISHED && !printed(bot).equals(expected)) {
				helper.fail("printed " + printed(bot) + ", expected " + expected + ", log " + BotScripts.log(bot, Output.Level.DEBUG));
			}
		});
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state() + ", bot at "
				+ TestPositions.relative(helper, bot.blockPosition()).toShortString());
			world.run();
		});
	}

	static void expect(GameTestHelper helper, AntBot bot, String source, List<String> expected) {
		expect(helper, bot, source, expected, () -> {
		});
	}

	/** Fills every slot of the bot's inventory with something that stacks no further. */
	static void fillInventory(AntBot bot) {
		for (int i = 0; i < INVENTORY_SLOTS; i++) {
			bot.getInventory().addItem(new ItemStack(Items.ENDER_PEARL, 16));
		}
		if (bot.getInventory().canAddItem(new ItemStack(Items.ENDER_PEARL))) {
			throw new IllegalStateException("the bot's inventory has more than " + INVENTORY_SLOTS + " slots");
		}
	}

	// mine

	// Full runs: passes at up to tick 41 (#861).
	@GameTest(maxTicks = 90)
	public void mineWalksIntoReachBreaksWithTheBestToolAndPicksUpTheDrops(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.IRON_PICKAXE));
		expect(helper, bot, "r = mine(" + at(helper, 6, 1, 6) + ")\nprint(r.ok, r.reason, count('cobblestone'), held().item)\n",
			List.of("True None 1 minecraft:iron_pickaxe"),
			() -> helper.assertBlockPresent(Blocks.AIR, 6, 1, 6));
	}

	@GameTest(maxTicks = 20)
	public void mineAirIsTargetGoneAndLoggedAtDebug(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		String target = at(helper, 2, 1, 2);
		expect(helper, bot, "r = mine(" + target + ")\nprint(r.ok, r.reason)\n", List.of("False target_gone"), () -> {
			List<String> log = BotScripts.log(bot, Output.Level.DEBUG);
			helper.assertTrue(log.contains("mine(Pos" + target + ") -> target_gone"), "log " + log);
		});
	}

	@GameTest(maxTicks = 20)
	public void mineBedrockIsProtected(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.BEDROCK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
		expect(helper, bot, "r = mine(" + at(helper, 2, 1, 2) + ")\nprint(r.ok, r.reason)\n", List.of("False protected"),
			() -> helper.assertBlockPresent(Blocks.BEDROCK, 2, 1, 2));
	}

	/**
	 * {@code pos()} carries the bot's dimension, and a position from another dimension (a home in the Nether) is
	 * refused by senses and actions alike instead of being used at its coordinates in the Overworld (#357).
	 */
	@GameTest(maxTicks = 20)
	public void positionsFromAnotherDimensionAreRefused(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.CHEST);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		bot.setHome(helper.absolutePos(new BlockPos(2, 1, 2)), Level.NETHER);
		expect(helper, bot, "h = home()\nprint(pos().dimension, block(h), container(h), mine(h).reason, put(h, 'dirt').reason)\n",
			List.of("minecraft:overworld None None wrong_dimension wrong_dimension"),
			() -> helper.assertBlockPresent(Blocks.CHEST, 2, 1, 2));
	}

	/**
	 * Every bot native that takes a position, called with one from another dimension, and what it refuses it with
	 * (#360). The test fails until a newly registered native is listed here or in {@link #TAKE_NO_POSITION}.
	 */
	private static final Map<String, String> REFUSE_ANOTHER_DIMENSION = Map.ofEntries(
		Map.entry("goto", "goto(h).reason"),
		Map.entry("travel", "travel(h).reason"),
		Map.entry("reachable", "reachable(h)"),
		Map.entry("mine", "mine(h).reason"),
		Map.entry("place", "place(h, 'dirt').reason"),
		Map.entry("use", "use(h).reason"),
		Map.entry("put", "put(h, 'dirt').reason"),
		Map.entry("take", "take(h, 'dirt').reason"),
		Map.entry("craft", "craft('stick', table=h).reason"),
		Map.entry("smelt", "smelt('dirt', furnace=h).reason"),
		Map.entry("build", "build('antcraft:cobble_step', h).reason"),
		Map.entry("block", "block(h)"),
		Map.entry("biome", "biome(h)"),
		Map.entry("surface", "surface(h)"),
		Map.entry("container", "container(h)"),
		Map.entry("room", "room(h, 'dirt')"),
		Map.entry("mineshaft", "mineshaft([0], near=h)"),
		Map.entry("mineshaft_at", "mineshaft_at(h, [0])"),
		Map.entry("ore_vein", "ore_vein(h)"),
		Map.entry("find_nearest", "find_nearest('chest', around=h)"),
		Map.entry("scan", "scan('chest', around=h)"),
		Map.entry("keep_blocks", "keep_blocks([h])"));

	/**
	 * The bot natives and {@code Pos} methods that take no position, or take one on purpose from any dimension:
	 * {@code set_home} sets the home in the position's dimension, and the {@code Pos} methods work on coordinates.
	 */
	private static final Set<String> TAKE_NO_POSITION = Set.of("pos", "say", "wait", "stop", "home", "home_dimension",
		"dimension", "set_home", "portals", "inventory", "free_slots", "count", "has", "matches", "tag_items", "foods", "monsters", "held", "entities",
		"items_near", "structures", "deaths", "last_death", "colony", "time", "game_time", "is_day", "placed_scaffold", "equip", "drop", "pick_up", "Pos", "Pos.up", "Pos.down", "Pos.north",
		"Pos.south", "Pos.east", "Pos.west", "Pos.distance", "blueprint", "post_job", "jobs", "take_job", "finish_job",
		"drop_job", "block_tags", "biome_tags", "structure_tags", "health", "max_health", "food", "saturation", "eat", "attack", "shoot", "needs", "cooks_in", "build_placement");

	/**
	 * Every native that takes a position refuses one from another dimension (a home in the Nether over a chest in the
	 * Overworld): an action with {@code wrong_dimension}, {@code reachable} with False and a sense with None (#360).
	 */
	@GameTest(maxTicks = 20)
	public void everyNativeThatTakesAPositionRefusesOneFromAnotherDimension(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.CHEST);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		bot.setHome(helper.absolutePos(new BlockPos(2, 1, 2)), Level.NETHER);
		StringBuilder source = new StringBuilder("h = home()\n");
		List<String> expected = new ArrayList<>();
		new TreeMap<>(REFUSE_ANOTHER_DIMENSION).forEach((name, call) -> {
			source.append("print('").append(name).append("', ").append(call).append(")\n");
			expected.add(name + " " + switch (name) {
				case "reachable" -> "False";
				case "block", "biome", "surface", "container", "room", "mineshaft", "mineshaft_at", "find_nearest", "keep_blocks" -> "None";
				case "ore_vein", "scan" -> "[]";
				default -> Result.WRONG_DIMENSION;
			});
		});
		expect(helper, bot, source.toString(), expected, () -> helper.assertBlockPresent(Blocks.CHEST, 2, 1, 2));
		ScriptRun run = Objects.requireNonNull(bot.getScript());
		helper.onEachTick(() -> {
			if (run.action() != null) {
				helper.fail(run.action() + " started instead of refusing a position from another dimension");
			}
		});
		Natives natives = new Natives();
		BotApi.register(natives, run);
		Set<String> unlisted = new TreeSet<>(natives.names());
		unlisted.removeAll(REFUSE_ANOTHER_DIMENSION.keySet());
		unlisted.removeAll(TAKE_NO_POSITION);
		helper.assertTrue(unlisted.isEmpty(), "natives " + unlisted + " are in neither REFUSE_ANOTHER_DIMENSION nor TAKE_NO_POSITION");
	}

	@GameTest(maxTicks = 20)
	public void mineStoneWithoutAPickaxeIsNoTool(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.IRON_SHOVEL));
		expect(helper, bot, "r = mine(" + at(helper, 2, 1, 2) + ")\nprint(r.ok, r.reason)\n", List.of("False no_tool"),
			() -> helper.assertBlockPresent(Blocks.STONE, 2, 1, 2));
	}

	/** Dirt high in the air, and nothing to climb with. */
	@GameTest(maxTicks = 50)
	public void mineABlockOutOfReachIsOutOfReach(GameTestHelper helper) {
		helper.setBlock(4, 7, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = mine(" + at(helper, 4, 7, 4) + ")\nprint(r.ok, r.reason)\n", List.of("False out_of_reach"),
			() -> helper.assertBlockPresent(Blocks.DIRT, 4, 7, 4));
	}

	/**
	 * The block the bot stands on, 4 blocks over the floor, such as the last of a scaffold pillar whose lower blocks were
	 * taken back from the ground: broken from where it stands, the bot would fall 5 and get hurt, as it did felling a
	 * giant spruce (#668). Standing on it is no stand to mine it from. With no other way down, the bot drops off its edge,
	 * hurt or not, and mines it from the floor (#674): a bot left up on its own scaffold had stayed there for good.
	 */
	// Measured: passes at tick 23 (red before #674: printed False out_of_reach at tick 2).
	@GameTest(maxTicks = 50)
	public void mineTheBlockUnderfootOverADropDropsOffAndMinesIt(GameTestHelper helper) {
		helper.setBlock(4, 5, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 6, 4);
		minedFromTheFloor(helper, bot);
	}

	/**
	 * As {@link #mineTheBlockUnderfootOverADropDropsOffAndMinesIt}, the bot over the edge of the block, its feet in the
	 * block beside, as it lands after the block over it was mined (#668): it still rests on the block, so it does not break
	 * it from there, but drops off and mines it from the floor (#674).
	 */
	// Measured: passes at tick 25.
	@GameTest(maxTicks = 50)
	public void mineTheBlockItRestsOnFromOverTheEdgeDropsOffAndMinesIt(GameTestHelper helper) {
		helper.setBlock(4, 5, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(3.8, 6, 4.5));
		minedFromTheFloor(helper, bot);
	}

	/**
	 * As {@link #mineTheBlockItRestsOnFromOverTheEdgeDropsOffAndMinesIt}, with the path nodes of the ticks until it has
	 * landed spent, as under load (#680). Just spawned, not on the ground yet, it plans from the air block beside the dirt,
	 * from where the dirt is in reach: a path with no steps. Its search ends a tick later, with the bot landed and its feet
	 * over the dirt, and the empty path from where it no longer is had failed the walk, out_of_reach at tick 4. Spending
	 * the budget holds up the other tests' searches of those two ticks too.
	 */
	// Measured: passes at tick 25 (red: printed False out_of_reach at tick 4).
	@GameTest(maxTicks = 50)
	public void mineTheBlockItRestsOnFromOverTheEdgeWithItsSearchHeldUpDropsOffAndMinesIt(GameTestHelper helper) {
		helper.setBlock(4, 5, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(3.8, 6, 4.5));
		// Entities tick before onEachTick: spend the next tick's nodes.
		helper.onEachTick(() -> {
			if (!bot.onGround()) {
				long next = helper.getLevel().getServer().getTickCount() + 1L;
				PathBudget.SERVER.spend(next, PathBudget.SERVER.available(next));
			}
		});
		minedFromTheFloor(helper, bot);
	}

	/**
	 * As {@link #mineTheBlockUnderfootOverADropDropsOffAndMinesIt}, with jungle leaves with no log in range under the
	 * block: the bot would land on them, but they decay at a random tick and drop it 4, so they are no floor to land on
	 * (#673), and it drops off and mines the block from the floor (#674).
	 */
	// Measured: passes at tick 23.
	@GameTest(maxTicks = 50)
	public void mineTheBlockUnderfootOverLeavesThatDecayDropsOffAndMinesIt(GameTestHelper helper) {
		helper.setBlock(4, 4, 4, Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, LeavesBlock.DECAY_DISTANCE));
		helper.setBlock(4, 5, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 6, 4);
		minedFromTheFloor(helper, bot);
	}

	/**
	 * The bot on the dirt at (4, 5, 4) mines it: not from on top, where it would fall with it (#668), but from the floor,
	 * which it drops to off the edge, hurt (#674). Until the dirt is gone, the bot is never on its column above it.
	 */
	private static void minedFromTheFloor(GameTestHelper helper, AntBot bot) {
		BlockPos dirt = helper.absolutePos(new BlockPos(4, 5, 4));
		helper.onEachTick(() -> {
			if (helper.getLevel().getBlockState(dirt).isAir() && bot.getY() > dirt.getY() + 0.5) {
				helper.fail("broke the dirt from on top, bot at " + bot.position());
			}
		});
		expect(helper, bot, "r = mine(" + at(helper, 4, 5, 4) + ")\nprint(r.ok, r.reason)\n", List.of("True None"), () -> {
			helper.assertBlockPresent(Blocks.AIR, 4, 5, 4);
			helper.assertTrue(bot.getY() < dirt.getY() - 2, "not on the floor: " + bot.position());
		});
	}

	/** The same block with a floor 3 under it: the bot breaks it from where it stands and drops 3, unhurt. */
	// Measured: passes at tick 16.
	@GameTest(maxTicks = 35)
	public void mineTheBlockUnderfootOverAShortDropBreaksIt(GameTestHelper helper) {
		helper.setBlock(4, 3, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 4, 4);
		expect(helper, bot, "r = mine(" + at(helper, 4, 3, 4) + ")\nprint(r.ok, r.reason)\n", List.of("True None"), () -> {
			helper.assertBlockPresent(Blocks.AIR, 4, 3, 4);
			helper.assertValueEqual(bot.getHealth(), bot.getMaxHealth(), "health");
		});
	}

	/** The block is mined, but its drop does not fit and stays on the ground. */
	@GameTest(maxTicks = 20)
	public void mineWithAFullInventoryIsNoSpace(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_PICKAXE));
		fillInventory(bot);
		expect(helper, bot, "r = mine(" + at(helper, 2, 1, 2) + ")\nprint(r.ok, r.reason, count('cobblestone'))\n",
			List.of("False no_space 0"), () -> {
				helper.assertBlockPresent(Blocks.AIR, 2, 1, 2);
				helper.assertItemEntityPresent(Items.COBBLESTONE, new BlockPos(2, 1, 2), 2.0);
			});
	}

	/** Every block action checks the server's spawn protection, as a player's hand does. */
	@GameTest(maxTicks = 20)
	public void blockActionsUnderSpawnProtectionAreProtected(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.DIRT);
		helper.setBlock(2, 0, 3, Blocks.DIRT);
		GameTests.protect(helper, new BlockPos(2, 1, 2));
		GameTests.protect(helper, new BlockPos(3, 1, 2));
		GameTests.protect(helper, new BlockPos(2, 0, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SHOVEL));
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		expect(helper, bot, "r = mine(" + at(helper, 2, 1, 2) + ")\ns = place(" + at(helper, 3, 1, 2) + ", 'dirt')\n"
				+ "t = use(" + at(helper, 2, 0, 3) + ", 'wooden_hoe')\nprint(r.reason, s.reason, t.reason, count('dirt'))\n",
			List.of("protected protected protected 1"), () -> {
				helper.assertBlockPresent(Blocks.DIRT, 2, 1, 2);
				helper.assertBlockPresent(Blocks.AIR, 3, 1, 2);
				helper.assertBlockPresent(Blocks.DIRT, 2, 0, 3);
			});
	}

	/** The walk into reach finds the chunks on its way never load: {@code not_loaded}, passed on as {@code goto} gives it. */
	@GameTest(maxTicks = 50)
	public void aBlockActionWhoseWalkFindsNoLoadedChunksIsNotLoaded(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		StandInBody body = new StandInBody(bot.getBody());
		body.chunksLoaded = false;
		bot.setBody(body);
		expect(helper, bot, "r = mine(" + at(helper, 6, 1, 6) + ")\ns = place(" + at(helper, 6, 2, 6) + ", 'dirt')\n"
				+ "print(r.ok, r.reason, s.reason)\n",
			List.of("False not_loaded not_loaded"), () -> helper.assertBlockPresent(Blocks.DIRT, 6, 1, 6));
	}

	/** The walk into reach gets no closer: {@code timeout}, passed on as {@code goto} gives it. */
	@GameTest(maxTicks = 30)
	public void aBlockActionWhoseWalkGetsNoCloserTimesOut(GameTestHelper helper) {
		helper.setBlock(6, 1, 6, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		StandInBody body = new StandInBody(bot.getBody());
		body.standStill = true;
		bot.setBody(body);
		expect(helper, bot, "r = mine(" + at(helper, 6, 1, 6) + ")\nprint(r.ok, r.reason)\n", List.of("False timeout"),
			() -> helper.assertBlockPresent(Blocks.DIRT, 6, 1, 6));
	}

	/**
	 * The bot's body with parts stood in for: chunks that never load, or a walk that never moves the bot; and short waits
	 * before {@code not_loaded} and {@code timeout}, so the tests need not wait out the real ones.
	 */
	static final class StandInBody implements MoveApi.Body {
		private static final int WAIT_TICKS = 10;
		private final MoveApi.Body body;
		boolean chunksLoaded = true;
		boolean standStill;

		StandInBody(MoveApi.Body body) {
			this.body = body;
		}

		@Override
		public Pos home() {
			return this.body.home();
		}

		@Override
		public void setHome(Pos home, String dimension) {
			this.body.setHome(home, dimension);
		}

		@Override
		public String dimension() {
			return this.body.dimension();
		}

		@Override
		public String homeDimension() {
			return this.body.homeDimension();
		}

		@Override
		public boolean chunkLoaded(int x, int z) {
			return this.chunksLoaded && this.body.chunkLoaded(x, z);
		}

		@Override
		public void loadChunk(int x, int z) {
			if (this.chunksLoaded) {
				this.body.loadChunk(x, z);
			}
		}

		@Override
		public void startPath(Pos target, int near) {
			if (!this.standStill) {
				this.body.startPath(target, near);
			}
		}

		@Override
		public void startReach(Pos target, double reach) {
			if (!this.standStill) {
				this.body.startReach(target, reach);
			}
		}

		@Override
		public boolean inReach(Pos target) {
			return this.body.inReach(target);
		}

		@Override
		public MoveApi.PathStatus tickPath() {
			return this.standStill ? MoveApi.PathStatus.RUNNING : this.body.tickPath();
		}

		@Override
		public void stopPath() {
			this.body.stopPath();
		}

		@Override
		public MoveApi.Plan plan(Pos target, int near) {
			return this.body.plan(target, near);
		}

		@Override
		public int loadWaitTicks() {
			return WAIT_TICKS;
		}

		@Override
		public int stallTicks() {
			return WAIT_TICKS;
		}
	}

	// afloat (#840)

	/**
	 * A pool 3 deep, 5 by 5, in stone up to y 3: water at y 1 to 3 over the floor x 2 to 6, z 2 to 6. The bot stands at
	 * (0, 4, 0), on the rim; the middle of the pool's bottom is out of reach (4 from the eyes) from every block of the rim.
	 */
	private static AntBot pool(GameTestHelper helper) {
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				boolean pool = x >= 2 && x <= 6 && z >= 2 && z <= 6;
				for (int y = 1; y <= 3; y++) {
					helper.setBlock(x, y, z, pool ? Blocks.WATER : Blocks.STONE);
				}
			}
		}
		return helper.spawn(ModEntities.ANT_BOT, 0, 4, 0);
	}

	/** {@code place} on the floor of the middle of a pool 3 deep swims out and places from the surface. */
	// Measured: passes at tick 32 (red: out_of_reach).
	@GameTest(maxTicks = 70)
	public void placeOnTheFloorOfADeepPoolFromTheSurface(GameTestHelper helper) {
		AntBot bot = pool(helper);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		expect(helper, bot, "r = place(" + at(helper, 4, 1, 4) + ", 'dirt')\nprint(r.ok, r.reason)\n", List.of("True None"),
			() -> helper.assertBlockPresent(Blocks.DIRT, 4, 1, 4));
	}

	/** {@code mine} of kelp in the middle of a pool 3 deep swims out and cuts it from the surface. */
	// Measured: passes at tick 27 (red: out_of_reach).
	@GameTest(maxTicks = 70)
	public void mineKelpInADeepPoolFromTheSurface(GameTestHelper helper) {
		AntBot bot = pool(helper);
		helper.setBlock(4, 1, 4, Blocks.KELP_PLANT);
		helper.setBlock(4, 2, 4, Blocks.KELP);
		expect(helper, bot, "r = mine(" + at(helper, 4, 2, 4) + ")\nprint(r.ok, r.reason, count('kelp'))\n", List.of("True None 1"),
			() -> helper.assertBlockPresent(Blocks.WATER, 4, 2, 4));
	}

	/**
	 * {@code use} of an empty bucket on the water of the middle of a pool 3 deep's bottom swims out and fills it from the
	 * surface, as use walks into reach as place and mine do (#857).
	 */
	// Measured: passes at tick 32 (green already: use walks with BotBody.startReach, as place and mine do).
	@GameTest(maxTicks = 70)
	public void useABucketOnWaterInADeepPoolFromTheSurface(GameTestHelper helper) {
		AntBot bot = pool(helper);
		bot.getInventory().addItem(new ItemStack(Items.BUCKET));
		expect(helper, bot, "r = use(" + at(helper, 4, 1, 4) + ", 'bucket')\nprint(r.ok, r.reason, count('water_bucket'))\n",
			List.of("True None 1"));
	}

	// place

	// Full runs: passes at up to tick 36 (#861).
	@GameTest(maxTicks = 80)
	public void placeWalksIntoReachAndPlacesFacingTheWayAsked(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		bot.getInventory().addItem(new ItemStack(Items.OAK_LOG, 2));
		expect(helper, bot, "r = place(" + at(helper, 6, 1, 6) + ", 'furnace', facing='east')\n"
				+ "s = place(" + at(helper, 6, 1, 5) + ", ['#logs'], 'east')\n"
				+ "t = place(" + at(helper, 6, 2, 5) + ", 'oak_log')\n"
				+ "print(r.ok, r.reason, s.ok, t.ok, count('furnace'), count('oak_log'))\n",
			List.of("True None True True 0 0"), () -> {
				helper.assertBlockProperty(new BlockPos(6, 1, 6), FurnaceBlock.FACING, Direction.EAST);
				helper.assertBlockProperty(new BlockPos(6, 1, 5), RotatedPillarBlock.AXIS, Direction.Axis.X);
				helper.assertBlockPresent(Blocks.OAK_LOG, 6, 2, 5);
			});
	}

	/** half sets a slab's type; a slab placed onto a slab is blocked, as onto any block, rather than made a double slab. */
	@GameTest(maxTicks = 60)
	public void placeHalfTopGivesATopSlab(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.OAK_SLAB, 3));
		expect(helper, bot, "r = place(" + at(helper, 5, 1, 5) + ", 'oak_slab', half='top')\n"
				+ "s = place(" + at(helper, 5, 1, 4) + ", 'oak_slab', half='bottom')\n"
				+ "t = place(" + at(helper, 5, 1, 4) + ", 'oak_slab', half='top')\n"
				+ "print(r.ok, s.ok, t.ok, t.reason, count('oak_slab'))\n",
			List.of("True True False blocked 1"), () -> {
				helper.assertBlockProperty(new BlockPos(5, 1, 5), SlabBlock.TYPE, SlabType.TOP);
				helper.assertBlockProperty(new BlockPos(5, 1, 4), SlabBlock.TYPE, SlabType.BOTTOM);
			});
	}

	@GameTest(maxTicks = 20)
	public void placeWithoutTheItemIsNoItem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.STICK));
		expect(helper, bot, "r = place(" + at(helper, 2, 1, 2) + ", 'dirt')\ns = place(" + at(helper, 2, 1, 2) + ", 'stick')\n"
			+ "print(r.ok, r.reason, s.reason)\n", List.of("False no_item no_item"),
			() -> helper.assertBlockPresent(Blocks.AIR, 2, 1, 2));
	}

	@GameTest(maxTicks = 20)
	public void placeWhereABlockIsIsBlocked(GameTestHelper helper) {
		helper.setBlock(2, 1, 2, Blocks.GLASS);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		expect(helper, bot, "r = place(" + at(helper, 2, 1, 2) + ", 'dirt')\nprint(r.ok, r.reason, r.value, count('dirt'))\nprint(r.detail)\n",
			List.of("False blocked None 1", "minecraft:glass at Pos" + at(helper, 2, 1, 2) + " cannot be replaced"),
			() -> helper.assertBlockPresent(Blocks.GLASS, 2, 1, 2));
	}

	/**
	 * Grass, tall grass (onto either half), snow of 1 and 3 layers, leaves and a flower in the way are broken, their drops
	 * kept, and the block placed (#747).
	 */
	// Measured: passes at tick 62.
	@GameTest(maxTicks = 130)
	public void placeBreaksSoftBlocksInTheWay(GameTestHelper helper) {
		BlockState upper = Blocks.TALL_GRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
		for (BlockPos plant : List.of(new BlockPos(2, 0, 3), new BlockPos(4, 0, 3), new BlockPos(6, 0, 3), new BlockPos(2, 0, 7))) {
			helper.setBlock(plant, Blocks.DIRT);
		}
		helper.setBlock(2, 1, 3, Blocks.SHORT_GRASS);
		helper.setBlock(4, 1, 3, Blocks.TALL_GRASS);
		helper.setBlock(4, 2, 3, upper);
		helper.setBlock(6, 1, 3, Blocks.TALL_GRASS);
		helper.setBlock(6, 2, 3, upper);
		helper.setBlock(2, 1, 5, Blocks.SNOW);
		helper.setBlock(4, 1, 5, Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 3));
		helper.setBlock(6, 1, 5, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		helper.setBlock(2, 1, 7, Blocks.POPPY);
		List<BlockPos> spots = List.of(new BlockPos(2, 1, 3), new BlockPos(4, 1, 3), new BlockPos(6, 2, 3), new BlockPos(2, 1, 5),
			new BlockPos(4, 1, 5), new BlockPos(6, 1, 5), new BlockPos(2, 1, 7));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, spots.size()));
		StringBuilder source = new StringBuilder("r = []\n");
		for (BlockPos spot : spots) {
			source.append("r.append(place(").append(at(helper, spot.getX(), spot.getY(), spot.getZ())).append(", 'dirt').reason)\n");
		}
		source.append("print(r, count('dirt'), count('poppy'))\n");
		expect(helper, bot, source.toString(), List.of("[None, None, None, None, None, None, None] 0 1"),
			() -> spots.forEach(spot -> helper.assertBlockPresent(Blocks.DIRT, spot)));
	}

	/**
	 * An oak sapling goes where an oak sapling stands (#759): the one standing is broken, its drop kept, as any soft block
	 * in the way. Only a block placed into itself, snow onto snow, stays and grows. It failed with "the game would not place".
	 */
	// Measured: passes at tick 3 (red: blocked).
	@GameTest(maxTicks = 20)
	public void placeASaplingWhereTheSameSaplingStands(GameTestHelper helper) {
		helper.setBlock(2, 0, 3, Blocks.DIRT);
		helper.setBlock(2, 1, 3, Blocks.OAK_SAPLING);
		helper.setBlock(4, 1, 3, Blocks.SNOW);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.OAK_SAPLING));
		bot.getInventory().addItem(new ItemStack(Items.SNOW));
		expect(helper, bot, "r = place(" + at(helper, 2, 1, 3) + ", 'oak_sapling')\ns = place(" + at(helper, 4, 1, 3) + ", 'snow')\n"
				+ "print(r.reason, s.reason, count('oak_sapling'), count('snow'))\n",
			List.of("None None 1 0"), () -> {
				helper.assertBlockPresent(Blocks.OAK_SAPLING, 2, 1, 3);
				helper.assertBlockProperty(new BlockPos(4, 1, 3), BlockStateProperties.LAYERS, 2);
			});
	}

	/** A torch goes onto a spot holding grass or 3 layers of snow (#747). */
	// Measured: passes at tick 12.
	@GameTest(maxTicks = 30)
	public void placeATorchOnGrassOrSnow(GameTestHelper helper) {
		helper.setBlock(2, 0, 3, Blocks.DIRT);
		helper.setBlock(2, 1, 3, Blocks.SHORT_GRASS);
		helper.setBlock(4, 1, 3, Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.TORCH, 2));
		expect(helper, bot, "r = place(" + at(helper, 2, 1, 3) + ", 'torch')\ns = place(" + at(helper, 4, 1, 3) + ", 'torch')\n"
				+ "print(r.reason, s.reason, count('torch'))\n",
			List.of("None None 0"), () -> {
				helper.assertBlockPresent(Blocks.TORCH, 2, 1, 3);
				helper.assertBlockPresent(Blocks.TORCH, 4, 1, 3);
			});
	}

	/** A pig stands where the block would go. */
	@GameTest(maxTicks = 20)
	public void placeWhereAnEntityStandsIsBlocked(GameTestHelper helper) {
		helper.spawnWithNoFreeWill(EntityTypes.PIG, 3, 1, 3);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		expect(helper, bot, "r = place(" + at(helper, 3, 1, 3) + ", 'dirt')\nprint(r.ok, r.reason, r.value, count('dirt'))\nprint(r.detail)\n",
			List.of("False blocked None 1", "the game would not place minecraft:dirt at Pos" + at(helper, 3, 1, 3)),
			() -> helper.assertBlockPresent(Blocks.AIR, 3, 1, 3));
	}

	/** An idle bot stands where the block goes: it is asked to make way, steps aside, and the block is placed (#824). */
	// Measured: passes at tick 9.
	@GameTest(maxTicks = 20)
	public void placeWhereAnotherBotStandsAsksItToMakeWay(GameTestHelper helper) {
		AntBot other = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		expect(helper, bot, "r = place(" + at(helper, 3, 1, 3) + ", 'dirt')\nprint(r.ok, r.reason, count('dirt'))\n",
			List.of("True None 0"), () -> {
				helper.assertBlockPresent(Blocks.DIRT, 3, 1, 3);
				helper.assertValueEqual(other.getWaysMade() > 0, true, "the other bot made way");
			});
	}

	@GameTest(maxTicks = 20)
	public void placeAboveTheWorldIsProtected(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		expect(helper, bot, "r = place(" + aboveTheWorld(helper) + ", 'dirt')\nprint(r.ok, r.reason, count('dirt'))\n",
			List.of("False protected 1"));
	}

	/** A spot high in the air, and nothing the bot can climb with: a furnace is no scaffold. */
	@GameTest(maxTicks = 50)
	public void placeOutOfReachIsOutOfReach(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.FURNACE));
		expect(helper, bot, "r = place(" + at(helper, 4, 7, 4) + ", 'furnace')\nprint(r.ok, r.reason, count('furnace'))\n",
			List.of("False out_of_reach 1"), () -> helper.assertBlockPresent(Blocks.AIR, 4, 7, 4));
	}

	// pick_up

	@GameTest(maxTicks = 100)
	public void pickUpCollectsTheMatchingItemsAround(GameTestHelper helper) {
		helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(6, 1, 6));
		helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(6, 1, 4));
		helper.spawnItem(Items.HEART_OF_THE_SEA, new BlockPos(1, 1, 6));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = pick_up('nautilus_shell', radius=7)\nprint(r.ok, r.reason, r.value, count('nautilus_shell'), count('heart_of_the_sea'))\n",
			List.of("True None 2 2 0"), () -> helper.assertItemEntityPresent(Items.HEART_OF_THE_SEA));
	}

	@GameTest(maxTicks = 100)
	public void pickUpWithoutWhatTakesAnyItemAround(GameTestHelper helper) {
		helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(5, 1, 5));
		helper.spawnItem(Items.HEART_OF_THE_SEA, new BlockPos(1, 1, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = pick_up(radius=6)\nprint(r.ok, r.value, count('nautilus_shell'), count('heart_of_the_sea'))\n",
			List.of("True 2 1 1"), () -> helper.assertValueEqual(bot.getScript().lastResult(), "pick_up(radius=6) -> ok, 2", "last result"));
	}

	@GameTest(maxTicks = 100)
	public void pickUpWithNoArgumentsTakesAnyItemAround(GameTestHelper helper) {
		helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(5, 1, 5));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = pick_up()\nprint(r.ok, r.value, count('nautilus_shell'))\n",
			List.of("True 1 1"), () -> helper.assertValueEqual(bot.getScript().lastResult(), "pick_up() -> ok, 1", "last result"));
	}

	@GameTest(maxTicks = 20)
	public void pickUpWithNothingAroundIsTargetGone(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = pick_up('nautilus_shell', radius=7)\nprint(r.ok, r.reason)\n", List.of("False target_gone"));
	}

	@GameTest(maxTicks = 20)
	public void pickUpWithAFullInventoryIsNoSpace(GameTestHelper helper) {
		helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(3, 1, 3));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		fillInventory(bot);
		expect(helper, bot, "r = pick_up('nautilus_shell', radius=7)\nprint(r.ok, r.reason)\n", List.of("False no_space"),
			() -> helper.assertItemEntityPresent(Items.NAUTILUS_SHELL));
	}

	/** An item on top of a bedrock pillar, which the bot cannot dig away, and nothing to climb with. */
	@GameTest(maxTicks = 80)
	public void pickUpAnItemOutOfReachIsOutOfReach(GameTestHelper helper) {
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(5, y, 5, Blocks.BEDROCK);
		}
		ItemEntity shell = helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(5, 5, 5));
		shell.setPos(shell.getX() + 0.5, shell.getY(), shell.getZ() + 0.5);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = pick_up('nautilus_shell', radius=7)\nprint(r.ok, r.reason)\n", List.of("False out_of_reach"),
			() -> helper.assertItemEntityPresent(Items.NAUTILUS_SHELL));
	}

	/** One shell lies in the open, another on a bedrock pillar: it takes the one and is done, ok. */
	@GameTest(maxTicks = 80)
	public void pickUpTakesWhatItCanReachAndSkipsTheRest(GameTestHelper helper) {
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(5, y, 5, Blocks.BEDROCK);
		}
		ItemEntity high = helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(5, 5, 5));
		high.setPos(high.getX() + 0.5, high.getY(), high.getZ() + 0.5);
		helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(3, 1, 1));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = pick_up('nautilus_shell', radius=7)\nprint(r.ok, r.reason, r.value, count('nautilus_shell'))\n",
			List.of("True None 1 1"), () -> helper.assertItemEntityPresent(Items.NAUTILUS_SHELL));
	}

	/**
	 * Bone meal lying in a composter, in its far half from the bot beside it, out of its grab: it takes the bone meal from
	 * beside the composter and leaves the composter standing (#817), where it walked onto the item's block and broke it.
	 */
	@GameTest(maxTicks = 40)
	public void pickUpTakesAnItemInABlockWithoutBreakingIt(GameTestHelper helper) {
		helper.setBlock(4, 1, 4, Blocks.COMPOSTER);
		ItemEntity meal = helper.spawnItem(Items.BONE_MEAL, new BlockPos(4, 1, 4));
		Vec3 in = helper.absoluteVec(new Vec3(4.25, 1.125, 4.25));
		meal.setPos(in.x, in.y, in.z);
		meal.setDeltaMovement(Vec3.ZERO);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(4.5, 1, 5.85));
		expect(helper, bot, "r = pick_up('bone_meal', radius=3)\nprint(r.ok, r.value, count('bone_meal'))\n", List.of("True 1 1"),
			() -> helper.assertBlockPresent(Blocks.COMPOSTER, 4, 1, 4));
	}

	/**
	 * Berries lying in a ripe sweet berry bush, in its far half from the bot beside it, out of its grab: it takes them from
	 * beside the bush, never walking into it, which hurts (#832).
	 */
	@GameTest(maxTicks = 40)
	public void pickUpTakesAnItemInABushWithoutEnteringIt(GameTestHelper helper) {
		helper.setBlock(4, 1, 4, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
		ItemEntity berries = helper.spawnItem(Items.SWEET_BERRIES, new BlockPos(4, 1, 4));
		Vec3 in = helper.absoluteVec(new Vec3(4.25, 1, 4.25));
		berries.setPos(in.x, in.y, in.z);
		berries.setDeltaMovement(Vec3.ZERO);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(4.5, 1, 5.85));
		helper.onEachTick(() -> {
			if (bot.getLastDamageSource() != null && bot.getLastDamageSource().is(DamageTypes.SWEET_BERRY_BUSH)) {
				helper.fail("the bush hurt the bot at " + TestPositions.relative(helper, bot.blockPosition()));
			}
		});
		expect(helper, bot, "r = pick_up('sweet_berries', radius=3)\nprint(r.ok, r.value, count('sweet_berries'))\n", List.of("True 1 1"));
	}

	// equip

	@GameTest(maxTicks = 20)
	public void equipHoldsTheItem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
		bot.getInventory().addItem(new ItemStack(Items.DIAMOND_SWORD));
		expect(helper, bot, "r = equip('#swords')\nprint(r.ok, r.reason, held().item, count('stick'))\n",
			List.of("True None minecraft:diamond_sword 1"));
	}

	@GameTest(maxTicks = 20)
	public void equipWithoutTheItemIsNoItem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.STICK));
		expect(helper, bot, "r = equip('diamond_sword')\nprint(r.ok, r.reason, held().item)\n",
			List.of("False no_item minecraft:stick"));
	}

	// use

	@GameTest(maxTicks = 70)
	public void useTillsDirtWithAHoe(GameTestHelper helper) {
		helper.setBlock(6, 0, 6, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		expect(helper, bot, "r = use(" + at(helper, 6, 0, 6) + ", 'wooden_hoe')\nprint(r.ok, r.reason, held().item)\n",
			List.of("True None minecraft:wooden_hoe"), () -> helper.assertBlockPresent(Blocks.FARMLAND, 6, 0, 6));
	}

	@GameTest(maxTicks = 20)
	public void useScoopsAndPoursWaterWithABucket(GameTestHelper helper) {
		helper.setBlock(3, 0, 3, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.BUCKET));
		expect(helper, bot, "r = use(" + at(helper, 3, 0, 3) + ", 'bucket')\nprint(r.ok, held().item)\n"
				+ "s = use(" + at(helper, 1, 0, 3) + ")\nprint(s.ok, held().item)\n",
			List.of("True minecraft:water_bucket", "True minecraft:bucket"), () -> {
				helper.assertBlockPresent(Blocks.AIR, 3, 0, 3);
				helper.assertTrue(helper.getBlockState(new BlockPos(1, 1, 3)).getFluidState().is(Fluids.WATER), "no water poured at (1, 1, 3)");
			});
	}

	/** use on an Entity walks to a cow and feeds it wheat, as a player's right click does: the cow is in love (#837). */
	// Measured: passes at tick 48 (red: a position is a Pos or a tuple of three ints, not 'Entity').
	@GameTest(maxTicks = 100)
	public void useFeedsACowWheat(GameTestHelper helper) {
		Cow cow = helper.spawn(EntityTypes.COW, 6, 1, 6);
		cow.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WHEAT, 2));
		expect(helper, bot, "r = use(entities('cow')[0], 'wheat')\nprint(r.ok, count('wheat'))\n",
			List.of("True 1"), () -> helper.assertTrue(cow.isInLove(), "the cow is not in love"));
	}

	/** use with shears on a sheep shears it, its wool dropped; on a sheared sheep it does nothing (blocked) (#837). */
	// Measured: passes at tick 49.
	@GameTest(maxTicks = 100)
	public void useShearsASheepOnce(GameTestHelper helper) {
		Sheep sheep = helper.spawn(EntityTypes.SHEEP, 6, 1, 6);
		sheep.setNoAi(true);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.SHEARS));
		expect(helper, bot, "s = entities('sheep')[0]\nr = use(s, 'shears')\nt = use(s, 'shears')\nprint(r.ok, t.ok, t.reason)\n",
			List.of("True False blocked"), () -> helper.assertTrue(sheep.isSheared(), "the sheep is not sheared"));
	}

	/** use on a block every face of which another block hides fails out_of_reach and does nothing (#616). */
	@GameTest(maxTicks = 10)
	public void useOnABlockWithEveryFaceHiddenIsOutOfReach(GameTestHelper helper) {
		helper.setBlock(3, 1, 3, Blocks.DIRT);
		for (BlockPos around : List.of(new BlockPos(2, 1, 3), new BlockPos(4, 1, 3), new BlockPos(3, 1, 2), new BlockPos(3, 1, 4),
			new BlockPos(3, 2, 3))) {
			helper.setBlock(around, Blocks.STONE);
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(1.5, 1, 3.5));
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WATER_BUCKET));
		helper.succeedWhen(() -> {
			String failed = BotHands.use(bot, helper.absolutePos(new BlockPos(3, 1, 3)));
			helper.assertTrue(Result.OUT_OF_REACH.equals(failed), "use gave " + failed);
			helper.assertTrue(bot.getMainHandItem().is(Items.WATER_BUCKET), "held " + bot.getMainHandItem());
		});
	}

	/**
	 * Where the walk takes a block to be in reach, use aims at it, and the other way round (#616): a bot off the middle of
	 * its block, whose eyes a block beside the target hides it from though it is seen from the middle (the walk stands bots
	 * anywhere in the end block), and a target seen only through a cobweb, which a bucket's ray stops at.
	 */
	@GameTest(maxTicks = 10)
	public void walkAndUseAgreeOnReach(GameTestHelper helper) {
		helper.setBlock(4, 2, 2, Blocks.STONE);
		helper.setBlock(5, 2, 2, Blocks.STONE);
		AntBot offMiddle = helper.spawn(ModEntities.ANT_BOT, new Vec3(3.5, 1, 3.05));
		helper.setBlock(5, 1, 6, Blocks.STONE);
		helper.setBlock(4, 1, 6, Blocks.COBWEB);
		for (BlockPos around : List.of(new BlockPos(6, 1, 6), new BlockPos(5, 1, 5), new BlockPos(5, 1, 7), new BlockPos(5, 2, 6))) {
			helper.setBlock(around, Blocks.STONE);
		}
		AntBot web = helper.spawn(ModEntities.ANT_BOT, new Vec3(3.5, 1, 6.5));
		helper.succeedWhen(() -> {
			assertAgree(helper, offMiddle, new BlockPos(5, 2, 2));
			assertAgree(helper, web, new BlockPos(5, 1, 6));
		});
	}

	/**
	 * Mine and place judge reach from the same eyes as use, over the middle of the bot's block (#624): a bot off the middle,
	 * whose own eyes a block beside the target hides it from, mines and places it where it stands, and use aims at it too.
	 */
	@GameTest(maxTicks = 40)
	public void mineAndPlaceAgreeWithUseOnReachFromOffTheMiddle(GameTestHelper helper) {
		helper.setBlock(4, 2, 2, Blocks.STONE);
		helper.setBlock(5, 2, 2, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(3.5, 1, 3.05));
		String target = at(helper, 5, 2, 2);
		expect(helper, bot, "p = pos()\nprint(mine(" + target + ").ok, pos() == p, place(" + target + ", 'dirt').ok, pos() == p, use("
			+ target + ").reason)\n", List.of("True True True True blocked"));
	}

	/** That {@code bot} has {@code target} in reach exactly when an empty-handed use of it does not fail out_of_reach. */
	private static void assertAgree(GameTestHelper helper, AntBot bot, BlockPos target) {
		BlockPos at = helper.absolutePos(target);
		boolean inReach = bot.getBody().inReach(new Pos(at.getX(), at.getY(), at.getZ()));
		String failed = BotHands.use(bot, at);
		helper.assertTrue(inReach != Result.OUT_OF_REACH.equals(failed),
			"at " + bot.position() + ": in reach " + inReach + " but use gave " + failed + " on " + target);
	}

	/**
	 * Pouring into a 1x1 hole from off the middle of its edge, where the line from the eyes to the middle of the hole's
	 * floor clips the rim (#591): the water goes into the hole, or use fails and pours nothing, never onto the rim.
	 */
	@GameTest(maxTicks = 10)
	public void usePoursIntoAHoleFromItsEdgeOrFails(GameTestHelper helper) {
		for (int x = 1; x <= 5; x++) {
			for (int z = 1; z <= 5; z++) {
				helper.setBlock(x, 1, z, x == 3 && z == 3 ? Blocks.AIR : Blocks.DIRT);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(2.2, 2, 3.5));
		bot.getInventory().addItem(new ItemStack(Items.WATER_BUCKET));
		ScriptRun run = BotScripts.run(bot, "r = use(" + at(helper, 3, 0, 3) + ", 'water_bucket')\nprint(r.ok)\n");
		BotScripts.failOnError(helper, bot);
		helper.succeedWhen(() -> {
			helper.assertTrue(run.state() == ScriptRun.State.FINISHED, "script " + run.state());
			for (int x = 1; x <= 5; x++) {
				for (int z = 1; z <= 5; z++) {
					helper.assertTrue(!helper.getBlockState(new BlockPos(x, 2, z)).getFluidState().is(Fluids.WATER),
						"water poured onto the rim at (" + x + ", 2, " + z + "), printed " + printed(bot));
				}
			}
			boolean filled = helper.getBlockState(new BlockPos(3, 1, 3)).getFluidState().is(Fluids.WATER);
			helper.assertTrue(printed(bot).equals(List.of(filled ? "True" : "False")),
				"printed " + printed(bot) + " with the hole " + (filled ? "filled" : "dry"));
		});
	}

	@GameTest(maxTicks = 20)
	public void useGrowsWheatWithBoneMeal(GameTestHelper helper) {
		helper.setBlock(3, 0, 3, Blocks.FARMLAND);
		helper.setBlock(3, 1, 3, Blocks.WHEAT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.BONE_MEAL, 3));
		expect(helper, bot, "r = use(" + at(helper, 3, 1, 3) + ", 'bone_meal')\nprint(r.ok, count('bone_meal'))\n",
			List.of("True 2"), () -> helper.assertTrue(helper.getBlockState(new BlockPos(3, 1, 3)).getValue(CropBlock.AGE) > 0, "wheat did not grow"));
	}

	@GameTest(maxTicks = 20)
	public void useLightsAFireWithFlintAndSteel(GameTestHelper helper) {
		helper.setBlock(3, 0, 3, Blocks.NETHERRACK);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.FLINT_AND_STEEL));
		expect(helper, bot, "r = use(" + at(helper, 3, 0, 3) + ", 'flint_and_steel')\nprint(r.ok, r.reason)\n",
			List.of("True None"), () -> helper.assertBlockPresent(Blocks.FIRE, 3, 1, 3));
	}

	@GameTest(maxTicks = 20)
	public void useWithoutTheItemIsNoItem(GameTestHelper helper) {
		helper.setBlock(2, 0, 2, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = use(" + at(helper, 2, 0, 2) + ", 'wooden_hoe')\nprint(r.ok, r.reason)\n", List.of("False no_item"),
			() -> helper.assertBlockPresent(Blocks.DIRT, 2, 0, 2));
	}

	@GameTest(maxTicks = 20)
	public void useAboveTheWorldIsProtected(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		expect(helper, bot, "r = use(" + aboveTheWorld(helper) + ", 'wooden_hoe')\nprint(r.ok, r.reason)\n", List.of("False protected"));
	}

	@GameTest(maxTicks = 20)
	public void useThatDoesNothingIsBlocked(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		expect(helper, bot, "r = use(" + at(helper, 2, 0, 2) + ", 'wooden_hoe')\nprint(r.ok, r.reason, r.value)\nprint(r.detail)\n",
			List.of("False blocked None", "using minecraft:wooden_hoe on minecraft:stone at Pos" + at(helper, 2, 0, 2) + " did nothing"),
			() -> helper.assertBlockPresent(Blocks.STONE, 2, 0, 2));
	}

	@GameTest(maxTicks = 50)
	public void useOutOfReachIsOutOfReach(GameTestHelper helper) {
		helper.setBlock(4, 7, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		expect(helper, bot, "r = use(" + at(helper, 4, 7, 4) + ", 'wooden_hoe')\nprint(r.ok, r.reason)\n", List.of("False out_of_reach"),
			() -> helper.assertBlockPresent(Blocks.DIRT, 4, 7, 4));
	}

	// drop

	/** Dropped items land on the ground, and the bot does not pick them up again by itself. */
	// Full runs: passes at up to tick 61 (#861).
	@GameTest(maxTicks = 130)
	public void dropThrowsItemsOutAndLeavesThem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.NAUTILUS_SHELL, 5));
		expect(helper, bot, "r = drop('nautilus_shell', 2)\nprint(r.ok, r.reason, r.value, count('nautilus_shell'))\n"
				+ "wait(60)\nprint(count('nautilus_shell'), len(items_near('nautilus_shell', 4)))\n",
			List.of("True None 2 3", "3 1"));
	}

	/** A nautilus shell lying at {@code (x, 1, z)} that {@code thrower} dropped, which anyone may pick up at once. */
	static ItemEntity droppedBy(GameTestHelper helper, Entity thrower, int x, int z) {
		ItemEntity item = helper.spawnItem(Items.NAUTILUS_SHELL, new BlockPos(x, 1, z));
		item.setThrower(thrower);
		item.setNoPickUpDelay();
		return item;
	}

	/** What one bot dropped on purpose, another bot standing on it leaves lying too (#420). */
	@GameTest(maxTicks = 60)
	public void aBotLeavesWhatAnotherBotDropped(GameTestHelper helper) {
		AntBot dropper = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		droppedBy(helper, dropper, 5, 5);
		expect(helper, bot, "wait(20)\nprint(count('nautilus_shell'))\n", List.of("0"),
			() -> helper.assertItemEntityPresent(Items.NAUTILUS_SHELL));
	}

	@GameTest(maxTicks = 20)
	public void pickUpTakesWhatAnotherBotDropped(GameTestHelper helper) {
		AntBot dropper = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		droppedBy(helper, dropper, 5, 5);
		expect(helper, bot, "r = pick_up('nautilus_shell')\nprint(r.ok, count('nautilus_shell'))\n", List.of("True 1"));
	}

	/** What a player or a mob dropped the bot picks up by itself as it passes. */
	@GameTest(maxTicks = 60)
	public void aBotPicksUpWhatAPlayerOrAMobDropped(GameTestHelper helper) {
		Cow cow = helper.spawnWithNoFreeWill(EntityTypes.COW, 1, 1, 1);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		Entity player = BotScripts.owner(helper, bot);
		droppedBy(helper, cow, 5, 5);
		droppedBy(helper, player, 5, 5);
		expect(helper, bot, "wait(20)\nprint(count('nautilus_shell'))\n", List.of("2"));
	}

	@GameTest(maxTicks = 20)
	public void dropWithoutTheItemIsNoItem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		expect(helper, bot, "r = drop('nautilus_shell')\nprint(r.ok, r.reason)\n", List.of("False no_item"));
	}

	// build

	/** The blocks of {@code antcraft:cobble_step} built at (3, 1, 3) turned {@code turn} quarters: two cobblestone, oak stairs on the first, air beside them. */
	static void assertCobbleStep(GameTestHelper helper, int turn) {
		BlockPos side = turn == 0 ? new BlockPos(1, 0, 0) : new BlockPos(0, 0, 1);
		BlockPos origin = new BlockPos(3, 1, 3);
		helper.assertValueEqual(helper.getBlockState(origin), Blocks.COBBLESTONE.defaultBlockState(), "block at the origin");
		helper.assertValueEqual(helper.getBlockState(origin.offset(side)), Blocks.COBBLESTONE.defaultBlockState(), "block beside the origin");
		helper.assertValueEqual(helper.getBlockState(origin.above()),
			Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, turn == 0 ? Direction.EAST : Direction.SOUTH), "stairs");
		helper.assertBlockPresent(Blocks.AIR, origin.above().offset(side));
	}

	static void carryCobbleStep(AntBot bot, int cobblestone) {
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, cobblestone));
		bot.getInventory().addItem(new ItemStack(Items.OAK_STAIRS));
	}

	/** A bot carrying the materials builds the cobble step block for block, clearing the dirt where air goes; value is the blocks placed. */
	@GameTest(maxTicks = 60)
	public void buildBuildsTheCobbleStepAtTurn0(GameTestHelper helper) {
		helper.setBlock(4, 2, 3, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value, count('cobblestone'))\n",
			List.of("True None 3 0"), () -> assertCobbleStep(helper, 0));
	}

	/** Grass, 3 layers of snow and leaves where the cobble step goes are broken and the step built (#747). */
	// Measured: passes at tick 28.
	@GameTest(maxTicks = 60)
	public void buildBuildsOverGrassSnowAndLeaves(GameTestHelper helper) {
		helper.setBlock(3, 0, 3, Blocks.DIRT);
		helper.setBlock(3, 1, 3, Blocks.SHORT_GRASS);
		helper.setBlock(4, 1, 3, Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 3));
		helper.setBlock(3, 2, 3, Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value, count('cobblestone'))\n",
			List.of("True None 3 0"), () -> assertCobbleStep(helper, 0));
	}

	/**
	 * A bot that goes hungry once it placed the first block of a build, carrying bread, eats it between steps before the
	 * build ends, as a player would (#773): it eats while that wastes none of the food, so both bread at 5 food.
	 */
	// Measured: passes at tick 73 (red: built with both bread uneaten at tick 13).
	@GameTest(maxTicks = 150)
	public void buildEatsBetweenStepsWhenHungry(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		boolean[] hungry = new boolean[1];
		helper.onEachTick(() -> {
			if (!hungry[0] && !helper.getBlockState(new BlockPos(3, 1, 3)).isAir()) {
				hungry[0] = true;
				bot.getFood().setFoodLevel(5);
				bot.getFood().setSaturation(0);
				bot.getInventory().addItem(new ItemStack(Items.BREAD, 2));
			}
		});
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, count('bread'))\n",
			List.of("True None 0"), () -> assertCobbleStep(helper, 0));
	}

	/**
	 * A hungry bot building eats none of its suspicious stew, which lib.food leaves out too (#781): its effects are a
	 * component of its own, not on eating, so it eats the bread, though the stew has more points.
	 */
	@GameTest(maxTicks = 150)
	public void buildEatsNoSuspiciousStew(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		boolean[] hungry = new boolean[1];
		helper.onEachTick(() -> {
			if (!hungry[0] && !helper.getBlockState(new BlockPos(3, 1, 3)).isAir()) {
				hungry[0] = true;
				bot.getFood().setFoodLevel(5);
				bot.getFood().setSaturation(0);
				bot.getInventory().addItem(new ItemStack(Items.SUSPICIOUS_STEW));
				bot.getInventory().addItem(new ItemStack(Items.BREAD));
			}
		});
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\n"
				+ "print(r.ok, r.reason, count('suspicious_stew'), count('bread'))\n",
			List.of("True None 1 0"), () -> assertCobbleStep(helper, 0));
	}

	/** A bot builds a blueprint imported from a Litematica schematic ({@code imported_step.litematic}) as any other. */
	@GameTest(maxTicks = 25)
	public void buildBuildsAnImportedSchematic(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 2));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS));
		expect(helper, bot, "r = build('antcraft-gametest:imported_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value)\n",
			List.of("True None 3"), () -> {
				helper.assertBlockPresent(Blocks.COBBLESTONE, 3, 1, 3);
				helper.assertBlockPresent(Blocks.COBBLESTONE, 4, 1, 3);
				helper.assertBlockPresent(Blocks.OAK_PLANKS, 4, 2, 3);
			});
	}

	/**
	 * Structure void keeps what stands there ({@code kept_void.litematic}: structure void, then air): built over two
	 * stone blocks, the bot clears the one under the air and leaves the one under the structure void, placing nothing.
	 */
	// Measured: passes at tick 17.
	@GameTest(maxTicks = 40)
	public void buildLeavesStoneUnderStructureVoid(GameTestHelper helper) {
		helper.setBlock(3, 1, 3, Blocks.STONE);
		helper.setBlock(4, 1, 3, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.IRON_PICKAXE));
		expect(helper, bot, "r = build('antcraft-gametest:kept_void', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value)\n",
			List.of("True None 0"), () -> {
				helper.assertBlockPresent(Blocks.STONE, 3, 1, 3);
				helper.assertBlockPresent(Blocks.AIR, 4, 1, 3);
			});
	}

	/**
	 * A schematic's block entity data comes along ({@code imported_sign.litematic}): the built sign reads its text, and the
	 * chest, which holds a diamond in the schematic, is built empty.
	 */
	@GameTest(maxTicks = 25)
	public void buildGivesAnImportedSignItsText(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
		bot.getInventory().addItem(new ItemStack(Items.OAK_SIGN));
		bot.getInventory().addItem(new ItemStack(Items.CHEST));
		expect(helper, bot, "r = build('antcraft-gametest:imported_sign', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value)\n",
			List.of("True None 3"), () -> {
				helper.assertValueEqual(helper.getBlockEntity(new BlockPos(3, 2, 3), SignBlockEntity.class).getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString(),
					"Hello", "sign text");
				helper.assertTrue(helper.getBlockEntity(new BlockPos(4, 1, 3), ChestBlockEntity.class).isEmpty(), "chest not empty");
			});
	}

	/** The bytes of {@code imported_step.litematic}: cobblestone at (0, 0, 0) and (1, 0, 0), oak planks at (1, 1, 0). */
	private static byte[] importedStep() {
		try (InputStream in = WorkGameTest.class.getResourceAsStream("/data/antcraft-gametest/blueprint/imported_step.litematic")) {
			return Objects.requireNonNull(in).readAllBytes();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Sends {@code file} as Litematica placement {@code name} from {@code player}, part by part as a client does. */
	private static void sendPlacement(ServerPlayer player, String name, BlockPos origin, Rotation rotation, Mirror mirror, byte[] file) {
		BlueprintNetworking.PlacementPart.of(name, origin, rotation, mirror, file).forEach(part -> BlueprintNetworking.handle(player, part));
	}

	/**
	 * A player sends a Litematica placement of {@code imported_step} at (3, 1, 3), mirrored front to back, then turned a
	 * quarter clockwise; the player's bot builds it where Litematica shows it: x mirrored to (0, 0, 0), (-1, 0, 0),
	 * (-1, 1, 0), then turned to (0, 0, 0), (0, 0, -1), (0, 1, -1) from the origin.
	 */
	@GameTest(maxTicks = 35)
	public void buildPlacementBuildsWhereLitematicaPlacesIt(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 6, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 2));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS));
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);
		sendPlacement(owner, "step", helper.absolutePos(new BlockPos(3, 1, 3)), Rotation.CLOCKWISE_90, Mirror.FRONT_BACK, importedStep());
		helper.assertTrue(owner.messages().stream().anyMatch(message -> message.text().contains("received")), "owner told " + owner.messages());
		expect(helper, bot, "r = build_placement('step')\nprint(r.ok, r.reason, r.value)\n", List.of("True None 3"), () -> {
			helper.assertBlockPresent(Blocks.COBBLESTONE, 3, 1, 3);
			helper.assertBlockPresent(Blocks.COBBLESTONE, 3, 1, 2);
			helper.assertBlockPresent(Blocks.OAK_PLANKS, 3, 2, 2);
			helper.assertBlockPresent(Blocks.AIR, 4, 1, 3);
		});
	}

	/**
	 * A malformed file is refused with a message and kept by no name; a bot builds only its own owner's placements, not
	 * another player's of the same name: both give no_blueprint and nothing is built.
	 */
	@GameTest(maxTicks = 20)
	public void buildPlacementRefusesAMalformedFileAndAnotherPlayersPlacement(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 6, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 2));
		bot.getInventory().addItem(new ItemStack(Items.OAK_PLANKS));
		BotScripts.RecordingPlayer stranger = BotScripts.owner(helper, helper.spawn(ModEntities.ANT_BOT, 1, 1, 1));
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);
		sendPlacement(stranger, "step", helper.absolutePos(new BlockPos(3, 1, 3)), Rotation.NONE, Mirror.NONE, importedStep());
		sendPlacement(owner, "bad", helper.absolutePos(new BlockPos(3, 1, 3)), Rotation.NONE, Mirror.NONE, "not a schematic".getBytes());
		helper.assertTrue(owner.messages().stream().anyMatch(message -> message.text().contains("refused")), "owner told " + owner.messages());
		expect(helper, bot, "print(build_placement('step').reason, build_placement('bad').reason)\n", List.of("no_blueprint no_blueprint"), () -> {
			helper.assertBlockPresent(Blocks.AIR, 3, 1, 3);
			helper.assertBlockPresent(Blocks.AIR, 4, 1, 3);
		});
	}

	/** Turned a quarter clockwise, the step runs south and its stairs face south. */
	@GameTest(maxTicks = 60)
	public void buildBuildsTheCobbleStepTurnedAQuarter(GameTestHelper helper) {
		helper.setBlock(3, 2, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 6, 1, 1);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ", turn=1)\nprint(r.ok, r.reason, r.value)\n",
			List.of("True None 3"), () -> assertCobbleStep(helper, 1));
	}

	/** Short of a cobblestone, the bot stops with missing_ingredients; given one, build again finishes the step. */
	@GameTest(maxTicks = 30)
	public void buildWithoutEnoughBlocksIsMissingIngredientsAndResumes(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 1);
		String build = "build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")";
		boolean[] given = new boolean[1];
		helper.onEachTick(() -> {
			if (!given[0] && printed(bot).size() == 1) {
				given[0] = true;
				bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
			}
		});
		expect(helper, bot, "r = " + build + "\nprint(r.ok, r.reason, r.value)\nwhile count('cobblestone') == 0:\n    wait(1)\nr = " + build
			+ "\nprint(r.ok, r.reason, r.value)\n", List.of("False missing_ingredients 1", "True None 2"), () -> assertCobbleStep(helper, 0));
	}

	/**
	 * A bot with the starter house's materials and some dirt builds the whole house, pillaring up with the dirt where it
	 * cannot reach, and takes the dirt down again: none is left standing, and it carries all of it.
	 */
	// Measured: passes at tick 323 (567 before beds were floor, #647).
	@GameTest(maxTicks = 1100)
	public void buildPillarsWithDirtForTheStarterHouseAndTakesItDown(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 7, 1, 7);
		ServerLevel level = helper.getLevel();
		Identifier id = AntCraft.id("starter_house");
		Blueprint blueprint = Blueprints.get(id).orElseThrow();
		Optional<StructureTemplate> template = level.getServer().getStructureTemplateManager().get(blueprint.structure());
		BlueprintSummary.of(id, blueprint, template).materials().forEach((item, count) -> {
			for (int i = 0; i < count; i++) {
				bot.getInventory().addItem(new ItemStack(item));
			}
		});
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		BuildPlan plan = RegionPlan.load(template.orElseThrow(), helper.absolutePos(new BlockPos(1, 1, 1)), Mirror.NONE, Rotation.NONE,
			blueprint.palette()).orElseThrow();
		expect(helper, bot, "r = build('antcraft:starter_house', " + at(helper, 1, 1, 1) + ")\nprint(r.ok, r.reason, count('dirt'))\n",
			List.of("True None 16"), () -> {
				helper.assertTrue(plan.nextStep(level::getBlockState).isEmpty(), "not built: " + plan.nextStep(level::getBlockState));
				for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
					helper.assertFalse(helper.getBlockState(pos).is(Blocks.DIRT), "dirt left at " + pos);
				}
			});
	}

	/**
	 * A block too high to reach from the ground (the one-block test blueprint {@code two_palettes}, cobblestone): the bot
	 * pillars up to it with dirt, places it, and takes the pillar down again, getting its dirt back.
	 */
	@GameTest(maxTicks = 110)
	public void buildPillarsUpWithDirtToAHighBlockAndTakesThePillarDown(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		expect(helper, bot, "r = build('antcraft-gametest:two_palettes', " + at(helper, 3, 7, 3) + ")\nprint(r.ok, r.reason, r.value, count('dirt'))\n",
			List.of("True None 1 16"), () -> {
				helper.assertBlockPresent(Blocks.COBBLESTONE, 3, 7, 3);
				for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 6, 7)) {
					helper.assertFalse(helper.getBlockState(pos).is(Blocks.DIRT), "dirt left at " + pos);
				}
			});
	}

	/**
	 * The cobble step too high to reach, with dirt to pillar but one cobblestone of two: the bot pillars up, places it,
	 * and takes its pillar down before it gives missing_ingredients, getting its dirt back. (Its stairs would stand
	 * above the test area, which a build finds protected.)
	 */
	@GameTest(maxTicks = 110)
	public void buildThatStopsShortTakesItsScaffoldDown(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 7, 3) + ")\nprint(r.ok, r.reason, r.value, count('dirt'))\n",
			List.of("False missing_ingredients 1 16"), () -> {
				for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 6, 7)) {
					helper.assertFalse(helper.getBlockState(pos).is(Blocks.DIRT), "dirt left at " + pos);
				}
			});
	}

	/**
	 * The cobble step with its first cobblestone and its stairs in place, and the bot shut in a glass corridor they end:
	 * breaking the stairs with its axe would be the shortest way to see where the second cobblestone goes, but a build's
	 * walks keep the blocks it has in place, so it pillars up with dirt instead, and the stairs stay.
	 */
	@GameTest(maxTicks = 80)
	public void buildWalksKeepTheBlocksInPlace(GameTestHelper helper) {
		helper.setBlock(3, 1, 3, Blocks.COBBLESTONE);
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		helper.setBlock(3, 2, 3, stairs);
		for (int y = 1; y <= 2; y++) {
			helper.setBlock(0, y, 3, Blocks.GLASS);
			for (int x = 1; x <= 2; x++) {
				helper.setBlock(x, y, 2, Blocks.GLASS);
				helper.setBlock(x, y, 4, Blocks.GLASS);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
		bot.getInventory().addItem(new ItemStack(Items.IRON_AXE));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		helper.onEachTick(() -> {
			if (!helper.getBlockState(new BlockPos(3, 2, 3)).equals(stairs) || !helper.getBlockState(new BlockPos(3, 1, 3)).is(Blocks.COBBLESTONE)) {
				helper.fail("a block in place was broken");
			}
		});
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value, count('dirt'))\n",
			List.of("True None 1 16"), () -> {
				assertCobbleStep(helper, 0);
				for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 6, 7)) {
					helper.assertFalse(helper.getBlockState(pos).is(Blocks.DIRT), "dirt left at " + pos);
				}
			});
	}

	/**
	 * As {@link #buildWalksKeepTheBlocksInPlace}, without dirt: no walk reaches the second cobblestone's spot without
	 * breaking a block in place, so the bot breaks the stairs as a last resort, places them again and finishes.
	 */
	@GameTest(maxTicks = 50)
	public void buildShutInByItsBlueprintWithoutDirtBreaksThroughAndFinishes(GameTestHelper helper) {
		helper.setBlock(3, 1, 3, Blocks.COBBLESTONE);
		helper.setBlock(3, 2, 3, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
		for (int y = 1; y <= 2; y++) {
			helper.setBlock(0, y, 3, Blocks.GLASS);
			for (int x = 1; x <= 2; x++) {
				helper.setBlock(x, y, 2, Blocks.GLASS);
				helper.setBlock(x, y, 4, Blocks.GLASS);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
		bot.getInventory().addItem(new ItemStack(Items.IRON_AXE));
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason)\n",
			List.of("True None"), () -> assertCobbleStep(helper, 0));
	}

	/**
	 * Breaking through is for one step only (#391). The cobble step with its first cobblestone and stairs in place, the
	 * bot in a glass corridor they end, glass round the second cobblestone's spot and in the air spot above it: no walk
	 * sees that spot without breaking both blocks in place, so the bot breaks them, places the cobblestone and puts the
	 * stairs back. The glass above, to be cleared, it then sees only from beyond a dirt wall of the corridor or by
	 * breaking the stairs again, the shorter way; the walk there goes through the dirt and keeps the stairs.
	 */
	// Measured: passes at tick 68.
	@GameTest(maxTicks = 140)
	public void buildBreaksThroughBlocksInPlaceForOneStepOnly(GameTestHelper helper) {
		BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST);
		helper.setBlock(3, 1, 3, Blocks.COBBLESTONE);
		helper.setBlock(3, 2, 3, stairs);
		helper.setBlock(4, 2, 3, Blocks.GLASS);
		for (BlockPos pos : List.of(new BlockPos(4, 1, 2), new BlockPos(4, 1, 4), new BlockPos(5, 1, 3), new BlockPos(0, 1, 3),
			new BlockPos(1, 1, 2), new BlockPos(2, 1, 2), new BlockPos(3, 1, 2), new BlockPos(1, 1, 4), new BlockPos(3, 1, 4), new BlockPos(1, 1, 5),
			new BlockPos(5, 1, 5), new BlockPos(2, 1, 6), new BlockPos(3, 1, 6), new BlockPos(4, 1, 6))) {
			helper.setBlock(pos, Blocks.GLASS);
			if (pos.getX() != 4 || pos.getZ() == 6) {
				helper.setBlock(pos.above(), Blocks.GLASS);
			}
		}
		helper.setBlock(2, 1, 4, Blocks.DIRT);
		helper.setBlock(2, 2, 4, Blocks.DIRT);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
		bot.getInventory().addItem(new ItemStack(Items.IRON_PICKAXE));
		bot.getInventory().addItem(new ItemStack(Items.IRON_AXE));
		boolean[] backInPlace = {false};
		helper.onEachTick(() -> {
			boolean inPlace = helper.getBlockState(new BlockPos(3, 2, 3)).is(Blocks.OAK_STAIRS);
			if (backInPlace[0] && !inPlace) {
				helper.fail("the stairs were broken again");
			}
			backInPlace[0] |= inPlace && helper.getBlockState(new BlockPos(4, 1, 3)).is(Blocks.COBBLESTONE);
		});
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason)\n",
			List.of("True None"), () -> assertCobbleStep(helper, 0));
	}

	/**
	 * A step too high to reach and nothing to pillar with but what the blueprint needs: blocked, saying it has no dirt to
	 * reach the step with (#485), and nothing placed up there.
	 */
	@GameTest(maxTicks = 40)
	public void buildOutOfReachWithoutScaffoldIsBlocked(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 7, 3) + ")\nprint(r.ok, r.reason, r.value, count('cobblestone'))\nprint(r.detail)\n",
			List.of("False blocked 0 2", "no dirt to pillar up with, to reach Pos" + at(helper, 3, 7, 3)), () -> helper.assertBlockPresent(Blocks.AIR, 3, 7, 3));
	}

	/** Water where the cobble step has air, to be cleared: blocked, saying which fluid is in the way where (#485). */
	// Measured: passes at tick 11.
	@GameTest(maxTicks = 24)
	public void buildWithWaterOnTheSiteIsBlocked(GameTestHelper helper) {
		helper.setBlock(4, 2, 3, Blocks.WATER);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value)\nprint(r.detail)\n",
			List.of("False blocked 3", "minecraft:water is in the way at Pos" + at(helper, 4, 2, 3)));
	}

	/**
	 * A cobblestone too high to reach on top of a shaft of air ({@code air_shaft}: 3 by 7 by 3 of air, the cobblestone at
	 * the top in the middle), the bot shut in the shaft by barrier: it carries dirt, but dirt goes on no spot the blueprint
	 * puts air in, so it is blocked saying so (#486), and nothing is placed.
	 */
	// Measured: passes at tick 4.
	@GameTest(maxTicks = 12)
	public void buildCarryingDirtWithNoRoomForItIsBlocked(GameTestHelper helper) {
		for (int y = 1; y <= 2; y++) {
			for (int i = 1; i <= 5; i++) {
				helper.setBlock(i, y, 1, Blocks.BARRIER);
				helper.setBlock(i, y, 5, Blocks.BARRIER);
				helper.setBlock(1, y, i, Blocks.BARRIER);
				helper.setBlock(5, y, i, Blocks.BARRIER);
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		expect(helper, bot, "r = build('antcraft-gametest:air_shaft', " + at(helper, 2, 1, 2) + ")\nprint(r.ok, r.reason, r.value, count('dirt'))\nprint(r.detail)\n",
			List.of("False blocked 0 16", "no walk reaches Pos" + at(helper, 3, 7, 3)
				+ ", and dirt goes on no spot the blueprint puts a block or air in (structure_void leaves room for it)"),
			() -> helper.assertBlockPresent(Blocks.AIR, 3, 7, 3));
	}

	/**
	 * The bot rests on a lone stone block 4 over the floor, where {@code kept_void} has air, over its edge with its feet in
	 * the block beside, which has a ledge 2 below it, so its feet count as there (see PathFollower.feet), not over the
	 * stone. It may not break the only block it rests on with a fall under it that hurts (BotBody.inReach, #668), and the
	 * walk no longer counts that spot as in reach either: it drops to the ledge and clears the stone from there (#753).
	 * Before, the stone in sight made both walks arrive at once and the build was blocked, "got nowhere twice" (#486). It
	 * waits to land first, as just spawned it plans from the air.
	 */
	// Measured: passes at tick 27 (red: False blocked 0, the walk got nowhere twice, at tick 10).
	@GameTest(maxTicks = 60)
	public void buildOnTheOnlyBlockItRestsOnDropsOffAndClearsIt(GameTestHelper helper) {
		helper.setBlock(4, 5, 3, Blocks.STONE);
		helper.setBlock(3, 3, 3, Blocks.STONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, new Vec3(3.8, 6, 3.5));
		bot.getInventory().addItem(new ItemStack(Items.IRON_PICKAXE));
		expect(helper, bot, "wait(5)\nr = build('antcraft-gametest:kept_void', " + at(helper, 3, 5, 3) + ")\nprint(r.ok, r.reason, r.value)\n",
			List.of("True None 0"), () -> helper.assertBlockPresent(Blocks.AIR, 4, 5, 3));
	}

	/**
	 * The bot shut in a box of barrier, roof and all, with dirt, the cobble step outside it: no walk reaches the step, and
	 * the detail says only that, as no spot of the blueprint is beside the bot to keep its dirt off (#753). Before, any
	 * walk that failed with dirt carried blamed the blueprint's spots.
	 */
	// Measured: passes at tick 4 (red: the detail blamed the blueprint, tick 5).
	@GameTest(maxTicks = 10)
	public void buildWalledInByBarrierDoesNotBlameTheBlueprint(GameTestHelper helper) {
		for (int x = 0; x <= 2; x++) {
			for (int y = 0; y <= 3; y++) {
				for (int z = 4; z <= 6; z++) {
					helper.setBlock(x, y, z, x == 1 && z == 5 && y < 3 ? Blocks.AIR : Blocks.BARRIER);
				}
			}
		}
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 5);
		carryCobbleStep(bot, 2);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 4, 1, 2) + ")\nprint(r.ok, r.reason, r.value)\nprint(r.detail)\n",
			List.of("False blocked 0", "no walk reaches Pos" + at(helper, 4, 1, 2)));
	}

	/**
	 * {@code portal_step}: a cobblestone with a nether portal block on it, which no item places. The bot places the
	 * cobblestone, then is blocked saying no step left can be placed and where the next is (#486).
	 */
	// Measured: passes at tick 3.
	@GameTest(maxTicks = 10)
	public void buildWithOnlyAnUnplaceableStepLeftIsBlocked(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE));
		expect(helper, bot, "r = build('antcraft-gametest:portal_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value)\nprint(r.detail)\n",
			List.of("False blocked 1", "no step left can be placed, the next is at Pos" + at(helper, 3, 2, 3)), () -> {
				helper.assertBlockPresent(Blocks.COBBLESTONE, 3, 1, 3);
				helper.assertBlockPresent(Blocks.AIR, 3, 2, 3);
			});
	}

	/** A second bot idling where the step's second cobblestone goes is asked to step aside, and the build finishes. */
	@GameTest(maxTicks = 40)
	public void buildMovesABotIdlingOnTheSite(GameTestHelper helper) {
		helper.spawn(ModEntities.ANT_BOT, 4, 1, 3);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value, count('cobblestone'))\n",
			List.of("True None 3 0"), () -> assertCobbleStep(helper, 0));
	}

	/**
	 * An idle bot whose home is in the farm's last row, standing over the edge into the row before, is asked to make way
	 * when the builder gets to that row, and keeps off the site until the build is done, though the planks in the last
	 * row take the builder a while to clear: it does not go back home before its block goes there, and is asked once.
	 */
	@GameTest(maxTicks = 380)
	public void buildMovesAnIdleBotHomedOnTheSiteOnce(GameTestHelper helper) {
		helper.setBlock(1, 1, 5, Blocks.OAK_PLANKS);
		AntBot idle = helper.spawn(ModEntities.ANT_BOT, new Vec3(2.5, 1, 5.05));
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 7, 1, 7);
		Identifier id = AntCraft.id("farm");
		Blueprint blueprint = Blueprints.get(id).orElseThrow();
		BlueprintSummary.of(id, blueprint, helper.getLevel().getServer().getStructureTemplateManager().get(blueprint.structure())).materials()
			.forEach((item, count) -> bot.getInventory().addItem(new ItemStack(item, count)));
		expect(helper, bot, "r = build('antcraft:farm', " + at(helper, 1, 1, 1) + ")\nprint(r.ok, r.reason)\n",
			List.of("True None"), () -> helper.assertValueEqual(idle.getWaysMade(), 1, "times the idle bot made way"));
	}

	/** The building bot standing where the step's second cobblestone goes, beside the first moves off it and finishes. */
	@GameTest(maxTicks = 30)
	public void buildMovesTheBuilderOffTheSite(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 3);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value, count('cobblestone'))\n",
			List.of("True None 3 0"), () -> assertCobbleStep(helper, 0));
	}

	/** An armor stand where a block goes does not move: blocked after a short wait, naming it (#485), and it still stands there. */
	@GameTest(maxTicks = 100)
	public void buildWithAnArmorStandOnTheSiteIsBlocked(GameTestHelper helper) {
		helper.spawn(EntityTypes.ARMOR_STAND, 3, 1, 3);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason)\nprint(r.detail)\n",
			List.of("False blocked", "minecraft:armor_stand stands at Pos" + at(helper, 3, 1, 3) + ", where a block goes"), () -> {
				helper.assertBlockPresent(Blocks.AIR, 3, 1, 3);
				helper.assertEntityPresent(EntityTypes.ARMOR_STAND, 3, 1, 3);
			});
	}

	/** An id no blueprint has gives no_blueprint, and blueprint() None for it. */
	@GameTest(maxTicks = 20)
	public void buildAnUnknownBlueprintIsNoBlueprint(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "r = build('antcraft:castle', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, blueprint('antcraft:castle'))\n"
			+ "print(blueprint('antcraft:cobble_step')['materials'])\n",
			List.of("False no_blueprint None", "{'minecraft:cobblestone': 2, 'minecraft:oak_stairs': 1}"));
	}

	/** Given where it goes, blueprint() gives what is still missing there: a cobble step with one cobblestone in place lacks the other and the stairs (#554). */
	@GameTest(maxTicks = 20)
	public void blueprintAtGivesWhatIsMissingThere(GameTestHelper helper) {
		helper.setBlock(3, 1, 3, Blocks.COBBLESTONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "print(blueprint('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")['missing'])\n",
			List.of("{'minecraft:cobblestone': 1, 'minecraft:oak_stairs': 1}"));
	}

	/**
	 * missing is read in the level of at's dimension, not the bot's (#566): the cobblestone in place in the Overworld
	 * does not count for a copy at the same coordinates in the Nether, where nothing is built.
	 */
	@GameTest(maxTicks = 20)
	public void blueprintAtInAnotherDimensionReadsThatDimension(GameTestHelper helper) {
		helper.setBlock(3, 1, 3, Blocks.COBBLESTONE);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		expect(helper, bot, "print(blueprint('antcraft:cobble_step', Pos" + at(helper, 3, 1, 3).replace(")", ", 'minecraft:the_nether')") + ")['missing'])\n",
			List.of("{'minecraft:cobblestone': 2, 'minecraft:oak_stairs': 1}"));
	}
}
