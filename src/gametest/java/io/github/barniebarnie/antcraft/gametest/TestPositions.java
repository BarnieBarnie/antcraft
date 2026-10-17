package io.github.barniebarnie.antcraft.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

/** Position helpers shared by the game tests. */
public final class TestPositions {
	private TestPositions() {
	}

	/**
	 * {@code pos} relative to the test, the inverse of {@link GameTestHelper#absolutePos} for these
	 * unrotated tests. Use it instead of {@link GameTestHelper#relativePos}, which in 26.3 mirrors x
	 * and z when the test is not rotated.
	 */
	public static BlockPos relative(GameTestHelper helper, BlockPos pos) {
		return pos.subtract(helper.absolutePos(BlockPos.ZERO));
	}
}
