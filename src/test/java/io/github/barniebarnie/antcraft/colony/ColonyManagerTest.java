package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColonyManagerTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	/**
	 * A colony whose core's chunk is not loaded, such as one the players left far behind, is not surveyed: the survey
	 * would load or generate the chunks of its territory, holding up the server tick, every time. It is surveyed as
	 * soon as its core is loaded again.
	 */
	@Test
	void aColonyIsSurveyedEveryIntervalWhileItsCoreIsLoaded() {
		assertTrue(ColonyManager.surveyDue(-1, 500, true), "never surveyed");
		assertFalse(ColonyManager.surveyDue(-1, 500, false), "never surveyed, core not loaded");
		assertFalse(ColonyManager.surveyDue(450, 500, true), "surveyed 50 ticks ago");
		assertTrue(ColonyManager.surveyDue(400, 500, true), "surveyed 100 ticks ago");
		assertFalse(ColonyManager.surveyDue(100, 5000, false), "core not loaded");
	}
}
