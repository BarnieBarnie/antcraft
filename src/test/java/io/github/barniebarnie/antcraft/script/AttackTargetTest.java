package io.github.barniebarnie.antcraft.script;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.config.AntCraftConfig;
import io.github.barniebarnie.antcraft.script.lang.Output;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Which targets {@code attack} may hit (#415): by default hostile mobs and untamed, unnamed animals only. */
class AttackTargetTest {
	private static AntCraftConfig.Values allowing(boolean players, boolean bots, boolean tamed) {
		return new AntCraftConfig.Values(true, 64, false, AntCraftConfig.DEFAULT_SCRIPT_INSTRUCTIONS_PER_TICK, Output.Level.ERROR, true, true,
			AntCraftConfig.DEFAULT_BOT_RESPAWN_TICKS, AntCraftConfig.DEFAULT_BOT_FLEE_HEALTH, AntCraftConfig.DEFAULT_BOT_SURFACE_AIR, players, bots, tamed);
	}

	private static Set<WorkApi.Target> allowed(AntCraftConfig.Values values) {
		Set<WorkApi.Target> allowed = EnumSet.noneOf(WorkApi.Target.class);
		for (WorkApi.Target target : WorkApi.Target.values()) {
			if (WorkApi.mayAttack(target, values)) {
				allowed.add(target);
			}
		}
		return allowed;
	}

	@Test
	void byDefaultOnlyHostileMobsAndWildAnimals() {
		assertEquals(Set.of(WorkApi.Target.HOSTILE, WorkApi.Target.ANIMAL), allowed(new AntCraftConfig.Values(true, 64)));
	}

	@Test
	void eachKeyAllowsItsTargets() {
		assertEquals(Set.of(WorkApi.Target.HOSTILE, WorkApi.Target.ANIMAL, WorkApi.Target.PLAYER), allowed(allowing(true, false, false)));
		assertEquals(Set.of(WorkApi.Target.HOSTILE, WorkApi.Target.ANIMAL, WorkApi.Target.BOT), allowed(allowing(false, true, false)));
		assertEquals(Set.of(WorkApi.Target.HOSTILE, WorkApi.Target.ANIMAL, WorkApi.Target.TAMED), allowed(allowing(false, false, true)));
		// The bot itself and other entities, such as villagers or dropped items, never.
		assertEquals(Set.of(WorkApi.Target.HOSTILE, WorkApi.Target.ANIMAL, WorkApi.Target.PLAYER, WorkApi.Target.BOT, WorkApi.Target.TAMED),
			allowed(allowing(true, true, true)));
	}
}
