package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.JobType;
import io.github.barniebarnie.antcraft.colony.Territory;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.goal.BuilderGoal;
import io.github.barniebarnie.antcraft.entity.goal.CrafterGoal;
import io.github.barniebarnie.antcraft.entity.goal.FarmerGoal;
import io.github.barniebarnie.antcraft.entity.goal.HaulerGoal;
import io.github.barniebarnie.antcraft.entity.goal.LumberjackGoal;
import io.github.barniebarnie.antcraft.entity.goal.MinerGoal;
import io.github.barniebarnie.antcraft.entity.goal.ReturnHomeGoal;
import io.github.barniebarnie.antcraft.entity.goal.WalkToTargetGoal;
import io.github.barniebarnie.antcraft.entity.goal.WorkerGoal;
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
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * A colony worker. Carries an inventory, picks up items, defends itself
 * against hostile mobs and can be sent to a target position. In a colony it
 * takes whatever job the colony has (see {@link WorkerGoal}); without one it
 * does no work. While it runs a script ({@link #runScript}), it does no colony work: the script decides what it does.
 */
public class AntBot extends PathfinderMob implements InventoryCarrier {
	private static final int INVENTORY_SIZE = 27;
	/**
	 * Path scaffold stays protected while the bot is within this many blocks of it, measured flat, so a
	 * pillar the bot stands on counts as near however tall it is.
	 * ponytail: a distance stands in for "still needed"; a bot coming back over a bridge it left may have to bridge again.
	 */
	private static final int PATH_SCAFFOLD_RANGE = 8;
	/**
	 * The type of the job the bot took last, empty before its first, synched so clients can dress the bot for its
	 * work. Not saved: a bot loads in its own skin until it takes a job.
	 */
	public static final EntityDataAccessor<String> DATA_JOB = SynchedEntityData.defineId(AntBot.class, EntityDataSerializers.STRING);

	private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
	private @Nullable BlockPos walkTarget;
	private @Nullable BlockPos home;
	private @Nullable UUID colonyId;
	/** The player who placed the bot's Bot Core, or who last started a script on it; gets the script's chat. Saved. */
	private @Nullable UUID owner;
	/** The script the bot runs or ran last; not saved yet (docs/scripting.md, milestone 5). */
	private @Nullable ScriptRun script;
	/** The source of {@link #script} when it came from the script library, so a reload can tell whether it changed. */
	private @Nullable String scriptSource;
	/** The scripts' {@code memory}: outlives each script run, and is saved. */
	private PyDict memory = Memory.create();
	private final BotLog log = new BotLog();
	/** What the script's move actions walk with; made when a script first moves. */
	private @Nullable BotBody body;
	/** Set while the bot has work, a job or work between jobs; not saved, it is set again after loading. */
	private boolean working;
	private int idleTicks;
	/** Until which {@link #tickCount} currents leave the bot alone; see {@link #holdAgainstCurrent()}. Not saved. */
	private int holdAgainstCurrentUntil = -1;
	/** Where the bot steps aside to until {@link #stepAsideUntil}, whatever its goals want; see {@link #stepAside}. Not saved. */
	private @Nullable BlockPos stepAsideTo;
	private int stepAsideUntil = -1;
	/** Scaffold blocks the bot placed for its current work and still has to take down; saved, so cleanup survives unloading. */
	private final Set<BlockPos> scaffold = new HashSet<>();
	/**
	 * Every block the bot placed while following a path, which other bots leave alone until the bot
	 * has moved on (see {@link #PATH_SCAFFOLD_RANGE}). Not saved: after loading they are left standing, unprotected.
	 */
	private final Set<BlockPos> pathScaffold = new HashSet<>();
	private final Map<BlockPos, Long> opened = new HashMap<>();

	public AntBot(EntityType<? extends AntBot> type, Level level) {
		super(type, level);
		this.setCanPickUpLoot(true);
		// Tools and weapons are the colony's property: always drop what is in hand.
		this.setGuaranteedDrop(EquipmentSlot.MAINHAND);
	}

	public static AttributeSupplier.Builder createAttributes() {
		return Mob.createMobAttributes()
			.add(Attributes.MAX_HEALTH, 20.0)
			.add(Attributes.MOVEMENT_SPEED, 0.3)
			.add(Attributes.ATTACK_DAMAGE, 4.0)
			.add(Attributes.FOLLOW_RANGE, 32.0);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DATA_JOB, "");
	}

	public static int countIn(ServerLevel level) {
		return level.getEntities(ModEntities.ANT_BOT, AntBot::isAlive).size();
	}

	@Override
	protected void registerGoals() {
		this.goalSelector.addGoal(0, new FloatGoal(this));
		this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.2, true));
		this.goalSelector.addGoal(2, new WalkToTargetGoal(this));
		this.goalSelector.addGoal(2, new ReturnHomeGoal(this));
		// All colony work: jobs from the board, and what is left to do between them.
		this.goalSelector.addGoal(3, new WorkerGoal(this, new LumberjackGoal(this), new FarmerGoal(this), new MinerGoal(this),
			new CrafterGoal(this), new HaulerGoal(this), new BuilderGoal(this)));
		this.goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0F));
		this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
		this.targetSelector.addGoal(1, new HurtByTargetGoal(this, Player.class, AntBot.class));
		// Creepers are left alone until bots can fight at range.
		this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, true,
			(target, level) -> !(target instanceof Creeper) && this.mayTarget(level, target)));
	}

	/** Colony members only go after mobs inside the colony, so they are not lured away. */
	private boolean mayTarget(ServerLevel level, LivingEntity target) {
		if (this.colonyId == null) {
			return true;
		}
		return ColonyManager.get(level).byId(this.colonyId)
			.map(colony -> Territory.of(colony).mayTarget(target.blockPosition()))
			.orElse(true);
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
			// Before the goals, so they see whether a script runs this tick.
			if (allowed && this.script != null) {
				this.idleIfDone();
				if (this.script.running()) {
					ScriptRun run = this.script;
					TickProfiler.time("bot.script", () -> run.tick(AntCraftConfig.scriptInstructionsPerTick()));
				}
			}
			// Before the goals, which open the door ahead again this tick if the bot is still on its way through.
			PathFollower.closeBehind(this, level);
		}
		super.tick();
		// After the move, so the ticket goes along with the bot into the next chunk before it stops ticking there.
		if (this.isRunningScript() && AntCraftConfig.botsLoadChunks()) {
			BotChunks.keep(this);
		}
		BlockPos feet = this.blockPosition();
		this.pathScaffold.removeIf(pos -> Math.max(Math.abs(pos.getX() - feet.getX()), Math.abs(pos.getZ() - feet.getZ())) > PATH_SCAFFOLD_RANGE);
		this.idleTicks = this.working ? 0 : this.idleTicks + 1;
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

	public @Nullable BlockPos getWalkTarget() {
		return this.walkTarget;
	}

	public void setWalkTarget(@Nullable BlockPos target) {
		this.walkTarget = target;
		if (target == null) {
			this.getNavigation().stop();
		}
	}

	/**
	 * Steps to the middle of {@code to} for the next {@code ticks} ticks, over what its goals want, such as out of a
	 * spot another bot places a block in. Its goals keep running, so it goes back to its work after.
	 */
	public void stepAside(BlockPos to, int ticks) {
		this.stepAsideTo = to;
		this.stepAsideUntil = this.tickCount + ticks;
	}

	@Override
	protected void customServerAiStep(ServerLevel level) {
		super.customServerAiStep(level);
		// After the goals and before the move control, so the step aside wins this tick.
		if (this.stepAsideTo != null && this.tickCount < this.stepAsideUntil) {
			this.getMoveControl().setWantedPosition(this.stepAsideTo.getX() + 0.5, this.stepAsideTo.getY(), this.stepAsideTo.getZ() + 0.5, 1.0);
		}
	}

	/** The type of the job the bot took last, or empty before its first. */
	public Optional<JobType> getJobType() {
		String id = this.entityData.get(DATA_JOB);
		return Stream.of(JobType.values()).filter(type -> type.getSerializedName().equals(id)).findFirst();
	}

	public void setJobType(JobType type) {
		this.entityData.set(DATA_JOB, type.getSerializedName());
	}

	/** Whether the bot has work in progress, such as a claimed job. */
	public boolean isWorking() {
		return this.working;
	}

	public void setWorking(boolean working) {
		this.working = working;
	}

	/** Ticks since the bot last had work. */
	public int getIdleTicks() {
		return this.idleTicks;
	}

	/** Scaffold blocks this bot placed and still has to take down. Saved with the bot; callers change it in place. */
	public Set<BlockPos> getScaffold() {
		return this.scaffold;
	}

	/** Records a scaffold block the bot placed for its work, to take down once done with it. */
	public void addScaffold(BlockPos pos) {
		this.scaffold.add(pos.immutable());
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

	/** Blocks other bots must not break: scaffold this bot will take down, and path scaffold it is still near. */
	public Set<BlockPos> getProtectedScaffold() {
		Set<BlockPos> all = new HashSet<>(this.scaffold);
		all.addAll(this.pathScaffold);
		return all;
	}

	/**
	 * The storage block this bot brings its work to: its colony's core. Also the home of a script ({@code home()}),
	 * which is where the bot was first spawned until a script or a colony sets it.
	 */
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

	/** Starts the compiled script {@code code} under {@code name}, replacing the script the bot runs; its first instructions run next tick. */
	public void runScript(String name, Code code, List<String> args) {
		this.stopScript();
		this.log.add(this.level().getGameTime(), Output.Level.INFO, "run " + name + (args.isEmpty() ? "" : " " + String.join(" ", args)));
		this.script = new ScriptRun(name, code, args, new ScriptHost(), this.log, this.memory);
		this.scriptSource = null;
	}

	/**
	 * Starts {@code idle.py} once the script the bot ran ended, stopped or failed (docs/scripting.md, Idle, home and
	 * death). Not for a colony member, which goes back to its colony's work until the colony makes way for scripts, nor
	 * after {@code idle.py} itself ended, so a broken one is not started over and over.
	 */
	private void idleIfDone() {
		if (this.script != null && !this.script.running() && this.colonyId == null && !this.script.name().equals(DefaultScripts.IDLE)) {
			ServerScripts.idle().ifPresent(idle -> this.runScript(idle, List.of()));
		}
	}

	/** Stops the script the bot runs, if any. */
	public void stopScript() {
		if (this.script != null && this.script.running()) {
			this.script.stop();
			this.log.add(this.level().getGameTime(), Output.Level.INFO, "stopped " + this.script.name());
		}
	}

	/** Whether the bot runs a script, which keeps its colony work from running. */
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

	public BotLog getLog() {
		return this.log;
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
			if (AntBot.this.body == null) {
				AntBot.this.body = new BotBody(AntBot.this);
			}
			return AntBot.this.body;
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
		}

		@Override
		public AntBot bot() {
			return AntBot.this;
		}
	}

	@Override
	public void die(DamageSource source) {
		super.die(source);
		if (this.level() instanceof ServerLevel level) {
			ColonyManager.get(level).leave(this);
		}
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
		InventoryCarrier.pickUpItem(level, this, this, entity);
	}

	@Override
	protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
		super.dropCustomDeathLoot(level, source, killedByPlayer);
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
		output.storeNullable("WalkTarget", BlockPos.CODEC, this.walkTarget);
		output.storeNullable("Home", BlockPos.CODEC, this.home);
		output.storeNullable("Colony", UUIDUtil.CODEC, this.colonyId);
		output.storeNullable("Owner", UUIDUtil.CODEC, this.owner);
		Memory.save(this.memory, output, problem -> this.log.add(this.level().getGameTime(), Output.Level.WARN, problem));
		output.store("Scaffold", BlockPos.CODEC.listOf(), List.copyOf(this.scaffold));
		if (!this.opened.isEmpty()) {
			output.store("Opened", BlockPos.CODEC.listOf(), List.copyOf(this.opened.keySet()));
		}
	}

	/** Bots saved with a role ({@code Role}, {@code RoleSetByPlayer}, {@code ScaffoldRole}) load without it, as workers like any other. */
	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.readInventoryFromTag(input);
		this.walkTarget = input.read("WalkTarget", BlockPos.CODEC).orElse(null);
		this.home = input.read("Home", BlockPos.CODEC).orElse(null);
		this.colonyId = input.read("Colony", UUIDUtil.CODEC).orElse(null);
		this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
		this.memory = Memory.load(input);
		this.scaffold.clear();
		input.read("Scaffold", BlockPos.CODEC.listOf()).ifPresent(this.scaffold::addAll);
		this.opened.clear();
		// Walked through long ago, as far as a loaded bot knows: shut once it is clear of them.
		input.read("Opened", BlockPos.CODEC.listOf()).ifPresent(doors -> doors.forEach(pos -> this.opened.put(pos, Long.MIN_VALUE)));
	}
}
