package io.github.barniebarnie.antcraft.entity;

/**
 * Takes a bot's AI away while the server has not opted in to bots and gives it back once it has, leaving an AI taken
 * away otherwise ({@code setNoAi}) alone (#970). It set the AI by the config every tick, which undid every
 * {@code setNoAi}.
 */
final class AiGate {
	/** Whether the gate took the AI away, so it is the gate's to give back. Saved with the bot. */
	private boolean tookAi;

	/** The bot's no-AI flag for this tick, given its flag {@code noAi} and whether bots are {@code allowed}. */
	boolean noAi(boolean noAi, boolean allowed) {
		if (!allowed && !noAi) {
			this.tookAi = true;
			return true;
		}
		if (allowed && this.tookAi) {
			this.tookAi = false;
			return false;
		}
		return noAi;
	}

	boolean tookAi() {
		return this.tookAi;
	}

	void tookAi(boolean tookAi) {
		this.tookAi = tookAi;
	}
}
