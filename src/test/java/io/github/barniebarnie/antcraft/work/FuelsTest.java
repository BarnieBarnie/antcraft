package io.github.barniebarnie.antcraft.work;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.barniebarnie.antcraft.colony.TaskPlanner;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FuelsTest {
	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void coalFirstThenCharcoalThenLogs() {
		List<TaskPlanner.Fuel<Item>> order = Fuels.order(Set.of(Items.OAK_LOG, Items.BIRCH_LOG));

		assertEquals(List.of(
			new TaskPlanner.Fuel<>(Set.of(Items.COAL), 1600),
			new TaskPlanner.Fuel<>(Set.of(Items.CHARCOAL), 1600),
			new TaskPlanner.Fuel<>(Set.of(Items.OAK_LOG, Items.BIRCH_LOG), 300)), order);
	}

	@Test
	void oneCoalSmeltsEightItems() {
		TaskPlanner.Fuel<Item> coal = Fuels.order(Set.of()).getFirst();

		assertEquals(1, coal.itemsFor(8));
		assertEquals(2, coal.itemsFor(9));
	}
}
