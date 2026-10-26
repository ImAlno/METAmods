package se.metacraft.skins;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.Items;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What an item of a Kind is made of, from a fixed set that mirrors vanilla (GLOSSARY: Material).
 * An item's Material is read off the item itself, against a fixed table of the 71 vanilla items
 * that come in several: no data pack, command or other mod can change what it says, and the
 * netherite upgrade swaps the item, so it reads as netherite at once. Any other item (another
 * mod's ruby sword, the mace, the elytra) has none.
 */
public enum Material {
	WOOD, STONE, LEATHER, CHAINMAIL, COPPER, IRON, GOLD, DIAMOND, NETHERITE, TURTLE;

	private static final List<Material> TOOLS = List.of(WOOD, STONE, COPPER, IRON, GOLD, DIAMOND, NETHERITE);
	private static final List<Material> ARMOUR = List.of(LEATHER, CHAINMAIL, COPPER, IRON, GOLD, DIAMOND, NETHERITE);
	private static final List<Material> HELMETS = List.of(LEATHER, CHAINMAIL, COPPER, IRON, GOLD, DIAMOND, NETHERITE, TURTLE);

	/** Each Kind's vanilla items, in the order of {@link #of(Kind)}. */
	private static final Map<Kind, List<Item>> ITEMS = new EnumMap<>(Map.of(
			Kind.SWORD, List.of(Items.WOODEN_SWORD, Items.STONE_SWORD, Items.COPPER_SWORD, Items.IRON_SWORD, Items.GOLDEN_SWORD, Items.DIAMOND_SWORD,
					Items.NETHERITE_SWORD),
			Kind.AXE, List.of(Items.WOODEN_AXE, Items.STONE_AXE, Items.COPPER_AXE, Items.IRON_AXE, Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE),
			Kind.PICKAXE, List.of(Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.COPPER_PICKAXE, Items.IRON_PICKAXE, Items.GOLDEN_PICKAXE,
					Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE),
			Kind.SHOVEL, List.of(Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.COPPER_SHOVEL, Items.IRON_SHOVEL, Items.GOLDEN_SHOVEL, Items.DIAMOND_SHOVEL,
					Items.NETHERITE_SHOVEL),
			Kind.HOE, List.of(Items.WOODEN_HOE, Items.STONE_HOE, Items.COPPER_HOE, Items.IRON_HOE, Items.GOLDEN_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE),
			Kind.SPEAR, List.of(Items.WOODEN_SPEAR, Items.STONE_SPEAR, Items.COPPER_SPEAR, Items.IRON_SPEAR, Items.GOLDEN_SPEAR, Items.DIAMOND_SPEAR,
					Items.NETHERITE_SPEAR),
			Kind.HELMET, List.of(Items.LEATHER_HELMET, Items.CHAINMAIL_HELMET, Items.COPPER_HELMET, Items.IRON_HELMET, Items.GOLDEN_HELMET,
					Items.DIAMOND_HELMET, Items.NETHERITE_HELMET, Items.TURTLE_HELMET),
			Kind.CHESTPLATE, List.of(Items.LEATHER_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE, Items.COPPER_CHESTPLATE, Items.IRON_CHESTPLATE,
					Items.GOLDEN_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE),
			Kind.LEGGINGS, List.of(Items.LEATHER_LEGGINGS, Items.CHAINMAIL_LEGGINGS, Items.COPPER_LEGGINGS, Items.IRON_LEGGINGS, Items.GOLDEN_LEGGINGS,
					Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS),
			Kind.BOOTS, List.of(Items.LEATHER_BOOTS, Items.CHAINMAIL_BOOTS, Items.COPPER_BOOTS, Items.IRON_BOOTS, Items.GOLDEN_BOOTS, Items.DIAMOND_BOOTS,
					Items.NETHERITE_BOOTS)));
	/** The table: every one of those items and its Material. */
	private static final Map<Item, Material> TABLE = new IdentityHashMap<>();

	static {
		ITEMS.forEach((kind, items) -> {
			for (int i = 0; i < items.size(); i++) TABLE.put(items.get(i), of(kind).get(i));
		});
	}

	/** As written in {@code skin.json5}: a noun, as in vanilla's material ids ({@code wood}, {@code gold}). */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** As players read it, lower case, as in vanilla's item names: {@code wooden}, {@code golden}, {@code leather}. */
	public String adjective() {
		return switch (this) {
			case WOOD -> "wooden";
			case GOLD -> "golden";
			default -> id();
		};
	}

	public static Optional<Material> byId(String id) {
		return Arrays.stream(values()).filter(m -> m.id().equals(id)).findFirst();
	}

	/** The Materials a Kind comes in, in vanilla order; none for the mace and elytra. */
	public static List<Material> of(Kind kind) {
		return switch (kind) {
			case SWORD, AXE, PICKAXE, SHOVEL, HOE, SPEAR -> TOOLS;
			case HELMET -> HELMETS;
			case CHESTPLATE, LEGGINGS, BOOTS -> ARMOUR;
			case MACE, ELYTRA -> List.of();
		};
	}

	/** The Kind's vanilla item of this Material: the iron sword, the leather helmet. Only for a Material {@linkplain #of(Kind) the Kind comes in}. */
	static Item item(Kind kind, Material material) {
		return ITEMS.get(kind).get(of(kind).indexOf(material));
	}

	/** The item's Material, if it's one of the table's vanilla items; empty for any other (unrecognised). */
	public static Optional<Material> of(ItemInstance item) {
		return Optional.ofNullable(TABLE.get(item.typeHolder().value()));
	}
}
