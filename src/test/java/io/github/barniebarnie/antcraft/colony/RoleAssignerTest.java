package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.entity.BotRole;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoleAssignerTest {
	@Test
	void firstCrafterWhenThereAreOrders() {
		assertEquals(BotRole.CRAFTER, RoleAssigner.choose(Map.of(), Map.of(JobType.CHOP_TREE, 5), true));
	}

	@Test
	void noSecondCrafter() {
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.choose(Map.of(BotRole.CRAFTER, 1), Map.of(JobType.CHOP_TREE, 5), true));
	}

	@Test
	void picksTheRoleWithTheMostOpenJobsPerWorker() {
		// 6 trees for 1 lumberjack (3 each once a second joins) beats 2 layers for no miners (2).
		Map<BotRole, Integer> roles = Map.of(BotRole.LUMBERJACK, 1);
		Map<JobType, Integer> jobs = Map.of(JobType.CHOP_TREE, 6, JobType.MINE_LAYER, 2);
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.choose(roles, jobs, false));
	}

	@Test
	void unstaffedWorkWinsWhenBusy() {
		Map<BotRole, Integer> roles = Map.of(BotRole.LUMBERJACK, 3);
		Map<JobType, Integer> jobs = Map.of(JobType.CHOP_TREE, 6, JobType.HARVEST_CROP, 3);
		assertEquals(BotRole.FARMER, RoleAssigner.choose(roles, jobs, false));
	}

	@Test
	void guardWhenThereIsNoWork() {
		assertEquals(BotRole.GUARD, RoleAssigner.choose(Map.of(BotRole.LUMBERJACK, 1), Map.of(), false));
	}

	@Test
	void staysUnassignedWhenNoWorkAndAlreadyGuarded() {
		assertEquals(BotRole.NONE, RoleAssigner.choose(Map.of(BotRole.GUARD, 1), Map.of(), false));
	}
}
