package io.github.barniebarnie.antcraft.pathing;

import io.github.barniebarnie.antcraft.colony.ColonyManager;
import io.github.barniebarnie.antcraft.entity.Tools;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** {@link WorldView} backed by a live level. Break times assume the bot uses its best tool for each block. */
public class LevelWorldView implements WorldView {
	private final Level level;
	private final SimpleContainer inventory;
	private final ItemStack hand;
	private final Set<BlockPos> keep;
	/** Where colonies want no scaffold, looked up once per view, the first time a path asks. */
	private @Nullable Predicate<BlockPos> keepOpen;

	/** {@code keep} are blocks never to break, such as scaffolding other bots placed. */
	public LevelWorldView(Level level, SimpleContainer inventory, ItemStack hand, Set<BlockPos> keep) {
		this.level = level;
		this.inventory = inventory;
		this.hand = hand;
		this.keep = keep;
	}

	/** Paths place scaffold anywhere but where a colony keeps blocks open (see {@link ColonyManager#keepOpen}), whatever work the bot is on. */
	@Override
	public boolean mayPlace(BlockPos pos) {
		if (this.keepOpen == null) {
			this.keepOpen = ColonyManager.keepOpen(this.level);
		}
		return !this.keepOpen.test(pos);
	}

	@Override
	public boolean isPassable(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		return state.getCollisionShape(this.level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	@Override
	public boolean canStandOn(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		// Farmland is a little lower than a full block, and a stair half a block lower at the front, but both are walkable.
		return state.isFaceSturdy(this.level, pos, Direction.UP) || state.is(Blocks.FARMLAND) || state.getBlock() instanceof StairBlock;
	}

	@Override
	public boolean stairUp(BlockPos pos, Direction dir) {
		return stairUp(this.level.getBlockState(pos), dir);
	}

	/**
	 * A bot moving {@code dir} walks up onto this stair without jumping when the top half of the half block it
	 * enters by is empty in the vanilla collision shape: the low front of a straight stair, either low side of an
	 * outer corner, and never an inner corner or an upside-down stair, which are tall on every side.
	 */
	static boolean stairUp(BlockState state, Direction dir) {
		if (!(state.getBlock() instanceof StairBlock)) {
			return false;
		}
		// The entry half: the half of the block away from dir, from y 0.5 up.
		double minX = dir == Direction.WEST ? 0.5 : 0, maxX = dir == Direction.EAST ? 0.5 : 1;
		double minZ = dir == Direction.NORTH ? 0.5 : 0, maxZ = dir == Direction.SOUTH ? 0.5 : 1;
		VoxelShape entry = Shapes.box(minX, 0.5, minZ, maxX, 1, maxZ);
		return !Shapes.joinIsNotEmpty(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO), entry, BooleanOp.AND);
	}

	@Override
	public boolean isBedrock(BlockPos pos) {
		return this.level.getBlockState(pos).is(Blocks.BEDROCK);
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

	/** Water a bot can swim through: no collision box, and not a bubble column, which drags it along. */
	@Override
	public boolean isWater(BlockPos pos) {
		BlockState state = this.level.getBlockState(pos);
		return state.getFluidState().is(FluidTags.WATER) && !state.is(Blocks.BUBBLE_COLUMN) && state.getCollisionShape(this.level, pos).isEmpty();
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
