package io.github.barniebarnie.antcraft.pathing;

/** Vanilla mining time, without enchantments or status effects. */
public final class BreakTime {
	private BreakTime() {
	}

	/**
	 * @param hardness the block's destroy speed; negative means unbreakable
	 * @param toolSpeed the held item's destroy speed against the block (1 for bare hands)
	 * @param canHarvest whether the block drops with this tool; harvesting without the right tool is much slower
	 * @return ticks until the block breaks, or -1 if it cannot be broken
	 */
	public static int ticks(float hardness, float toolSpeed, boolean canHarvest) {
		if (hardness < 0) {
			return -1;
		}
		if (hardness == 0) {
			return 1;
		}
		float progressPerTick = toolSpeed / hardness / (canHarvest ? 30.0F : 100.0F);
		return (int) Math.ceil(1.0F / progressPerTick);
	}
}
