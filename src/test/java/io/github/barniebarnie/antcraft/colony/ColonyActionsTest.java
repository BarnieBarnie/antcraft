package io.github.barniebarnie.antcraft.colony;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.datafixers.util.Either;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;
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
		return new Colony(UUID.randomUUID(), CORE, List.of(MEMBER), new JobBoard(), 20, Map.of(), Optional.empty(),
			List.of(StockTarget.of(ItemTags.PICKAXES, 2, Items.STONE_PICKAXE)), Map.of(), List.of(), Optional.empty(), Optional.empty(),
			Map.of(), List.of(), Map.of());
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

	@Test
	void viewingFollowsTheSameRuleAsActing() {
		Colony colony = owned();
		for (UUID player : List.of(OWNER, FRIEND, STRANGER)) {
			for (BlockPos from : List.of(NEAR, FAR)) {
				for (boolean isOp : new boolean[] {false, true}) {
					for (boolean requireOp : new boolean[] {false, true}) {
						String which = player + " from " + from + " isOp=" + isOp + " requireOp=" + requireOp;
						assertEquals(ColonyActions.mayAct(colony, player, from, isOp, requireOp),
							ColonyActions.mayView(colony, player, from, isOp, requireOp), which);
					}
				}
			}
		}
	}

	@Test
	void theFirstPlayerWhoMayActClaimsAnOwnerlessColony() {
		Colony colony = colony();
		assertFalse(ColonyActions.claim(colony, STRANGER, FAR, false, false), "claimed from out of range");
		assertEquals(Optional.empty(), colony.owner());
		assertFalse(ColonyActions.claim(colony, STRANGER, NEAR, false, true), "non-operator claimed with only operators allowed");
		assertEquals(Optional.empty(), colony.owner());

		assertTrue(ColonyActions.claim(colony, OWNER, NEAR, false, false));
		assertEquals(Optional.of(OWNER), colony.owner());
		assertFalse(ColonyActions.claim(colony, STRANGER, NEAR, true, false), "an operator took over an owned colony");
		assertEquals(Optional.of(OWNER), colony.owner());
	}

	@Test
	void editsOnlyChangeColonyDataSoTheyDoNotNeedBotsAllowed() {
		for (ColonyActions.Edit edit : ColonyActions.Edit.values()) {
			assertTrue(ColonyActions.mayEdit(edit, true), edit + " with bots allowed");
			assertTrue(ColonyActions.mayEdit(edit, false), edit + " with bots not allowed");
		}
	}

	private static final List<ColonyActions.Edit> COMMAND_EDITS = List.of(ColonyActions.Edit.MINESHAFT, ColonyActions.Edit.BUILD_COPY, ColonyActions.Edit.BUILD_PLACE,
		ColonyActions.Edit.BUILD_CANCEL, ColonyActions.Edit.TERRAFORM, ColonyActions.Edit.CLEAR_BLOCKED, ColonyActions.Edit.PROJECT_CANCEL,
		ColonyActions.Edit.PROJECT_PRIORITY, ColonyActions.Edit.BUILD_RESTORE);

	@Test
	void mineshaftBuildTerraformAndBlockedEditsOnlyChangeColonyData() {
		for (ColonyActions.Edit edit : COMMAND_EDITS) {
			assertFalse(edit.commandsBots(), edit.toString());
			assertTrue(ColonyActions.mayEdit(edit, true), edit + " with bots allowed");
			assertTrue(ColonyActions.mayEdit(edit, false), edit + " with bots not allowed");
		}
	}

	@Test
	void theScreenCannotMakeEditsThatOnlyTheCommandsHave() {
		for (ColonyActions.Edit edit : COMMAND_EDITS) {
			Colony colony = colony();
			assertFalse(edit.fromScreen(), edit.toString());
			assertFalse(ColonyActions.edit(colony, edit, "minecraft:torch", 8), edit.toString());
			assertEquals(Map.of(), colony.orders(), edit.toString());
			assertEquals(List.of(), colony.builds(), edit.toString());
			assertEquals(20, colony.radius(), edit.toString());
		}
		for (ColonyActions.Edit edit : ColonyActions.Edit.values()) {
			assertEquals(!COMMAND_EDITS.contains(edit), edit.fromScreen(), edit.toString());
		}
	}

	@Test
	void ordersAnItem() {
		Colony colony = colony();
		assertTrue(ColonyActions.edit(colony, ColonyActions.Edit.ORDER, "minecraft:torch", 8));
		assertEquals(Map.of(Items.TORCH, 8), colony.orders());
	}

	@Test
	void rejectsOrdersForUnknownItemsOrCountsOutOfRange() {
		Colony colony = colony();
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.ORDER, "minecraft:no_such_item", 8));
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.ORDER, "not an id", 8));
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.ORDER, "minecraft:air", 8));
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.ORDER, "#minecraft:logs", 8));
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.ORDER, "minecraft:torch", 0));
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.ORDER, "minecraft:torch", ColonyActions.MAX_COUNT + 1));
		assertEquals(Map.of(), colony.orders());
	}

	@Test
	void cancelsOnlyThePlayersPartOfAnOrder() {
		Colony colony = colony();
		colony.addOrder(Items.OAK_LOG, 1);
		colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.OAK_LOG, 3));
		colony.addOrder(Items.TORCH, 4);

		assertTrue(ColonyActions.edit(colony, ColonyActions.Edit.CANCEL, "minecraft:oak_log", 0));
		assertTrue(ColonyActions.edit(colony, ColonyActions.Edit.CANCEL, "minecraft:torch", 0));
		assertEquals(Map.of(Items.OAK_LOG, 3), colony.orders());
		assertEquals(Map.of(Items.OAK_LOG, 3), colony.ordersFor(Colony.OrderPurpose.BUILD));
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.CANCEL, "minecraft:no_such_item", 0));
	}

	@Test
	void theCancelCommandAndTheScreenLeaveTheSameOrders() {
		Colony byCommand = colony();
		Colony byScreen = colony();
		for (Colony colony : List.of(byCommand, byScreen)) {
			colony.addOrder(Items.OAK_LOG, 1);
			colony.orderFor(Colony.OrderPurpose.BUILD, Map.of(Items.OAK_LOG, 3));
			colony.orderFor(Colony.OrderPurpose.STORAGE, Map.of(Items.CHEST, 2));
		}

		ColonyActions.cancelOrder(byCommand, Items.OAK_LOG);
		ColonyActions.cancelOrder(byCommand, Items.CHEST);
		assertTrue(ColonyActions.edit(byScreen, ColonyActions.Edit.CANCEL, "minecraft:oak_log", 0));
		assertTrue(ColonyActions.edit(byScreen, ColonyActions.Edit.CANCEL, "minecraft:chest", 0));

		assertEquals(byScreen.orders(), byCommand.orders());
		assertEquals(Map.of(Items.OAK_LOG, 3, Items.CHEST, 2), byCommand.orders());
		for (Colony.OrderPurpose purpose : Colony.OrderPurpose.values()) {
			assertEquals(byScreen.ordersFor(purpose), byCommand.ordersFor(purpose), purpose.toString());
		}
	}

	@Test
	void setsStockTargetsForItemsAndTagsKeepingWhatToMake() {
		Colony colony = colony();
		assertTrue(ColonyActions.edit(colony, ColonyActions.Edit.SET_STOCK, "#minecraft:pickaxes", 5));
		assertTrue(ColonyActions.edit(colony, ColonyActions.Edit.SET_STOCK, "minecraft:torch", 16));
		assertEquals(List.of(StockTarget.of(ItemTags.PICKAXES, 5, Items.STONE_PICKAXE), StockTarget.of(Items.TORCH, 16)), colony.stockTargets());
	}

	@Test
	void rejectsStockTargetsForUnknownItemsOrCountsOutOfRange() {
		Colony colony = colony();
		List<StockTarget> before = colony.stockTargets();
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.SET_STOCK, "minecraft:no_such_item", 5));
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.SET_STOCK, "#not a tag", 5));
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.SET_STOCK, "#minecraft:pickaxes", 0));
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.SET_STOCK, "minecraft:torch", ColonyActions.MAX_COUNT + 1));
		assertEquals(before, colony.stockTargets());
	}

	@Test
	void removesStockTargets() {
		Colony colony = colony();
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.REMOVE_STOCK, "minecraft:torch", 0));
		assertTrue(ColonyActions.edit(colony, ColonyActions.Edit.REMOVE_STOCK, "#minecraft:pickaxes", 0));
		assertEquals(List.of(), colony.stockTargets());
	}

	@Test
	void changesTheRadiusWithinTheCommandsLimits() {
		Colony colony = colony();
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.RADIUS, "", 3));
		assertFalse(ColonyActions.edit(colony, ColonyActions.Edit.RADIUS, "", 129));
		assertEquals(20, colony.radius());
		assertTrue(ColonyActions.edit(colony, ColonyActions.Edit.RADIUS, "", 48));
		assertEquals(48, colony.radius());
	}
}
