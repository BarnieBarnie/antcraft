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

	@Test
	void onlyPlayersInRangeMayAct() {
		Colony colony = colony();
		assertTrue(ColonyActions.mayAct(colony, CORE.offset(10, 0, 10), false, false));
		assertFalse(ColonyActions.mayAct(colony, CORE.offset(100, 0, 0), false, false));
		assertFalse(ColonyActions.mayAct(colony, CORE.offset(100, 0, 0), true, false));
	}

	@Test
	void onlyOperatorsMayActWhenTheConfigSaysSo() {
		Colony colony = colony();
		assertFalse(ColonyActions.mayAct(colony, CORE, false, true));
		assertTrue(ColonyActions.mayAct(colony, CORE, true, true));
	}

	@Test
	void onlyMembersOfThisColonyCanBeGivenRoles() {
		Colony colony = colony();
		assertTrue(ColonyActions.isMember(colony, MEMBER, colony.id()));
		assertFalse(ColonyActions.isMember(colony, MEMBER, UUID.randomUUID()), "member of another colony");
		assertFalse(ColonyActions.isMember(colony, MEMBER, null), "left the colony");
		assertFalse(ColonyActions.isMember(colony, UUID.randomUUID(), colony.id()), "unknown bot");
	}

	@Test
	void rolesAreGivenOnlyWhenBotsAreAllowedOnTheServer() {
		Colony colony = colony();
		assertTrue(ColonyActions.mayGiveRole(colony, MEMBER, colony.id(), true));
		assertFalse(ColonyActions.mayGiveRole(colony, MEMBER, colony.id(), false), "bots not allowed");
		assertFalse(ColonyActions.mayGiveRole(colony, UUID.randomUUID(), colony.id(), true), "not a member");
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
