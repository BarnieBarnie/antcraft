package io.github.barniebarnie.antcraft.gametest;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRespawns;
import io.github.barniebarnie.antcraft.registry.ModBlocks;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.ServerScripts;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/** Bots dying and respawning at home (docs/features/death.md, Idle, home and death). */
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

	private static final String SCRIPT ="""
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
		BotScripts.run(bot, "d = last_death()\nprint('last', d, d is not None and d.dimension)\n" + SCRIPT);
		BlockPos spot = helper.absolutePos(new BlockPos(1, 1, 1));
		long[] died = {-1};
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("run 1 deaths 0"), "log " + BotScripts.log(bot, Output.Level.DEBUG));
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("last None False"), "log " + BotScripts.log(bot, Output.Level.DEBUG));
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
			String last = "last Pos(" + spot.getX() + ", " + spot.getY() + ", " + spot.getZ() + ") minecraft:overworld";
			helper.assertTrue(printed.contains(last), "printed " + printed + ", expected " + last);
			helper.assertTrue(owner.messages().stream().anyMatch(message -> message.text().contains("died")), "owner told " + owner.messages());
			helper.assertItemEntityPresent(Items.COBBLESTONE, new BlockPos(1, 1, 1), 2.0);
		});
	}

	/**
	 * A colony member that dies stays a member while it waits, and respawns one (#353): its script, run again from the top,
	 * sees its colony.
	 */
	@GameTest(maxTicks = 440)
	public void aColonyMemberThatDiesRespawnsAMember(GameTestHelper helper) {
		helper.setBlock(3, 1, 6, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(3, 1, 6))).orElseThrow();
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		bot.setHome(helper.absolutePos(new BlockPos(6, 1, 6)));
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		BotScripts.run(bot, "c = colony()\nprint('deaths', deaths(), 'colony', c is not None and c['radius'])\nwhile True:\n    wait(5)\n");
		helper.runAfterDelay(5, () -> {
			bot.kill(helper.getLevel());
			helper.assertTrue(colony.members().contains(id), "left the colony on death: " + colony.members());
		});
		helper.succeedWhen(() -> {
			AntBot again = bot(helper, id);
			helper.assertTrue(again != null && again != bot, "not respawned");
			List<String> printed = BotScripts.log(Objects.requireNonNull(again), Output.Level.PRINT);
			helper.assertTrue(printed.contains("deaths 1 colony 32"), "printed " + printed);
			helper.assertTrue(colony.id().equals(again.getColonyId()) && colony.members().contains(id), "members " + colony.members());
		});
	}

	/**
	 * A member that dies holding a job keeps it (#378): its script, run again from the top, sees itself as the job's taker.
	 * It takes the job only on its first run, so a job given back on death would show as open.
	 */
	@GameTest(maxTicks = 440)
	public void aBotThatDiesHoldingAJobKeepsIt(GameTestHelper helper) {
		helper.setBlock(3, 1, 6, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(3, 1, 6))).orElseThrow();
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		bot.setHome(helper.absolutePos(new BlockPos(6, 1, 6)));
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		colony.jobs().post("haul", new CompoundTag(), id);
		BotScripts.run(bot, """
			if deaths() == 0:
			    take_job('haul')
			print('deaths', deaths(), 'taker', jobs('haul')[0]['taker'])
			while True:
			    wait(5)
			""");
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("deaths 0 taker " + id), "log " + BotScripts.log(bot, Output.Level.DEBUG));
			bot.kill(helper.getLevel());
		});
		helper.succeedWhen(() -> {
			AntBot again = bot(helper, id);
			helper.assertTrue(again != null && again != bot, "not respawned");
			List<String> printed = BotScripts.log(Objects.requireNonNull(again), Output.Level.PRINT);
			helper.assertTrue(printed.contains("deaths 1 taker " + id), "printed " + printed);
		});
	}

	/**
	 * A dead member whose respawn is dropped (here by the cleanup at a test's end) leaves its colony and gives back the jobs
	 * it took, as one that dies with {@code botRespawn} off does (#379).
	 */
	@GameTest(maxTicks = 20)
	public void aDroppedRespawnLeavesTheColonyAndGivesBackItsJobs(GameTestHelper helper) {
		helper.setBlock(3, 1, 6, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(3, 1, 6))).orElseThrow();
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		bot.setHome(helper.absolutePos(new BlockPos(6, 1, 6)));
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		long job = colony.jobs().post("haul", new CompoundTag(), id);
		helper.assertTrue(colony.jobs().take("haul", id).isPresent(), "job not taken");
		bot.kill(helper.getLevel());
		helper.assertTrue(colony.members().contains(id), "left the colony on death: " + colony.members());
		GameTests.forgetRespawns(helper.getLevel().getServer(), helper.getBounds());
		helper.assertFalse(colony.members().contains(id), "still a member: " + colony.members());
		helper.assertTrue(colony.jobs().jobs("haul").stream().allMatch(j -> j.id() != job || j.taker().isEmpty()),
			"job still taken: " + colony.jobs().jobs("haul"));
		helper.succeed();
	}

	/**
	 * A bot loaded from a save in the middle of its script and killed before it ticks again respawns running its script
	 * from the top (docs/features/scripts.md, Save and resume; Idle, home and death), not resuming the script it was saved with.
	 */
	@GameTest(maxTicks = 440)
	public void aBotKilledBeforeItResumesItsSavedScriptRespawnsRunningItFromTheTop(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		BlockPos home = helper.absolutePos(new BlockPos(6, 1, 6));
		bot.setHome(home);
		String name = "death_resume_" + UUID.randomUUID().toString().substring(0, 8);
		Path script = ServerScripts.worldFolder(helper.getLevel().getServer()).resolve(name + ".py");
		GameTests.onEnd(helper, () -> {
			try {
				Files.deleteIfExists(script);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		try {
			Files.createDirectories(script.getParent());
			Files.writeString(script, SCRIPT);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		BotScripts.command(helper, "antcraft script reload");
		BotScripts.command(helper, "antcraft bot " + bot.getStringUUID() + " run " + name);
		helper.runAfterDelay(5, () -> {
			helper.assertTrue(BotScripts.log(bot, Output.Level.PRINT).contains("run 1 deaths 0"), "log " + BotScripts.log(bot, Output.Level.DEBUG));
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
			bot.saveWithoutId(output);
			bot.discard();
			AntBot loaded = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
			loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
			loaded.kill(helper.getLevel());
		});
		helper.succeedWhen(() -> {
			AntBot again = bot(helper, id);
			helper.assertTrue(again != null && again.getDeaths() == 1, "not respawned");
			List<String> printed = BotScripts.log(Objects.requireNonNull(again), Output.Level.PRINT);
			helper.assertTrue(printed.contains("run 2 deaths 1"), "printed " + printed + ", log " + BotScripts.log(again, Output.Level.DEBUG));
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
			GlobalPos spot = GlobalPos.of(Level.OVERWORLD, helper.absolutePos(new BlockPos(1, 1, 1)));
			helper.assertTrue(spot.equals(again.getLastDeath()), "last death " + again.getLastDeath() + ", expected " + spot);
		});
	}

	/**
	 * A bot whose home is in the Overworld and that dies in the Nether respawns at its home in the Overworld. The bot is
	 * made anew in the Nether from its save, as vanilla does when it takes it through a portal, but not added there: an
	 * entity there counts only once its chunk's entities are loaded, in the background, and game tests run at a new random
	 * spot each run, so the chunk is new. In the last batch of a full run, while the chunks of the batches before are
	 * saved, that took seconds, which the server, idle but for this batch, spent on hundreds of short ticks (#355). Dying
	 * needs only the bot's level.
	 */
	@GameTest(maxTicks = 440)
	public void aBotKilledInAnotherDimensionRespawnsAtHomeInItsHomeDimension(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		BlockPos home = helper.absolutePos(new BlockPos(6, 1, 6));
		bot.setHome(home);
		ServerLevel nether = Objects.requireNonNull(helper.getLevel().getServer().getLevel(Level.NETHER));
		AntBot moved = Objects.requireNonNull(ModEntities.ANT_BOT.create(nether, EntitySpawnReason.DIMENSION_TRAVEL));
		moved.restoreFrom(bot);
		moved.snapTo(Vec3.atBottomCenterOf(new BlockPos(home.getX(), 100, home.getZ())), 0, 0);
		bot.remove(Entity.RemovalReason.CHANGED_DIMENSION);
		moved.kill(nether);
		helper.succeedWhen(() -> {
			AntBot again = bot(helper, id);
			helper.assertTrue(again != null && again.getDeaths() == 1, "not respawned in the Overworld");
			BlockPos at = Objects.requireNonNull(again).blockPosition();
			helper.assertTrue(at.distManhattan(home) <= 1, "respawned at " + at + ", home " + home);
			GlobalPos spot = GlobalPos.of(Level.NETHER, new BlockPos(home.getX(), 100, home.getZ()));
			helper.assertTrue(spot.equals(again.getLastDeath()), "last death " + again.getLastDeath() + ", expected " + spot);
		});
	}

	/**
	 * A bot whose home chunk nothing keeps loaded loads it when its wait is over and respawns there, once the chunk ticks
	 * entities. That takes the chunk and the 24 around it at full status. Fresh, they generate in the background, which took
	 * up to a second alone and several in full builds (#362: nearly all the wait was generation, the entities loaded within
	 * a tick of the chunks). So the home's chunks are generated up front, here, and unload again by the second tick, as
	 * nothing keeps them: the respawn loads them back from disk, within 20 ticks (about 0.2 s) alone and 7 ticks with two
	 * full builds running. The chunk wait keeps a budget in wall-clock time ({@link GameTests#waitAtMost}), 15 s, far more
	 * than three times that; {@code maxTicks} is the respawn time plus that budget in paced ticks and some to spare, never
	 * reached first.
	 */
	@GameTest(maxTicks = 3400)
	public void aBotRespawnsAtAHomeWhoseChunkIsNotLoaded(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		BlockPos home = helper.absolutePos(new BlockPos(1, 1, 1)).offset(2000, 0, 0);
		ServerLevel level = helper.getLevel();
		helper.assertFalse(level.hasChunkAt(home), "home chunk loaded");
		ChunkPos homeChunk = ChunkPos.containing(home);
		for (int cx = homeChunk.x() - 2; cx <= homeChunk.x() + 2; cx++) {
			for (int cz = homeChunk.z() - 2; cz <= homeChunk.z() + 2; cz++) {
				level.getChunk(cx, cz);
			}
		}
		bot.setHome(home);
		GameTests.onEnd(helper, () -> {
			BotRespawns.get(level).forget(level.getServer(), home::equals);
			if (level.getEntity(id) instanceof AntBot again) {
				again.discard();
			}
		});
		GameTests.waitAtMost(helper, () -> {
			BotRespawns.Pending respawn = BotRespawns.get(level).pending(id);
			return respawn != null && respawn.at() <= level.getGameTime();
		}, () -> "due but not respawned");
		helper.runAfterDelay(5, () -> {
			helper.assertFalse(level.hasChunkAt(home), "home chunk still loaded");
			bot.kill(level);
		});
		helper.succeedWhen(() -> {
			Entity entity = level.getEntity(id);
			helper.assertTrue(entity instanceof AntBot again && again.isAlive() && again.getDeaths() == 1, "not respawned");
			BlockPos at = Objects.requireNonNull(entity).blockPosition();
			helper.assertTrue(at.getX() == home.getX() && at.getZ() == home.getZ(), "respawned at " + at + ", home " + home);
		});
	}

	/**
	 * The cleanup at the end of every test ({@link GameTests#start}) forgets a bot waiting to respawn at a home in the test's
	 * area also when the home is in another dimension, where the bot waits (#346), so it does not come back into the next
	 * test there.
	 */
	@GameTest(maxTicks = 20)
	public void testEndCleanupForgetsARespawnWaitingInAnotherLevel(GameTestHelper helper) {
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		bot.setHome(helper.absolutePos(new BlockPos(6, 1, 6)), Level.NETHER);
		ServerLevel nether = Objects.requireNonNull(helper.getLevel().getServer().getLevel(Level.NETHER));
		bot.kill(helper.getLevel());
		helper.assertTrue(BotRespawns.get(nether).pending(id) != null, "no respawn waiting in the Nether");
		GameTests.forgetRespawns(helper.getLevel().getServer(), helper.getBounds());
		helper.assertTrue(BotRespawns.get(nether).pending(id) == null, "respawn still waiting in the Nether");
		helper.succeed();
	}

	/**
	 * {@code /antcraft bot remove} by its owner (#458) stops the bot's script, drops what it carries where it stands, takes it
	 * out of its colony with its jobs given back, and removes it for good: no respawn waits for it.
	 */
	@GameTest(maxTicks = 20)
	public void removeTakesTheBotAwayForGood(GameTestHelper helper) {
		helper.setBlock(3, 1, 6, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(3, 1, 6))).orElseThrow();
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		bot.setHome(helper.absolutePos(new BlockPos(6, 1, 6)));
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		long job = colony.jobs().post("haul", new CompoundTag(), id);
		helper.assertTrue(colony.jobs().take("haul", id).isPresent(), "job not taken");
		bot.getInventory().addItem(new ItemStack(Items.COBBLESTONE, 5));
		bot.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);
		BotScripts.run(bot, SCRIPT);

		List<String> answer = BotScripts.commandAsPlayer(helper, owner, "antcraft bot " + id + " remove");
		helper.assertTrue(answer.equals(List.of("commands.antcraft.bot.remove[1]")), "answer " + answer);
		helper.assertTrue(helper.getLevel().getEntity(id) == null, "bot still there");
		helper.assertTrue(BotRespawns.get(helper.getLevel()).pending(id) == null, "a respawn waits");
		helper.assertFalse(colony.members().contains(id), "still a member: " + colony.members());
		helper.assertTrue(colony.jobs().jobs("haul").stream().allMatch(j -> j.id() != job || j.taker().isEmpty()),
			"job still taken: " + colony.jobs().jobs("haul"));
		helper.assertItemEntityPresent(Items.COBBLESTONE, new BlockPos(1, 1, 1), 2.0);
		helper.assertItemEntityPresent(Items.IRON_PICKAXE, new BlockPos(1, 1, 1), 2.0);
		helper.succeed();
	}

	/** A player cannot remove another player's bot (#458); an operator can. */
	@GameTest(maxTicks = 20)
	public void onlyItsOwnerOrAnOperatorRemovesABot(GameTestHelper helper) {
		AntBot own = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BotScripts.RecordingPlayer player = BotScripts.owner(helper, own);
		AntBot other = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		other.setOwner(UUID.randomUUID());

		List<String> refused = BotScripts.commandAsPlayer(helper, player, "antcraft bot " + other.getStringUUID() + " remove");
		helper.assertTrue(other.isAlive() && !other.isRemoved(), "removed another player's bot");
		helper.assertTrue(refused.size() == 1 && refused.getFirst().endsWith(" is not your bot"), "answer " + refused);
		BotScripts.command(helper, "antcraft bot " + other.getStringUUID() + " remove");
		helper.assertTrue(other.isRemoved(), "the operator did not remove it");
		helper.assertTrue(own.isAlive() && !own.isRemoved(), "removed the player's own bot too");
		helper.succeed();
	}

	/**
	 * A bot removed while its dead body still lies there (#458) no longer waits to respawn, here in its home's dimension,
	 * and leaves its colony.
	 */
	@GameTest(maxTicks = 20)
	public void removingADeadBotDropsItsRespawn(GameTestHelper helper) {
		helper.setBlock(3, 1, 6, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(3, 1, 6))).orElseThrow();
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		bot.setHome(helper.absolutePos(new BlockPos(6, 1, 6)), Level.NETHER);
		ServerLevel nether = Objects.requireNonNull(helper.getLevel().getServer().getLevel(Level.NETHER));
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		bot.kill(helper.getLevel());
		helper.assertTrue(BotRespawns.get(nether).pending(id) != null, "no respawn waits");
		BotScripts.command(helper, "antcraft bot " + id + " remove");
		helper.assertTrue(BotRespawns.get(nether).pending(id) == null, "a respawn still waits");
		helper.assertFalse(colony.members().contains(id), "still a member: " + colony.members());
		helper.succeed();
	}

	/**
	 * A dead bot whose body is gone, waiting to respawn, is removed by its UUID (#464): its owner's {@code bot <uuid> remove}
	 * drops the respawn, here waiting in the Nether, and takes it out of its colony.
	 */
	@GameTest(maxTicks = 20)
	public void removeByUuidReachesABotWaitingToRespawn(GameTestHelper helper) {
		helper.setBlock(3, 1, 6, ModBlocks.COLONY_CORE);
		Colony colony = ColonyManager.get(helper.getLevel()).byCore(helper.absolutePos(new BlockPos(3, 1, 6))).orElseThrow();
		AntBot bot = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		UUID id = bot.getUUID();
		bot.setHome(helper.absolutePos(new BlockPos(6, 1, 6)), Level.NETHER);
		ServerLevel nether = Objects.requireNonNull(helper.getLevel().getServer().getLevel(Level.NETHER));
		ColonyManager.get(helper.getLevel()).join(bot, colony);
		BotScripts.RecordingPlayer owner = BotScripts.owner(helper, bot);
		bot.kill(helper.getLevel());
		bot.remove(Entity.RemovalReason.KILLED); // the body is gone
		helper.assertTrue(helper.getLevel().getEntity(id) == null, "the body is still there");
		helper.assertTrue(BotRespawns.get(nether).pending(id) != null, "no respawn waits");

		List<String> answer = BotScripts.commandAsPlayer(helper, owner, "antcraft bot " + id + " remove");
		helper.assertTrue(answer.equals(List.of("commands.antcraft.bot.remove[1]")), "answer " + answer);
		helper.assertTrue(BotRespawns.get(nether).pending(id) == null, "a respawn still waits");
		helper.assertFalse(colony.members().contains(id), "still a member: " + colony.members());
		helper.succeed();
	}

	/** A player cannot remove another player's bot waiting to respawn (#464); an operator can. */
	@GameTest(maxTicks = 20)
	public void onlyItsOwnerOrAnOperatorRemovesABotWaitingToRespawn(GameTestHelper helper) {
		AntBot own = helper.spawn(ModEntities.ANT_BOT, 1, 1, 1);
		BotScripts.RecordingPlayer player = BotScripts.owner(helper, own);
		AntBot other = helper.spawn(ModEntities.ANT_BOT, 5, 1, 5);
		UUID id = other.getUUID();
		other.setOwner(UUID.randomUUID());
		other.setHome(helper.absolutePos(new BlockPos(6, 1, 6)));
		other.kill(helper.getLevel());
		other.remove(Entity.RemovalReason.KILLED);

		List<String> refused = BotScripts.commandAsPlayer(helper, player, "antcraft bot " + id + " remove");
		helper.assertTrue(refused.size() == 1 && refused.getFirst().endsWith(" is not your bot"), "answer " + refused);
		helper.assertTrue(BotRespawns.get(helper.getLevel()).pending(id) != null, "removed another player's waiting bot");
		List<String> removed = BotScripts.command(helper, "antcraft bot " + id + " remove");
		helper.assertTrue(removed.equals(List.of("commands.antcraft.bot.remove[1]")), "answer " + removed);
		helper.assertTrue(BotRespawns.get(helper.getLevel()).pending(id) == null, "the operator did not remove it");
		helper.succeed();
	}
}
