package se.metacraft.skins;

import com.mojang.datafixers.util.Pair;
import eu.pb4.polymer.common.api.PolymerCommonUtils;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetCursorItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ItemCombinerMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The drawing check: the adapter between Polymer's per-viewer item callback and
 * {@link Skins#clientView}. The callback only knows who is looking; while it runs, the packet being
 * written (bound in {@link #PACKET} by the mod's mixin on the packet encoder) also tells who the
 * item is with. That's the player whose inventory, worn armour, offhand, cursor or ender chest the
 * packet's own (top-level) stack is in, or the Owner using the workstation it's in. Anything else,
 * including an item inside a bundle or shulker box, an item entity, a frame, an armour stand, a
 * chest's slots and an encode with no packet bound, is with no one: unknown means plain. Polymer's
 * slot hashing (the server comparing a slot with the client's hash of it after a click) writes no
 * packet; the mod's menu mixin binds the slot in {@link #COMPARED} instead, and it's drawn as the
 * packet sending it would draw it. After a reload that took a Look away, {@link #redrawEveryone}
 * sends every online player's items again, tagged so this check reads them right.
 */
public final class DrawingCheck {
	/** The packet being encoded for a client, for the duration of the encode. */
	public static final ScopedValue<Packet<?>> PACKET = ScopedValue.newInstance();
	/** The slot of a player's menu whose item the server is comparing with the client's hash of it, for the duration of the comparison. */
	public static final ScopedValue<MenuSlot> COMPARED = ScopedValue.newInstance();

	/** Slot {@code index} of {@code menu}, or its cursor ({@link #CURSOR}). */
	public record MenuSlot(AbstractContainerMenu menu, int index) {
		public static final int CURSOR = -1;
	}

	private DrawingCheck() {}

	/** What the viewer of {@code context} is sent of {@code original}: Polymer's client copy, drawn by {@code skins}. Any thread. */
	public static ItemStack draw(Skins skins, ItemStack original, ItemStack client, @Nullable PacketContext context) {
		return skins.clientView(original, client, with(original, PolymerCommonUtils.getPlayer(context)));
	}

	/**
	 * After a reload that took a Look away: every online player is sent their own items again (their
	 * inventory, and any menu they have open), and their equipment goes again to everyone who sees
	 * them, tagged with the wearer so {@link #with} reads it as being with them. Clients then drop
	 * the Look that's gone. Server thread.
	 */
	public static void redrawEveryone(MinecraftServer server) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			player.containerMenu.sendAllDataToRemote();
			if (player.containerMenu != player.inventoryMenu) player.inventoryMenu.sendAllDataToRemote();
			List<Pair<EquipmentSlot, ItemStack>> worn = new ArrayList<>();
			for (EquipmentSlot slot : EquipmentSlot.VALUES) {
				ItemStack item = player.getItemBySlot(slot);
				if (!item.isEmpty()) worn.add(Pair.of(slot, item.copy()));
			}
			if (worn.isEmpty()) continue;
			player.level().getChunkSource().sendToTrackingPlayers(player,
					PolymerEntityUtils.setEntityContext(new ClientboundSetEquipmentPacket(player.getId(), worn), player));
		}
	}

	/** Who {@code original}, a Skinned Item being encoded for {@code viewer}, is with, if anyone; read from the packet being written, or the menu slot being compared. */
	static @Nullable UUID with(ItemStack original, @Nullable ServerPlayer viewer) {
		SkinMarker marker = original.get(SkinMarker.TYPE);
		if (marker == null || viewer == null) return null;
		if (!PACKET.isBound()) return COMPARED.isBound() ? compared(viewer, marker, original, COMPARED.get()) : null;
		Packet<?> packet = PACKET.get();
		// Equipment and entity data: Polymer ties them to their entity. Only a player carries anything.
		Entity entity = PolymerEntityUtils.getEntityContext(packet);
		if (entity != null) return entity instanceof Player player && entityStacks(packet).stream().anyMatch(s -> s == original) ? player.getUUID() : null;
		return switch (packet) {
			case ClientboundSetCursorItemPacket cursor -> cursor.contents() == original ? viewer.getUUID() : null;
			case ClientboundSetPlayerInventoryPacket slot -> slot.contents() == original ? viewer.getUUID() : null;
			case ClientboundContainerSetSlotPacket slot -> slot.getItem() == original ? inMenu(viewer, marker, slot.getContainerId(), slot.getSlot()) : null;
			case ClientboundContainerSetContentPacket content -> {
				if (content.carriedItem() == original) yield viewer.getUUID();
				yield inMenu(viewer, marker, content.containerId(), indexOf(content.items(), original));
			}
			default -> null;
		};
	}

	/** The packet's own stacks, for an equipment or entity-data packet. */
	private static List<ItemStack> entityStacks(Packet<?> packet) {
		return switch (packet) {
			case ClientboundSetEquipmentPacket equipment -> equipment.getSlots().stream().map(Pair::getSecond).toList();
			case ClientboundSetEntityDataPacket data -> data.packedItems().stream().filter(v -> v.value() instanceof ItemStack)
					.map(v -> (ItemStack) v.value()).toList();
			default -> List.of();
		};
	}

	/** The item in {@code at}, one of the viewer's own menus, being compared with their client's hash of it: with whom it'd be sent. */
	private static @Nullable UUID compared(ServerPlayer viewer, SkinMarker marker, ItemStack original, MenuSlot at) {
		AbstractContainerMenu menu = at.menu();
		if (at.index() == MenuSlot.CURSOR) return menu.getCarried() == original ? viewer.getUUID() : null;
		return menu.getSlot(at.index()).getItem() == original ? inMenu(viewer, marker, menu, at.index()) : null;
	}

	/** Slot {@code slot} of the viewer's menu {@code containerId}, if it's the one they have open (or their inventory). */
	private static @Nullable UUID inMenu(ServerPlayer viewer, SkinMarker marker, int containerId, int slot) {
		if (slot < 0) return null;
		if (containerId == InventoryMenu.CONTAINER_ID) return viewer.getUUID();
		// Read from the network thread: a menu's slots never change, and a replaced menu has another id.
		AbstractContainerMenu menu = viewer.containerMenu;
		return menu.containerId == containerId ? inMenu(viewer, marker, menu, slot) : null;
	}

	/**
	 * Slot {@code slot} of {@code menu}, one of the viewer's: their inventory menu is all theirs, 2×2
	 * grid included. In another menu, only for the Owner: every slot of a {@linkplain #workstation
	 * workstation}, result included, and elsewhere only their inventory and ender chest slots.
	 */
	private static @Nullable UUID inMenu(ServerPlayer viewer, SkinMarker marker, AbstractContainerMenu menu, int slot) {
		if (menu == viewer.inventoryMenu) return viewer.getUUID();
		if (!viewer.getUUID().equals(marker.owner()) || slot >= menu.slots.size()) return null;
		if (workstation(menu)) return viewer.getUUID();
		Container container = menu.getSlot(slot).container;
		return container == viewer.getInventory() || container == viewer.getEnderChestInventory() ? viewer.getUUID() : null;
	}

	/**
	 * A workstation's menu: the crafting table, the smithing table and anvil ({@link ItemCombinerMenu}),
	 * the grindstone, enchanting table, stonecutter, loom or cartography table. Its slots aren't storage: when it closes, it gives what's
	 * in them back to the player (each one's {@code removed} calls {@code clearContainer}, as only the
	 * inventory menu does besides), so an item in them is still with that player. Storage menus
	 * (chests, barrels, shulker boxes, hoppers, furnaces, crafters, brewing stands, dispensers) keep
	 * their items, which are Away.
	 */
	private static boolean workstation(AbstractContainerMenu menu) {
		return menu instanceof CraftingMenu || menu instanceof ItemCombinerMenu || menu instanceof GrindstoneMenu || menu instanceof EnchantmentMenu
				|| menu instanceof StonecutterMenu || menu instanceof LoomMenu || menu instanceof CartographyTableMenu;
	}

	private static int indexOf(List<ItemStack> items, ItemStack stack) {
		for (int i = 0; i < items.size(); i++) if (items.get(i) == stack) return i;
		return -1;
	}
}
