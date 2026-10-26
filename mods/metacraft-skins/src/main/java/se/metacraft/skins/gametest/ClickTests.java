package se.metacraft.skins.gametest;

import com.google.common.hash.HashCode;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import se.metacraft.skins.ItemLocation;
import se.metacraft.skins.MetacraftSkins;
import se.metacraft.skins.Skins;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.HashOps;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;

import static se.metacraft.skins.gametest.SkinFixtures.assertThat;

/**
 * What the Owner's client shows as they move a Skinned Item between places, click by click: real
 * click packets carrying the hashes a vanilla client sends of what it predicts, answered by the
 * server through a real packet encoder. The slots of a storage block are Away; the Owner's own slots,
 * their cursor and the slots of a workstation they're using are with them.
 */
public final class ClickTests {
	private static final BlockPos BLOCK = new BlockPos(2, 1, 2);

	/**
	 * A sword the Owner puts into a chest, barrel, hopper or shulker box turns plain at once, and shows
	 * its Look again the moment it's back with them: on the cursor, or in their inventory by a click, a
	 * shift-click, a hotbar key or a drag.
	 */
	@GameTest
	public void aSkinnedItemShowsItsLookAgainOnceItLeavesStorage(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null);
		fx.reload();
		SkinFixtures.Listening owner = SkinFixtures.join(helper);
		Skins previous = MetacraftSkins.use(fx.skins);
		try {
			skinnedSwords(helper, fx, owner, "candy_sword_test");
			for (BlockState storage : List.of(Blocks.CHEST.defaultBlockState(), Blocks.BARREL.defaultBlockState(), Blocks.HOPPER.defaultBlockState(),
					Blocks.SHULKER_BOX.defaultBlockState())) {
				Client client = open(helper, owner, storage);
				String a = "the " + BuiltInRegistries.BLOCK.getKey(storage.getBlock()).getPath();
				int hotbar = client.hotbar(0);
				int last = client.hotbar(8);
				int stored = 0;
				assertLook(client.slot(hotbar), "candy_sword_test", "the sword in the hotbar below " + a);

				client.pickUp(hotbar);
				assertLook(client.cursor(), "candy_sword_test", "the sword picked up from the hotbar above " + a);
				client.put(stored);
				assertPlain(client.slot(stored), Items.DIAMOND_SWORD, "the sword put in " + a);
				client.pickUp(stored);
				assertLook(client.cursor(), "candy_sword_test", "the sword picked up from " + a);
				client.put(hotbar);
				assertLook(client.slot(hotbar), "candy_sword_test", "the sword put back in the hotbar from " + a);

				client.shiftClick(hotbar, stored);
				assertPlain(client.slot(stored), Items.DIAMOND_SWORD, "the sword shift-clicked into " + a);
				client.shiftClick(stored, last);
				assertLook(client.slot(last), "candy_sword_test", "the sword shift-clicked out of " + a);

				client.swap(stored, 8, last);
				assertPlain(client.slot(stored), Items.DIAMOND_SWORD, "the sword swapped into " + a + " with a hotbar key");
				client.swap(stored, 0, hotbar);
				assertLook(client.slot(hotbar), "candy_sword_test", "the sword swapped out of " + a + " with a hotbar key");

				client.pickUp(hotbar);
				client.drag(stored);
				assertPlain(client.slot(stored), Items.DIAMOND_SWORD, "the sword dragged into " + a);
				client.pickUp(stored);
				client.drag(hotbar);
				assertLook(client.slot(hotbar), "candy_sword_test", "the sword dragged out of " + a);

				assertThat(fx.skins.liveSkin(owner.getInventory().getItem(0)).isPresent(), "the sword didn't end up back in hotbar slot 0 from " + a);
				owner.closeContainer();
			}
		} finally {
			MetacraftSkins.use(previous);
			helper.getLevel().getServer().getPlayerList().remove(owner);
		}
		helper.succeed();
	}

	/**
	 * A sword the Owner puts in a workstation keeps its Look, on the cursor and in the slot. A smithing
	 * table's result is drawn as the upgraded item will be: with the Look for a skin that allows
	 * netherite, plain for a diamond-only one, which no longer fits it.
	 */
	@GameTest
	public void aSkinnedItemKeepsItsLookInAWorkstation(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null).limited("diamond_sword_test", "sword", "[\"diamond\"]");
		fx.reload();
		SkinFixtures.Listening owner = SkinFixtures.join(helper);
		Skins previous = MetacraftSkins.use(fx.skins);
		try {
			skinnedSwords(helper, fx, owner, "candy_sword_test", "diamond_sword_test");
			owner.getInventory().setItem(2, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
			owner.getInventory().setItem(3, new ItemStack(Items.NETHERITE_INGOT));
			// Each workstation a sword fits in (a loom and a cartography table take none), and its slot for it.
			List<Map.Entry<BlockState, Integer>> inputs = List.of(Map.entry(Blocks.CRAFTING_TABLE.defaultBlockState(), 1),
					Map.entry(Blocks.SMITHING_TABLE.defaultBlockState(), 1), Map.entry(Blocks.ANVIL.defaultBlockState(), 0),
					Map.entry(Blocks.GRINDSTONE.defaultBlockState().setValue(GrindstoneBlock.FACE, AttachFace.FLOOR), 0),
					Map.entry(Blocks.ENCHANTING_TABLE.defaultBlockState(), 0), Map.entry(Blocks.STONECUTTER.defaultBlockState(), 0));
			for (Map.Entry<BlockState, Integer> station : inputs) {
				Client client = open(helper, owner, station.getKey());
				String a = "the " + BuiltInRegistries.BLOCK.getKey(station.getKey().getBlock()).getPath();
				int hotbar = client.hotbar(0);
				int input = station.getValue();
				client.pickUp(hotbar);
				client.put(input);
				assertLook(client.slot(input), "candy_sword_test", "the sword put in " + a);
				client.pickUp(input);
				assertLook(client.cursor(), "candy_sword_test", "the sword picked up from " + a);
				client.put(hotbar);
				assertLook(client.slot(hotbar), "candy_sword_test", "the sword put back in the hotbar from " + a);
				owner.closeContainer();
			}

			Client smithing = open(helper, owner, Blocks.SMITHING_TABLE.defaultBlockState());
			smithing.pickUp(smithing.hotbar(2));
			smithing.put(0);
			smithing.pickUp(smithing.hotbar(3));
			smithing.put(2);
			smithing.pickUp(smithing.hotbar(0));
			smithing.put(1);
			assertLook(smithing.slot(3), "candy_sword_test", "the netherite upgrade of a sword whose skin allows netherite");
			smithing.pickUp(1);
			smithing.put(smithing.hotbar(0));
			smithing.pickUp(smithing.hotbar(1));
			smithing.put(1);
			assertLook(smithing.slot(1), "diamond_sword_test", "a diamond-only skin's sword on the smithing table");
			assertPlain(smithing.slot(3), Items.NETHERITE_SWORD, "the netherite upgrade of a sword whose skin is diamond-only");
			smithing.pickUp(1);
			smithing.put(smithing.hotbar(1));
			owner.closeContainer();
		} finally {
			MetacraftSkins.use(previous);
			helper.getLevel().getServer().getPlayerList().remove(owner);
		}
		helper.succeed();
	}

	/** Gives {@code owner} a diamond sword per skin, from hotbar slot 0, each skinned by them; stands them by the test's block; encodes for real from now on. */
	private static void skinnedSwords(GameTestHelper helper, SkinFixtures fx, SkinFixtures.Listening owner, String... skins) {
		owner.getInventory().clearContent();
		for (int i = 0; i < skins.length; i++) {
			owner.getInventory().setItem(i, new ItemStack(Items.DIAMOND_SWORD));
			fx.skins.grant(owner.nameAndId(), skins[i]);
			assertThat(fx.skins.apply(owner, skins[i], ItemLocation.hotbar(i)).applied(), skins[i] + " wasn't applied");
		}
		owner.setPos(helper.absoluteVec(new Vec3(2.5, 1, 0.5)));
		owner.encodeForReal();
	}

	/** Puts {@code block} at the test's spot and opens it for {@code player} as a right-click does; their client then shows it. */
	private static Client open(GameTestHelper helper, SkinFixtures.Listening player, BlockState block) {
		helper.setBlock(BLOCK, block);
		MenuProvider menu = helper.getLevel().getBlockState(helper.absolutePos(BLOCK)).getMenuProvider(helper.getLevel(), helper.absolutePos(BLOCK));
		assertThat(menu != null, block + " opens no menu");
		player.openMenu(menu);
		// The test's channel encodes as the server sends, before openMenu makes the menu the open one; a real
		// server hands the write to its network thread, which encodes it later. Send it again, now it's open.
		player.containerMenu.sendAllDataToRemote();
		return new Client(player);
	}

	private static void assertLook(ItemStack shown, String skin, String where) {
		assertThat(Identifier.parse("skins:" + skin).equals(shown.get(DataComponents.ITEM_MODEL)), where + " shows " + shown + " as " + shown.get(DataComponents.ITEM_MODEL));
	}

	private static void assertPlain(ItemStack shown, Item item, String where) {
		assertThat(shown.is(item) && BuiltInRegistries.ITEM.getKey(item).equals(shown.get(DataComponents.ITEM_MODEL)),
				where + " shows " + shown + " as " + shown.get(DataComponents.ITEM_MODEL));
	}

	/**
	 * A vanilla client's open menu: what it shows in each slot and on the cursor, as it was last told
	 * or as it predicted after a click. A click is sent as the client sends it: the slots it changed and
	 * the cursor, as hashes of what it now shows there.
	 */
	private static final class Client {
		private final SkinFixtures.Listening player;
		private final HashedPatchMap.HashGenerator hasher;
		private final Int2ObjectMap<ItemStack> slots = new Int2ObjectOpenHashMap<>();
		private ItemStack cursor = ItemStack.EMPTY;

		Client(SkinFixtures.Listening player) {
			this.player = player;
			// The client's hashes, as ServerPlayer's container synchronizer computes them.
			RegistryOps<HashCode> ops = player.registryAccess().createSerializationContext(HashOps.CRC32C_INSTANCE);
			this.hasher = component -> component.encodeValue(ops).getOrThrow().asInt();
			read();
		}

		ItemStack slot(int slot) {
			return slots.getOrDefault(slot, ItemStack.EMPTY);
		}

		ItemStack cursor() {
			return cursor;
		}

		/** The menu slot that shows hotbar slot {@code i}. */
		int hotbar(int i) {
			for (Slot slot : player.containerMenu.slots) if (slot.container == player.getInventory() && slot.getContainerSlot() == i) return slot.index;
			throw SkinFixtures.fail(player.containerMenu + " doesn't show hotbar slot " + i);
		}

		/** A left click on {@code slot}, with nothing on the cursor: picks up what's there. */
		void pickUp(int slot) {
			click(slot, 0, ContainerInput.PICKUP, Map.of(slot, ItemStack.EMPTY), slot(slot));
		}

		/** A left click on empty {@code slot}: puts the cursor down there. */
		void put(int slot) {
			click(slot, 0, ContainerInput.PICKUP, Map.of(slot, cursor), ItemStack.EMPTY);
		}

		/** A shift-click on {@code from}, which the game moves to empty {@code to}. */
		void shiftClick(int from, int to) {
			click(from, 0, ContainerInput.QUICK_MOVE, Map.of(from, ItemStack.EMPTY, to, slot(from)), cursor);
		}

		/** Hotbar key {@code key} over {@code slot}: swaps it with hotbar slot {@code key}, which the menu shows as {@code hotbar}. */
		void swap(int slot, int key, int hotbar) {
			click(slot, key, ContainerInput.SWAP, Map.of(slot, slot(hotbar), hotbar, slot(slot)), cursor);
		}

		/** A left drag of the cursor over empty {@code slot} alone: start, the slot, end. */
		void drag(int slot) {
			click(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(0, 0), ContainerInput.QUICK_CRAFT, Map.of(), cursor);
			click(slot, AbstractContainerMenu.getQuickcraftMask(1, 0), ContainerInput.QUICK_CRAFT, Map.of(), cursor);
			click(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(2, 0), ContainerInput.QUICK_CRAFT, Map.of(slot, cursor),
					ItemStack.EMPTY);
		}

		/** Shows the prediction at once, sends the click with its hashes, and takes in the server's answer. */
		private void click(int slot, int button, ContainerInput input, Map<Integer, ItemStack> changed, ItemStack carried) {
			Int2ObjectMap<HashedStack> hashes = new Int2ObjectOpenHashMap<>();
			changed.forEach((i, stack) -> hashes.put((int) i, HashedStack.create(stack, hasher)));
			slots.putAll(changed);
			cursor = carried;
			AbstractContainerMenu menu = player.containerMenu;
			player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) slot, (byte) button, input,
					hashes, HashedStack.create(carried, hasher)));
			read();
		}

		/** Takes in what the server sent the client since the last read. */
		private void read() {
			int open = player.containerMenu.containerId;
			for (Object packet : player.received(Object.class)) {
				switch (packet) {
					case ClientboundContainerSetContentPacket content when content.containerId() == open -> {
						slots.clear();
						for (int i = 0; i < content.items().size(); i++) slots.put(i, content.items().get(i));
						cursor = content.carriedItem();
					}
					case ClientboundContainerSetSlotPacket set when set.getContainerId() == open -> slots.put(set.getSlot(), set.getItem());
					case ClientboundSetCursorItemPacket set -> cursor = set.contents();
					default -> { }
				}
			}
		}
	}
}
