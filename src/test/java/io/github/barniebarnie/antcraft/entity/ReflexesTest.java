package io.github.barniebarnie.antcraft.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.barniebarnie.antcraft.entity.Reflexes.Kind;
import io.github.barniebarnie.antcraft.entity.Reflexes.Senses;
import org.junit.jupiter.api.Test;

/** Which reflex takes over, from what the bot senses and the config's thresholds (docs/scripting.md, Reflexes). */
class ReflexesTest {
	private static final int FLEE_HEALTH = 6;
	private static final int SURFACE_AIR = 100;
	private static final Senses SAFE = new Senses(false, false, false, 300, 20, false);

	private static Kind choose(Senses senses) {
		return Reflexes.choose(senses, FLEE_HEALTH, SURFACE_AIR);
	}

	@Test
	void noReflexWhenSafe() {
		assertNull(choose(SAFE));
		// A hostile mob near a bot in good health is for the bot to fight.
		assertNull(choose(new Senses(false, false, false, 300, 20, true)));
		// Under water with air to spare.
		assertNull(choose(new Senses(false, false, true, 100, 20, false)));
	}

	@Test
	void eachDangerHasItsReflex() {
		assertEquals(Kind.HAZARD, choose(new Senses(true, false, false, 300, 20, false)));
		assertEquals(Kind.FALLING_BLOCK, choose(new Senses(false, true, false, 300, 20, false)));
		assertEquals(Kind.SURFACE, choose(new Senses(false, false, true, 99, 20, false)));
		assertEquals(Kind.FLEE, choose(new Senses(false, false, false, 300, 6, true)));
		assertNull(choose(new Senses(false, false, false, 300, 6.5f, true)));
	}

	@Test
	void theNearerDangerComesFirst() {
		assertEquals(Kind.HAZARD, choose(new Senses(true, true, true, 0, 1, true)));
		assertEquals(Kind.FALLING_BLOCK, choose(new Senses(false, true, true, 0, 1, true)));
		assertEquals(Kind.SURFACE, choose(new Senses(false, false, true, 0, 1, true)));
	}

	/** A fleeing bot runs home when home lies further from the mob than the bot, and away from the mob otherwise. */
	@Test
	void fleesHomeOnlyWhenHomeIsAwayFromTheMob() {
		assertEquals(true, Reflexes.fleesHome(3, 10, 7));
		assertEquals(false, Reflexes.fleesHome(3, 2, 2));
		// Home already: it runs on, away from the mob.
		assertEquals(false, Reflexes.fleesHome(3, 3.5, 0.5));
	}

	/** A bot that found no way to flee waits a while before it plans again, rather than plan every tick (#345). */
	@Test
	void noNewFlightPlanSoonAfterAFailedOne() {
		assertEquals(false, Reflexes.mayPlanFlight(0));
		assertEquals(false, Reflexes.mayPlanFlight(1));
		assertEquals(false, Reflexes.mayPlanFlight(Reflexes.FLEE_RETRY_TICKS - 1));
		assertEquals(true, Reflexes.mayPlanFlight(Reflexes.FLEE_RETRY_TICKS));
	}

	@Test
	void aThresholdOfZeroTurnsItsReflexOff() {
		assertNull(Reflexes.choose(new Senses(false, false, false, 300, 0.5f, true), 0, SURFACE_AIR));
		assertNull(Reflexes.choose(new Senses(false, false, true, 0, 20, false), FLEE_HEALTH, 0));
	}
}
