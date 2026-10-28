package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.entity.navigation.LineOfSight;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.pathing.Path;
import io.github.barniebarnie.antcraft.pathing.PathBudget;
import io.github.barniebarnie.antcraft.pathing.PathFinder;
import io.github.barniebarnie.antcraft.profile.TickProfiler;
import io.github.barniebarnie.antcraft.script.MoveApi;
import io.github.barniebarnie.antcraft.script.Pos;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** What the move actions of a script ({@link MoveApi}) do with a bot: its home, chunks and path follower. */
final class BotBody implements MoveApi.Body {
	private final AntBot bot;
	private final PathFollower follower;
	/** One per bot: goals compare by value (see {@link Goal.InSight}). */
	private final Goal.Sight sight;

	BotBody(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
		this.sight = (feet, target, broken) -> LineOfSight.sees(bot.level(), feet, target, broken);
	}

	@Override
	public Pos home() {
		BlockPos home = this.bot.getScriptHome();
		return new Pos(home.getX(), home.getY(), home.getZ());
	}

	@Override
	public void setHome(Pos home) {
		this.bot.setHome(new BlockPos(clamp(home.x()), clamp(home.y()), clamp(home.z())));
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
	public void startReach(Pos target) {
		this.follower.start(this.reach(target));
	}

	@Override
	public boolean inReach(Pos target) {
		return this.reach(target).isReached(this.bot.blockPosition());
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
				int nodes = PathBudget.SERVER.available(tick);
				if (nodes <= 0 || this.done()) {
					return 0;
				}
				int expanded = TickProfiler.time("path.plan", () -> search.run(nodes));
				PathBudget.SERVER.spend(tick, expanded);
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
