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
import io.github.barniebarnie.antcraft.registry.ModEntities;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
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
 * does no work.
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
		if (this.level() instanceof ServerLevel level) {
			// Bots on a server that has not opted in stand still instead of being deleted,
			// so enabling the mod again later restores them.
			boolean allowed = AntCraftConfig.botsAllowed(level.getServer());
			if (this.isNoAi() == allowed) {
				this.setNoAi(!allowed);
			}
		}
		super.tick();
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

	/** The storage block this bot brings its work to: its colony's core. */
	public @Nullable BlockPos getHome() {
		return this.home;
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
		output.store("Scaffold", BlockPos.CODEC.listOf(), List.copyOf(this.scaffold));
	}

	/** Bots saved with a role ({@code Role}, {@code RoleSetByPlayer}, {@code ScaffoldRole}) load without it, as workers like any other. */
	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.readInventoryFromTag(input);
		this.walkTarget = input.read("WalkTarget", BlockPos.CODEC).orElse(null);
		this.home = input.read("Home", BlockPos.CODEC).orElse(null);
		this.colonyId = input.read("Colony", UUIDUtil.CODEC).orElse(null);
		this.scaffold.clear();
		input.read("Scaffold", BlockPos.CODEC.listOf()).ifPresent(this.scaffold::addAll);
	}
}
