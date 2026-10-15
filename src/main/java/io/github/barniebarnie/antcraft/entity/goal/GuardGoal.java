package io.github.barniebarnie.antcraft.entity.goal;

import io.github.barniebarnie.antcraft.colony.Colony;
import io.github.barniebarnie.antcraft.entity.AntBot;
import io.github.barniebarnie.antcraft.entity.BotRole;
import io.github.barniebarnie.antcraft.entity.Tools;
import io.github.barniebarnie.antcraft.entity.navigation.PathFollower;
import io.github.barniebarnie.antcraft.pathing.Goal;
import io.github.barniebarnie.antcraft.work.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/**
 * Guard work loop: arm itself from the core, then walk between random spots around
 * the core. Fighting is handled by the bot's attack goals, which take priority.
 */
public class GuardGoal extends ColonyWorkGoal {
	/** Guards stay closer to the core than the colony's full work radius. */
	private static final int MAX_PATROL_RADIUS = 16;
	private static final int PAUSE_TICKS = 20;

	private @Nullable BlockPos patrolTarget;

	public GuardGoal(AntBot bot) {
		super(bot, BotRole.GUARD);
	}

	@Override
	protected void work(ServerLevel level, Colony colony) {
		if (this.armFromCore(level, colony)) {
			return;
		}
		if (this.patrolTarget == null) {
			this.patrolTarget = this.pickPatrolTarget(level, colony);
		}
		PathFollower.Status status = this.travel(new Goal.Near(this.patrolTarget, 1));
		if (status != PathFollower.Status.RUNNING) {
			this.patrolTarget = null;
			this.cooldown = PAUSE_TICKS;
		}
	}

	/** Fetches a better weapon from the core if there is one. Returns true while busy doing so. */
	private boolean armFromCore(ServerLevel level, Colony colony) {
		Container core = Storage.containerAt(level, colony.core());
		if (core == null) {
			return false;
		}
		float carried = Math.max(Tools.weaponScore(this.bot.getMainHandItem()), bestWeaponScore(this.bot.getInventory()));
		int slot = -1;
		float best = carried;
		for (int i = 0; i < core.getContainerSize(); i++) {
			float score = Tools.weaponScore(core.getItem(i));
			if (score > best) {
				best = score;
				slot = i;
			}
		}
		if (slot < 0) {
			return false;
		}
		if (this.reach(colony.core())) {
			this.bot.getInventory().addItem(core.removeItem(slot, 1));
			Tools.equipBestWeapon(this.bot);
		}
		return true;
	}

	private static float bestWeaponScore(Container container) {
		float best = 0;
		for (int i = 0; i < container.getContainerSize(); i++) {
			best = Math.max(best, Tools.weaponScore(container.getItem(i)));
		}
		return best;
	}

	private BlockPos pickPatrolTarget(ServerLevel level, Colony colony) {
		int radius = Math.min(colony.radius(), MAX_PATROL_RADIUS);
		BlockPos core = colony.core();
		int x = core.getX() + this.bot.getRandom().nextIntBetweenInclusive(-radius, radius);
		int z = core.getZ() + this.bot.getRandom().nextIntBetweenInclusive(-radius, radius);
		BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, core.getY(), z));
		// Under a roof the heightmap is too high; stay at the core's level then.
		return Math.abs(surface.getY() - core.getY()) > radius ? new BlockPos(x, core.getY(), z) : surface;
	}
}
