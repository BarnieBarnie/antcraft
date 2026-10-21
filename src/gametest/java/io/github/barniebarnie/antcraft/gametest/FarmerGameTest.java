package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.Field;
import io.github.barniebarnie.antcraft.colony.StockTarget;
import io.github.barniebarnie.antcraft.work.StorageArea;
import java.lang.reflect.Method;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class FarmerGameTest implements CustomTestMethodInvoker {
	private static final BlockPos CORE = new BlockPos(1, 1, 1);

	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		AntCraftConfig.setValues(new AntCraftConfig.Values(true, 64));
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				helper.setBlock(x, 0, z, Blocks.DIRT);
			}
		}
		method.invoke(this, helper);
	}

	private static Colony colony(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE)).orElseThrow();
		colony.setRadius(6);
		// No default stock targets, so only the test's own orders give the colony work.
		colony.setStockTargets(List.of());
		return colony;
	}

	private static Container core(GameTestHelper helper) {
		return (Container) helper.getBlockEntity(CORE, BlockEntity.class);
	}

	/** A row of wheat on farmland at x = 3..5, z = 5. */
	private static void wheatRow(GameTestHelper helper, int age) {
		for (int x = 3; x <= 5; x++) {
			helper.setBlock(x, 0, 5, Blocks.FARMLAND);
			helper.setBlock(x, 1, 5, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, age));
		}
	}

	private static AntBot farmer(GameTestHelper helper, Colony colony) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 2, 1, 3);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	@GameTest(maxTicks = 800)
	public void harvestsRipeWheatReplantsAndStoresIt(GameTestHelper helper) {
		Colony colony = colony(helper);
		wheatRow(helper, 7);
		farmer(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.WHEAT) == 3, "core has " + core(helper).countItem(Items.WHEAT) + " wheat");
			for (int x = 3; x <= 5; x++) {
				BlockState crop = helper.getBlockState(new BlockPos(x, 1, 5));
				helper.assertTrue(crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) < 7, "not replanted at x=" + x);
			}
		});
	}

	@GameTest(maxTicks = 200)
	public void leavesUnripeCropsAlone(GameTestHelper helper) {
		Colony colony = colony(helper);
		wheatRow(helper, 3);
		farmer(helper, colony);

		helper.runAfterDelay(150, () -> {
			for (int x = 3; x <= 5; x++) {
				helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(x, 1, 5));
			}
			helper.assertTrue(core(helper).countItem(Items.WHEAT) == 0, "harvested unripe wheat");
			helper.succeed();
		});
	}

	private static int carried(AntBot bot, Item item) {
		return bot.getInventory().countItem(item) + (bot.getMainHandItem().is(item) ? bot.getMainHandItem().getCount() : 0);
	}

	@GameTest(maxTicks = 400)
	public void swapsToBetterHoeInCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.STONE_HOE));
		AntBot bot = farmer(helper, colony);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));

		helper.succeedWhen(() -> {
			helper.assertTrue(carried(bot, Items.STONE_HOE) == 1, "farmer has no stone hoe");
			helper.assertTrue(carried(bot, Items.WOODEN_HOE) == 0, "farmer kept its wooden hoe");
			helper.assertTrue(core(helper).countItem(Items.WOODEN_HOE) == 1, "wooden hoe not back in the core");
		});
	}

	@GameTest(maxTicks = 400)
	public void ordersBetterHoeTheColonyCanMake(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.COBBLESTONE, 2));
		core(helper).setItem(1, new ItemStack(Items.STICK, 2));
		AntBot bot = farmer(helper, colony);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));

		helper.succeedWhen(() -> helper.assertTrue(colony.orders().getOrDefault(Items.STONE_HOE, 0) == 1, "orders: " + colony.orders()));
	}

	@GameTest(maxTicks = 800)
	public void keepsItsHoeWhenStoringProduce(GameTestHelper helper) {
		Colony colony = colony(helper);
		wheatRow(helper, 7);
		AntBot bot = farmer(helper, colony);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.WHEAT) == 3, "core has " + core(helper).countItem(Items.WHEAT) + " wheat");
			helper.assertTrue(carried(bot, Items.WOODEN_HOE) == 1, "farmer stored its hoe");
		});
	}

	/** The colony wants wheat: it lays out a field only while it wants what one yields. */
	private static void wantWheat(Colony colony) {
		colony.setStockTarget(StockTarget.of(Items.WHEAT, 64));
	}

	private static boolean planted(GameTestHelper helper, BlockPos crop) {
		return helper.getLevel().getBlockState(crop).is(Blocks.WHEAT) && helper.getLevel().getBlockState(crop.below()).is(Blocks.FARMLAND);
	}

	@GameTest(maxTicks = 600)
	public void collectsSeedsFromGrassTillsAndPlants(GameTestHelper helper) {
		Colony colony = colony(helper);
		wantWheat(colony);
		for (int x = 0; x < 8; x++) {
			for (int z = 0; z < 8; z++) {
				if (helper.getBlockState(new BlockPos(x, 1, z)).isAir()) {
					helper.setBlock(x, 1, z, Blocks.SHORT_GRASS);
				}
			}
		}
		AntBot bot = farmer(helper, colony);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.field().isPresent(), "no field");
			Field field = colony.field().get();
			helper.assertTrue(field.toPlant(crop -> planted(helper, crop)).size() < 24, "nothing planted");
		});
	}

	@GameTest(maxTicks = 800)
	public void plantsAnEmptyFieldNearTheCore(GameTestHelper helper) {
		Colony colony = colony(helper);
		wantWheat(colony);
		AntBot bot = farmer(helper, colony);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		bot.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 32));

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.field().isPresent(), "no field");
			Field field = colony.field().get();
			helper.assertTrue(field.center().distManhattan(colony.core()) <= 8, "field far from core: " + field.center());
			helper.assertTrue(field.toPlant(crop -> planted(helper, crop)).isEmpty(), "unplanted: " + field.toPlant(crop -> planted(helper, crop)));
		});
	}

	@GameTest(maxTicks = 800)
	public void fillsABucketAtNearbyWaterForTheField(GameTestHelper helper) {
		BlockPos source = new BlockPos(7, 0, 7);
		helper.setBlock(source, Blocks.WATER);
		Colony colony = colony(helper);
		wantWheat(colony);
		AntBot bot = farmer(helper, colony);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		bot.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 32));
		bot.getInventory().addItem(new ItemStack(Items.BUCKET));

		helper.succeedWhen(() -> {
			helper.assertTrue(colony.field().isPresent(), "no field");
			helper.assertTrue(helper.getLevel().getBlockState(colony.field().get().center()).is(Blocks.WATER), "no water in the middle");
			helper.assertBlockNotPresent(Blocks.WATER, source);
			helper.assertTrue(carried(bot, Items.BUCKET) == 1, "farmer did not keep the empty bucket");
		});
	}

	@GameTest(maxTicks = 400)
	public void ordersABucketAndTheFieldWaterWaits(GameTestHelper helper) {
		Colony colony = colony(helper);
		wantWheat(colony);
		AntBot bot = farmer(helper, colony);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		bot.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 32));

		helper.runAfterDelay(350, () -> {
			helper.assertTrue(colony.orders().getOrDefault(Items.BUCKET, 0) == 1, "orders: " + colony.orders());
			Field field = colony.field().orElseThrow();
			helper.assertFalse(helper.getLevel().getBlockState(field.center()).is(Blocks.WATER), "water without a bucket");
			helper.assertTrue(field.toPlant(crop -> planted(helper, crop)).size() < 24, "nothing planted while the water waits");
			helper.succeed();
		});
	}

	/** A sheep that stays put at x = 5, z = 5, so the test does not depend on where it wanders. */
	private static Sheep sheep(GameTestHelper helper) {
		Sheep sheep = helper.spawn(EntityTypes.SHEEP, 5, 1, 5);
		sheep.setNoAi(true);
		return sheep;
	}

	private static int wool(Container container) {
		int wool = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			if (container.getItem(i).is(ItemTags.WOOL)) {
				wool += container.getItem(i).getCount();
			}
		}
		return wool;
	}

	@GameTest(maxTicks = 400)
	public void shearsASheepAndStoresTheWool(GameTestHelper helper) {
		Colony colony = colony(helper);
		core(helper).setItem(0, new ItemStack(Items.SHEARS));
		Sheep sheep = sheep(helper);
		AntBot bot = farmer(helper, colony);

		helper.succeedWhen(() -> {
			helper.assertTrue(sheep.isSheared(), "sheep not sheared");
			helper.assertTrue(wool(core(helper)) > 0, "no wool in the core");
			helper.assertTrue(carried(bot, Items.SHEARS) == 1, "farmer did not keep the shears");
		});
	}

	/** Wool picked up after the fleece pickup ended, by walking over it, still goes to the core while the field waits for seeds. */
	@GameTest(maxTicks = 200)
	public void storesWoolItCarriesWhileWaitingForSeeds(GameTestHelper helper) {
		Colony colony = colony(helper);
		wantWheat(colony);
		AntBot bot = farmer(helper, colony);
		// Once the field is planned and its planting waits for seeds the farmer has none of.
		helper.runAfterDelay(60, () -> {
			helper.assertTrue(colony.field().isPresent(), "no field to wait for seeds for");
			bot.getInventory().addItem(new ItemStack(Items.WOOL.white(), 2));
			helper.succeedWhen(() -> helper.assertTrue(wool(core(helper)) == 2, "core has " + wool(core(helper)) + " wool"));
		});
	}

	@GameTest(maxTicks = 300)
	public void ordersShearsWhenThereIsASheepToShear(GameTestHelper helper) {
		Colony colony = colony(helper);
		sheep(helper);
		farmer(helper, colony);

		helper.succeedWhen(() -> helper.assertTrue(colony.orders().getOrDefault(Items.SHEARS, 0) == 1, "orders: " + colony.orders()));
	}

	@GameTest(maxTicks = 300)
	public void ordersNoShearsWithoutSheep(GameTestHelper helper) {
		Colony colony = colony(helper);
		farmer(helper, colony);

		helper.runAfterDelay(250, () -> {
			helper.assertFalse(colony.orders().containsKey(Items.SHEARS), "orders: " + colony.orders());
			helper.succeed();
		});
	}

	private static final BlockPos STORAGE = new BlockPos(2, 1, 4);

	/** Fills every slot of the core with cobblestone, so nothing else fits. */
	private static void fillCore(GameTestHelper helper) {
		Container core = core(helper);
		for (int i = 0; i < core.getContainerSize(); i++) {
			core.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
		}
	}

	/** A farmer carrying wheat, and seeds enough that it does not go looking for more. */
	private static AntBot farmerWithWheat(GameTestHelper helper, Colony colony, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.getInventory().addItem(new ItemStack(Items.WHEAT, 5));
		bot.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 4));
		return bot;
	}

	@GameTest(maxTicks = 300)
	public void storesWheatInAStorageChestWhenTheCoreIsFull(GameTestHelper helper) {
		Colony colony = colony(helper);
		fillCore(helper);
		colony.setStorage(new StorageArea(helper.absolutePos(STORAGE)));
		helper.setBlock(STORAGE, Blocks.CHEST);
		Container chest = (Container) helper.getBlockEntity(STORAGE, BlockEntity.class);
		AntBot bot = farmerWithWheat(helper, colony, 2, 3);

		helper.succeedWhen(() -> {
			helper.assertTrue(chest.countItem(Items.WHEAT) == 5, "storage holds " + chest.countItem(Items.WHEAT) + " wheat");
			helper.assertTrue(carried(bot, Items.WHEAT) == 0, "farmer still carries wheat");
			helper.assertTrue(carried(bot, Items.WHEAT_SEEDS) == 4, "farmer stored its seeds");
		});
	}

	/** With no room for its wheat anywhere the farmer does not go and stand at the core. */
	@GameTest(maxTicks = 200)
	public void staysAwayFromAFullCoreWithNoRoomForItsWheat(GameTestHelper helper) {
		Colony colony = colony(helper);
		fillCore(helper);
		AntBot bot = farmerWithWheat(helper, colony, 6, 6);
		// A full core gets a storage area, and a bot with room would take surplus out for a chest (see
		// GuardHaulerGameTest); this one keeps a pack full of scaffold blocks, so it has no room for that.
		for (int i = 0; i < bot.getInventory().getContainerSize() - 3; i++) {
			bot.getInventory().addItem(new ItemStack(Items.DIRT, 64));
		}

		helper.runAfterDelay(150, () -> {
			// The core is 7 blocks off and reached from 4: a farmer heading there has moved 3.
			double moved = Math.sqrt(helper.absolutePos(new BlockPos(6, 1, 6)).distToCenterSqr(bot.position()));
			helper.assertTrue(moved < 1.5, "farmer walked " + moved + " blocks towards the full core");
			helper.assertTrue(carried(bot, Items.WHEAT) == 5, "farmer lost its wheat");
			helper.succeed();
		});
	}

	/** Wool it has no room for does not keep the farmer from shearing. */
	@GameTest(maxTicks = 400)
	public void keepsShearingWithNoRoomForTheWool(GameTestHelper helper) {
		Colony colony = colony(helper);
		fillCore(helper);
		Sheep sheep = sheep(helper);
		AntBot bot = farmer(helper, colony);
		bot.getInventory().addItem(new ItemStack(Items.SHEARS));
		bot.getInventory().addItem(new ItemStack(Items.WOOL.white(), 2));

		helper.succeedWhen(() -> helper.assertTrue(sheep.isSheared(), "sheep not sheared"));
	}

	/** A colony bot with another role, kept by a player so the colony does not make it a farmer. */
	private static AntBot colonyGuard(GameTestHelper helper, Colony colony, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		return bot;
	}

	@GameTest(maxTicks = 800)
	public void colonyBotOfAnotherRoleHarvestsAndReplantsARipeCrop(GameTestHelper helper) {
		Colony colony = colony(helper);
		wheatRow(helper, 7);
		AntBot bot = colonyGuard(helper, colony, 2, 3);

		helper.succeedWhen(() -> {
			for (int x = 3; x <= 5; x++) {
				BlockState crop = helper.getBlockState(new BlockPos(x, 1, 5));
				helper.assertTrue(crop.is(Blocks.WHEAT) && crop.getValue(CropBlock.AGE) < 7, "not replanted at x=" + x);
			}
			// Done with the job, it brings the wheat home rather than keep it from the colony.
			helper.assertTrue(core(helper).countItem(Items.WHEAT) == 3, "core has " + core(helper).countItem(Items.WHEAT) + " wheat");
		});
	}

	/**
	 * Ripe wheat on a ledge too high to reach from the ground: the bot pillars up with dirt to harvest it, and
	 * takes its pillar down once done, though harvesting is no build or tree job.
	 */
	@GameTest(maxTicks = 800)
	public void takesDownThePillarItPlacedToReachACrop(GameTestHelper helper) {
		Colony colony = colony(helper);
		// A 5x5 ledge, four high, with the wheat in the middle: out of reach from the ground around it.
		for (BlockPos pos : BlockPos.betweenClosed(3, 1, 3, 7, 4, 7)) {
			helper.setBlock(pos, Blocks.STONE);
		}
		helper.setBlock(5, 4, 5, Blocks.FARMLAND);
		helper.setBlock(5, 5, 5, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
		AntBot bot = farmer(helper, colony);
		bot.getInventory().addItem(new ItemStack(Items.DIRT, 16));
		boolean[] pillared = {false};
		helper.onEachTick(() -> pillared[0] |= dirtAboveFloor(helper) > 0);

		helper.succeedWhen(() -> {
			helper.assertTrue(core(helper).countItem(Items.WHEAT) == 1, "core has " + core(helper).countItem(Items.WHEAT) + " wheat");
			helper.assertTrue(pillared[0], "never pillared up");
			helper.assertTrue(dirtAboveFloor(helper) == 0, dirtAboveFloor(helper) + " dirt left standing");
			helper.assertTrue(bot.getScaffold().isEmpty(), "scaffold still recorded: " + bot.getScaffold());
		});
	}

	private static int dirtAboveFloor(GameTestHelper helper) {
		int found = 0;
		for (BlockPos pos : BlockPos.betweenClosed(0, 1, 0, 7, 7, 7)) {
			if (helper.getBlockState(pos).is(Blocks.DIRT)) {
				found++;
			}
		}
		return found;
	}

	@GameTest(maxTicks = 1200)
	public void colonyBotOfAnotherRoleTillsPlantsAndWatersTheField(GameTestHelper helper) {
		BlockPos source = new BlockPos(7, 0, 7);
		helper.setBlock(source, Blocks.WATER);
		Colony colony = colony(helper);
		Field field = new Field(helper.absolutePos(new BlockPos(4, 0, 4)));
		colony.setField(field);
		AntBot bot = colonyGuard(helper, colony, 0, 6);
		bot.getInventory().addItem(new ItemStack(Items.WOODEN_HOE));
		bot.getInventory().addItem(new ItemStack(Items.WHEAT_SEEDS, 32));
		bot.getInventory().addItem(new ItemStack(Items.BUCKET));

		helper.succeedWhen(() -> {
			helper.assertTrue(field.toPlant(crop -> planted(helper, crop)).isEmpty(), "unplanted: " + field.toPlant(crop -> planted(helper, crop)));
			helper.assertTrue(helper.getLevel().getBlockState(field.center()).is(Blocks.WATER), "no water in the middle");
			helper.assertBlockNotPresent(Blocks.WATER, source);
		});
	}
}
