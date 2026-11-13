package io.github.barniebarnie.antcraft.gametest;

import java.lang.reflect.Method;
import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * The area every game test starts with, as CLAUDE.md describes it: a stone floor at y=0 under air (#558). The invoker
 * does only what every other one does first, {@link GameTests#start}, so the floor comes from there.
 */
public class FloorGameTest implements CustomTestMethodInvoker {
	@Override
	public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
		GameTests.start(helper);
		method.invoke(this, helper);
	}

	private static void assertFloor(GameTestHelper helper) {
		AABB bounds = helper.getBounds();
		for (int x = 0; x < (int) bounds.getXsize(); x++) {
			for (int z = 0; z < (int) bounds.getZsize(); z++) {
				helper.assertBlockPresent(Blocks.STONE, new BlockPos(x, 0, z));
				helper.assertBlockPresent(Blocks.AIR, new BlockPos(x, 1, z));
			}
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 5)
	public void defaultAreaHasAStoneFloor(GameTestHelper helper) {
		assertFloor(helper);
	}

	@GameTest(structure = "antcraft-gametest:wide", maxTicks = 5)
	public void wideAreaHasAStoneFloor(GameTestHelper helper) {
		assertFloor(helper);
	}

	@GameTest(structure = "antcraft-gametest:tall", maxTicks = 5)
	public void tallAreaHasAStoneFloor(GameTestHelper helper) {
		assertFloor(helper);
	}
}
