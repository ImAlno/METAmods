package se.metacraft.skins;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Where on a player an item is: a slot of their {@link Inventory}, worn armour and offhand
 * included (0–8 the hotbar, 9–35 the rest, 36–39 feet to head, 40 the offhand).
 */
public record ItemLocation(int slot) {
	private static final int HOTBAR = 9, MAIN = 36;
	/** Every place a skin can be applied, in the order the apply screen lists them. */
	public static final List<ItemLocation> ON_A_PLAYER;

	static {
		List<ItemLocation> all = new java.util.ArrayList<>();
		for (int i = 0; i < MAIN; i++) all.add(new ItemLocation(i));
		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) all.add(worn(slot));
		all.add(offhand());
		ON_A_PLAYER = List.copyOf(all);
	}

	public ItemLocation {
		if (slot < 0 || slot > Inventory.SLOT_OFFHAND) throw new IllegalArgumentException("not a slot on a player: " + slot);
	}

	public static ItemLocation hotbar(int index) {
		if (index < 0 || index >= HOTBAR) throw new IllegalArgumentException("not a hotbar slot: " + index);
		return new ItemLocation(index);
	}

	public static ItemLocation worn(EquipmentSlot slot) {
		for (int i = MAIN; i < Inventory.SLOT_OFFHAND; i++) {
			if (Inventory.EQUIPMENT_SLOT_MAPPING.get(i) == slot) return new ItemLocation(i);
		}
		throw new IllegalArgumentException("not a worn slot: " + slot);
	}

	public static ItemLocation offhand() {
		return new ItemLocation(Inventory.SLOT_OFFHAND);
	}

	public ItemStack in(Inventory inventory) {
		return inventory.getItem(slot);
	}

	/** As the apply screen labels it: "Hotbar slot 1", "Inventory", "Worn (head)", "Offhand". */
	public String label() {
		if (slot < HOTBAR) return "Hotbar slot " + (slot + 1);
		if (slot < MAIN) return "Inventory";
		if (slot == Inventory.SLOT_OFFHAND) return "Offhand";
		return "Worn (" + Inventory.EQUIPMENT_SLOT_MAPPING.get(slot).getName().toLowerCase(Locale.ROOT) + ")";
	}
}
