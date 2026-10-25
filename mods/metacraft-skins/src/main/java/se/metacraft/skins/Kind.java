package se.metacraft.skins;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The fixed family of items a skin fits, every material of it (GLOSSARY: Kind). Membership is the
 * vanilla item tag where there is one, so other mods' items count when they tag them; the elytra
 * has no tag and is anything that glides from the chest slot. Anything that stacks is never a
 * member, and neither are the bow, crossbow, fishing rod, shield, trident and the small tools:
 * no Kind's tag holds them.
 */
public enum Kind {
	SWORD(ItemTags.SWORDS, Items.DIAMOND_SWORD),
	AXE(ItemTags.AXES, Items.DIAMOND_AXE),
	PICKAXE(ItemTags.PICKAXES, Items.DIAMOND_PICKAXE),
	SHOVEL(ItemTags.SHOVELS, Items.DIAMOND_SHOVEL),
	HOE(ItemTags.HOES, Items.DIAMOND_HOE),
	MACE(ItemTags.MACE_ENCHANTABLE, Items.MACE),
	SPEAR(ItemTags.SPEARS, Items.DIAMOND_SPEAR),
	HELMET(ItemTags.HEAD_ARMOR, Items.DIAMOND_HELMET),
	CHESTPLATE(ItemTags.CHEST_ARMOR, Items.DIAMOND_CHESTPLATE),
	LEGGINGS(ItemTags.LEG_ARMOR, Items.DIAMOND_LEGGINGS),
	BOOTS(ItemTags.FOOT_ARMOR, Items.DIAMOND_BOOTS),
	ELYTRA(null, Items.ELYTRA);

	private final TagKey<Item> tag;
	private final Item icon;

	Kind(TagKey<Item> tag, Item icon) {
		this.tag = tag;
		this.icon = icon;
	}

	/** As written in {@code skin.json5}, and as players read it ("Fits any sword"). */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** "a sword", "an axe", "boots": for "You aren't carrying …". */
	public String aOne() {
		if (this == LEGGINGS || this == BOOTS) return id();
		return ("aeiou".indexOf(id().charAt(0)) >= 0 ? "an " : "a ") + id();
	}

	public static Optional<Kind> byId(String id) {
		return Arrays.stream(values()).filter(k -> k.id().equals(id)).findFirst();
	}

	/** The one Kind whose Look has two models: an icon and an in-hand model. */
	public boolean isSpear() {
		return this == SPEAR;
	}

	/** A hat: its Look is also drawn on the wearer's head. */
	public boolean isHat() {
		return this == HELMET;
	}

	/** The vanilla item a skin of this Kind is shown on in menus (with the skin's Look). */
	public Item icon() {
		return icon;
	}

	/** Is this item of this Kind? Read off the real (server) item, never a stack that stacks. */
	public boolean fits(ItemStack stack) {
		if (stack.isEmpty() || stack.isStackable()) return false;
		if (tag != null) return stack.is(tag);
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return stack.has(DataComponents.GLIDER) && equippable != null && equippable.slot() == EquipmentSlot.CHEST;
	}

	/** The Kind an item belongs to, if any. */
	public static Optional<Kind> of(ItemStack stack) {
		return Arrays.stream(values()).filter(k -> k.fits(stack)).findFirst();
	}
}
