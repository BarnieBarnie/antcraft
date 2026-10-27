package io.github.barniebarnie.antcraft.entity;

import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.script.MoveApi;
import io.github.barniebarnie.antcraft.script.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** What the move actions of a script ({@link MoveApi}) do with a bot: its home, chunks and path follower. */
final class BotBody implements MoveApi.Body {
	private final AntBot bot;
	private final PathFollower follower;

	BotBody(AntBot bot) {
		this.bot = bot;
		this.follower = new PathFollower(bot);
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

	private ServerLevel level() {
		return (ServerLevel) this.bot.level();
	}
}
