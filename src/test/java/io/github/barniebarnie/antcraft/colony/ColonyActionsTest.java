package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ColonyActionsTest {
	private static final BlockPos CORE = new BlockPos(0, 64, 0);
	private static final UUID MEMBER = UUID.randomUUID();

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Colony colony() {
		return new Colony(UUID.randomUUID(), CORE, List.of(MEMBER), 20);
	}

	private static final UUID OWNER = UUID.randomUUID();
	private static final UUID FRIEND = UUID.randomUUID();
	private static final UUID STRANGER = UUID.randomUUID();
	private static final BlockPos NEAR = CORE.offset(10, 0, 10);
	private static final BlockPos FAR = CORE.offset(100, 0, 0);

	private static Colony owned() {
		Colony colony = colony();
		colony.setOwner(OWNER);
		colony.trust(FRIEND);
		return colony;
	}

	@Test
	void theOwnerAndTrustedPlayersMayActFromAnywhere() {
		Colony colony = owned();
		assertTrue(ColonyActions.mayAct(colony, OWNER, NEAR, false, false));
		assertTrue(ColonyActions.mayAct(colony, OWNER, FAR, false, false));
		assertTrue(ColonyActions.mayAct(colony, FRIEND, NEAR, false, false));
		assertTrue(ColonyActions.mayAct(colony, FRIEND, FAR, false, false));
	}

	@Test
	void otherPlayersMayNotActEvenNearby() {
		Colony colony = owned();
		assertFalse(ColonyActions.mayAct(colony, STRANGER, NEAR, false, false));
		assertFalse(ColonyActions.mayAct(colony, STRANGER, CORE, false, false));
		colony.untrust(FRIEND);
		assertFalse(ColonyActions.mayAct(colony, FRIEND, NEAR, false, false), "untrusted player");
	}

	@Test
	void operatorsMayActOnAnyColony() {
		assertTrue(ColonyActions.mayAct(owned(), STRANGER, NEAR, true, false));
		assertTrue(ColonyActions.mayAct(owned(), STRANGER, FAR, true, false));
		assertTrue(ColonyActions.mayAct(colony(), STRANGER, FAR, true, false), "ownerless colony out of range");
	}

	@Test
	void theOwnerMayChangeWhomTheColonyTrusts() {
		assertTrue(ColonyActions.mayTrust(owned(), OWNER, false, false));
	}

	@Test
	void trustedPlayersAndStrangersMayNotChangeWhomTheColonyTrusts() {
		assertFalse(ColonyActions.mayTrust(owned(), FRIEND, false, false), "trusted player");
		assertFalse(ColonyActions.mayTrust(owned(), STRANGER, false, false), "stranger");
		assertFalse(ColonyActions.mayTrust(colony(), STRANGER, false, false), "anyone, on a colony with no owner");
	}

	@Test
	void operatorsMayChangeWhomAnyColonyTrusts() {
		assertTrue(ColonyActions.mayTrust(owned(), STRANGER, true, false));
		assertTrue(ColonyActions.mayTrust(owned(), STRANGER, true, true), "with colonyActionsRequireOp");
		assertTrue(ColonyActions.mayTrust(colony(), STRANGER, true, false), "colony with no owner");
	}

	@Test
	void withRequireOpTheOwnerMayNotChangeWhomTheColonyTrusts() {
		assertFalse(ColonyActions.mayTrust(owned(), OWNER, false, true));
	}

	@Test
	void ownerlessColoniesLetAnyoneInRangeAct() {
		Colony colony = colony();
		assertTrue(ColonyActions.mayAct(colony, STRANGER, NEAR, false, false));
		assertFalse(ColonyActions.mayAct(colony, STRANGER, FAR, false, false));
	}

	@Test
	void onlyOperatorsMayActWhenTheConfigSaysSo() {
		assertFalse(ColonyActions.mayAct(colony(), STRANGER, CORE, false, true));
		assertFalse(ColonyActions.mayAct(owned(), OWNER, CORE, false, true));
		assertFalse(ColonyActions.mayAct(owned(), FRIEND, CORE, false, true));
		assertTrue(ColonyActions.mayAct(owned(), STRANGER, CORE, true, true));
	}
}
