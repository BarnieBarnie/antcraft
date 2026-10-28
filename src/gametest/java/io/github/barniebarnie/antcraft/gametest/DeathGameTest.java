package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRespawns;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Bots dying and respawning at home (docs/scripting.md, Idle, home and death). */
public class DeathGameTest implements CustomTestMethodInvoker {
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

	private static final String SCRIPT = """
		memory['runs'] = memory.get('runs', 0) + 1
		print('run', memory['runs'], 'deaths', deaths())
		while True:
		    wait(5)
		""";

	/** The bot that has {@code id} in the test's level now, or null. */
	private static AntBot bot(GameTestHelper helper, UUID id) {
		Entity entity = helper.getLevel().getEntity(id);
		return entity instanceof AntBot bot && bot.isAlive() ? bot : null;
	}

	/**
	 * A bot killed while it runs a script drops what it carries where it died and comes back at its home after
	 * {@code botRespawnTicks}, with full health, its memory and its owner, and runs its script again from the top.
	 */
	@GameTest(maxTicks = 440)
	public void aKilledBotDropsItsItemsAndRespawnsAtHomeRunningItsScriptAgain(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		BlockPos home = helper.absolutePos(new BlockPos(6, 1, 6));
		bot.setHome(home);
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
		BotScripts.run(bot, SCRIPT);
		long[] died = {-1};
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("run 1 deaths 0"), "log " + BotScripts.log(bot, Output.Level.DEBUG));
			bot.kill(helper.getLevel());
			died[0] = helper.getLevel().getGameTime();
		});
		helper.runAfterDelay(10, () -> helper.assertItemEntityPresent(Items.COBBLESTONE, new BlockPos(1, 1, 1), 2.0));
		helper.succeedWhen(() -> {
			AntBot respawned = bot(helper, id);
			helper.assertTrue(respawned != null && respawned != bot, "not respawned");
			AntBot again = Objects.requireNonNull(respawned);
			long waited = helper.getLevel().getGameTime() - died[0];
			helper.assertTrue(waited >= AntCraftConfig.botRespawnTicks(), "respawned " + waited + " ticks after it died");
			helper.assertTrue(again.blockPosition().distManhattan(home) <= 1, "respawned at " + again.blockPosition() + ", home " + home);
			helper.assertTrue(again.getHealth() == again.getMaxHealth(), "health " + again.getHealth());
			helper.assertTrue(again.getInventory().isEmpty() && again.getMainHandItem().isEmpty(), "still carries " + again.getInventory().getItems());
			helper.assertTrue(again.getDeaths() == 1, "deaths " + again.getDeaths());
			helper.assertTrue(owner.getUUID().equals(again.getOwner()), "owner " + again.getOwner());
			List<String> printed = BotScripts.log(again, Output.Level.PRINT);
			helper.assertTrue(printed.contains("run 2 deaths 1"), "printed " + printed);
			helper.assertTrue(owner.messages().stream().anyMatch(message -> message.text().contains("died")), "owner told " + owner.messages());
			helper.assertItemEntityPresent(Items.COBBLESTONE, new BlockPos(1, 1, 1), 2.0);
		});
	}

	/** The wait for a respawn is saved with the world: a bot waiting across a save and load still respawns, keeping its memory. */
	@GameTest(maxTicks = 440)
	public void aBotWaitingToRespawnAcrossASaveAndLoadStillRespawns(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		BlockPos home = helper.absolutePos(new BlockPos(6, 1, 6));
		bot.setHome(home);
		BotScripts.run(bot, "memory['kept'] = 'yes'\n");
		helper.runAfterDelay(5, () -> bot.kill(helper.getLevel()));
		helper.runAfterDelay(30, () -> {
			BotRespawns respawns = BotRespawns.get(helper.getLevel());
			BotRespawns.Pending pending = respawns.pending(id);
			helper.assertTrue(pending != null, "no respawn waiting");
			RegistryOps<Tag> ops = helper.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE);
			Tag saved = BotRespawns.Pending.CODEC.encodeStart(ops, Objects.requireNonNull(pending)).getOrThrow();
			respawns.add(BotRespawns.Pending.CODEC.parse(ops, saved).getOrThrow());
		});
		helper.succeedWhen(() -> {
			AntBot respawned = bot(helper, id);
			helper.assertTrue(respawned != null && respawned != bot, "not respawned");
			AntBot again = Objects.requireNonNull(respawned);
			helper.assertTrue(again.blockPosition().distManhattan(home) <= 1, "respawned at " + again.blockPosition() + ", home " + home);
			helper.assertTrue(again.getDeaths() == 1, "deaths " + again.getDeaths());
			helper.assertTrue("yes".equals(again.getMemory().get("kept")), "memory " + again.getMemory());
		});
	}
}
