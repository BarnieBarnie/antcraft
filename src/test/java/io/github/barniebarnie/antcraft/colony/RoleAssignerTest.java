package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.entity.BotRole;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoleAssignerTest {
	/** A colony past its starter roster: haulers take no board jobs, so they leave the load maths alone. */
	private static Map<BotRole, Integer> staffed(Object... roleCounts) {
		Map<BotRole, Integer> roles = new EnumMap<>(BotRole.class);
		roles.put(BotRole.HAULER, RoleAssigner.STARTER_ROSTER.size());
		for (int i = 0; i < roleCounts.length; i += 2) {
			roles.put((BotRole) roleCounts[i], (Integer) roleCounts[i + 1]);
		}
		return roles;
	}

	@Test
	void newColonyGetsTheStarterRosterFirst() {
		// No branch jobs, no crops, only trees: demand alone would make every bot a lumberjack.
		Map<JobType, Integer> jobs = Map.of(JobType.CHOP_TREE, 20);
		Map<BotRole, Integer> roles = new EnumMap<>(BotRole.class);
		roles.put(BotRole.NONE, 4);
		for (BotRole expected : List.of(BotRole.LUMBERJACK, BotRole.MINER, BotRole.BUILDER, BotRole.FARMER)) {
			BotRole role = RoleAssigner.choose(roles, jobs, true, false);
			assertEquals(expected, role);
			roles.merge(role, 1, Integer::sum);
			roles.merge(BotRole.NONE, -1, Integer::sum);
		}
		// Past the roster, open jobs decide again.
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.choose(roles, jobs, false, false));
	}

	@Test
	void rosterSkipsRolesAlreadyFilled() {
		// The player made the first bot a miner.
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.choose(Map.of(BotRole.MINER, 1), Map.of(), false, false));
		assertEquals(BotRole.BUILDER, RoleAssigner.choose(Map.of(BotRole.MINER, 1, BotRole.LUMBERJACK, 1), Map.of(), false, false));
	}

	@Test
	void onlyBotInAStarterRoleIsNotReassignedWhenIdle() {
		Map<JobType, Integer> trees = Map.of(JobType.CHOP_TREE, 20);
		for (BotRole role : List.of(BotRole.MINER, BotRole.FARMER, BotRole.BUILDER)) {
			assertEquals(role, RoleAssigner.reassign(role, false, IDLE * 10, staffed(role, 1), trees, false, false));
			// A second one may go.
			assertEquals(BotRole.LUMBERJACK, RoleAssigner.reassign(role, false, IDLE * 10, staffed(role, 2), trees, false, false));
		}
	}

	@Test
	void firstCrafterWhenThereAreOrders() {
		assertEquals(BotRole.CRAFTER, RoleAssigner.choose(staffed(), Map.of(JobType.CHOP_TREE, 5), true, false));
	}

	@Test
	void noSecondCrafter() {
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.choose(staffed(BotRole.CRAFTER, 1), Map.of(JobType.CHOP_TREE, 5), true, false));
	}

	@Test
	void picksTheRoleWithTheMostOpenJobsPerWorker() {
		// 6 trees for 1 lumberjack (3 each once a second joins) beats 2 layers for no miners (2).
		Map<BotRole, Integer> roles = staffed(BotRole.LUMBERJACK, 1);
		Map<JobType, Integer> jobs = Map.of(JobType.CHOP_TREE, 6, JobType.MINE_BRANCH, 2);
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.choose(roles, jobs, false, false));
	}

	@Test
	void unstaffedWorkWinsWhenBusy() {
		Map<BotRole, Integer> roles = staffed(BotRole.LUMBERJACK, 3);
		Map<JobType, Integer> jobs = Map.of(JobType.CHOP_TREE, 6, JobType.HARVEST_CROP, 3);
		assertEquals(BotRole.FARMER, RoleAssigner.choose(roles, jobs, false, false));
	}

	@Test
	void builderForAnOpenBuildJob() {
		assertEquals(BotRole.BUILDER, RoleAssigner.choose(staffed(BotRole.GUARD, 1), Map.of(JobType.BUILD, 1), false, false));
	}

	@Test
	void haulerWhenTheCoreIsNearlyFull() {
		assertEquals(BotRole.HAULER, RoleAssigner.choose(Map.of(BotRole.GUARD, 1, BotRole.CRAFTER, 4), Map.of(JobType.CHOP_TREE, 5), false, true));
		// One is enough.
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.choose(Map.of(BotRole.HAULER, 1, BotRole.CRAFTER, 4), Map.of(JobType.CHOP_TREE, 5), false, true));
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.choose(staffed(), Map.of(JobType.CHOP_TREE, 5), false, false));
	}

	@Test
	void aNearlyFullCoreGetsAHaulerBeforeTheStarterRoster() {
		// A full core stalls every role that deposits, the starter roles included.
		assertEquals(BotRole.HAULER, RoleAssigner.choose(Map.of(BotRole.CRAFTER, 1), Map.of(JobType.CHOP_TREE, 5), false, true, true));
		// One is enough; then the roster goes on.
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.choose(Map.of(BotRole.CRAFTER, 1, BotRole.HAULER, 1), Map.of(JobType.CHOP_TREE, 5), false, true, true));
		// Needed for other reasons, such as a landing chest to empty, a hauler waits for the roster.
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.choose(Map.of(BotRole.CRAFTER, 1), Map.of(JobType.CHOP_TREE, 5), false, true, false));
	}

	@Test
	void idleWorkerBecomesHaulerWhenOneIsNeeded() {
		assertEquals(BotRole.HAULER, RoleAssigner.reassign(BotRole.MINER, false, IDLE, Map.of(BotRole.MINER, 2, BotRole.CRAFTER, 4), Map.of(), false, true));
	}

	@Test
	void guardWhenThereIsNoWork() {
		assertEquals(BotRole.GUARD, RoleAssigner.choose(staffed(BotRole.LUMBERJACK, 1), Map.of(), false, false));
	}

	@Test
	void staysUnassignedWhenNoWorkAndAlreadyGuarded() {
		assertEquals(BotRole.NONE, RoleAssigner.choose(staffed(BotRole.GUARD, 1), Map.of(), false, false));
	}

	private static final int IDLE = RoleAssigner.IDLE_TICKS_BEFORE_REASSIGN;

	@Test
	void idleLumberjackMovesToWaitingCrops() {
		// Even the only one, in a colony too small for the starter roster: trees need no setting up.
		assertEquals(BotRole.FARMER, RoleAssigner.reassign(BotRole.LUMBERJACK, false, IDLE,
			Map.of(BotRole.LUMBERJACK, 1), Map.of(JobType.HARVEST_CROP, 3), false, false));
	}

	@Test
	void idleWorkerKeepsRoleWhenNothingElseHasWork() {
		// No switch to guard: an idle lumberjack may get trees again soon.
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.reassign(BotRole.LUMBERJACK, false, IDLE,
			staffed(BotRole.LUMBERJACK, 2), Map.of(), false, false));
	}

	@Test
	void idleWorkerBecomesCrafterForUnstaffedOrders() {
		assertEquals(BotRole.CRAFTER, RoleAssigner.reassign(BotRole.MINER, false, IDLE,
			staffed(BotRole.MINER, 2), Map.of(), true, false));
	}

	@Test
	void notReassignedBeforeIdleLongEnough() {
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.reassign(BotRole.LUMBERJACK, false, IDLE - 1,
			staffed(BotRole.LUMBERJACK, 2), Map.of(JobType.HARVEST_CROP, 3), false, false));
	}

	@Test
	void notReassignedWhileOwnJobsAreOpen() {
		// Open jobs it has not claimed yet, e.g. unreachable for now: still its work.
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.reassign(BotRole.LUMBERJACK, false, IDLE,
			staffed(BotRole.LUMBERJACK, 2), Map.of(JobType.CHOP_TREE, 1, JobType.HARVEST_CROP, 3), false, false));
	}

	@Test
	void roleSetByPlayerIsNeverReassigned() {
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.reassign(BotRole.LUMBERJACK, true, IDLE * 10,
			staffed(BotRole.LUMBERJACK, 2), Map.of(JobType.HARVEST_CROP, 3), false, false));
	}

	@Test
	void rolesWithoutBoardJobsAreNotReassigned() {
		for (BotRole role : new BotRole[] {BotRole.GUARD, BotRole.HAULER, BotRole.CRAFTER}) {
			assertEquals(role, RoleAssigner.reassign(role, false, IDLE, Map.of(role, 1), Map.of(JobType.HARVEST_CROP, 3), false, false));
		}
	}

	@Test
	void idleWorkersBecomeBuildersWhileBuildBatchesAreOpen() {
		// A mineshaft's stairs and a hall open at once: one builder is busy, so idle bots lend a hand.
		Map<BotRole, Integer> roles = staffed(BotRole.LUMBERJACK, 3, BotRole.MINER, 2, BotRole.BUILDER, 1);
		Map<JobType, Integer> batches = Map.of(JobType.BUILD, 2);
		assertEquals(BotRole.BUILDER, RoleAssigner.reassign(BotRole.LUMBERJACK, false, IDLE, roles, batches, false, false));
		assertEquals(BotRole.BUILDER, RoleAssigner.reassign(BotRole.MINER, false, IDLE, roles, batches, false, false));
		// Once every batch is claimed there is nothing more to lend a hand with.
		assertEquals(BotRole.LUMBERJACK, RoleAssigner.reassign(BotRole.LUMBERJACK, false, IDLE, roles, Map.of(), false, false));
	}

	@Test
	void countsItselfAsFreeWhenComparingLoad() {
		// One farmer has 2 crops; this miner joining makes 1 each, which still beats its own 0.
		assertEquals(BotRole.FARMER, RoleAssigner.reassign(BotRole.MINER, false, IDLE,
			staffed(BotRole.MINER, 2, BotRole.FARMER, 1), Map.of(JobType.HARVEST_CROP, 2), false, false));
	}
}
