package io.github.barniebarnie.antcraft.entity;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
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
	 * {@code facing} if given and the block can face that way. False when it cannot be placed there, such as where an
	 * entity stands or where the block cannot stay.
	 */
	public static boolean place(AntBot bot, BlockPos pos, @Nullable Direction facing) {
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
			if (facing != null) {
				turn(level, pos, facing);
			}
			return true;
		});
	}

	/** Turns the block at {@code pos} to {@code facing}, if it has a facing or axis that can take it and stays where it is. */
	private static void turn(ServerLevel level, BlockPos pos, Direction facing) {
		BlockState placed = level.getBlockState(pos);
		BlockState state = placed;
		for (EnumProperty<Direction> property : FACINGS) {
			if (state.hasProperty(property) && property.getPossibleValues().contains(facing)) {
				state = state.setValue(property, facing);
			}
		}
		for (EnumProperty<Direction.Axis> property : List.of(BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS)) {
			if (state.hasProperty(property) && property.getPossibleValues().contains(facing.getAxis())) {
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
	 * the bot does not pick them up again by itself (see {@link AntBot}). Returns how many it dropped.
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
