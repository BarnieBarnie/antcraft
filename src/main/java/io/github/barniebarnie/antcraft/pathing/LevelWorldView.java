package io.github.barniebarnie.antcraft.pathing;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.entity.Tools;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** {@link WorldView} backed by a live level. Break times assume the bot uses its best tool for each block. */
public class LevelWorldView implements WorldView {
	private final Level level;
	private final SimpleContainer inventory;
	private final ItemStack hand;
	private final Set<BlockPos> keep;
	private final Predicate<BlockPos> mayPlace;

	/** {@code keep} are blocks never to break, such as scaffolding other bots placed. */
	public LevelWorldView(Level level, SimpleContainer inventory, ItemStack hand, Set<BlockPos> keep) {
		this(level, inventory, hand, keep, pos -> true);
	}

	/** Like {@link #LevelWorldView(Level, SimpleContainer, ItemStack, Set)}; paths place scaffold blocks only where {@code mayPlace} allows. */
	public LevelWorldView(Level level, SimpleContainer inventory, ItemStack hand, Set<BlockPos> keep, Predicate<BlockPos> mayPlace) {
		this.level = level;
		this.inventory = inventory;
		this.hand = hand;
		this.keep = keep;
		this.mayPlace = mayPlace;
	}

	@Override
	public boolean mayPlace(BlockPos pos) {
		return this.mayPlace.test(pos);
	}

	@Override
	public boolean isPassable(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		return state.getCollisionShape(this.level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	@Override
	public boolean canStandOn(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		// Farmland is a little lower than a full block, but walkable.
		return state.isFaceSturdy(this.level, pos, Direction.UP) || state.is(Blocks.FARMLAND);
	}

	@Override
	public boolean isHazard(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		return !state.getFluidState().isEmpty()
			|| state.is(BlockTags.FIRE)
			|| state.is(Blocks.MAGMA_BLOCK)
			|| state.is(Blocks.CACTUS)
			|| state.is(Blocks.SWEET_BERRY_BUSH)
			|| state.is(Blocks.POWDER_SNOW)
			|| state.is(Blocks.CAMPFIRE)
			|| state.is(Blocks.SOUL_CAMPFIRE)
			|| state.is(Blocks.WITHER_ROSE);
	}

	@Override
	public float breakTicks(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		// Leave containers, other block entities, blocks the caller keeps and what colony builders built alone.
		if (state.hasBlockEntity() || this.keep.contains(pos) || ColonyManager.isProtected(this.level, pos)) {
			return -1;
		}
		return breakTicks(state, this.level, pos, Tools.bestToolFor(this.inventory, this.hand, state));
	}

	public static int breakTicks(BlockState state, Level level, BlockPos pos, ItemStack tool) {
		boolean canHarvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		return BreakTime.ticks(state.getDestroySpeed(level, pos), tool.getDestroySpeed(state), canHarvest);
	}
}
