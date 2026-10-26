package se.metacraft.skins.gametest;

import eu.pb4.sgui.api.containerwrappers.SlotBasedWrapperMenu;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import se.metacraft.skins.ItemLocation;
import se.metacraft.skins.OwnedSkin;
import se.metacraft.skins.ui.SkinMenu;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

import static se.metacraft.skins.gametest.SkinFixtures.assertThat;

/**
 * Wiring only: the Skin Menu clicked the way a client clicks it (container-click packets through
 * the player's connection, so sgui's own handling runs). The rules behind each click are
 * ApplyTests'. The menu gets the test's Skins directly, so nothing global is swapped.
 */
public final class SkinMenuTests {
	static final int FIRST_ITEM = 9;
	private static final int REMOVE = 8, MIDDLE = 22, PREVIOUS_PAGE = 45, BOOK = 49, NEXT_PAGE = 53;
	/** "Apply: <skin>"'s page arrows, either side of the preview. */
	private static final int APPLY_PREVIOUS_PAGE = 3, APPLY_NEXT_PAGE = 5;

	@GameTest
	public void clickingThroughTheMenuAppliesAndRemovesASkin(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("crown_test", "helmet", "Crown", null)
				.perky("candy_sword_test", "sword", "Candy Sword", "Sweet.", "[ { trigger: \"kill\", effects: [ { type: \"heal\", amount: 4 } ] } ]")
				.skin("gone", "sword");
		fx.reload();
		SkinFixtures.Listening player = SkinFixtures.join(helper);
		try {
			player.getInventory().clearContent();
			player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			player.getInventory().setItem(5, new ItemStack(Items.DIRT, 10));
			for (String skin : List.of("crown_test", "candy_sword_test", "gone")) fx.skins.grant(player.nameAndId(), skin);
			fx.delete("gone");
			fx.reload();
			SkinMenu.open(player, fx.skins);

			assertThat(title(player).equals("Your Skins"), "the menu is " + title(player));
			assertThat(rows(player) == 6, "Your Skins has " + rows(player) + " rows");
			// Sorted by Kind (sword before helmet); the Dormant skin isn't listed.
			assertThat(name(player, 0).equals("Candy Sword") && name(player, 1).equals("Crown") && slot(player, 2).isEmpty(),
					"listed " + name(player, 0) + ", " + name(player, 1) + ", " + name(player, 2));
			assertThat(lore(player, 0).equals(List.of("Fits any sword", "On kill: heals 2 hearts", "Sweet.", "Unapplied", "Click to apply or remove")),
					"the skin's tooltip: " + lore(player, 0));
			assertThat("skins:candy_sword_test".equals(String.valueOf(slot(player, 0).get(DataComponents.ITEM_MODEL))), "the icon isn't the Look");
			assertThat(!slot(player, PREVIOUS_PAGE).is(Items.ARROW) && !slot(player, NEXT_PAGE).is(Items.ARROW), "page arrows with one page");
			assertThat(name(player, BOOK).equals("How skins work"), "row 6 has no book: " + name(player, BOOK));

			// The player's own inventory is locked: a click on their hotbar moves nothing.
			click(player, rows(player) * 9 + 27);
			assertThat(player.getInventory().getItem(0).is(Items.DIAMOND_SWORD) && player.containerMenu.getCarried().isEmpty(), "the inventory isn't locked");

			click(player, 0);
			assertThat(title(player).equals("Apply: Candy Sword") && rows(player) == 4, "clicking the skin opened " + title(player));
			assertThat(name(player, 4).equals("Candy Sword") && !slot(player, REMOVE).is(Items.GRINDSTONE), "the apply screen's top row");
			assertThat(lore(player, FIRST_ITEM).containsAll(List.of("Hotbar slot 1", "Click to apply")), "the sword's label: " + lore(player, FIRST_ITEM));
			assertThat(slot(player, FIRST_ITEM + 1).isEmpty(), "the dirt is a candidate");

			click(player, FIRST_ITEM);
			assertThat(title(player).equals("Your Skins"), "applying went to " + title(player));
			assertThat(fx.skins.liveSkin(player.getInventory().getItem(0)).isPresent(), "the sword wasn't skinned");
			String applied = "✔ Candy Sword applied to your Diamond Sword";
			assertThat(player.heard.contains(applied) && player.actionBar.contains(applied), "the player was told " + player.heard);
			assertThat(lore(player, 0).contains("✔ Applied to an item") && Boolean.TRUE.equals(slot(player, 0).get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)),
					"an applied skin doesn't say so or glint: " + lore(player, 0));

			click(player, 0);
			assertThat(lore(player, FIRST_ITEM).contains("Already has this skin"), "the skinned sword's label: " + lore(player, FIRST_ITEM));
			assertThat(slot(player, REMOVE).is(Items.GRINDSTONE), "no Remove while applied");
			click(player, REMOVE);
			assertThat(title(player).equals("Your Skins"), "removing went to " + title(player));
			assertThat(fx.skins.ownedSkins(player.getUUID()).skins().stream().allMatch(s -> s.state() != OwnedSkin.State.APPLIED), "the skin is still applied");
			assertThat(player.heard.contains("Candy Sword removed. Its item reverts wherever it is."), "the player was told " + player.heard);
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
		helper.succeed();
	}

	@GameTest
	public void theApplyScreenSaysWhatEachItemWouldDo(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("crown_test", "helmet", "Crown", null).skin("candy_sword_test", "sword", "Candy Sword", null)
				.skin("frost", "sword", "Frost Blade", null);
		fx.reload();
		SkinFixtures.Listening player = SkinFixtures.join(helper);
		try {
			player.getInventory().clearContent();
			player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_SWORD));
			for (String skin : List.of("crown_test", "candy_sword_test", "frost")) fx.skins.grant(player.nameAndId(), skin);
			fx.skins.apply(player, "frost", ItemLocation.offhand());
			SkinMenu.open(player, fx.skins);

			click(player, 2);   // swords first: candy_sword_test, frost, then crown_test
			assertThat(title(player).equals("Apply: Crown"), "opened " + title(player));
			assertThat(slot(player, MIDDLE).is(Items.BARRIER) && name(player, MIDDLE).equals("You aren't carrying a helmet"),
					"with no helmet: " + name(player, MIDDLE));
			click(player, 0);   // Back
			click(player, 0);
			assertThat(title(player).equals("Apply: Candy Sword"), "opened " + title(player));
			assertThat(lore(player, FIRST_ITEM).containsAll(List.of("Offhand", "Replaces Frost Blade")), "the offhand sword's label: " + lore(player, FIRST_ITEM));
			click(player, FIRST_ITEM);
			assertThat(player.heard.contains("✔ Candy Sword applied to your Iron Sword (replaced Frost Blade)"), "the player was told " + player.heard);

			// A failure says so in red and stays on the apply screen.
			player.getInventory().setItem(3, new ItemStack(Items.GOLDEN_SWORD));
			click(player, 1);
			player.getInventory().setItem(3, ItemStack.EMPTY);
			click(player, FIRST_ITEM);
			assertThat(player.heard.getLast().equals("That Golden Sword isn't there any more."), "the player was told " + player.heard);
			assertThat(title(player).equals("Apply: Frost Blade"), "a failed apply went to " + title(player));
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
		helper.succeed();
	}

	/** The apply screen lists only items the skin fits, Material included: a diamond helmet isn't offered a leather-only skin. */
	@GameTest
	public void theApplyScreenHidesItemsOfTheWrongMaterial(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().limited("cap", "helmet", "[\"leather\"]");
		fx.reload();
		SkinFixtures.Listening player = SkinFixtures.join(helper);
		try {
			player.getInventory().clearContent();
			player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_HELMET));
			player.getInventory().setItem(1, new ItemStack(Items.LEATHER_HELMET));
			player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TURTLE_HELMET));
			fx.skins.grant(player.nameAndId(), "cap");
			SkinMenu.open(player, fx.skins);

			click(player, 0);
			assertThat(title(player).equals("Apply: cap skin"), "opened " + title(player));
			assertThat(slot(player, FIRST_ITEM).is(Items.LEATHER_HELMET) && lore(player, FIRST_ITEM).contains("Hotbar slot 2"),
					"the first item offered: " + slot(player, FIRST_ITEM) + " " + lore(player, FIRST_ITEM));
			assertThat(slot(player, FIRST_ITEM + 1).isEmpty(), "a helmet of the wrong Material is offered: " + slot(player, FIRST_ITEM + 1));
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
		helper.succeed();
	}

	@GameTest
	public void moreThanAChestOfSkinsIsPaged(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create();
		for (int i = 0; i < 46; i++) fx.skin("skin" + i, "hoe");
		fx.reload();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		for (int i = 0; i < 46; i++) fx.skins.grant(player.nameAndId(), "skin" + i);
		SkinMenu.open(player, fx.skins);
		assertThat(title(player).equals("Your Skins (1/2)"), "the first page is " + title(player));
		assertThat(!slot(player, 44).isEmpty() && slot(player, NEXT_PAGE).is(Items.ARROW) && !slot(player, PREVIOUS_PAGE).is(Items.ARROW), "the first page's arrows");
		click(player, NEXT_PAGE);
		assertThat(title(player).equals("Your Skins (2/2)"), "the next page is " + title(player));
		assertThat(!slot(player, 0).isEmpty() && slot(player, 1).isEmpty(), "the second page holds more than the 46th skin");
		assertThat(slot(player, PREVIOUS_PAGE).is(Items.ARROW) && !slot(player, NEXT_PAGE).is(Items.ARROW), "the last page's arrows");
		helper.succeed();
	}

	/** A player can carry 41 items a skin fits (every slot, worn and offhand too): past 27 the apply screen is paged. */
	@GameTest
	public void moreItemsThanTheApplyScreenHoldsArePaged(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null);
		fx.reload();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.getInventory().clearContent();
		for (int slot = 0; slot <= 40; slot++) player.getInventory().setItem(slot, new ItemStack(Items.IRON_SWORD));
		fx.skins.grant(player.nameAndId(), "candy_sword_test");
		SkinMenu.open(player, fx.skins);
		click(player, 0);

		assertThat(title(player).equals("Apply: Candy Sword (1/2)") && rows(player) == 4, "the first page is " + title(player));
		assertThat(!slot(player, FIRST_ITEM + 26).isEmpty() && slot(player, APPLY_NEXT_PAGE).is(Items.ARROW) && !slot(player, APPLY_PREVIOUS_PAGE).is(Items.ARROW),
				"the first page's arrows");
		click(player, APPLY_NEXT_PAGE);
		assertThat(title(player).equals("Apply: Candy Sword (2/2)"), "the next page is " + title(player));
		assertThat(lore(player, FIRST_ITEM).contains("Inventory") && lore(player, FIRST_ITEM + 13).contains("Offhand") && slot(player, FIRST_ITEM + 14).isEmpty(),
				"the second page holds the 28th to 41st items: " + lore(player, FIRST_ITEM) + ", " + lore(player, FIRST_ITEM + 13));
		assertThat(slot(player, APPLY_PREVIOUS_PAGE).is(Items.ARROW) && !slot(player, APPLY_NEXT_PAGE).is(Items.ARROW), "the last page's arrows");
		click(player, FIRST_ITEM + 13);
		assertThat(fx.skins.liveSkin(player.getItemBySlot(EquipmentSlot.OFFHAND)).isPresent(), "clicking the offhand sword on page 2 didn't skin it");
		helper.succeed();
	}

	@GameTest
	public void theBookSummarisesSkins(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create();
		fx.reload();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		SkinMenu.open(player, fx.skins);
		assertThat(lore(player, BOOK).equals(List.of("Claim a Skin Token to own a skin.", "Click a skin to put it on one item.",
				"A skin is on one item at a time.")), "the book reads " + lore(player, BOOK));
		click(player, BOOK);
		assertThat(title(player).equals("Your Skins"), "clicking the book left " + title(player));
		helper.succeed();
	}

	/** Clicks a slot of the open menu as a client does: a left click, through the player's connection. */
	static void click(ServerPlayer player, int slot) {
		AbstractContainerMenu menu = player.containerMenu;
		player.connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) slot, (byte) 0,
				ContainerInput.PICKUP, Int2ObjectMaps.emptyMap(), HashedStack.EMPTY));
	}

	static String title(ServerPlayer player) {
		return player.containerMenu instanceof SlotBasedWrapperMenu menu ? menu.getBackingGui().getTitle().getString() : "no Skin Menu (" + player.containerMenu + ")";
	}

	private static int rows(ServerPlayer player) {
		return player.containerMenu instanceof SlotBasedWrapperMenu menu ? menu.getBackingGui().getHeight() : 0;
	}

	private static ItemStack slot(ServerPlayer player, int slot) {
		return player.containerMenu.getSlot(slot).getItem();
	}

	private static String name(ServerPlayer player, int slot) {
		return slot(player, slot).getHoverName().getString();
	}

	static List<String> lore(ServerPlayer player, int slot) {
		return slot(player, slot).getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().map(Component::getString).toList();
	}
}
