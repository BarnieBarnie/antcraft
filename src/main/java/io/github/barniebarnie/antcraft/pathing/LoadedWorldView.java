package io.github.barniebarnie.antcraft.pathing;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A view of only the loaded blocks of {@code world}: an unloaded block is a wall that cannot be broken, built on, opened
 * or entered, and is never read, so a plan over this view loads no chunks (see {@code reachable} in docs/reference/index.md).
 */
public final class LoadedWorldView implements WorldView {
	private final WorldView world;
	private final Predicate<BlockPos> loaded;

	public LoadedWorldView(WorldView world, Predicate<BlockPos> loaded) {
		this.world = world;
		this.loaded = loaded;
	}

	@Override
	public boolean isPassable(BlockPos pos) {
		return this.loaded.test(pos) && this.world.isPassable(pos);
	}

	@Override
	public boolean canStandOn(BlockPos pos) {
		return this.loaded.test(pos) && this.world.canStandOn(pos);
	}

	@Override
	public boolean isHazard(BlockPos pos) {
		return !this.loaded.test(pos) || this.world.isHazard(pos);
	}

	@Override
	public boolean isPortal(BlockPos pos) {
		return this.loaded.test(pos) && this.world.isPortal(pos);
	}

	@Override
	public boolean isWater(BlockPos pos) {
		return this.loaded.test(pos) && this.world.isWater(pos);
	}

	@Override
	public float breakTicks(BlockPos pos) {
		return this.loaded.test(pos) ? this.world.breakTicks(pos) : -1;
	}

	@Override
	public boolean canOpen(BlockPos pos, Direction dir) {
		return this.loaded.test(pos) && this.world.canOpen(pos, dir);
	}

	@Override
	public boolean isClimbable(BlockPos pos) {
		return this.loaded.test(pos) && this.loaded.test(pos.below()) && this.world.isClimbable(pos);
	}

	@Override
	public boolean stairUp(BlockPos pos, Direction dir) {
		return this.loaded.test(pos) && this.world.stairUp(pos, dir);
	}

	@Override
	public boolean isBedrock(BlockPos pos) {
		return this.loaded.test(pos) && this.world.isBedrock(pos);
	}

	@Override
	public boolean mayPlace(BlockPos pos) {
		return this.loaded.test(pos) && this.world.mayPlace(pos);
	}
}
