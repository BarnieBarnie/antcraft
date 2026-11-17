package io.github.barniebarnie.antcraft.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;

/** Position helpers shared by the game tests. */
public final class TestPositions {
	private TestPositions() {
	}

	/**
	 * {@code pos} relative to the test, the inverse of {@link GameTestHelper#absolutePos} for these
	 * unrotated tests. Use it instead of {@link GameTestHelper#relativePos}, which in 26.3 mirrors x
	 * and z when the test is not rotated: (1, 0, 2) comes back as (-1, 0, -2).
	 */
	public static BlockPos relative(GameTestHelper helper, BlockPos pos) {
		return pos.subtract(helper.absolutePos(BlockPos.ZERO));
	}

	/** {@code pos} relative to the test, as {@link #relative(GameTestHelper, BlockPos)}; instead of {@link GameTestHelper#relativeVec}. */
	public static Vec3 relative(GameTestHelper helper, Vec3 pos) {
		return pos.subtract(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)));
	}
}
