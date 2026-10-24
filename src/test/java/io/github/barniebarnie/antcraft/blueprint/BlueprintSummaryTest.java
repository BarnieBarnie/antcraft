package io.github.barniebarnie.antcraft.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import io.github.barniebarnie.antcraft.item.BlueprintItem;
import io.github.barniebarnie.antcraft.network.ColonyNetworking;
import io.github.barniebarnie.antcraft.work.VanillaData;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** What the server tells clients about its blueprints, and how the picker and the Blueprint item show it. */
class BlueprintSummaryTest {
	private static final Path BLUEPRINTS = Path.of("src/main/resources/data/antcraft/blueprint");

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("antcraft", path);
	}

	private static Blueprint blueprint(String name) throws IOException {
		return Blueprint.parse(JsonParser.parseString(Files.readString(BLUEPRINTS.resolve(name + ".json")))).getOrThrow();
	}

	private static BlueprintSummary bundled(String name) throws IOException {
		return BlueprintSummary.of(id(name), blueprint(name), Optional.of(VanillaData.structure(blueprint(name).structure().getPath())));
	}

	private static BlueprintBlocks bundledBlocks(String name) throws IOException {
		return BlueprintBlocks.of(id(name), Optional.of(VanillaData.structure(blueprint(name).structure().getPath())));
	}

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
	}

	private static String key(Component component) {
		return ((TranslatableContents) component.getContents()).getKey();
	}

	private static Object[] args(Component component) {
		return ((TranslatableContents) component.getContents()).getArgs();
	}

	@Test
	void summaryHoldsTheStructuresSizeAndMaterials() throws IOException {
		BlueprintSummary step = bundled("cobble_step");
		assertEquals(id("cobble_step"), step.id());
		assertEquals("blueprint.antcraft.cobble_step", step.name());
		assertEquals(Optional.empty(), step.role());
		assertEquals(new Vec3i(2, 2, 1), step.size());
		Map<Item, Integer> materials = new LinkedHashMap<>();
		materials.put(Items.COBBLESTONE, 2);
		materials.put(Items.OAK_STAIRS, 1);
		assertEquals(materials, step.materials());
		assertEquals(List.copyOf(materials.keySet()), List.copyOf(step.materials().keySet()), "most first");
	}

	@Test
	void blocksHoldTheStructuresBlocksWithoutAir() throws IOException {
		BlueprintBlocks step = bundledBlocks("cobble_step");
		assertEquals(id("cobble_step"), step.id());
		assertEquals(new Vec3i(2, 2, 1), step.size());
		assertTrue(step.blocks().contains(new BlueprintSummary.Block(new BlockPos(0, 1, 0),
			Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST))), step.blocks().toString());
		assertTrue(step.blocks().stream().noneMatch(block -> block.state().isAir()), "air is left out: " + step.blocks());
	}

	@Test
	void aBlueprintWithoutItsStructureHasNoSizeOrBlocks() {
		BlueprintSummary missing = BlueprintSummary.of(id("lost"), new Blueprint(id("nowhere"), "blueprint.antcraft.lost", Optional.empty(), List.of(), List.of()),
			Optional.empty());
		assertEquals(Vec3i.ZERO, missing.size());
		assertEquals(Map.of(), missing.materials());
		assertEquals(new BlueprintBlocks(id("lost"), Vec3i.ZERO, List.of()), BlueprintBlocks.of(id("lost"), Optional.empty()));
	}

	/** A cube of {@code side} filled with {@code kinds} different block states, none air. */
	private static BlueprintBlocks cube(int side, int kinds) {
		List<BlockState> states = BuiltInRegistries.BLOCK.stream().filter(block -> !block.defaultBlockState().isAir())
			.flatMap(block -> block.getStateDefinition().getPossibleStates().stream()).limit(kinds).toList();
		List<BlueprintSummary.Block> blocks = new ArrayList<>();
		for (int y = 0; y < side; y++) {
			for (int z = 0; z < side; z++) {
				for (int x = 0; x < side; x++) {
					blocks.add(new BlueprintSummary.Block(new BlockPos(x, y, z), states.get(blocks.size() % states.size())));
				}
			}
		}
		return new BlueprintBlocks(id("big"), new Vec3i(side, side, side), blocks);
	}

	/** The list holds no blocks, so a blueprint of 32768 blocks takes no more room in it than a small one. */
	@Test
	void theListPayloadOfAHugeBlueprintStaysSmall() {
		BlueprintBlocks big = cube(32, 64);
		assertEquals(32768, big.blocks().size());
		BlueprintSummary summary = BlueprintSummary.of(id("big"), new Blueprint(id("big"), "blueprint.antcraft.big", Optional.empty(), List.of(), List.of()), big);
		assertEquals(new Vec3i(32, 32, 32), summary.size());
		assertFalse(summary.materials().isEmpty());
		RegistryFriendlyByteBuf buf = buffer();
		ColonyNetworking.BlueprintList.STREAM_CODEC.encode(buf, new ColonyNetworking.BlueprintList(List.of(summary)));
		assertTrue(buf.readableBytes() < 4096, buf.readableBytes() + " bytes");
	}

	/** The blocks of the largest structure a structure block saves, in many different states, fit well in one payload. */
	@Test
	void theBlocksOfTheLargestStructureFitInOnePayload() {
		BlueprintBlocks big = cube(48, 20000);
		RegistryFriendlyByteBuf buf = buffer();
		ColonyNetworking.BlueprintBlocksReply.STREAM_CODEC.encode(buf, new ColonyNetworking.BlueprintBlocksReply(big));
		assertTrue(buf.readableBytes() < ColonyNetworking.PAYLOAD_LIMIT / 2, buf.readableBytes() + " bytes");
		assertEquals(big, ColonyNetworking.BlueprintBlocksReply.STREAM_CODEC.decode(buf).blocks());
	}

	/** Blocks too many for one payload are not sent; the size still is, so the client can still tell whether the site is free. */
	@Test
	void blocksTooManyForAPayloadAreLeftOut() {
		BlueprintBlocks huge = cube(80, 10);
		assertEquals(new BlueprintBlocks(id("big"), new Vec3i(80, 80, 80), List.of()), huge.sendable());
		BlueprintBlocks big = cube(48, 10);
		assertEquals(big, big.sendable());
	}

	@Test
	void blocksAreKeptInLayerOrderAndInsideTheirSize() {
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlueprintBlocks blocks = new BlueprintBlocks(id("step"), new Vec3i(2, 2, 2), List.of(
			new BlueprintSummary.Block(new BlockPos(1, 1, 0), stone),
			new BlueprintSummary.Block(new BlockPos(0, 0, 1), stone),
			new BlueprintSummary.Block(new BlockPos(1, 0, 0), stone),
			new BlueprintSummary.Block(new BlockPos(2, 0, 0), stone)));
		assertEquals(List.of(new BlockPos(1, 0, 0), new BlockPos(0, 0, 1), new BlockPos(1, 1, 0)),
			blocks.blocks().stream().map(BlueprintSummary.Block::pos).toList());
	}

	/** The picker shows each blueprint's name, size, role and materials. */
	@Test
	void pickerLinesNameSizeRoleAndMaterials() throws IOException {
		BlueprintSummary step = bundled("cobble_step");
		assertEquals("blueprint.antcraft.cobble_step", key(step.title()));
		List<Component> details = step.details();
		assertEquals(List.of("screen.antcraft.blueprint_picker.size", "screen.antcraft.blueprint_picker.materials"),
			details.stream().map(BlueprintSummaryTest::key).toList());
		assertEquals(List.of(2, 2, 1), List.of(args(details.get(0))));

		BlueprintSummary farm = new BlueprintSummary(id("farm"), "blueprint.antcraft.farm", Optional.of(Blueprint.Role.FARM), new Vec3i(1, 1, 1), new LinkedHashMap<>());
		List<Component> farmDetails = farm.details();
		assertEquals(List.of("screen.antcraft.blueprint_picker.size", "screen.antcraft.blueprint_picker.role"),
			farmDetails.stream().map(BlueprintSummaryTest::key).toList());
		assertEquals("screen.antcraft.blueprint_picker.role.farm", key((Component) args(farmDetails.get(1))[0]));
	}

	/** Every bundled blueprint has a summary with a size and materials to show in the picker. */
	@Test
	void everyBundledBlueprintHasASummary() throws IOException {
		List<String> names;
		try (Stream<Path> files = Files.list(BLUEPRINTS)) {
			names = files.map(file -> file.getFileName().toString().replace(".json", "")).sorted().toList();
		}
		assertEquals(List.of("cobble_generator", "cobble_step", "farm", "starter_house", "warehouse"), names);
		for (String name : names) {
			BlueprintSummary summary = bundled(name);
			assertTrue(summary.size().getX() > 0 && summary.size().getY() > 0 && summary.size().getZ() > 0, name + " " + summary.size());
			assertFalse(summary.materials().isEmpty(), name);
		}
	}

	@Test
	void blueprintListRoundTripsThroughItsStreamCodec() throws IOException {
		ColonyNetworking.BlueprintList list = new ColonyNetworking.BlueprintList(List.of(bundled("cobble_step"), bundled("starter_house"),
			new BlueprintSummary(id("farm"), "blueprint.antcraft.farm", Optional.of(Blueprint.Role.FARM), Vec3i.ZERO, new LinkedHashMap<>())));
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ColonyNetworking.BlueprintList.STREAM_CODEC.encode(buf, list);
		assertEquals(list, ColonyNetworking.BlueprintList.STREAM_CODEC.decode(buf));
		assertEquals(0, buf.readableBytes());
	}

	@Test
	void blueprintBlocksRequestAndReplyRoundTripThroughTheirStreamCodecs() throws IOException {
		ColonyNetworking.BlueprintBlocksRequest request = new ColonyNetworking.BlueprintBlocksRequest(id("starter_house"));
		RegistryFriendlyByteBuf buf = buffer();
		ColonyNetworking.BlueprintBlocksRequest.STREAM_CODEC.encode(buf, request);
		assertEquals(request, ColonyNetworking.BlueprintBlocksRequest.STREAM_CODEC.decode(buf));
		for (BlueprintBlocks blocks : List.of(bundledBlocks("starter_house"), bundledBlocks("cobble_step"), new BlueprintBlocks(id("lost"), Vec3i.ZERO, List.of()))) {
			ColonyNetworking.BlueprintBlocksReply reply = new ColonyNetworking.BlueprintBlocksReply(blocks);
			ColonyNetworking.BlueprintBlocksReply.STREAM_CODEC.encode(buf, reply);
			assertEquals(reply, ColonyNetworking.BlueprintBlocksReply.STREAM_CODEC.decode(buf));
			assertEquals(0, buf.readableBytes());
		}
	}

	@Test
	void chooseBlueprintRoundTripsThroughItsStreamCodec() {
		ColonyNetworking.ChooseBlueprint choose = new ColonyNetworking.ChooseBlueprint(InteractionHand.OFF_HAND, id("cobble_step"));
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ColonyNetworking.ChooseBlueprint.STREAM_CODEC.encode(buf, choose);
		assertEquals(choose, ColonyNetworking.ChooseBlueprint.STREAM_CODEC.decode(buf));
	}

	@Test
	void chosenBlueprintRoundTripsThroughItsCodecs() {
		ChosenBlueprint chosen = new ChosenBlueprint(id("cobble_step"), "blueprint.antcraft.cobble_step");
		assertEquals(chosen, ChosenBlueprint.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
			ChosenBlueprint.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, chosen).getOrThrow()).getOrThrow());
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		ChosenBlueprint.STREAM_CODEC.encode(buf, chosen);
		assertEquals(chosen, ChosenBlueprint.STREAM_CODEC.decode(buf));
	}

	/** The item's tooltip names the chosen blueprint, or says none is chosen yet. */
	@Test
	void tooltipNamesTheChosenBlueprint() {
		Component chosen = BlueprintItem.tooltip(Optional.of(new ChosenBlueprint(id("cobble_step"), "blueprint.antcraft.cobble_step")));
		assertEquals("item.antcraft.blueprint.chosen", key(chosen));
		assertEquals("blueprint.antcraft.cobble_step", key((Component) args(chosen)[0]));
		assertEquals("item.antcraft.blueprint.none", key(BlueprintItem.tooltip(Optional.empty())));
	}
}
