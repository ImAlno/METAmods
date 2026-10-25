package se.metacraft.skins.gametest;

import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import se.metacraft.skins.ItemLocation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.Equippable;

import java.util.List;
import java.util.Objects;

import static se.metacraft.skins.gametest.SkinFixtures.assertThat;

/**
 * What a viewer is sent of a Skinned Item: Polymer's client copy of the stack, which the Skins
 * module dresses in the Look and the tooltip block. The server's item itself never changes (that
 * is ApplyTests'); whether Polymer's per-viewer pipeline reaches vanilla items at all is checked
 * end to end through the network codec in SkinsCommandTests.
 */
public final class ViewTests {

	@GameTest
	public void everyViewerSeesTheLookOfALiveSkinOnly(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.DIAMOND_SWORD));
		ServerPlayer someoneElse = helper.makeMockServerPlayerInLevel();
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack skinned = owner.getInventory().getItem(0);

		for (ServerPlayer viewer : List.of(owner, someoneElse)) {
			assertThat(model(view(helper, fx, skinned, viewer)).equals(Identifier.parse("skins:candy_sword_test")), "a viewer sees " + model(view(helper, fx, skinned, viewer)));
		}
		assertThat(model(view(helper, fx, owner.getInventory().getItem(1), owner)).equals(Identifier.parse("minecraft:diamond_sword")), "a plain sword has a Look");
		fx.skins.remove(owner.nameAndId(), "candy_sword_test");
		ItemStack stale = view(helper, fx, skinned, someoneElse);
		assertThat(model(stale).equals(Identifier.parse("minecraft:diamond_sword")), "a removed skin's item still shows " + model(stale));
		assertThat(lore(stale).stream().noneMatch(l -> l.getString().startsWith("Skin: ")), "a removed skin's item still says so: " + lore(stale));
		helper.succeed();
	}

	/** Any head armour, turtle shell included, shows the hat on the head and keeps its armour (the server's item is untouched). */
	@GameTest
	public void aHatIsDrawnOnTheHeadOfAnyHelmet(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("crown_test", "helmet");
		fx.reload();
		ServerPlayer player = ApplyTests.carrying(helper, new ItemStack(Items.TURTLE_HELMET));
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
		fx.skins.grant(player.nameAndId(), "crown_test");

		for (ItemLocation where : List.of(ItemLocation.hotbar(0), ItemLocation.worn(EquipmentSlot.HEAD))) {
			assertThat(fx.skins.apply(player, "crown_test", where).applied(), "the crown wasn't applied at " + where.label());
			ItemStack helmet = where.in(player.getInventory());
			ItemStack client = view(helper, fx, helmet, player);
			assertThat(model(client).equals(Identifier.parse("skins:crown_test")), helmet + " shows " + model(client));
			Equippable worn = client.get(DataComponents.EQUIPPABLE);
			assertThat(worn != null && worn.slot() == EquipmentSlot.HEAD && worn.assetId().isEmpty(), helmet + " is sent as equippable " + worn);
			assertThat(Objects.equals(worn.equipSound(), helmet.get(DataComponents.EQUIPPABLE).equipSound()), "the equip sound changed");
			assertThat(helmet.get(DataComponents.EQUIPPABLE).assetId().isPresent(), "the server's helmet lost its armour asset");
			assertThat(Objects.equals(client.get(DataComponents.ATTRIBUTE_MODIFIERS), helmet.get(DataComponents.ATTRIBUTE_MODIFIERS)),
					helmet + " is sent with other armour: " + client.get(DataComponents.ATTRIBUTE_MODIFIERS));
		}
		helper.succeed();
	}

	/** Body armour and the elytra change their hand and inventory look only; worn, they look vanilla. A spear keeps its charge and jab. */
	@GameTest
	public void otherKindsChangeOnlyTheirItemModel(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("plate", "chestplate").skin("wings", "elytra").skin("lance", "spear").skin("hammer", "mace");
		fx.reload();
		ServerPlayer player = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_CHESTPLATE), new ItemStack(Items.ELYTRA),
				new ItemStack(Items.IRON_SPEAR), new ItemStack(Items.MACE));
		List<String> skins = List.of("plate", "wings", "lance", "hammer");
		for (int i = 0; i < skins.size(); i++) {
			fx.skins.grant(player.nameAndId(), skins.get(i));
			assertThat(fx.skins.apply(player, skins.get(i), ItemLocation.hotbar(i)).applied(), skins.get(i) + " wasn't applied");
			ItemStack item = player.getInventory().getItem(i);
			ItemStack client = view(helper, fx, item, player);
			assertThat(model(client).equals(Identifier.fromNamespaceAndPath("skins", skins.get(i))), item + " shows " + model(client));
			assertThat(Objects.equals(client.get(DataComponents.EQUIPPABLE), item.get(DataComponents.EQUIPPABLE)), item + " is worn differently: "
					+ client.get(DataComponents.EQUIPPABLE));
		}
		ItemStack spear = player.getInventory().getItem(2);
		ItemStack client = view(helper, fx, spear, player);
		assertThat(client.get(DataComponents.KINETIC_WEAPON) != null && Objects.equals(client.get(DataComponents.KINETIC_WEAPON), spear.get(DataComponents.KINETIC_WEAPON)),
				"the spear's charge is sent as " + client.get(DataComponents.KINETIC_WEAPON));
		assertThat(client.get(DataComponents.ATTACK_ANIMATION) != null && Objects.equals(client.get(DataComponents.ATTACK_ANIMATION), spear.get(DataComponents.ATTACK_ANIMATION)),
				"the spear's jab is sent as " + client.get(DataComponents.ATTACK_ANIMATION));
		helper.succeed();
	}

	@GameTest
	public void theTooltipBlockFollowsVanillasLines(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("candy_sword_test", "sword", "Candy Sword", "Sweet, and sharp.", """
				[ { trigger: "held", effects: [ { type: "potion", effect: "minecraft:speed", level: 2 },
				{ type: "attribute", attribute: "minecraft:attack_damage", amount: 0.1, operation: "add_multiplied_base" } ] } ]""")
				.skin("plain", "axe", "Plain Axe", null);
		fx.reload();
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 2);
		sword.set(DataComponents.CUSTOM_NAME, Component.literal("Old Faithful"));
		ServerPlayer owner = ApplyTests.carrying(helper, sword, new ItemStack(Items.IRON_AXE));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.grant(owner.nameAndId(), "plain");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		fx.skins.apply(owner, "plain", ItemLocation.hotbar(1));
		ServerPlayer viewer = helper.makeMockServerPlayerInLevel();
		String name = owner.getGameProfile().name();

		ItemStack client = view(helper, fx, owner.getInventory().getItem(0), viewer);
		List<Component> lore = lore(client);
		assertThat(lore.size() > 4, "vanilla's lines (the enchantment, the attack damage) are missing: " + text(lore));
		List<Component> block = lore.subList(lore.size() - 4, lore.size());
		assertLine(block.get(0), "Skin: Candy Sword", ChatFormatting.GOLD);
		assertLine(block.get(1), "While held: Speed II and +10% Attack Damage", ChatFormatting.BLUE);
		assertLine(block.get(2), "Sweet, and sharp.", ChatFormatting.GRAY);
		assertLine(block.get(3), "Owned by " + name, ChatFormatting.GRAY);
		assertThat(lore.subList(0, lore.size() - 4).stream().anyMatch(l -> l.getString().contains("Sharpness")), "vanilla's lines don't come first: " + text(lore));
		assertThat(client.getHoverName().getString().equals("Old Faithful"), "the item's name became " + client.getHoverName().getString());
		assertThat(Boolean.TRUE.equals(client.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)) || client.hasFoil(), "the enchanted item lost its glint");

		ItemStack axe = view(helper, fx, owner.getInventory().getItem(1), viewer);
		List<Component> axeLore = lore(axe);
		assertLine(axeLore.get(axeLore.size() - 2), "Skin: Plain Axe", ChatFormatting.GOLD);
		assertLine(axeLore.getLast(), "Owned by " + name, ChatFormatting.GRAY);
		assertThat(!Boolean.TRUE.equals(axe.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)), "a skin gave the axe a glint");
		assertThat(axe.getHoverName().getString().equals(Items.IRON_AXE.getName(axe).getString()), "the axe's name became " + axe.getHoverName().getString());
		helper.succeed();
	}

	/** An offline Owner is named by their last known name: the one they last joined with. */
	@GameTest
	public void anOfflineOwnerIsShownByTheirLastKnownName(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack sword = owner.getInventory().getItem(0);
		// They last joined as "Renamed"; the item shows that name whether or not they're online now.
		fx.skins.seen(new NameAndId(owner.getUUID(), "Renamed"));
		List<Component> lore = lore(view(helper, fx, sword, helper.makeMockServerPlayerInLevel()));
		assertLine(lore.getLast(), "Owned by Renamed", ChatFormatting.GRAY);
		helper.succeed();
	}

	/** What Polymer sends this viewer for the server's stack, dressed by the test's Skins. */
	static ItemStack view(GameTestHelper helper, SkinFixtures fx, ItemStack stack, ServerPlayer viewer) {
		ItemStack client = PolymerItemUtils.getPolymerItemStack(stack, viewer.connection.getPacketContext(), helper.getLevel().registryAccess());
		return fx.skins.clientView(stack, client);
	}

	private static Identifier model(ItemStack client) {
		return client.get(DataComponents.ITEM_MODEL);
	}

	private static List<Component> lore(ItemStack client) {
		return client.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines();
	}

	private static List<String> text(List<Component> lines) {
		return lines.stream().map(Component::getString).toList();
	}

	private static void assertLine(Component line, String text, ChatFormatting colour) {
		assertThat(line.getString().equals(text), "expected \"" + text + "\", got \"" + line.getString() + "\"");
		assertThat(Objects.equals(line.getStyle().getColor(), TextColor.fromLegacyFormat(colour)), "\"" + text + "\" is coloured " + line.getStyle().getColor());
		assertThat(!line.getStyle().isItalic(), "\"" + text + "\" is italic");
	}
}
