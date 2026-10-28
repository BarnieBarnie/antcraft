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
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.BotApi;
import io.github.barniebarnie.antcraft.script.MoveApi;
import io.github.barniebarnie.antcraft.script.Pos;
import io.github.barniebarnie.antcraft.script.Result;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Natives;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FurnaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.material.Fluids;

/**
 * The block actions of the bot API (docs/scripting.md, Actions): {@code mine}, {@code place}, {@code pick_up},
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
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.STONE);
			}
		}
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
		helper.onEachTick(() -> {
			if (run.state() == ScriptRun.State.FAILED) {
				helper.fail("script failed: " + BotScripts.log(bot, Output.Level.DEBUG));
			}
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

	@GameTest(maxTicks = 70)
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
		Map.entry("container", "container(h)"),
		Map.entry("mineshaft", "mineshaft([0], near=h)"),
		Map.entry("mineshaft_at", "mineshaft_at(h, [0])"));

	/**
	 * The bot natives and {@code Pos} methods that take no position, or take one on purpose from any dimension:
	 * {@code set_home} sets the home in the position's dimension, and the {@code Pos} methods work on coordinates.
	 */
	private static final Set<String> TAKE_NO_POSITION = Set.of("pos", "say", "wait", "stop", "home", "home_dimension",
		"dimension", "set_home", "portals", "inventory", "count", "has", "matches", "held", "find_nearest", "scan", "entities",
		"items_near", "deaths", "colony", "time", "is_day", "equip", "drop", "pick_up", "Pos.up", "Pos.down", "Pos.north",
		"Pos.south", "Pos.east", "Pos.west", "Pos.distance", "blueprint", "post_job", "jobs", "take_job", "finish_job",
		"drop_job");

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
				case "block", "container", "mineshaft", "mineshaft_at" -> "None";
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
	@GameTest(maxTicks = 40)
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
		public void startReach(Pos target) {
			if (!this.standStill) {
				this.body.startReach(target);
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

	// place

	@GameTest(maxTicks = 60)
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
		expect(helper, bot, "r = place(" + at(helper, 2, 1, 2) + ", 'dirt')\nprint(r.ok, r.reason, count('dirt'))\n",
			List.of("False blocked 1"), () -> helper.assertBlockPresent(Blocks.GLASS, 2, 1, 2));
	}

	/** A pig stands where the block would go. */
	@GameTest(maxTicks = 20)
	public void placeWhereAnEntityStandsIsBlocked(GameTestHelper helper) {
		helper.spawnWithNoFreeWill(EntityTypes.PIG, 3, 1, 3);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		bot.getInventory().addItem(new ItemStack(Items.DIRT));
		expect(helper, bot, "r = place(" + at(helper, 3, 1, 3) + ", 'dirt')\nprint(r.ok, r.reason, count('dirt'))\n",
			List.of("False blocked 1"), () -> helper.assertBlockPresent(Blocks.AIR, 3, 1, 3));
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
		expect(helper, bot, "r = use(" + at(helper, 2, 0, 2) + ", 'wooden_hoe')\nprint(r.ok, r.reason)\n", List.of("False blocked"),
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
	@GameTest(maxTicks = 100)
	public void dropThrowsItemsOutAndLeavesThem(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 3, 1, 3);
		bot.getInventory().addItem(new ItemStack(Items.NAUTILUS_SHELL, 5));
		expect(helper, bot, "r = drop('nautilus_shell', 2)\nprint(r.ok, r.reason, r.value, count('nautilus_shell'))\n"
				+ "wait(60)\nprint(count('nautilus_shell'), len(items_near('nautilus_shell', 4)))\n",
			List.of("True None 2 3", "3 1"));
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
	@GameTest(maxTicks = 40)
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
	// Measured: passes at tick 567.
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

	/** A step too high to reach and nothing to pillar with but what the blueprint needs: blocked, and nothing placed up there. */
	@GameTest(maxTicks = 40)
	public void buildOutOfReachWithoutScaffoldIsBlocked(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 7, 3) + ")\nprint(r.ok, r.reason, r.value, count('cobblestone'))\n",
			List.of("False blocked 0 2"), () -> helper.assertBlockPresent(Blocks.AIR, 3, 7, 3));
	}

	/** A second bot idling where the step's second cobblestone goes is asked to step aside, and the build finishes. */
	@GameTest(maxTicks = 80)
	public void buildMovesABotIdlingOnTheSite(GameTestHelper helper) {
		helper.spawn(ModEntities.ANT_BOT, 4, 1, 3);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value, count('cobblestone'))\n",
			List.of("True None 3 0"), () -> assertCobbleStep(helper, 0));
	}

	/** The building bot standing where the step's second cobblestone goes, beside the first moves off it and finishes. */
	@GameTest(maxTicks = 80)
	public void buildMovesTheBuilderOffTheSite(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 4, 1, 3);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason, r.value, count('cobblestone'))\n",
			List.of("True None 3 0"), () -> assertCobbleStep(helper, 0));
	}

	/** An armor stand where a block goes does not move: blocked after a short wait, and it still stands there. */
	@GameTest(maxTicks = 100)
	public void buildWithAnArmorStandOnTheSiteIsBlocked(GameTestHelper helper) {
		helper.spawn(EntityTypes.ARMOR_STAND, 3, 1, 3);
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 6);
		carryCobbleStep(bot, 2);
		expect(helper, bot, "r = build('antcraft:cobble_step', " + at(helper, 3, 1, 3) + ")\nprint(r.ok, r.reason)\n",
			List.of("False blocked"), () -> {
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
}
