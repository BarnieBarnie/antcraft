package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.colony.Territory;
import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.entity.goal.BuilderGoal;
import io.github.barniebarnie.antcraft.entity.goal.CrafterGoal;
import io.github.barniebarnie.antcraft.entity.goal.FarmerGoal;
import io.github.barniebarnie.antcraft.entity.goal.GuardGoal;
import io.github.barniebarnie.antcraft.entity.goal.HaulerGoal;
import io.github.barniebarnie.antcraft.entity.goal.LumberjackGoal;
import io.github.barniebarnie.antcraft.entity.goal.MinerGoal;
import io.github.barniebarnie.antcraft.entity.goal.ReturnHomeGoal;
import io.github.barniebarnie.antcraft.entity.goal.WalkToTargetGoal;
import io.github.barniebarnie.antcraft.entity.goal.WorkerGoal;
import io.github.barniebarnie.antcraft.registry.ModEntities;
import io.github.barniebarnie.antcraft.work.Revisit;
import io.github.barniebarnie.antcraft.work.TreeRetry;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
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
 * against hostile mobs and can be sent to a target position.
 */
public class AntBot extends PathfinderMob implements InventoryCarrier {
	private static final int INVENTORY_SIZE = 27;
	/**
	 * Path scaffold stays protected while the bot is within this many blocks of it, measured flat, so a
	 * pillar the bot stands on counts as near however tall it is.
	 * ponytail: a distance stands in for "still needed"; a bot coming back over a bridge it left may have to bridge again.
	 */
	private static final int PATH_SCAFFOLD_RANGE = 8;
	/** The role id, synched so clients can dress the bot for its role. */
	public static final EntityDataAccessor<String> DATA_ROLE = SynchedEntityData.defineId(AntBot.class, EntityDataSerializers.STRING);

	private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
	private @Nullable BlockPos walkTarget;
	/** Roles set by a player are kept; the colony may change the ones it assigned itself. */
	private boolean roleSetByPlayer = true;
	private @Nullable BlockPos home;
	private @Nullable UUID colonyId;
	/** Set by the role goals while they have a job; not saved, they set it again after loading. */
	private boolean working;
	private int idleTicks;
	/** Until which {@link #tickCount} currents leave the bot alone; see {@link #holdAgainstCurrent()}. Not saved. */
	private int holdAgainstCurrentUntil = -1;
	/** Scaffold blocks the bot placed for its current work and still has to take down; saved, so cleanup survives unloading. */
	private final Set<BlockPos> scaffold = new HashSet<>();
	/**
	 * The role whose goal placed that scaffold and takes it down while the bot has that role; saved with it.
	 * Bots saved before this was saved only had a lumberjack's scaffold.
	 */
	private BotRole scaffoldRole = BotRole.LUMBERJACK;
	/**
	 * Every block the bot placed while following a path, which other bots leave alone until the bot
	 * has moved on (see {@link #PATH_SCAFFOLD_RANGE}). Not saved: after loading they are left standing, unprotected.
	 */
	private final Set<BlockPos> pathScaffold = new HashSet<>();
	/** Saved, so after loading a solo lumberjack goes back to its tree and others still leave it alone. */
	private List<BlockPos> tree = List.of();
	/** Where that tree stood on the ground, which its bottom log may no longer show; saved with it. */
	private @Nullable BlockPos treeBase;
	/** Felled trees the bot could not replant, by base; saved, so it comes back after unloading. */
	private final Map<BlockPos, Revisit> revisits = new LinkedHashMap<>();
	/** Trees a solo bot could not fell whole, by base, to come back to; saved, so the retries and their bound survive unloading. */
	private final Map<BlockPos, TreeRetry> treeRetries = new LinkedHashMap<>();

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
		builder.define(DATA_ROLE, BotRole.NONE.id());
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
		// Colony jobs that have moved off the role goals; it takes the bot from its role goal while that has no work.
		LumberjackGoal lumberjack = new LumberjackGoal(this);
		this.goalSelector.addGoal(3, new WorkerGoal(this, lumberjack));
		// First of the role goals: it also takes down scaffolding left from an earlier role, before the new role's work.
		this.goalSelector.addGoal(4, lumberjack);
		this.goalSelector.addGoal(4, new CrafterGoal(this));
		this.goalSelector.addGoal(4, new MinerGoal(this));
		this.goalSelector.addGoal(4, new FarmerGoal(this));
		this.goalSelector.addGoal(4, new GuardGoal(this));
		this.goalSelector.addGoal(4, new HaulerGoal(this));
		this.goalSelector.addGoal(4, new BuilderGoal(this));
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

	public BotRole getRole() {
		return BotRole.byId(this.entityData.get(DATA_ROLE));
	}

	/** Sets the role on a player's behalf; the colony will not change it. */
	public void setRole(BotRole role) {
		this.changeRole(role, true);
	}

	/** Sets the role on the colony's behalf; the colony may move the bot to other work later. */
	public void assignRole(BotRole role) {
		this.changeRole(role, false);
	}

	private void changeRole(BotRole role, boolean byPlayer) {
		this.entityData.set(DATA_ROLE, role.id());
		this.roleSetByPlayer = byPlayer;
		this.working = false;
		this.idleTicks = 0;
	}

	/** Hands the bot back to the colony: it keeps its role until the colony moves it to other work. */
	public void releaseRole() {
		this.roleSetByPlayer = false;
	}

	public boolean isRoleSetByPlayer() {
		return this.roleSetByPlayer;
	}

	/** Whether the bot's role has work in progress, such as a claimed job. */
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

	/** Records a scaffold block {@code role}'s goal placed and will take down while the bot has that role. */
	public void addScaffold(BlockPos pos, BotRole role) {
		this.scaffold.add(pos.immutable());
		this.scaffoldRole = role;
	}

	/** The role whose goal placed the scaffold in {@link #getScaffold}. */
	public BotRole getScaffoldRole() {
		return this.scaffoldRole;
	}

	/** Whether there is scaffold up that the bot's role did not place, such as after a role change, for whichever goal cleans that up. */
	public boolean hasLeftoverScaffold() {
		return !this.scaffold.isEmpty() && this.scaffoldRole != this.getRole();
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

	/** Logs of the tree the bot is felling, so other lumberjacks leave that tree alone. Empty when it is not felling one. */
	public List<BlockPos> getTree() {
		return this.tree;
	}

	/** Claims {@code logs}, taking the lowest of them as the tree's base. */
	public void setTree(List<BlockPos> logs) {
		this.setTree(lowest(logs), logs);
	}

	public void setTree(@Nullable BlockPos base, List<BlockPos> logs) {
		this.tree = List.copyOf(logs);
		this.treeBase = logs.isEmpty() ? null : base;
	}

	/** Where the claimed tree stood on the ground, or null when not felling one. */
	public @Nullable BlockPos getTreeBase() {
		return this.treeBase;
	}

	private static @Nullable BlockPos lowest(List<BlockPos> logs) {
		return logs.stream().min(Comparator.comparingInt(BlockPos::getY)).orElse(null);
	}

	/**
	 * Trees this bot felled without the saplings to replant, by base, oldest first. Saved with the bot;
	 * callers change it in place.
	 */
	public Map<BlockPos, Revisit> getRevisits() {
		return this.revisits;
	}

	/**
	 * Trees this bot left logs of out of reach, by base, oldest first, to retry later or given up on.
	 * Saved with the bot; callers change it in place. Colony bots keep these on the job board instead.
	 */
	public Map<BlockPos, TreeRetry> getTreeRetries() {
		return this.treeRetries;
	}

	/** The storage block this bot brings its work to, usually a chest. */
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
		output.putString("Role", this.entityData.get(DATA_ROLE));
		output.putBoolean("RoleSetByPlayer", this.roleSetByPlayer);
		output.storeNullable("Home", BlockPos.CODEC, this.home);
		output.storeNullable("Colony", UUIDUtil.CODEC, this.colonyId);
		output.store("Scaffold", BlockPos.CODEC.listOf(), List.copyOf(this.scaffold));
		output.putString("ScaffoldRole", this.scaffoldRole.id());
		output.store("Tree", BlockPos.CODEC.listOf(), this.tree);
		output.storeNullable("TreeBase", BlockPos.CODEC, this.treeBase);
		output.store("Revisits", Revisit.CODEC.listOf(), List.copyOf(this.revisits.values()));
		output.store("TreeRetries", TreeRetry.CODEC.listOf(), List.copyOf(this.treeRetries.values()));
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		this.readInventoryFromTag(input);
		this.walkTarget = input.read("WalkTarget", BlockPos.CODEC).orElse(null);
		this.entityData.set(DATA_ROLE, BotRole.byId(input.getStringOr("Role", BotRole.NONE.id())).id());
		// Bots saved before this flag existed keep their role.
		this.roleSetByPlayer = input.getBooleanOr("RoleSetByPlayer", true);
		this.home = input.read("Home", BlockPos.CODEC).orElse(null);
		this.colonyId = input.read("Colony", UUIDUtil.CODEC).orElse(null);
		this.scaffold.clear();
		input.read("Scaffold", BlockPos.CODEC.listOf()).ifPresent(this.scaffold::addAll);
		this.scaffoldRole = BotRole.byId(input.getStringOr("ScaffoldRole", BotRole.LUMBERJACK.id()));
		List<BlockPos> claim = input.read("Tree", BlockPos.CODEC.listOf()).orElse(List.of());
		// Claims saved before the base was saved were the whole tree, so its lowest log is the base.
		this.setTree(input.read("TreeBase", BlockPos.CODEC).orElseGet(() -> lowest(claim)), claim);
		this.revisits.clear();
		input.read("Revisits", Revisit.CODEC.listOf())
			.ifPresent(list -> list.forEach(revisit -> this.revisits.put(revisit.pos(), revisit)));
		this.treeRetries.clear();
		input.read("TreeRetries", TreeRetry.CODEC.listOf())
			.ifPresent(list -> list.forEach(retry -> this.treeRetries.put(retry.base(), retry)));
	}
}
