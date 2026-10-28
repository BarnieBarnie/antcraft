package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.script.BotLog;
import io.github.barniebarnie.antcraft.script.Memory;
import io.github.barniebarnie.antcraft.script.DefaultScripts;
import io.github.barniebarnie.antcraft.script.MoveApi;
import io.github.barniebarnie.antcraft.script.ServerScripts;
import io.github.barniebarnie.antcraft.script.Pos;
import io.github.barniebarnie.antcraft.script.ScriptLibrary;
import io.github.barniebarnie.antcraft.script.ScriptRun;
import io.github.barniebarnie.antcraft.script.lang.Code;
import io.github.barniebarnie.antcraft.script.lang.Output;
import io.github.barniebarnie.antcraft.script.lang.PyDict;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntitySpawnRequest;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A bot. Carries an inventory, picks up items and defends itself against hostile mobs. What it does is up to the script it
 * runs ({@link #runScript}); without one, or once its script ended, stopped or failed, it runs {@code idle.py} (walk home,
 * wait), whether or not it is in a colony.
 */
public class AntBot extends PathfinderMob implements InventoryCarrier {
	private static final int INVENTORY_SIZE = 27;
	/**
	 * Path scaffold stays protected while the bot is within this many blocks of it, measured flat, so a
	 * pillar the bot stands on counts as near however tall it is.
	 * ponytail: a distance stands in for "still needed"; a bot coming back over a bridge it left may have to bridge again.
	 */
	private static final int PATH_SCAFFOLD_RANGE = 8;

	private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
	private @Nullable BlockPos home;
	private @Nullable UUID colonyId;
	/** The player who placed the bot's Bot Core, or who last started a script on it; gets the script's chat. Saved. */
	private @Nullable UUID owner;
	/** The script the bot runs or ran last; not saved yet (docs/scripting.md, milestone 5). */
	private @Nullable ScriptRun script;
	/**
	 * Whether {@link #script} is the {@code idle.py} that {@link #idleIfDone} started: then the bot holds its chunks loaded
	 * only while it walks (docs/scripting.md, Chunk loading).
	 */
	private boolean idling;
	/** The source of {@link #script} when it came from the script library, so a reload can tell whether it changed. */
	private @Nullable String scriptSource;
	/** The compiled code of {@link #script}, so it can start again from the top when the bot respawns. */
	private @Nullable Code scriptCode;
	/** How often the bot has died ({@code deaths()} in a script). Saved. */
	private int deaths;
	/** The scripts' {@code memory}: outlives each script run, and is saved. */
	private PyDict memory = Memory.create();
	private final BotLog log = new BotLog();
	/** What the bot said last, shown above it for a while. */
	private final SayBubble sayBubble = new SayBubble(this);
	/** What the script's move actions walk with; made when a script first moves. */
	private MoveApi.@Nullable Body body;
	/** Until which {@link #tickCount} currents leave the bot alone; see {@link #holdAgainstCurrent()}. Not saved. */
	private int holdAgainstCurrentUntil = -1;
	/**
	 * Every block the bot placed while following a path, which other bots leave alone until the bot
	 * has moved on (see {@link #PATH_SCAFFOLD_RANGE}). Not saved: after loading they are left standing, unprotected.
	 */
	private final Set<BlockPos> pathScaffold = new HashSet<>();
	private final Map<BlockPos, Long> opened = new HashMap<>();

	public AntBot(EntityType<? extends AntBot> type, Level level) {
		super(type, level);
		this.setCanPickUpLoot(true);
		// Always drop what is in hand, as the rest of the inventory, when it dies.
		this.setGuaranteedDrop(EquipmentSlot.MAINHAND);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 20.0)
			.add(Attributes.MOVEMENT_SPEED, 0.3)
			.add(Attributes.ATTACK_DAMAGE, 4.0)
			.add(Attributes.FOLLOW_RANGE, 32.0);
	}

	public static int countIn(ServerLevel level) {
		return level.getEntities(ModEntities.ANT_BOT, AntBot::isAlive).size();
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.2, true));
		this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Player.class, AntBot.class));
		// Creepers are left alone until bots can fight at range.
		this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, true,
			(target, level) -> !(target instanceof Creeper)));
	}

	/**
	 * Bots do not push each other, so they pass in a one-wide shaft or tunnel: a bot standing idle there, such as in
	 * the corner of a landing, would otherwise push one climbing past it back for good. Other entities they push as usual.
	 */
	@Override
	protected void doPush(Entity entity) {
		if (!(entity instanceof AntBot)) {
			super.doPush(entity);
		}
	}

	@Override
	public void tick() {
		long begin = TickProfiler.SERVER.begin();
		this.botTick();
		TickProfiler.SERVER.end("bot.tick", begin);
	}

	private void botTick() {
		if (this.level() instanceof ServerLevel level) {
			// Bots on a server that has not opted in stand still instead of being deleted,
			// so enabling the mod again later restores them.
			boolean allowed = AntCraftConfig.botsAllowed(level.getServer());
			if (this.isNoAi() == allowed) {
				this.setNoAi(!allowed);
			}
			if (this.home == null) {
				this.home = this.blockPosition(); // where it was first spawned
			}
			this.sayBubble.tick();
			// A dead bot lies there for a second before it is gone; it does nothing meanwhile.
			if (allowed && !this.isDeadOrDying()) {
				this.idleIfDone();
				if (this.script != null && this.script.running()) {
					ScriptRun run = this.script;
					TickProfiler.time("bot.script", () -> run.tick(AntCraftConfig.scriptInstructionsPerTick()));
				}
			}
			// Before the goals, which open the door ahead again this tick if the bot is still on its way through.
			PathFollower.closeBehind(this, level);
		}
		super.tick();
		this.sayBubble.follow();
		// After the move, so the ticket goes along with the bot into the next chunk before it stops ticking there.
		// An idle bot holds its chunks only while it walks (home): waiting there, it lets them unload.
		ScriptRun running = this.isRunningScript() ? this.script : null;
		if (running != null && (!this.idling || running.walking()) && AntCraftConfig.botsLoadChunks()) {
			BotChunks.keep(this);
		}
		BlockPos feet = this.blockPosition();
		this.pathScaffold.removeIf(pos -> Math.max(Math.abs(pos.getX() - feet.getX()), Math.abs(pos.getZ() - feet.getZ())) > PATH_SCAFFOLD_RANGE);
	}

	/**
	 * Swims against the current this tick and the next: flowing water does not push the bot while it follows a
	 * path through it. A mob's swim stroke is weaker than a current, which would otherwise wash it into a corner.
	 */
	public void holdAgainstCurrent() {
		this.holdAgainstCurrentUntil = this.tickCount + 1;
	}

	@Override
	public boolean isPushedByFluid() {
		return super.isPushedByFluid() && this.tickCount > this.holdAgainstCurrentUntil;
	}

	@Override
	public void setTarget(@Nullable LivingEntity target) {
		if (target != null && target != this.getTarget()) {
			Tools.equipBestWeapon(this);
		}
		super.setTarget(target);
	}

	/**
	 * Doors and fence gates the bot opened on its way, each with the game time a path step last walked through it, to
	 * shut again once the bot is through and clear of them (see {@link PathFollower#closeBehind}). Callers change it in place.
	 */
	public Map<BlockPos, Long> getOpened() {
		return this.opened;
	}

	/** Records a block placed while following a path, so other bots leave it alone while this bot is near it. */
	public void addPathScaffold(BlockPos pos) {
		this.pathScaffold.add(pos.immutable());
	}

	/** Blocks other bots must not break: path scaffold this bot is still near. */
	public Set<BlockPos> getProtectedScaffold() {
		return Set.copyOf(this.pathScaffold);
	}

	/** The bot's home ({@code home()} in a script): where it was first spawned, until a script sets it ({@code set_home}). */
	public @Nullable BlockPos getHome() {
		return this.home;
	}

	/** The home a script sees: {@link #getHome()}, or where the bot stands before its first tick has set it. */
	public BlockPos getScriptHome() {
		return this.home != null ? this.home : this.blockPosition();
	}

	public void setHome(@Nullable BlockPos home) {
		this.home = home;
	}

	public @Nullable UUID getColonyId() {
		return this.colonyId;
	}

	/** Set through {@link ColonyManager#join} and {@link ColonyManager#leave}, which also keep the member list in sync. */
	public void setColonyId(@Nullable UUID colonyId) {
		this.colonyId = colonyId;
	}

	public @Nullable UUID getOwner() {
		return this.owner;
	}

	public void setOwner(@Nullable UUID owner) {
		this.owner = owner;
	}

	/** Starts {@code script} from the script library, replacing the script the bot runs; it must compile. */
	public void runScript(ScriptLibrary.Script script, List<String> args) {
		this.runScript(script.name(), Objects.requireNonNull(script.code(), script.name() + " does not compile"), args);
		this.scriptSource = script.source();
	}

	/**
	 * Starts the compiled script {@code code} under {@code name}, replacing the script the bot runs; its first instructions
	 * run next tick. It imports its modules from the server's script library.
	 */
	public void runScript(String name, Code code, List<String> args) {
		this.stopScript();
		this.log.add(this.level().getGameTime(), Output.Level.INFO, "run " + name + (args.isEmpty() ? "" : " " + String.join(" ", args)));
		this.script = new ScriptRun(name, code, args, new ScriptHost(), this.log, this.memory, ServerScripts.library());
		this.scriptCode = code;
		this.scriptSource = null;
		this.idling = false;
	}

	/**
	 * Starts {@code idle.py} when the bot runs no script: it never ran one, or the one it ran ended, stopped or failed
	 * (docs/scripting.md, Idle, home and death). Not after {@code idle.py} itself ended, so a broken one is not started
	 * over and over.
	 */
	private void idleIfDone() {
		if (this.script == null || (!this.script.running() && !this.script.name().equals(DefaultScripts.IDLE))) {
			ServerScripts.idle().ifPresent(idle -> {
				this.runScript(idle, List.of());
				this.idling = true;
			});
		}
	}

	/** Stops the script the bot runs, if any. */
	public void stopScript() {
		if (this.script != null && this.script.running()) {
			this.script.stop();
			this.log.add(this.level().getGameTime(), Output.Level.INFO, "stopped " + this.script.name());
		}
	}

	/**
	 * Whether the bot runs the {@code idle.py} it fell back to without a script of its own: it then keeps its chunks loaded
	 * only while it walks.
	 */
	public boolean isIdling() {
		return this.idling && this.isRunningScript();
	}

	/** Whether the bot runs a script. */
	public boolean isRunningScript() {
		return this.script != null && this.script.running();
	}

	/** The script the bot runs or ran last, or null. */
	public @Nullable ScriptRun getScript() {
		return this.script;
	}

	/** The library source of {@link #getScript()}, or null for a script that did not come from the library. */
	public @Nullable String getScriptSource() {
		return this.scriptSource;
	}

	/** Reports a script error outside a running script, such as a compile error: to the log and the owner's chat. */
	public void reportError(String message) {
		this.log.add(this.level().getGameTime(), Output.Level.ERROR, message);
		new ScriptHost().chat(Output.Level.ERROR, message);
	}

	/** What the script's move actions walk with: the bot's path follower, chunks and home. */
	public MoveApi.Body getBody() {
		if (this.body == null) {
			this.body = new BotBody(this);
		}
		return this.body;
	}

	/** Puts {@code body} in place of {@link #getBody()}, as game tests do to stand in for parts of it. */
	public void setBody(MoveApi.Body body) {
		this.body = body;
	}

	public BotLog getLog() {
		return this.log;
	}

	/** The scripts' {@code memory}. */
	public PyDict getMemory() {
		return this.memory;
	}

	/** How often the bot has died. */
	public int getDeaths() {
		return this.deaths;
	}

	/** Sends {@code message} to the bot's owner, if online. */
	private void tellOwner(Component message) {
		if (this.owner != null && this.level() instanceof ServerLevel level) {
			ServerPlayer player = level.getServer().getPlayerList().getPlayer(this.owner);
			if (player != null) {
				player.sendSystemMessage(message);
			}
		}
	}

	/** What a script sees of this bot. */
	private final class ScriptHost implements ScriptRun.Host {
		@Override
		public MoveApi.Body body() {
			return AntBot.this.getBody();
		}

		@Override
		public long gameTime() {
			return AntBot.this.level().getGameTime();
		}

		@Override
		public Pos pos() {
			BlockPos feet = AntBot.this.blockPosition();
			return new Pos(feet.getX(), feet.getY(), feet.getZ());
		}

		@Override
		public Output.Level chatLevel() {
			return AntCraftConfig.scriptChatLevel();
		}

		@Override
		public void chat(Output.Level level, String line) {
			ChatFormatting color = switch (level) {
				case DEBUG, INFO -> ChatFormatting.GRAY;
				case WARN -> ChatFormatting.YELLOW;
				case ERROR -> ChatFormatting.RED;
				case PRINT -> ChatFormatting.WHITE;
			};
			AntBot.this.tellOwner(Component.literal("[" + AntBot.this.getName().getString() + "] " + line).withStyle(color));
		}

		@Override
		public void say(String text) {
			AntBot.this.tellOwner(Component.literal("<" + AntBot.this.getName().getString() + "> " + text));
			AntBot.this.sayBubble.show(text);
		}

		@Override
		public AntBot bot() {
			return AntBot.this;
		}
	}

	/**
	 * Drops what the bot carries, tells its log and owner where and how it died, and with {@code botRespawn} on lets it wait
	 * to come back at its home ({@link BotRespawns}), running the script it ran again from the top.
	 */
	@Override
	public void die(DamageSource source) {
		boolean dying = !this.isRemoved() && !this.dead;
		Component how = this.getCombatTracker().getDeathMessage();
		super.die(source);
		if (!dying || !(this.level() instanceof ServerLevel level)) {
			return;
		}
		ColonyManager.get(level).leave(this);
		this.deaths++;
		BlockPos at = this.blockPosition();
		String line = "died at " + at.getX() + ", " + at.getY() + ", " + at.getZ() + ": " + how.getString();
		this.log.add(level.getGameTime(), Output.Level.WARN, line);
		this.tellOwner(Component.literal("[" + this.getName().getString() + "] " + line).withStyle(ChatFormatting.RED));
		// The script it ran itself, not the idle.py it fell back to, which it runs anyway once back.
		ScriptRun last = this.idling ? null : this.script;
		boolean library = this.scriptSource != null;
		Code code = this.scriptCode;
		this.stopScript();
		BotRespawns.respawnTime(AntCraftConfig.values(), level.getGameTime()).ifPresent(time -> {
			TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
			this.saveWithoutId(output);
			BotRespawns.get(level).add(new BotRespawns.Pending(this.getUUID(), this.getScriptHome(), time, output.buildResult(),
				Optional.ofNullable(last).map(ScriptRun::name), last != null ? last.args() : List.of(), library, last != null ? code : null));
		});
	}

	/**
	 * Brings back the bot of {@code respawn} at its home, with full health, and starts the script it ran when it died again
	 * from the top: from the script library when it came from there, as it is now. Whether the bot is back, or its wait is
	 * over anyway because it cannot be made from what was saved.
	 */
	static boolean respawn(ServerLevel level, BotRespawns.Pending respawn) {
		AntBot bot = ModEntities.ANT_BOT.create(level, new EntitySpawnRequest(EntitySpawnReason.LOAD, true));
		if (bot == null) {
			return true;
		}
		bot.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), respawn.bot()));
		BlockPos home = respawn.home();
		bot.snapTo(Vec3.atBottomCenterOf(home), bot.getYRot(), bot.getXRot());
		bot.setDeltaMovement(Vec3.ZERO);
		bot.resetFallDistance();
		bot.clearFire();
		bot.removeAllEffects();
		bot.setAirSupply(bot.getMaxAirSupply());
		bot.deathTime = 0;
		bot.setHealth(bot.getMaxHealth());
		if (!level.addFreshEntity(bot)) {
			return false;
		}
		bot.log.add(level.getGameTime(), Output.Level.INFO, "respawned at home " + home.getX() + ", " + home.getY() + ", " + home.getZ());
		respawn.script().ifPresent(name -> {
			Optional<ScriptLibrary.Script> fromLibrary = respawn.library()
				? ServerScripts.library().get(name).filter(script -> script.code() != null) : Optional.empty();
			if (fromLibrary.isPresent()) {
				bot.runScript(fromLibrary.get(), respawn.args());
			} else if (respawn.code() != null) {
				bot.runScript(name, respawn.code(), respawn.args());
			} else {
				bot.log.add(level.getGameTime(), Output.Level.WARN, "cannot run " + name + " again: it is not in the script library");
			}
		});
		return true;
	}

	/** A bot that is removed, for good or only unloaded, takes what it said with it: the text is never saved. */
	@Override
	public void remove(RemovalReason reason) {
		this.sayBubble.remove();
		super.remove(reason);
	}

	@Override
	public SimpleContainer getInventory() {
		return this.inventory;
	}

	@Override
	public boolean wantsToPickUp(ServerLevel level, ItemStack itemStack) {
		return this.inventory.canAddItem(itemStack);
	}

	@Override
	protected void pickUpItem(ServerLevel level, ItemEntity entity) {
		// What it dropped on purpose (a script's drop) it leaves lying; pick_up still takes it (see BotHands.collect).
		if (entity.getOwner() == this) {
			return;
		}
		InventoryCarrier.pickUpItem(level, this, this, entity);
	}

	/** Drops everything the bot carries, like a player: its inventory and whatever it holds or wears. */
	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
		super.dropCustomDeathLoot(level, source, killedByPlayer);
		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			ItemStack stack = this.getItemBySlot(slot);
			if (!stack.isEmpty()) {
				this.spawnAtLocation(level, stack);
				this.setItemSlot(slot, ItemStack.EMPTY);
			}
		}
		for (ItemStack stack : this.inventory.removeAllItems()) {
			this.spawnAtLocation(level, stack);
		}
	}

	@Override
	public boolean requiresCustomPersistence() {
		return true;
	}

	@Override
	public boolean removeWhenFarAway(double distSqr) {
		return false;
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		this.writeInventoryToTag(output);
		output.storeNullable("Home", BlockPos.CODEC, this.home);
		output.storeNullable("Colony", UUIDUtil.CODEC, this.colonyId);
		output.storeNullable("Owner", UUIDUtil.CODEC, this.owner);
		if (this.deaths > 0) {
			output.putInt("Deaths", this.deaths);
		}
		Memory.save(this.memory, output, problem -> this.log.add(this.level().getGameTime(), Output.Level.WARN, problem));
		this.log.save(output);
		if (!this.opened.isEmpty()) {
			output.store("Opened", BlockPos.CODEC.listOf(), List.copyOf(this.opened.keySet()));
		}
	}

	/**
	 * Fields older versions saved and this one ignores: a role ({@code Role}, {@code RoleSetByPlayer}, {@code ScaffoldRole}),
	 * the walk target ({@code WalkTarget}) and the goal-driven colony's scaffold to take down ({@code Scaffold}).
	 */
	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.readInventoryFromTag(input);
		this.home = input.read("Home", BlockPos.CODEC).orElse(null);
		this.colonyId = input.read("Colony", UUIDUtil.CODEC).orElse(null);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		this.deaths = input.getIntOr("Deaths", 0);
		this.memory = Memory.load(input);
		this.log.load(input);
		this.opened.clear();
		// Walked through long ago, as far as a loaded bot knows: shut once it is clear of them.
		input.read("Opened", BlockPos.CODEC.listOf()).ifPresent(doors -> doors.forEach(pos -> this.opened.put(pos, Long.MIN_VALUE)));
	}
}
