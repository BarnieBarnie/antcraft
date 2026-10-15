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

	private static final int IDLE = RoleAssigner.IDLE_TICKS_BEFORE_REASSIGN;

	@Test
	void idleLumberjackMovesToWaitingCrops() {
		assertEquals(BotRole.FARMER, RoleAssigner.reassign(BotRole.LUMBERJACK, false, IDLE,
			Map.of(BotRole.LUMBERJACK, 1), Map.of(JobType.HARVEST_CROP, 3), false));
	}

	@Test
	void idleWorkerKeepsRoleWhenNothingElseHasWork() {
		// No switch to guard: an idle lumberjack may get trees again soon.
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.reassign(BotRole.LUMBERJACK, false, IDLE,
			Map.of(BotRole.LUMBERJACK, 1), Map.of(), false));
	}

	@Test
	void idleWorkerBecomesCrafterForUnstaffedOrders() {
		assertEquals(BotRole.CRAFTER, RoleAssigner.reassign(BotRole.MINER, false, IDLE,
			Map.of(BotRole.MINER, 1), Map.of(), true));
	}

	@Test
	void notReassignedBeforeIdleLongEnough() {
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.reassign(BotRole.LUMBERJACK, false, IDLE - 1,
			Map.of(BotRole.LUMBERJACK, 1), Map.of(JobType.HARVEST_CROP, 3), false));
	}

	@Test
	void notReassignedWhileOwnJobsAreOpen() {
		// Open jobs it has not claimed yet, e.g. unreachable for now: still its work.
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.reassign(BotRole.LUMBERJACK, false, IDLE,
			Map.of(BotRole.LUMBERJACK, 1), Map.of(JobType.CHOP_TREE, 1, JobType.HARVEST_CROP, 3), false));
	}

	@Test
	void roleSetByPlayerIsNeverReassigned() {
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.reassign(BotRole.LUMBERJACK, true, IDLE * 10,
			Map.of(BotRole.LUMBERJACK, 1), Map.of(JobType.HARVEST_CROP, 3), false));
	}

	@Test
	void rolesWithoutBoardJobsAreNotReassigned() {
		for (BotRole role : new BotRole[] {BotRole.GUARD, BotRole.HAULER, BotRole.CRAFTER}) {
			assertEquals(role, RoleAssigner.reassign(role, false, IDLE, Map.of(role, 1), Map.of(JobType.HARVEST_CROP, 3), false));
		}
	}

	@Test
	void countsItselfAsFreeWhenComparingLoad() {
		// One farmer has 2 crops; this miner joining makes 1 each, which still beats its own 0.
		assertEquals(BotRole.FARMER, RoleAssigner.reassign(BotRole.MINER, false, IDLE,
			Map.of(BotRole.MINER, 1, BotRole.FARMER, 1), Map.of(JobType.HARVEST_CROP, 2), false));
	}
}
