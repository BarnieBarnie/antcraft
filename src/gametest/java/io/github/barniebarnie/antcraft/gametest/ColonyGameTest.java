package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Job;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.registry.ModItems;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public class ColonyGameTest implements CustomTestMethodInvoker {
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

	private static Colony placeCore(GameTestHelper helper) {
		helper.setBlock(CORE, ModBlocks.COLONY_CORE);
		Colony colony = colonyAt(helper).orElseThrow(() -> new AssertionError("no colony founded"));
		// Keep the colony to this test area so it does not post jobs for other tests' trees.
		colony.setRadius(6);
		return colony;
	}

	private static Optional<Colony> colonyAt(GameTestHelper helper) {
		return ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(CORE));
	}

	private static void tree(GameTestHelper helper, int x, int z) {
		BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.DISTANCE, 1);
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				helper.setBlock(x + dx, 4, z + dz, leaves);
			}
		}
		helper.setBlock(x, 5, z, leaves);
		for (int y = 1; y <= 4; y++) {
			helper.setBlock(x, y, z, Blocks.OAK_LOG);
		}
	}

	private static AntBot member(GameTestHelper helper, Colony colony, int x, int z) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, x, 1, z);
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.setRole(BotRole.LUMBERJACK);
		return bot;
	}

	@GameTest
	public void placingCoreFoundsColony(GameTestHelper helper) {
		placeCore(helper);
		helper.succeed();
	}

	@GameTest
	public void breakingCoreDissolvesColony(GameTestHelper helper) {
		placeCore(helper);
		helper.setBlock(CORE, Blocks.AIR);

		helper.assertTrue(colonyAt(helper).isEmpty(), "colony still exists");
		helper.succeed();
	}

	@GameTest
	public void botActivatedNearCoreJoinsColony(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		Player player = helper.makeMockPlayer(GameType.SURVIVAL);
		player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BOT_CORE));
		BlockPos floor = helper.absolutePos(new BlockPos(4, 0, 4));
		helper.useBlock(new BlockPos(4, 0, 4), player, new BlockHitResult(Vec3.atCenterOf(floor), Direction.UP, floor, false));

		List<AntBot> bots = helper.getLevel().getEntities(ModEntities.ANT_BOT, bot -> bot.blockPosition().closerThan(floor, 3)).stream().map(AntBot.class::cast).toList();
		helper.assertTrue(bots.size() == 1, "expected one bot, found " + bots.size());
		AntBot bot = bots.getFirst();
		helper.assertValueEqual(bot.getColonyId(), colony.id(), "colony id");
		helper.assertValueEqual(bot.getHome(), helper.absolutePos(CORE), "home");
		helper.assertTrue(colony.members().contains(bot.getUUID()), "bot not in member list");
		helper.succeed();
	}

	@GameTest(maxTicks = 1600)
	public void lumberjacksSplitTreesAndStoreLogsInCore(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		tree(helper, 2, 5);
		tree(helper, 5, 2);
		AntBot alice = member(helper, colony, 1, 3);
		AntBot bob = member(helper, colony, 3, 1);

		helper.runAfterDelay(40, () -> {
			Optional<UUID> aliceJob = colony.board().jobOf(alice.getUUID()).map(Job::id);
			Optional<UUID> bobJob = colony.board().jobOf(bob.getUUID()).map(Job::id);
			helper.assertTrue(aliceJob.isPresent() && bobJob.isPresent(), "both bots should have a job");
			helper.assertTrue(!aliceJob.equals(bobJob), "bots claimed the same tree");
		});
		helper.succeedWhen(() -> {
			Container core = (Container) helper.getBlockEntity(CORE, net.minecraft.world.level.block.entity.BlockEntity.class);
			helper.assertTrue(core.countItem(Items.OAK_LOG) == 8, "core has " + core.countItem(Items.OAK_LOG) + " logs");
		});
	}

	@GameTest(maxTicks = 200)
	public void jobIsFreedWhenItsBotDies(GameTestHelper helper) {
		Colony colony = placeCore(helper);
		tree(helper, 5, 5);
		AntBot bot = member(helper, colony, 2, 2);

		helper.runAfterDelay(20, () -> {
			helper.assertTrue(colony.board().jobOf(bot.getUUID()).isPresent(), "bot has no job");
			bot.kill(helper.getLevel());
		});
		helper.runAfterDelay(25, () -> {
			helper.assertTrue(colony.board().jobs().stream().allMatch(Job::isOpen), "job still claimed");
			helper.assertTrue(!colony.members().contains(bot.getUUID()), "dead bot still a member");
			helper.succeed();
		});
	}
}
