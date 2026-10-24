package io.github.barniebarnie.antcraft.colony;

import java.util.Optional;
import java.util.UUID;

/**
 * Cancelling a build by clicking its ghost asks first: the first click on a build only arms it, and a second click on
 * the same build within {@link #TIMEOUT} ticks cancels it. A click on another build, or after the time ran out, asks again.
 */
public final class CancelConfirm {
	/** How long, in ticks, the question stands. */
	public static final long TIMEOUT = 100;

	private Optional<UUID> armed = Optional.empty();
	private long armedAt;

	/**
	 * A click on the build of project {@code project} at game time {@code tick}.
	 *
	 * @return whether it confirms the cancel; if not, the build is now armed
	 */
	public boolean click(UUID project, long tick) {
		if (this.armed(tick).equals(Optional.of(project))) {
			this.armed = Optional.empty();
			return true;
		}
		this.armed = Optional.of(project);
		this.armedAt = tick;
		return false;
	}

	/** The project armed to cancel at game time {@code tick}, if the question still stands. */
	public Optional<UUID> armed(long tick) {
		return tick - this.armedAt > TIMEOUT ? Optional.empty() : this.armed;
	}

	/** Forgets the question, such as on leaving the server. */
	public void reset() {
		this.armed = Optional.empty();
	}
}
