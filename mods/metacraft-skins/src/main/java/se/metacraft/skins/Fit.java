package se.metacraft.skins;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

import java.util.List;
import java.util.Optional;

/**
 * What a skin fits, as players read it and as menus show it: its Kind and, if it's limited, the
 * Materials it's limited to ({@code materials}, empty if it fits every one). Materials read as
 * vanilla's item names do (wooden, golden), in vanilla order whatever order {@code skin.json5}
 * lists them in.
 */
public record Fit(Kind kind, Optional<List<Material>> materials) {
	/** The gray line under "Fits …" for a Dyeable Hat. */
	private static final String DYE_HINT = "Dye the helmet to recolour it";

	public Fit {
		List<Material> order = Material.of(kind);
		materials = materials.map(m -> order.stream().filter(m::contains).toList());
	}

	/** "any sword", "diamond and netherite swords", "wooden, stone and iron pickaxes": for "Fits …" and "Crown fits …". */
	public String what() {
		return materials.map(m -> adjectives(m, "and") + " " + kind.plural()).orElse("any " + kind.id());
	}

	/** "a helmet", "a diamond or netherite sword", "an iron sword", "leather boots": for "You aren't carrying …". */
	public String aOne() {
		String one = materials.map(m -> adjectives(m, "or") + " ").orElse("") + kind.id();
		if (kind.isPair()) return one;
		return ("aeiou".indexOf(one.charAt(0)) >= 0 ? "an " : "a ") + one;
	}

	/**
	 * The lore that says what the skin fits, on its token, Claim screen and Skin Menu icons: "Fits …"
	 * and, for a Dyeable Hat ({@code dyeable}), "Dye the helmet to recolour it", both gray.
	 */
	public List<Component> lore(boolean dyeable) {
		Component fits = gray("Fits " + what());
		return dyeable ? List.of(fits, gray(DYE_HINT)) : List.of(fits);
	}

	/**
	 * The vanilla item the skin is shown on in menus (with its Look): the Kind's diamond item if the
	 * skin fits diamond, else its item of the skin's last Material in vanilla order. The mace and
	 * elytra have their one item.
	 */
	public Item icon() {
		return materials.filter(m -> !m.contains(Material.DIAMOND)).map(m -> Material.item(kind, m.getLast())).orElse(kind.icon());
	}

	private static Component gray(String text) {
		return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY));
	}

	/** "wooden, stone and iron", in vanilla order. */
	private static String adjectives(List<Material> materials, String last) {
		List<String> words = materials.stream().map(Material::adjective).toList();
		if (words.size() == 1) return words.getFirst();
		return String.join(", ", words.subList(0, words.size() - 1)) + " " + last + " " + words.getLast();
	}
}
