package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TripClaimsTest {
	private static final UUID ALICE = UUID.randomUUID();
	private static final UUID BOB = UUID.randomUUID();

	@Test
	void anotherBotsClaimBlocksTheSameSource() {
		TripClaims claims = new TripClaims();
		claims.claim(ALICE, "chest", 100);

		assertFalse(claims.isFree(BOB, "chest", 0));
		assertTrue(claims.isFree(BOB, "other chest", 0));
	}

	@Test
	void aBotKeepsItsOwnSourceUntilItClaimsAnother() {
		TripClaims claims = new TripClaims();
		claims.claim(ALICE, "chest", 100);
		assertTrue(claims.isFree(ALICE, "chest", 0));

		claims.claim(ALICE, "other chest", 100);
		assertTrue(claims.isFree(BOB, "chest", 0));
		assertFalse(claims.isFree(BOB, "other chest", 0));
	}

	@Test
	void claimsExpire() {
		TripClaims claims = new TripClaims();
		claims.claim(ALICE, "chest", 100);

		assertFalse(claims.isFree(BOB, "chest", 99));
		assertTrue(claims.isFree(BOB, "chest", 100));
	}

	@Test
	void releasedClaimsAreFree() {
		TripClaims claims = new TripClaims();
		claims.claim(ALICE, "chest", 100);
		claims.release(ALICE);

		assertTrue(claims.isFree(BOB, "chest", 0));
	}
}
