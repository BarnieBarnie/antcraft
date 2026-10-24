package io.github.barniebarnie.antcraft.pathing;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A {@link WorldView} that asks the world about each block once per question and remembers the answer, for one search
 * (see {@link PathFinder#find}): the search asks about the same blocks again and again from neighbouring nodes, and on
 * a live level each question looks up the chunk, the block's tags and the colonies' protected blocks. The world must
 * not change while the view is used, which holds within a server tick.
 */
final class CachedWorldView implements WorldView {
	private static final int NO = 1;
	private static final int YES = 2;
	/** The yes-or-no questions about a block, each in two bits of its entry (0 not asked yet). */
	private static final int PASSABLE = 0;
	private static final int STAND_ON = 1;
	private static final int HAZARD = 2;
	private static final int WATER = 3;
	private static final int CLIMBABLE = 4;
	private static final int BEDROCK = 5;
	private static final int MAY_PLACE = 6;
	/** {@code canOpen} and {@code stairUp}, one per horizontal direction. */
	private static final int CAN_OPEN = 7;
	private static final int STAIR_UP = 11;

	private final WorldView world;
	/** Per block, the answers so far, two bits per question. */
	private final Long2IntOpenHashMap answers = new Long2IntOpenHashMap();
	private final Long2FloatOpenHashMap breakTicks = new Long2FloatOpenHashMap();
	/** The block asked about last and its answers, as a search asks several questions about a block in a row. */
	private long lastKey = Long.MIN_VALUE;
	private int lastKnown;
	/** The world's questions, made once rather than per question asked. */
	private final Predicate<BlockPos> passable;
	private final Predicate<BlockPos> standOn;
	private final Predicate<BlockPos> hazard;
	private final Predicate<BlockPos> water;
	private final Predicate<BlockPos> climbable;
	private final Predicate<BlockPos> bedrock;
	private final Predicate<BlockPos> mayPlace;
	/** {@code canOpen} and {@code stairUp}, by {@link Direction#get2DDataValue}. */
	private final List<Predicate<BlockPos>> canOpen;
	private final List<Predicate<BlockPos>> stairUp;

	CachedWorldView(WorldView world) {
		this.world = world;
		this.breakTicks.defaultReturnValue(Float.NaN);
		this.passable = world::isPassable;
		this.standOn = world::canStandOn;
		this.hazard = world::isHazard;
		this.water = world::isWater;
		this.climbable = world::isClimbable;
		this.bedrock = world::isBedrock;
		this.mayPlace = world::mayPlace;
		this.canOpen = Direction.Plane.HORIZONTAL.stream().sorted((a, b) -> a.get2DDataValue() - b.get2DDataValue())
			.<Predicate<BlockPos>>map(dir -> pos -> world.canOpen(pos, dir)).toList();
		this.stairUp = Direction.Plane.HORIZONTAL.stream().sorted((a, b) -> a.get2DDataValue() - b.get2DDataValue())
			.<Predicate<BlockPos>>map(dir -> pos -> world.stairUp(pos, dir)).toList();
	}

	/** The answer to {@code question} about {@code pos}, asking the world the first time. */
	private boolean answer(BlockPos pos, int question, Predicate<BlockPos> ask) {
		long key = pos.asLong();
		int known = key == this.lastKey ? this.lastKnown : this.answers.get(key);
		int shift = 2 * question;
		int bits = known >>> shift & 3;
		if (bits == 0) {
			bits = ask.test(pos) ? YES : NO;
			known |= bits << shift;
			this.answers.put(key, known);
		}
		this.lastKey = key;
		this.lastKnown = known;
		return bits == YES;
	}

	@Override
	public boolean isPassable(BlockPos pos) {
		return this.answer(pos, PASSABLE, this.passable);
	}

	@Override
	public boolean canStandOn(BlockPos pos) {
		return this.answer(pos, STAND_ON, this.standOn);
	}

	@Override
	public boolean isHazard(BlockPos pos) {
		return this.answer(pos, HAZARD, this.hazard);
	}

	@Override
	public boolean isWater(BlockPos pos) {
		return this.answer(pos, WATER, this.water);
	}

	@Override
	public float breakTicks(BlockPos pos) {
		long key = pos.asLong();
		float ticks = this.breakTicks.get(key);
		if (Float.isNaN(ticks)) {
			ticks = this.world.breakTicks(pos);
			this.breakTicks.put(key, ticks);
		}
		return ticks;
	}

	@Override
	public boolean canOpen(BlockPos pos, Direction dir) {
		if (dir.getAxis().isVertical()) {
			return this.world.canOpen(pos, dir);
		}
		return this.answer(pos, CAN_OPEN + dir.get2DDataValue(), this.canOpen.get(dir.get2DDataValue()));
	}

	@Override
	public boolean isClimbable(BlockPos pos) {
		return this.answer(pos, CLIMBABLE, this.climbable);
	}

	@Override
	public boolean stairUp(BlockPos pos, Direction dir) {
		if (dir.getAxis().isVertical()) {
			return this.world.stairUp(pos, dir);
		}
		return this.answer(pos, STAIR_UP + dir.get2DDataValue(), this.stairUp.get(dir.get2DDataValue()));
	}

	@Override
	public boolean isBedrock(BlockPos pos) {
		return this.answer(pos, BEDROCK, this.bedrock);
	}

	@Override
	public boolean mayPlace(BlockPos pos) {
		return this.answer(pos, MAY_PLACE, this.mayPlace);
	}
}
