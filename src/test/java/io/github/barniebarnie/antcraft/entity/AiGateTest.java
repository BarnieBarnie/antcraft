package io.github.barniebarnie.antcraft.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AiGateTest {
	@Test
	void takesTheAiAwayWhileBotsAreNotAllowedAndGivesItBack() {
		AiGate gate = new AiGate();
		assertTrue(gate.noAi(false, false));
		assertTrue(gate.noAi(true, false));
		assertFalse(gate.noAi(true, true));
	}

	@Test
	void leavesAnAiTakenAwayOtherwiseAlone() {
		AiGate gate = new AiGate();
		assertTrue(gate.noAi(true, true));
		assertFalse(gate.noAi(false, true));
		// Taken away before bots were turned off, so not given back when they are on again.
		assertTrue(gate.noAi(true, false));
		assertTrue(gate.noAi(true, true));
	}

	@Test
	void anAiTakenAwayByTheConfigWhenSavedIsGivenBackOnceLoaded() {
		AiGate gate = new AiGate();
		gate.noAi(false, false);
		AiGate loaded = new AiGate();
		loaded.tookAi(gate.tookAi());
		assertFalse(loaded.noAi(true, true));
	}
}
