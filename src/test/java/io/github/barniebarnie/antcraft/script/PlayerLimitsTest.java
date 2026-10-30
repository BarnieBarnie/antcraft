package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Who may script which bot, and the limits of a player's scripts (docs/features/scripts.md, Players' scripts). */
class PlayerLimitsTest {
	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID STRANGER = UUID.randomUUID();

	@Test
	void anOwnerMayControlTheirBot() {
		assertTrue(PlayerLimits.mayControl(OWNER, OWNER, false));
	}

	@Test
	void anotherPlayerMayNot() {
		assertFalse(PlayerLimits.mayControl(OWNER, STRANGER, false));
		assertFalse(PlayerLimits.mayControl(null, STRANGER, false), "a bot nobody owns");
	}

	@Test
	void anOperatorMayControlAnyBot() {
		assertTrue(PlayerLimits.mayControl(OWNER, STRANGER, true));
		assertTrue(PlayerLimits.mayControl(null, STRANGER, true));
	}

	@Test
	void aPlayerMayRunScriptsUpToTheirCap() {
		assertTrue(PlayerLimits.mayStart(2, 1, 3, false));
		assertFalse(PlayerLimits.mayStart(2, 2, 3, false));
		assertFalse(PlayerLimits.mayStart(0, 1, 0, false), "a cap of 0 lets no player run scripts");
		assertTrue(PlayerLimits.mayStart(5, 5, 3, true), "operators are exempt");
	}

	@Test
	void aBotCoresScriptIsOneMoreScript() {
		assertTrue(PlayerLimits.mayStart(7, 1, 8, false));
		assertFalse(PlayerLimits.mayStart(8, 1, 8, false), "a player at the cap");
		assertTrue(PlayerLimits.mayStart(8, 1, 8, true), "an operator at the cap");
	}

	@Test
	void thePlayerBudgetIsSplitAcrossTheirBots() {
		assertEquals(1000, PlayerLimits.budget(2000, 10000, 10, false));
		assertEquals(2500, PlayerLimits.budget(5000, 10000, 4, false));
	}

	@Test
	void aBotNeverGetsMoreThanThePerBotBudget() {
		assertEquals(2000, PlayerLimits.budget(2000, 10000, 2, false));
		assertEquals(2000, PlayerLimits.budget(2000, 10000, 0, false));
	}

	@Test
	void everyBotGetsAtLeastOneInstruction() {
		assertEquals(1, PlayerLimits.budget(2000, 10, 50, false));
	}

	@Test
	void operatorsAreExempt() {
		assertEquals(2000, PlayerLimits.budget(2000, 10000, 10, true));
	}

	@Test
	void onADedicatedServerOnlyOperatorsAreExempt() {
		assertTrue(PlayerLimits.exempt(true, false, true));
		assertFalse(PlayerLimits.exempt(true, false, false));
	}

	@Test
	void inSinglePlayerTheHostIsExempt() {
		assertTrue(PlayerLimits.exempt(false, true, false), "the host, without cheats");
	}

	@Test
	void aLanGuestIsNotExemptAndTheirBotsAreThrottled() {
		boolean guest = PlayerLimits.exempt(false, false, false);
		assertFalse(guest);
		assertEquals(1000, PlayerLimits.budget(2000, 10000, 10, guest));
		assertTrue(PlayerLimits.exempt(false, false, true), "a guest who is an operator");
	}
}
