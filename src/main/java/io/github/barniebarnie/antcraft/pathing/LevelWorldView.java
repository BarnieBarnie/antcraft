package io.github.barniebarnie.antcraft.pathing;

import io.github.barniebarnie.antcraft.entity.Tools;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.VineBlock;
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
	private Predicate<BlockPos> mayPlace = pos -> true;
	private Predicate<BlockPos> kept = pos -> false;
	/** The level's blocks, looked up fast (see {@link LoadedBlocks}). */
	private final LoadedBlocks blocks;
	private float jumpBoost;
	private Predicate<ItemStack> scaffold = stack -> false;
	/** Per block state, whether it drops a scaffold block (see {@link #dropsScaffold}). */
	private final Map<BlockState, Boolean> dropsScaffold = new HashMap<>();

	/** {@code keep} are blocks never to break, such as scaffolding other bots placed. */
	public LevelWorldView(Level level, SimpleContainer inventory, ItemStack hand, Set<BlockPos> keep) {
		this.level = level;
		this.inventory = inventory;
		this.hand = hand;
		this.keep = keep;
		this.blocks = new LoadedBlocks(level);
	}

	/** Paths place scaffold only where {@code mayPlace} says, such as nowhere a build puts a block. */
	public LevelWorldView placingOnly(Predicate<BlockPos> mayPlace) {
		this.mayPlace = mayPlace;
		return this;
	}

	/** Jumps are planned with this added to their speed: the bot's Jump Boost, its {@code getJumpBoostPower} (#665). */
	public LevelWorldView jumpBoost(float boost) {
		this.jumpBoost = boost;
		return this;
	}

	/** The items the bot places as scaffold, so a block whose drop is one counts back (see {@link #dropsScaffold}). */
	public LevelWorldView scaffold(Predicate<ItemStack> scaffold) {
		this.scaffold = scaffold;
		return this;
	}

	/**
	 * Paths never break a block {@code kept} says, besides those kept already, such as one a build has in place, nor put
	 * scaffold there, such as in a farm cell's water (#869).
	 */
	public LevelWorldView keeping(Predicate<BlockPos> kept) {
		this.kept = kept;
		return this;
	}

	@Override
	public boolean mayPlace(BlockPos pos) {
		return this.mayPlace.test(pos) && !this.kept.test(pos) && !isPlant(this.blocks.get(pos));
	}

	/**
	 * A crop, sapling, sugar cane, sweet berry bush, nether wart, mushroom or kelp, such as a farm grows: paths treat it
	 * as air (kelp as water) but never put scaffold in its place, so they go round it (#865, #870). Grass and flowers are
	 * not: wild terrain is full of them, and scaffold breaks them with their drops (#608). Kelp is under water, which
	 * bridges never cross, so only a pillar from the kelp bed would replace it.
	 */
	static boolean isPlant(BlockState state) {
		return state.is(BlockTags.CROPS) || state.is(BlockTags.SAPLINGS) || state.is(Blocks.SUGAR_CANE)
			|| state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.NETHER_WART)
			|| state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.RED_MUSHROOM)
			|| state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT);
	}

	@Override
	public boolean isPassable(BlockPos pos) {
		BlockState state = this.blocks.get(pos);
		return state.getCollisionShape(this.level, pos).isEmpty() && state.getFluidState().isEmpty();
	}

	@Override
	public boolean canStandOn(BlockPos pos) {
		BlockState state = this.blocks.get(pos);
		if (decays(state)) {
			return false;
		}
		// A stair is half a block lower at the front, but walkable.
		return state.isFaceSturdy(this.level, pos, Direction.UP) || state.getBlock() instanceof StairBlock
			|| isLowFloor(state.getCollisionShape(this.level, pos));
	}

	/**
	 * Leaves with no log in range and not placed by hand, which vanilla decays at a random tick: no floor to stand on or
	 * land on. Felling a giant jungle, a bot walked on leaves that decayed under it once the trunk was gone (#673).
	 */
	public static boolean decays(BlockState state) {
		return state.getBlock() instanceof LeavesBlock && !state.getValue(LeavesBlock.PERSISTENT)
			&& state.getValue(LeavesBlock.DISTANCE) == LeavesBlock.DECAY_DISTANCE;
	}

	@Override
	public float floorTop(BlockPos pos) {
		VoxelShape shape = this.blocks.get(pos).getCollisionShape(this.level, pos);
		return isLowFloor(shape) ? (float) shape.max(Direction.Axis.Y) : 1;
	}

	@Override
	public float jumpTop(BlockPos pos) {
		return this.floorTop(pos) + (float) WorldView.jumpHeight(this.blocks.get(pos).getBlock().getJumpFactor(), this.jumpBoost);
	}

	/**
	 * A block lower than a full one that is as wide as one, give or take a sixteenth, such as farmland, a dirt path, a
	 * chest, a bed, snow two layers deep or more, or a bottom slab (#642): walked on as on a full block, but one lower than
	 * three quarters of a block is too low to jump a block up from ({@link #jumpTop}, #647). A bot standing on one
	 * is in the block over it, as paths count it (see {@code PathFollower.feet}).
	 */
	public static boolean isLowFloor(VoxelShape shape) {
		if (shape.isEmpty()) {
			return false;
		}
		double edge = 1 / 16.0 + 1e-6;
		return shape.max(Direction.Axis.Y) <= 1 && shape.min(Direction.Axis.X) <= edge && shape.max(Direction.Axis.X) >= 1 - edge
			&& shape.min(Direction.Axis.Z) <= edge && shape.max(Direction.Axis.Z) >= 1 - edge;
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
	public int climbFaces(BlockPos pos) {
		return climbFaces(this.blocks.get(pos));
	}

	/**
	 * A ladder's block behind it, a vine's faces, as bits by {@link Direction#get3DDataValue}; up for weeping and cave
	 * vines, which hang from the block above, down for twisting vines and scaffolding, which stand on the block below,
	 * or the sides for overhanging scaffolding (#752). 0 for a hatch, climbed only from the ladder under it.
	 */
	// ponytail: overhanging scaffolding holds on to any block beside it, not only scaffolding nearer its support.
	static int climbFaces(BlockState state) {
		if (state.getBlock() instanceof LadderBlock) {
			return 1 << state.getValue(LadderBlock.FACING).getOpposite().get3DDataValue();
		}
		if (state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT) || state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)) {
			return 1 << Direction.UP.get3DDataValue();
		}
		if (state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT)
			|| state.getBlock() instanceof ScaffoldingBlock && !state.getValue(ScaffoldingBlock.BOTTOM)) {
			return 1 << Direction.DOWN.get3DDataValue();
		}
		if (state.getBlock() instanceof ScaffoldingBlock) {
			int sides = 0;
			for (Direction dir : Direction.Plane.HORIZONTAL) {
				sides |= 1 << dir.get3DDataValue();
			}
			return sides;
		}
		int faces = 0;
		if (state.getBlock() instanceof VineBlock) {
			for (var face : VineBlock.PROPERTY_BY_DIRECTION.entrySet()) {
				if (state.getValue(face.getValue())) {
					faces |= 1 << face.getKey().get3DDataValue();
				}
			}
		}
		return faces;
	}

	@Override
	public boolean isVine(BlockPos pos) {
		return this.blocks.get(pos).getBlock() instanceof VineBlock;
	}

	@Override
	public boolean isBedrock(BlockPos pos) {
		return this.blocks.get(pos).is(Blocks.BEDROCK);
	}

	@Override
	public boolean isHazard(BlockPos pos) {
		return hazard(this.blocks.get(pos));
	}

	/** A block a bot is hurt in or on, or a fluid or portal it should not stand in by chance (see {@link #isHazard}). */
	public static boolean hazard(BlockState state) {
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
	public boolean isPrickly(BlockPos pos) {
		return this.blocks.get(pos).is(Blocks.CACTUS);
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
		if (state.hasBlockEntity() || this.keep.contains(pos) || this.kept.test(pos) || isBuilt(state)) {
			return -1;
		}
		return breakTicks(state, this.level, pos, Tools.bestToolFor(this.inventory, this.hand, state));
	}

	/** Its drops broken with the bot's best tool for it, as the follower breaks it, hold a scaffold block (#771). */
	// ponytail: one roll of its loot per block state, so gravel's flint or not is decided by the first ask.
	@Override
	public boolean dropsScaffold(BlockPos pos) {
		return this.dropsScaffold.computeIfAbsent(this.blocks.get(pos), state -> {
			ItemStack tool = Tools.bestToolFor(this.inventory, this.hand, state);
			return this.level instanceof ServerLevel server && Tools.canHarvest(tool, state)
				&& Block.getDrops(state, server, pos, null, null, tool).stream().anyMatch(this.scaffold);
		});
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
