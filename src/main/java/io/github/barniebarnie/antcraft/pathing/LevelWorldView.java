package io.github.barniebarnie.antcraft.pathing;

import io.github.barniebarnie.antcraft.entity.Tools;
import java.util.Set;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** {@link WorldView} backed by a live level. Break times assume the bot uses its best tool for each block. */
public class LevelWorldView implements WorldView {
	private final Level level;
	private final SimpleContainer inventory;
	private final ItemStack hand;
	private final Set<BlockPos> keep;
	/** The level's blocks, looked up fast (see {@link LoadedBlocks}). */
	private final LoadedBlocks blocks;

	/** {@code keep} are blocks never to break, such as scaffolding other bots placed. */
	public LevelWorldView(Level level, SimpleContainer inventory, ItemStack hand, Set<BlockPos> keep) {
		this.level = level;
		this.inventory = inventory;
		this.hand = hand;
		this.keep = keep;
		this.blocks = new LoadedBlocks(level);
	}

	@Override
	public boolean isPassable(BlockPos pos) {
		BlockState state = this.blocks.get(pos);
		return state.getCollisionShape(this.level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	@Override
	public boolean canStandOn(BlockPos pos) {
		BlockState state = this.blocks.get(pos);
		// Farmland is a little lower than a full block, and a stair half a block lower at the front, but both are walkable.
		return state.isFaceSturdy(this.level, pos, Direction.UP) || state.is(Blocks.FARMLAND) || state.getBlock() instanceof StairBlock;
	}

	@Override
	public boolean stairUp(BlockPos pos, Direction dir) {
		return stairUp(this.blocks.get(pos), dir);
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
	public boolean canOpen(BlockPos pos, Direction dir) {
		return canOpen(this.blocks.get(pos), dir);
	}

	/**
	 * A wooden door or a fence gate, open or shut, walked through along the way it faces. Iron doors stay shut, and
	 * trapdoors are climbed through instead (see {@link #isClimbable}).
	 */
	static boolean canOpen(BlockState state, Direction dir) {
		return isOpenable(state) && !(state.getBlock() instanceof TrapDoorBlock)
			&& state.getValue(HorizontalDirectionalBlock.FACING).getAxis() == dir.getAxis();
	}

	/** A wooden door, fence gate or trapdoor, which bots open by hand. */
	public static boolean isOpenable(BlockState state) {
		return DoorBlock.isWoodenDoor(state) || state.getBlock() instanceof FenceGateBlock || isHatch(state);
	}

	/** A trapdoor opened by hand: any but iron. */
	private static boolean isHatch(BlockState state) {
		return state.getBlock() instanceof TrapDoorBlock && !state.is(Blocks.IRON_TRAPDOOR);
	}

	/**
	 * A ladder, vine or scaffolding, which vanilla climbs inside (scaffolding is also stood on from above, and climbed
	 * down sneaking). Also a trapdoor opened by hand over a ladder facing the same way: vanilla climbs one that is open,
	 * and a bot opens it to climb through.
	 */
	@Override
	public boolean isClimbable(BlockPos pos) {
		BlockState state = this.blocks.get(pos);
		if (isHatch(state)) {
			BlockState below = this.blocks.get(pos.below());
			return below.is(Blocks.LADDER) && below.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING);
		}
		return state.is(BlockTags.CLIMBABLE);
	}

	@Override
	public boolean isBedrock(BlockPos pos) {
		return this.blocks.get(pos).is(Blocks.BEDROCK);
	}

	@Override
	public boolean isHazard(BlockPos pos) {
		BlockState state = this.blocks.get(pos);
		return !state.getFluidState().isEmpty()
			|| state.is(BlockTags.FIRE)
			|| state.is(Blocks.MAGMA_BLOCK)
			|| state.is(Blocks.CACTUS)
			|| state.is(Blocks.SWEET_BERRY_BUSH)
			|| state.is(Blocks.POWDER_SNOW)
			|| state.is(Blocks.CAMPFIRE)
			|| state.is(Blocks.SOUL_CAMPFIRE)
			|| state.is(Blocks.WITHER_ROSE)
			|| state.is(Blocks.NETHER_PORTAL);
	}

	@Override
	public boolean isPortal(BlockPos pos) {
		return this.blocks.get(pos).is(Blocks.NETHER_PORTAL);
	}

	/** Water a bot can swim through: no collision box, and not a bubble column, which drags it along. */
	@Override
	public boolean isWater(BlockPos pos) {
		BlockState state = this.blocks.get(pos);
		return state.getFluidState().is(FluidTags.WATER) && !state.is(Blocks.BUBBLE_COLUMN) && state.getCollisionShape(this.level, pos).isEmpty();
	}

	@Override
	public float breakTicks(BlockPos pos) {
		BlockState state = this.blocks.get(pos);
		// Leave containers, other block entities, blocks the caller keeps and what players build with alone.
		if (state.hasBlockEntity() || this.keep.contains(pos) || isBuilt(state)) {
			return -1;
		}
		return breakTicks(state, this.level, pos, Tools.bestToolFor(this.inventory, this.hand, state));
	}

	/**
	 * Blocks players build with to enclose or decorate, which paths go round rather than through: glass and panes,
	 * iron bars, fences, fence gates, walls, doors and trapdoors. Builders still break them for their plans (see BlockBreaker).
	 */
	static boolean isBuilt(BlockState state) {
		return state.is(ConventionalBlockTags.GLASS_BLOCKS)
			|| state.is(ConventionalBlockTags.GLASS_PANES)
			|| state.is(Blocks.IRON_BARS)
			|| state.is(BlockTags.FENCES)
			|| state.is(BlockTags.FENCE_GATES)
			|| state.is(BlockTags.WALLS)
			|| state.is(BlockTags.DOORS)
			|| state.is(BlockTags.TRAPDOORS);
	}

	public static int breakTicks(BlockState state, Level level, BlockPos pos, ItemStack tool) {
		boolean canHarvest = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
		return BreakTime.ticks(state.getDestroySpeed(level, pos), tool.getDestroySpeed(state), canHarvest);
	}
}
