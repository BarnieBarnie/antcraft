package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.entity.navigation.LineOfSight;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.pathing.LevelWorldView;
import io.github.barniebarnie.antcraft.pathing.Path;
import io.github.barniebarnie.antcraft.pathing.PathBudget;
import io.github.barniebarnie.antcraft.pathing.PathFinder;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import io.github.barniebarnie.antcraft.script.MoveApi;
import io.github.barniebarnie.antcraft.script.Pos;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/** What the move actions of a script ({@link MoveApi}) do with a bot: its home, chunks and path follower. */
final class BotBody implements MoveApi.Body {
	private final AntBot bot;
	private final PathFollower follower;
	/** One per bot: goals compare by value (see {@link Goal.InSight}). */
	private final Goal.Sight sight;

	BotBody(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		// Where the bot is now, over the edge of the only block it rests on, its feet count as beside it (#668): that spot
		// is no stand to break the block from either, so the walk drops off rather than arrive there and get nowhere (#753).
		this.sight = (feet, target, broken) -> LineOfSight.sees(bot.level(), feet, target, broken)
			&& (!feet.below().equals(target) || this.landsUnhurt(target))
			&& !(this.restsOnlyOn(target) && !this.landsUnhurt(target) && feet.equals(PathFollower.feet(bot)));
	}

	/**
	 * Whether the bot standing on {@code floor} lands at most {@link PathFollower#MAX_DROP} down, as a path drops it, once
	 * that block is broken: else standing on it is no stand to break it from. Taking back a scaffold pillar whose lower
	 * blocks it took back from the ground before, it stood on each block left to mine it and fell to the floor (#668).
	 * Leaves that decay are no floor to land on (#673).
	 */
	private boolean landsUnhurt(BlockPos floor) {
		ServerLevel level = this.level();
		for (int drop = 1; drop <= PathFollower.MAX_DROP; drop++) {
			BlockPos below = floor.below(drop);
			BlockState state = level.getBlockState(below);
			if (!state.getCollisionShape(level, below).isEmpty() && !LevelWorldView.decays(state) || state.getFluidState().is(FluidTags.WATER)) {
				return true;
			}
		}
		return false;
	}

	/** Where the bot stands at home: beside its home when that was built over, in its home's dimension (#711). */
	@Override
	public Pos home() {
		BlockPos home = this.bot.getScriptHome();
		if (this.bot.level().dimension() == this.bot.getHomeDimension()) {
			home = AntBot.freeSpotNear(this.level(), home);
		}
		return new Pos(home.getX(), home.getY(), home.getZ());
	}

	@Override
	public void setHome(Pos home, String dimension) {
		this.bot.setHome(new BlockPos(clamp(home.x()), clamp(home.y()), clamp(home.z())),
			ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)));
	}

	@Override
	public String dimension() {
		return this.bot.level().dimension().identifier().toString();
	}

	@Override
	public String homeDimension() {
		return this.bot.getHomeDimension().identifier().toString();
	}

	private static int clamp(long value) {
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
	}

	@Override
	public boolean chunkLoaded(int x, int z) {
		return BotChunks.loaded(this.level(), x, z);
	}

	@Override
	public void loadChunk(int x, int z) {
		BotChunks.load(this.level(), x, z);
	}

	@Override
	public void startPath(Pos target, int near) {
		this.follower.start(new Goal.Near(new BlockPos(clamp(target.x()), clamp(target.y()), clamp(target.z())), near));
	}

	@Override
	public void startReach(Pos target, double reach) {
		BlockPos pos = blockPos(target);
		Goal goal = new Goal.InSight(pos, reach, this.sight);
		// A block in water or under it, such as kelp or the floor of a pool, may be worked swimming at the surface where no
		// dry spot is in reach (#840). Only there: an afloat goal searches without breaking first (PathFinder.Search).
		boolean wet = this.level().getFluidState(pos).is(FluidTags.WATER) || this.level().getFluidState(pos.above()).is(FluidTags.WATER);
		this.follower.start(wet ? new Goal.Afloat(goal) : goal);
	}

	@Override
	public void setBuildScaffold(@Nullable Predicate<Pos> mayPlace, Predicate<Pos> keep) {
		this.follower.setScaffold(mayPlace == null ? PathFollower.SCAFFOLD_BLOCKS : PathFollower.FAST_SCAFFOLD_BLOCKS);
		this.follower.setMayPlaceAt(mayPlace == null ? pos -> true : pos -> mayPlace.test(new Pos(pos.getX(), pos.getY(), pos.getZ())));
		this.follower.setKeep(pos -> keep.test(new Pos(pos.getX(), pos.getY(), pos.getZ())));
	}

	@Override
	public void setKeepItems(Predicate<ItemStack> keep) {
		this.follower.setKeepItems(keep);
	}

	@Override
	public boolean inReach(Pos target) {
		return this.reach(target).isReached(PathFollower.feet(this.bot));
	}

	/**
	 * Whether {@code pos} is the only block the bot rests on. Over the edge of it, as the bot lands once the block over it
	 * is mined, its feet are in the block beside, so the reach goal does not see it stands on it (#668).
	 */
	private boolean restsOnlyOn(BlockPos pos) {
		ServerLevel level = this.level();
		AABB under = this.bot.getBoundingBox();
		under = new AABB(under.minX, under.minY - 0.05, under.minZ, under.maxX, under.minY, under.maxZ);
		boolean onPos = false;
		for (BlockPos at : BlockPos.betweenClosed(BlockPos.containing(under.minX, under.minY, under.minZ),
				BlockPos.containing(under.maxX, under.minY, under.maxZ))) {
			VoxelShape shape = level.getBlockState(at).getCollisionShape(level, at);
			if (!shape.isEmpty() && shape.move(at).bounds().intersects(under)) {
				if (!at.equals(pos)) {
					return false;
				}
				onPos = true;
			}
		}
		return onPos;
	}

	private Goal reach(Pos target) {
		return new Goal.InSight(blockPos(target), MoveApi.REACH, this.sight);
	}

	private static BlockPos blockPos(Pos pos) {
		return new BlockPos(clamp(pos.x()), clamp(pos.y()), clamp(pos.z()));
	}

	@Override
	public Pos portalFloor(Pos portal) {
		BlockPos at = blockPos(portal);
		while (this.level().isLoaded(at.below()) && this.level().getBlockState(at.below()).is(Blocks.NETHER_PORTAL)) {
			at = at.below();
		}
		return new Pos(at.getX(), at.getY(), at.getZ());
	}

	@Override
	public List<Pos> portals() {
		return this.bot.getPortals();
	}

	@Override
	public boolean noPortalAt(Pos portal) {
		BlockPos at = blockPos(portal);
		return this.level().isLoaded(at) && !this.level().getBlockState(at).is(Blocks.NETHER_PORTAL);
	}

	@Override
	public void forgetPortal(Pos portal) {
		this.bot.forgetPortal(portal);
	}

	@Override
	public void clearPortalCooldown() {
		this.bot.setPortalCooldown(0);
	}

	@Override
	public MoveApi.PathStatus tickPath() {
		return switch (this.follower.tick()) {
			case RUNNING -> MoveApi.PathStatus.RUNNING;
			case ARRIVED -> MoveApi.PathStatus.ARRIVED;
			case FAILED -> MoveApi.PathStatus.FAILED;
		};
	}

	@Override
	public void stopPath() {
		this.follower.stop();
	}

	@Override
	public MoveApi.Plan plan(Pos target, int near) {
		PathFinder.Search search = this.follower.plan(new Goal.Near(blockPos(target), near));
		return new MoveApi.Plan() {
			@Override
			public int run() {
				// The follower's clock: the budget is the one every bot's path follower shares.
				long tick = BotBody.this.level().getServer().getTickCount();
				if (this.done()) {
					return 0;
				}
				int expanded = TickProfiler.time("path.plan", () -> PathBudget.SERVER.run(tick, BotBody.this.bot, search));
				if (!this.done()) {
					PathBudget.SERVER.pause(tick, BotBody.this.bot);
				}
				return expanded;
			}

			@Override
			public boolean done() {
				return search.path().isPresent();
			}

			@Override
			public boolean found() {
				return search.path().map(Path::complete).orElse(false);
			}
		};
	}

	private ServerLevel level() {
		return (ServerLevel) this.bot.level();
	}
}
