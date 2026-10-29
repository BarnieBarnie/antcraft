package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.script.WorkApi;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * What a bot does with its hands, as a player would: hold, place, use, drop and pick up items. Placing and using go
 * through the game's own code with a {@link FakePlayer} standing where the bot stands and holding what it holds, so blocks
 * and items behave as for a player (a bucket fills, a hoe wears down, spawn protection applies).
 */
public final class BotHands {
	/** How long, in ticks, an item a bot drops lies before anyone may pick it up; a player's toss. */
	private static final int DROP_PICKUP_DELAY = 40;
	private static final List<EnumProperty<Direction>> FACINGS = List.of(
		BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING, BlockStateProperties.FACING_HOPPER,
		BlockStateProperties.VERTICAL_DIRECTION);

	private BotHands() {
	}

	/**
	 * How many ticks a bot waits between blows with what it holds: a player's attack cooldown, so 5 bare-handed, 13 with a
	 * sword and 20 to 25 with an axe.
	 */
	public static int strikeTicks(AntBot bot) {
		return strikeTicks(attackSpeed(bot.getMainHandItem()));
	}

	/** How many ticks pass between blows at {@code attackSpeed} blows a second, a part tick waited out in full. */
	static int strikeTicks(double attackSpeed) {
		return Mth.ceil(20 / attackSpeed);
	}

	/**
	 * The attack speed of {@code held}, in blows a second, as a player holding it has: the attribute's default (a bare
	 * hand's 4) with the item's modifiers. The bot itself has no such attribute, as mobs do not.
	 */
	static double attackSpeed(ItemStack held) {
		AttributeInstance speed = new AttributeInstance(Attributes.ATTACK_SPEED, instance -> {
		});
		held.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
			if (attribute.equals(Attributes.ATTACK_SPEED)) {
				speed.addTransientModifier(modifier);
			}
		});
		return speed.getValue();
	}

	/** Whether {@code target} is in the bot's melee reach and in its sight, so a blow would land. */
	public static boolean inStrikingReach(AntBot bot, LivingEntity target) {
		return bot.isWithinMeleeAttackRange(target) && bot.getSensing().hasLineOfSight(target);
	}

	/**
	 * Swings the held item at {@code target}, as a mob strikes: its damage is the bot's attack damage with what it holds. A
	 * blow that lands with a sword also sweeps ({@link #sweep}); a player's critical hit and sprint knockback a bot never
	 * has (docs/reference/combat/attack.md). A weapon put in the hand this tick counts, as for a player: the game only
	 * applies a held item's attribute modifiers on the bot's next tick, so the blow brings them up to date first (#425).
	 */
	public static void strike(AntBot bot, ServerLevel level, LivingEntity target) {
		bot.detectEquipmentUpdates();
		bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
		if (bot.doHurtTarget(level, target) && bot.getMainHandItem().is(ItemTags.SWORDS)) {
			sweep(bot, level, target);
		}
	}

	/**
	 * A fully charged player's sword sweep (Player.doSweepAttack): each living entity within a block of {@code target} and 3
	 * of the bot takes 1 damage plus the sword's sweeping edge share of the bot's attack damage, enchanted, and is knocked
	 * back. It spares what {@code attack} may not hit ({@link WorkApi#mayAttack}), such as players, bots and named animals.
	 */
	private static void sweep(AntBot bot, ServerLevel level, LivingEntity target) {
		ItemStack sword = bot.getMainHandItem();
		AttributeInstance ratio = new AttributeInstance(Attributes.SWEEPING_DAMAGE_RATIO, instance -> {
		});
		EnchantmentHelper.forEachModifier(sword, EquipmentSlot.MAINHAND, (attribute, modifier) -> {
			if (attribute.equals(Attributes.SWEEPING_DAMAGE_RATIO)) {
				ratio.addTransientModifier(modifier);
			}
		});
		float damage = 1 + (float) (ratio.getValue() * bot.getAttributeValue(Attributes.ATTACK_DAMAGE));
		DamageSource source = bot.damageSources().mobAttack(bot);
		float yaw = bot.getYRot() * Mth.DEG_TO_RAD;
		for (LivingEntity near : level.getEntitiesOfClass(LivingEntity.class, target.getBoundingBox().inflate(1, 0.25, 1))) {
			if (near == bot || near == target || bot.isAlliedTo(near) || near instanceof ArmorStand stand && stand.isMarker()
				|| bot.distanceToSqr(near) >= 9 || !WorkApi.mayAttack(near)) {
				continue;
			}
			float dealt = EnchantmentHelper.modifyDamage(level, sword, near, source, damage);
			if (near.hurtServer(level, source, dealt)) {
				near.knockback(0.4, Mth.sin(yaw), -Mth.cos(yaw), source, dealt);
				EnchantmentHelper.doPostAttackEffects(level, near, source);
			}
		}
		level.playSound(null, bot.getX(), bot.getY(), bot.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, bot.getSoundSource(), 1, 1);
		level.sendParticles(ParticleTypes.SWEEP_ATTACK, bot.getX() - Mth.sin(yaw), bot.getY(0.5), bot.getZ() + Mth.cos(yaw), 0,
			-Mth.sin(yaw), 0, Mth.cos(yaw), 0);
	}

	/** The first stack {@code item} matches that the bot carries, its held one first; null if it carries none. */
	public static @Nullable ItemStack find(AntBot bot, Predicate<ItemStack> item) {
		if (item.test(bot.getMainHandItem())) {
			return bot.getMainHandItem();
		}
		for (ItemStack stack : bot.getInventory().getItems()) {
			if (!stack.isEmpty() && item.test(stack)) {
				return stack;
			}
		}
		return null;
	}

	/** How many of the items {@code item} matches the bot carries, held and in its inventory. */
	public static long count(AntBot bot, Predicate<ItemStack> item) {
		long count = item.test(bot.getMainHandItem()) ? bot.getMainHandItem().getCount() : 0;
		for (ItemStack stack : bot.getInventory().getItems()) {
			if (!stack.isEmpty() && item.test(stack)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/**
	 * What the bot carries as one container: its inventory, then its hand as the last slot. Taking from it takes the
	 * inventory's items first; adding to it fills the hand only when the inventory has no room.
	 */
	public static Container carried(AntBot bot) {
		return new CompoundContainer(bot.getInventory(), new Hand(bot));
	}

	/** The bot's main hand as a container of one slot. */
	private record Hand(AntBot bot) implements Container {
		@Override
		public int getContainerSize() {
			return 1;
		}

		@Override
		public boolean isEmpty() {
			return this.bot.getMainHandItem().isEmpty();
		}

		@Override
		public ItemStack getItem(int slot) {
			return this.bot.getMainHandItem();
		}

		@Override
		public ItemStack removeItem(int slot, int count) {
			ItemStack taken = this.bot.getMainHandItem().split(count);
			if (this.bot.getMainHandItem().isEmpty()) {
				this.setItem(slot, ItemStack.EMPTY);
			}
			return taken;
		}

		@Override
		public ItemStack removeItemNoUpdate(int slot) {
			ItemStack held = this.bot.getMainHandItem();
			this.setItem(slot, ItemStack.EMPTY);
			return held;
		}

		@Override
		public void setItem(int slot, ItemStack stack) {
			this.bot.setItemSlot(EquipmentSlot.MAINHAND, stack);
		}

		@Override
		public void setChanged() {
		}

		@Override
		public boolean stillValid(Player player) {
			return true;
		}

		@Override
		public void clearContent() {
			this.setItem(0, ItemStack.EMPTY);
		}
	}

	/** Puts a stack {@code item} matches in the bot's hand, the held item going to its slot; false if it carries none. */
	public static boolean equip(AntBot bot, Predicate<ItemStack> item) {
		ItemStack hand = bot.getMainHandItem();
		if (item.test(hand)) {
			return true;
		}
		SimpleContainer inventory = bot.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (!stack.isEmpty() && item.test(stack)) {
				bot.setItemSlot(EquipmentSlot.MAINHAND, stack);
				inventory.setItem(i, hand);
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the bot may not change the block at {@code pos}: outside the world's build height or border, or under the
	 * server's spawn protection.
	 */
	public static boolean isProtected(ServerLevel level, BlockPos pos) {
		return !level.isInWorldBounds(pos) || !level.mayInteract(FakePlayer.get(level), pos);
	}

	/**
	 * Places the held block item at {@code pos}, which must be air or another block that may be replaced, turned to
	 * {@code facing} if given and the block can face that way, and to {@code half} if given and it has one (a trapdoor,
	 * stairs or a slab). False when it cannot be placed there, such as where an
	 * entity stands or where the block cannot stay.
	 */
	public static boolean place(AntBot bot, BlockPos pos, @Nullable Direction facing, @Nullable Half half) {
		if (!(bot.getMainHandItem().getItem() instanceof BlockItem item)) {
			return false;
		}
		ServerLevel level = (ServerLevel) bot.level();
		return withHand(bot, pos, player -> {
			BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
			BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, player.getMainHandItem(), hit);
			if (!context.getClickedPos().equals(pos) || !item.place(context).consumesAction()) {
				return false;
			}
			turn(level, pos, facing, half);
			return true;
		});
	}

	/**
	 * Turns the block at {@code pos} to {@code facing}, if it has a facing or axis that can take it, and to {@code half}, if
	 * it has a half, where it stays so.
	 */
	private static void turn(ServerLevel level, BlockPos pos, @Nullable Direction facing, @Nullable Half half) {
		BlockState placed = level.getBlockState(pos);
		BlockState state = placed;
		if (half != null && state.hasProperty(BlockStateProperties.HALF)) {
			state = state.setValue(BlockStateProperties.HALF, half);
		}
		if (half != null && state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
			state = state.setValue(BlockStateProperties.SLAB_TYPE, half == Half.TOP ? SlabType.TOP : SlabType.BOTTOM);
		}
		for (EnumProperty<Direction> property : FACINGS) {
			if (facing != null && state.hasProperty(property) && property.getPossibleValues().contains(facing)) {
				state = state.setValue(property, facing);
			}
		}
		for (EnumProperty<Direction.Axis> property : List.of(BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS)) {
			if (facing != null && state.hasProperty(property) && property.getPossibleValues().contains(facing.getAxis())) {
				state = state.setValue(property, facing.getAxis());
			}
		}
		if (state != placed && state.canSurvive(level, pos)) {
			level.setBlockAndUpdate(pos, state);
		}
	}

	/**
	 * Uses the held item on the block at {@code pos}, or with an empty hand the block itself, as a player's right click
	 * does: on the block first, then the item on its own (a bucket). False when that did nothing.
	 */
	public static boolean use(AntBot bot, BlockPos pos) {
		ServerLevel level = (ServerLevel) bot.level();
		return withHand(bot, pos, player -> {
			BlockHitResult hit = aim(level, player, pos);
			InteractionResult result = player.gameMode.useItemOn(player, level, player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
			if (result.consumesAction()) {
				return true;
			}
			ItemStack held = player.getMainHandItem();
			return !held.isEmpty() && player.gameMode.useItem(player, level, held, InteractionHand.MAIN_HAND).consumesAction();
		});
	}

	/** Where the player's look at the centre of {@code pos} meets the block, or its face turned to the player. */
	private static BlockHitResult aim(ServerLevel level, FakePlayer player, BlockPos pos) {
		Vec3 eyes = player.getEyePosition();
		Vec3 center = Vec3.atCenterOf(pos);
		BlockHitResult hit = level.clip(new ClipContext(eyes, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
			return hit;
		}
		Vec3 toEyes = eyes.subtract(center);
		return new BlockHitResult(center, Direction.getApproximateNearest(toEyes.x, toEyes.y, toEyes.z), pos, false);
	}

	/**
	 * Runs {@code action} with the server's fake player standing where the bot stands, looking at {@code pos} and holding
	 * the bot's held stack; then what the player holds goes back to the bot's hand, and what else it got (a filled bucket
	 * from a stack of empty ones) to the bot's inventory, or to the ground beside it if that is full.
	 */
	private static boolean withHand(AntBot bot, BlockPos pos, Function<FakePlayer, Boolean> action) {
		ServerLevel level = (ServerLevel) bot.level();
		FakePlayer player = FakePlayer.get(level);
		player.snapTo(bot.getX(), bot.getY(), bot.getZ(), bot.getYRot(), bot.getXRot());
		player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
		Inventory inventory = player.getInventory();
		inventory.clearContent();
		player.setItemInHand(InteractionHand.MAIN_HAND, bot.getMainHandItem());
		boolean done;
		try {
			done = action.apply(player);
		} finally {
			bot.setItemSlot(EquipmentSlot.MAINHAND, player.getMainHandItem());
			player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
			for (int i = 0; i < inventory.getContainerSize(); i++) {
				ItemStack rest = bot.getInventory().addItem(inventory.removeItemNoUpdate(i));
				if (!rest.isEmpty()) {
					bot.spawnAtLocation(level, rest);
				}
			}
		}
		if (done) {
			bot.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
			bot.getLookControl().setLookAt(Vec3.atCenterOf(pos));
		}
		return done;
	}

	/**
	 * Drops up to {@code n} of the items {@code item} matches, the held ones first, tossed ahead as a player tosses them;
	 * no bot picks them up by itself (see {@link AntBot}). Returns how many it dropped.
	 */
	public static long drop(AntBot bot, Predicate<ItemStack> item, long n) {
		long left = n;
		left -= drop(bot, bot.getMainHandItem(), item, left);
		SimpleContainer inventory = bot.getInventory();
		for (int i = 0; i < inventory.getContainerSize() && left > 0; i++) {
			left -= drop(bot, inventory.getItem(i), item, left);
		}
		inventory.setChanged();
		return n - left;
	}

	private static int drop(AntBot bot, ItemStack stack, Predicate<ItemStack> item, long n) {
		if (n <= 0 || stack.isEmpty() || !item.test(stack)) {
			return 0;
		}
		ItemStack dropped = stack.split((int) Math.min(n, stack.getCount()));
		ItemEntity entity = new ItemEntity(bot.level(), bot.getX(), bot.getEyeY() - 0.3, bot.getZ(), dropped);
		entity.setDeltaMovement(bot.getLookAngle().scale(0.3).add(0, 0.1, 0));
		entity.setPickUpDelay(DROP_PICKUP_DELAY);
		entity.setThrower(bot);
		bot.level().addFreshEntity(entity);
		return dropped.getCount();
	}

	/** Takes what fits of the dropped {@code entity} into the bot's inventory, once it may be picked up. */
	public static void collect(AntBot bot, ItemEntity entity) {
		if (entity.isAlive() && !entity.hasPickUpDelay()) {
			InventoryCarrier.pickUpItem((ServerLevel) bot.level(), bot, bot, entity);
		}
	}
}
