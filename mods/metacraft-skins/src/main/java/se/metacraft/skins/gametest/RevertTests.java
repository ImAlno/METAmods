package se.metacraft.skins.gametest;

import se.metacraft.skins.ItemLocation;
import se.metacraft.skins.LiveSkin;
import se.metacraft.skins.OwnedSkin;
import se.metacraft.skins.OwnershipChange;
import se.metacraft.skins.Perk;
import se.metacraft.skins.PlayerSkins;
import se.metacraft.skins.SkinMarker;
import se.metacraft.skins.Skins;
import se.metacraft.skins.ui.SkinMenu;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static se.metacraft.skins.gametest.SkinFixtures.assertThat;

/**
 * Stale reverts through the Skins module: when an Owner moves or removes a skin (or it's revoked,
 * changes Kind, or the world is new), every other item carrying it is plain from then on, wherever
 * it is, and the server clears its marker only when it tidies the Owner's own items. A Dormant
 * skin's markers are kept. The four overwrite invariants, and which crafting stations keep a skin.
 */
public final class RevertTests {

	/**
	 * Moved: the old item is plain wherever it is, a chest included. The move tidies the Owner's own
	 * items at once (inventory, ender chest, cursor, and the shulker boxes and bundles among them),
	 * clearing the marker; nothing else is searched, so a copy in a chest keeps its stale marker,
	 * which is never shown or used.
	 */
	@GameTest
	public void aMovedSkinsOldItemRevertsWhereverItIs(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.IRON_SWORD));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack old = owner.getInventory().getItem(0);

		BlockPos pos = new BlockPos(1, 1, 1);
		helper.setBlock(pos, Blocks.CHEST);
		ChestBlockEntity chest = helper.getBlockEntity(pos, ChestBlockEntity.class);
		chest.setItem(3, old.copy());
		owner.getEnderChestInventory().setItem(0, old.copy());
		ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
		shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT), old.copy())));
		owner.getInventory().setItem(5, shulker);
		ItemStack bundle = new ItemStack(Items.BUNDLE);
		bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(ItemStackTemplate.fromNonEmptyStack(old.copy()))));
		owner.getInventory().setItem(6, bundle);
		ItemStack plainBox = new ItemStack(Items.SHULKER_BOX);
		plainBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIRT), new ItemStack(Items.IRON_SWORD))));
		owner.getInventory().setItem(7, plainBox);
		ItemContainerContents nothingToTidy = plainBox.get(DataComponents.CONTAINER);
		owner.containerMenu.setCarried(old.copy());
		assertThat(fx.skins.liveSkin(chest.getItem(3)).isPresent(), "a copy in a chest isn't live before the move");

		assertThat(fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(1)).applied(), "the move was refused");
		assertThat(fx.skins.liveSkin(chest.getItem(3)).isEmpty(), "the old item in a chest is still live");
		assertThat(model(helper, fx, chest.getItem(3), owner).equals(Identifier.parse("minecraft:diamond_sword")),
				"the old item in a chest is drawn as " + model(helper, fx, chest.getItem(3), owner));
		assertThat(chest.getItem(3).has(SkinMarker.TYPE), "the move cleared a chest's copy: something searched the chest");

		assertThat(!old.has(SkinMarker.TYPE), "the old item in the owner's hotbar kept its marker");
		assertThat(!owner.getEnderChestInventory().getItem(0).has(SkinMarker.TYPE), "the ender chest's copy kept its marker");
		assertThat(!owner.containerMenu.getCarried().has(SkinMarker.TYPE), "the copy on the cursor kept its marker");
		List<ItemStack> inBox = owner.getInventory().getItem(5).getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
				.nonEmptyItemCopyStream().toList();
		assertThat(inBox.size() == 2 && inBox.stream().noneMatch(s -> s.has(SkinMarker.TYPE)), "the shulker box holds " + inBox);
		List<ItemStack> inBundle = owner.getInventory().getItem(6).getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).itemCopies().toList();
		assertThat(inBundle.size() == 1 && inBundle.stream().noneMatch(s -> s.has(SkinMarker.TYPE)), "the bundle holds " + inBundle);
		assertThat(plainBox.get(DataComponents.CONTAINER) == nothingToTidy, "a shulker box with nothing to tidy was written back");
		assertThat(fx.skins.liveSkin(owner.getInventory().getItem(1)).map(LiveSkin::id).equals(Optional.of("candy_sword_test")),
				"the new item lost the skin");
		assertThat(fx.skins.tidy(owner) == 0, "a live item was reverted, or a stale one twice");
		helper.succeed();
	}

	/**
	 * An item in the inventory of a player who was offline when the skin was removed is drawn plain
	 * once they join: liveness is checked at every draw, so nothing needs to touch it as they join.
	 */
	@GameTest
	public void aStaleItemInAnOfflinePlayersInventoryRevertsWhenTheyJoin(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		// Traded to Bob, who logs off holding it, with a copy in his ender chest.
		ServerPlayer bob = ApplyTests.carrying(helper, owner.getInventory().removeItemNoUpdate(0));
		bob.getEnderChestInventory().setItem(7, bob.getInventory().getItem(0).copy());

		assertThat(fx.skins.remove(owner.nameAndId(), "candy_sword_test") == OwnershipChange.DONE, "the remove was refused");
		fx.skins.seen(bob.nameAndId());
		for (ItemStack stale : List.of(bob.getInventory().getItem(0), bob.getEnderChestInventory().getItem(7))) {
			for (ServerPlayer with : List.of(bob, owner)) {
				assertThat(model(helper, fx, stale, with).equals(Identifier.parse("minecraft:diamond_sword")), "Bob joined holding a sword drawn as "
						+ model(helper, fx, stale, with));
			}
		}
		helper.succeed();
	}
	/**
	 * Duplicated copies (an exploit, a creative middle-click) all carry the skin, on the ground, in
	 * an item frame, on an armour stand or in someone else's hand, until the Owner next moves or
	 * removes it; then every copy reverts. The move clears the Owner's own copy; the others keep
	 * their stale markers, never shown or used.
	 */
	@GameTest
	public void duplicatedCopiesAllCarryTheSkinUntilItMovesThenAllRevert(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.GOLDEN_SWORD));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack original = owner.getInventory().getItem(0);

		ServerLevel level = helper.getLevel();
		Vec3 at = helper.absoluteVec(new Vec3(1.5, 2, 1.5));
		ItemEntity dropped = new ItemEntity(level, at.x, at.y, at.z, original.copy());
		level.addFreshEntity(dropped);
		helper.setBlock(new BlockPos(2, 1, 2), Blocks.STONE);
		ItemFrame frame = new ItemFrame(level, helper.absolutePos(new BlockPos(2, 2, 2)), Direction.UP);
		frame.setItem(original.copy(), false);
		level.addFreshEntity(frame);
		ArmorStand stand = helper.spawn(EntityTypes.ARMOR_STAND, new BlockPos(3, 2, 3));
		stand.setItemSlot(EquipmentSlot.MAINHAND, original.copy());
		ServerPlayer bob = ApplyTests.carrying(helper, original.copy());
		List<Supplier<ItemStack>> copies = List.of(() -> original, dropped::getItem, frame::getItem, () -> stand.getMainHandItem(),
				() -> bob.getInventory().getItem(0));
		for (Supplier<ItemStack> copy : copies) assertThat(fx.skins.liveSkin(copy.get()).isPresent(), "a duplicate isn't live: " + copy.get());

		assertThat(fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(1)).applied(), "the move was refused");
		for (Supplier<ItemStack> copy : copies) assertThat(fx.skins.liveSkin(copy.get()).isEmpty(), "a duplicate survived the move: " + copy.get());
		assertThat(!original.has(SkinMarker.TYPE), "the Owner's own copy kept its marker");
		assertThat(copies.stream().skip(1).allMatch(copy -> copy.get().has(SkinMarker.TYPE)), "the move searched beyond the Owner's items");
		assertThat(fx.skins.liveSkin(owner.getInventory().getItem(1)).isPresent(), "the item it moved to lost it");
		helper.succeed();
	}

	/** Revoked: the applied item reverts, and a new grant doesn't bring it back. */
	@GameTest
	public void aRevokeRevertsTheAppliedItem(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack sword = owner.getInventory().getItem(0);

		assertThat(fx.skins.revoke(owner.nameAndId(), "candy_sword_test") == OwnershipChange.DONE, "the revoke was refused");
		assertThat(fx.skins.liveSkin(sword).isEmpty(), "the revoked skin is still live on its item");
		assertThat(fx.skins.tidy(owner) == 1 && !sword.has(SkinMarker.TYPE), "the revoked skin's item kept its marker");
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		assertThat(state(fx.skins, owner) == OwnedSkin.State.UNAPPLIED, "granted again, the skin is " + state(fx.skins, owner));
		helper.succeed();
	}

	/**
	 * A Kind change (an admin edits {@code kind} and reloads, or edits it while the server is down)
	 * reverts the items of the old Kind and makes the skin Unapplied, for good: changing the Kind
	 * back doesn't revive them.
	 */
	@GameTest
	public void aKindChangeRevertsItemsOfTheOldKindAndMakesTheSkinUnapplied(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null);
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.IRON_AXE));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack sword = owner.getInventory().getItem(0);
		ItemStack untouched = sword.copy();   // somewhere the Owner's tidy never reaches: a chest, say

		fx.skin("candy_sword_test", "axe", "Candy Sword", null);
		fx.reload();
		assertThat(state(fx.skins, owner) == OwnedSkin.State.UNAPPLIED, "after the Kind change the skin is " + state(fx.skins, owner));
		assertThat(fx.skins.liveSkin(sword).isEmpty(), "the sword is still live as an axe skin");
		assertThat(fx.skins.tidy(owner) == 1 && !sword.has(SkinMarker.TYPE), "the sword kept its marker");
		assertThat(fx.skins.candidates(owner, "candy_sword_test").stream().map(c -> c.item().getItem()).toList().equals(List.of(Items.IRON_AXE)),
				"the axe skin fits " + fx.skins.candidates(owner, "candy_sword_test"));

		fx.skin("candy_sword_test", "sword", "Candy Sword", null);
		fx.reload();
		assertThat(fx.skins.liveSkin(untouched).isEmpty(), "changing the Kind back revived an old sword");
		assertThat(state(fx.skins, owner) == OwnedSkin.State.UNAPPLIED, "changing the Kind back made the skin " + state(fx.skins, owner));

		// Edited while the server was down: the same, from the first start on.
		owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack second = owner.getInventory().getItem(0);
		fx.skin("candy_sword_test", "axe", "Candy Sword", null);
		Skins restarted = fx.restart();
		assertThat(state(restarted, owner) == OwnedSkin.State.UNAPPLIED, "after a Kind change between runs the skin is " + state(restarted, owner));
		assertThat(restarted.liveSkin(second).isEmpty(), "the sword is live as an axe skin after a restart");
		helper.succeed();
	}

	/** A fresh world: every skin reads Unapplied in the Skin Menu, and an item carrying another world's application reverts. */
	@GameTest
	public void aFreshWorldShowsEverySkinUnapplied(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null);
		fx.reload();
		SkinFixtures.Listening owner = SkinFixtures.join(helper);
		try {
			owner.getInventory().clearContent();
			owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			fx.skins.grant(owner.nameAndId(), "candy_sword_test");
			fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
			Skins fresh = fx.restart(fx.root.resolve("fresh-world"));
			SkinMenu.open(owner, fresh);
			assertThat(SkinMenuTests.lore(owner, 0).contains("Unapplied"), "in a fresh world the skin reads " + SkinMenuTests.lore(owner, 0));
			assertThat(!owner.getInventory().getItem(0).has(SkinMarker.TYPE), "another world's item kept its marker as the Skin Menu opened");
			assertThat(fresh.apply(owner, "candy_sword_test", ItemLocation.hotbar(0)).applied(), "the skin can't be applied in the fresh world");
		} finally {
			owner.closeContainer();
			helper.getLevel().getServer().getPlayerList().remove(owner);
		}
		helper.succeed();
	}

	/**
	 * A reload says whether it took a Look away from items that showed one, so players' items are
	 * sent again: a skin gone Dormant or missing, a Kind change, a narrowed {@code materials}. A
	 * reload that changes nothing, adds a skin, or brings a Look back (fixed, widened) takes none away.
	 */
	@GameTest
	public void aReloadSaysWhetherItTookALookAway(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		assertThat(!fx.reload().lookTakenAway(), "the first reload took a Look away");
		assertThat(!fx.reload().lookTakenAway(), "an unchanged reload took a Look away");
		fx.skin("crown_test", "helmet");
		assertThat(!fx.reload().lookTakenAway(), "adding a skin took a Look away");

		fx.file("candy_sword_test", "candy_sword_test.json", "{ broken");
		assertThat(fx.reload().lookTakenAway(), "a skin going Dormant took no Look away");
		fx.skin("candy_sword_test", "sword");
		assertThat(!fx.reload().lookTakenAway(), "fixing a skin took a Look away");
		fx.delete("candy_sword_test");
		assertThat(fx.reload().lookTakenAway(), "a skin's folder going took no Look away");
		fx.skin("candy_sword_test", "sword");
		fx.reload();

		fx.skin("candy_sword_test", "axe");
		assertThat(fx.reload().lookTakenAway(), "a Kind change took no Look away");
		fx.skin("candy_sword_test", "sword");
		fx.reload();

		fx.limited("candy_sword_test", "sword", "[\"diamond\", \"netherite\"]");
		assertThat(fx.reload().lookTakenAway(), "limiting an unlimited skin took no Look away");
		fx.limited("candy_sword_test", "sword", "[\"iron\", \"diamond\", \"netherite\"]");
		assertThat(!fx.reload().lookTakenAway(), "widening materials took a Look away");
		fx.limited("candy_sword_test", "sword", "[\"iron\", \"netherite\"]");
		assertThat(fx.reload().lookTakenAway(), "narrowing materials took no Look away");
		fx.skin("candy_sword_test", "sword");
		assertThat(!fx.reload().lookTakenAway(), "dropping materials took a Look away");

		fx.dyeableHat("cowboy_hat_test");
		assertThat(!fx.reload().lookTakenAway(), "adding a Dyeable Hat took a Look away");
		fx.tintedHat("cowboy_hat_test", "{ model: \"cowboy_hat_test.json\" }");
		assertThat(fx.reload().lookTakenAway(), "turning dyeable off took no Look away");
		fx.dyeableHat("cowboy_hat_test");
		assertThat(!fx.reload().lookTakenAway(), "turning dyeable on took a Look away");
		helper.succeed();
	}

	/**
	 * Dormant (its definition broken, or its folder gone): the skin's items look vanilla and carry no
	 * Perks, but nothing clears their markers; fixed and reloaded, everything comes back unchanged.
	 */
	@GameTest
	public void aDormantSkinSleepsAndComesBackUnchanged(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null);
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack sword = owner.getInventory().getItem(0);

		for (Runnable sleep : List.<Runnable>of(() -> fx.file("candy_sword_test", "candy_sword_test.json", "{ broken"), () -> fx.delete("candy_sword_test"))) {
			sleep.run();
			fx.reload();
			assertThat(state(fx.skins, owner) == OwnedSkin.State.DORMANT, "the broken skin is " + state(fx.skins, owner));
			assertThat(fx.skins.liveSkin(sword).isEmpty(), "a Dormant skin is live (and would have Perks)");
			assertThat(!model(helper, fx, sword, owner).getNamespace().equals("skins"), "a Dormant skin's item shows " + model(helper, fx, sword, owner));
			assertThat(fx.skins.tidy(owner) == 0 && sword.has(SkinMarker.TYPE), "a Dormant skin's marker was cleared");
			assertThat(fx.restart().tidy(owner) == 0 && sword.has(SkinMarker.TYPE), "a Dormant skin's marker was cleared after a restart");

			fx.skin("candy_sword_test", "sword", "Candy Sword", null);
			fx.reload();
			assertThat(state(fx.skins, owner) == OwnedSkin.State.APPLIED, "fixed, the skin is " + state(fx.skins, owner));
			assertThat(fx.skins.liveSkin(sword).map(LiveSkin::name).equals(Optional.of("Candy Sword")), "fixed, the sword doesn't carry the skin");
			assertThat(model(helper, fx, sword, owner).toString().equals("skins:candy_sword_test"), "fixed, the sword shows " + model(helper, fx, sword, owner));
		}
		helper.succeed();
	}

	/** Invariant 1: only the latest application is live. Re-applying to the first item doesn't revive the copies made before. */
	@GameTest
	public void onlyTheLatestApplicationIsLive(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.IRON_SWORD));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack copyOfTheFirst = owner.getInventory().getItem(0).copy();
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(1));
		assertThat(fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0)).applied(), "applying back to the first item was refused");

		assertThat(fx.skins.liveSkin(owner.getInventory().getItem(0)).isPresent(), "the latest application isn't live");
		assertThat(fx.skins.liveSkin(owner.getInventory().getItem(1)).isEmpty(), "the second item is still live");
		assertThat(fx.skins.liveSkin(copyOfTheFirst).isEmpty(), "a copy of the first application came back with the new one");
		helper.succeed();
	}

	/**
	 * Invariants 2 and 4, A over B, then B moved: Alice's skin A goes over Bob's B, and the Perk
	 * lookup gives A in the same action. When Bob later moves or removes B, the item keeps A.
	 */
	@GameTest
	public void aRevertRemovesOnlyItsOwnSkin(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("candy_sword_test", "sword", "Candy Sword", null, PerkTests.HASTE_HELD)
				.perky("frost", "sword", "Frost Blade", null, "[ { trigger: \"held\", effects: [ { type: \"potion\", effect: \"minecraft:slowness\" } ] } ]");
		fx.reload();
		ServerPlayer bob = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.IRON_SWORD));
		ServerPlayer alice = ApplyTests.carrying(helper);
		fx.skins.grant(bob.nameAndId(), "frost");
		fx.skins.grant(alice.nameAndId(), "candy_sword_test");
		fx.skins.apply(bob, "frost", ItemLocation.hotbar(0));
		alice.getInventory().setItem(0, bob.getInventory().removeItemNoUpdate(0));
		ItemStack item = alice.getInventory().getItem(0);

		assertThat(fx.skins.apply(alice, "candy_sword_test", ItemLocation.hotbar(0)).replaced().equals(Optional.of("Frost Blade")), "A didn't go over B");
		assertThat(fx.skins.liveSkin(item).map(LiveSkin::id).equals(Optional.of("candy_sword_test")), "the Perks didn't switch to A at once: " + fx.skins.liveSkin(item));
		assertThat(fx.skins.perks(item, alice).stream().flatMap(p -> p.effects().stream())
				.allMatch(e -> e instanceof Perk.Potion potion && potion.effect().equals(MobEffects.HASTE)) && fx.skins.perks(item, alice).size() == 1,
				"the item's Perks didn't switch to A's at once: " + fx.skins.perks(item, alice));
		assertThat(state(fx.skins, bob) == OwnedSkin.State.UNAPPLIED, "B is " + state(fx.skins, bob));

		assertThat(fx.skins.apply(bob, "frost", ItemLocation.hotbar(1)).applied(), "Bob couldn't move B");
		assertThat(fx.skins.tidy(alice) == 0, "moving B reverted Alice's item");
		assertThat(fx.skins.liveSkin(item).map(LiveSkin::id).equals(Optional.of("candy_sword_test")), "moving B took A off the item");
		assertThat(fx.skins.remove(bob.nameAndId(), "frost") == OwnershipChange.DONE, "Bob couldn't remove B");
		assertThat(fx.skins.tidy(alice) == 0 && fx.skins.liveSkin(item).map(LiveSkin::id).equals(Optional.of("candy_sword_test")),
				"removing B took A off the item");
		helper.succeed();
	}

	/** Invariant 3: removing A from an item it overwrote B on leaves it plain; B never comes back. */
	@GameTest
	public void anOverwrittenSkinNeverComesBack(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null).skin("frost", "sword", "Frost Blade", null);
		fx.reload();
		ServerPlayer bob = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		ServerPlayer alice = ApplyTests.carrying(helper);
		fx.skins.grant(bob.nameAndId(), "frost");
		fx.skins.grant(alice.nameAndId(), "candy_sword_test");
		fx.skins.apply(bob, "frost", ItemLocation.hotbar(0));
		ItemStack bobsCopy = bob.getInventory().getItem(0).copy();
		alice.getInventory().setItem(0, bob.getInventory().removeItemNoUpdate(0));
		ItemStack item = alice.getInventory().getItem(0);
		fx.skins.apply(alice, "candy_sword_test", ItemLocation.hotbar(0));

		assertThat(fx.skins.remove(alice.nameAndId(), "candy_sword_test") == OwnershipChange.DONE, "Alice couldn't remove A");
		assertThat(fx.skins.liveSkin(item).isEmpty(), "the item still carries a skin: " + fx.skins.liveSkin(item));
		assertThat(fx.skins.tidy(alice) == 1 && !item.has(SkinMarker.TYPE), "the item kept a marker: " + item.get(SkinMarker.TYPE));
		assertThat(fx.skins.liveSkin(bobsCopy).isEmpty() && state(fx.skins, bob) == OwnedSkin.State.UNAPPLIED, "B came back");
		helper.succeed();
	}

	/** The anvil (rename and repair), the grindstone, the enchanting table, mending and a smithing upgrade all keep the skin. */
	@GameTest
	public void theAnvilGrindstoneEnchantingMendingAndSmithingKeepTheSkin(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack sword = owner.getInventory().getItem(0);
		ServerLevel level = helper.getLevel();
		ContainerLevelAccess here = ContainerLevelAccess.create(level, helper.absolutePos(new BlockPos(1, 1, 1)));
		var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);

		AnvilMenu anvil = new AnvilMenu(1, owner.getInventory(), here);
		ItemStack worn = sword.copy();
		worn.setDamageValue(900);
		anvil.getSlot(AnvilMenu.INPUT_SLOT).set(worn);
		anvil.getSlot(AnvilMenu.ADDITIONAL_SLOT).set(new ItemStack(Items.DIAMOND));
		anvil.setItemName("Old Faithful");
		anvil.createResult();
		ItemStack renamed = anvil.getSlot(AnvilMenu.RESULT_SLOT).getItem();
		assertThat(renamed.getHoverName().getString().equals("Old Faithful") && renamed.getDamageValue() < 900, "the anvil made " + renamed);
		assertThat(fx.skins.liveSkin(renamed).isPresent(), "the anvil lost the skin");

		GrindstoneMenu grindstone = new GrindstoneMenu(2, owner.getInventory(), here);
		ItemStack enchanted = sword.copy();
		enchanted.enchant(enchantments.getOrThrow(Enchantments.SHARPNESS), 3);
		grindstone.getSlot(GrindstoneMenu.INPUT_SLOT).set(enchanted);
		ItemStack ground = grindstone.getSlot(GrindstoneMenu.RESULT_SLOT).getItem();
		assertThat(ground.is(Items.DIAMOND_SWORD) && !ground.isEnchanted(), "the grindstone made " + ground);
		assertThat(fx.skins.liveSkin(ground).isPresent(), "the grindstone lost the skin");

		EnchantmentMenu table = new EnchantmentMenu(3, owner.getInventory(), here);
		table.getSlot(0).set(sword.copy());
		table.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 3));
		assertThat(table.costs[0] > 0, "the enchanting table offers nothing");
		assertThat(table.clickMenuButton(owner, 0), "the enchanting table refused");
		ItemStack fromTheTable = table.getSlot(0).getItem();
		assertThat(fromTheTable.isEnchanted() && fx.skins.liveSkin(fromTheTable).isPresent(), "the enchanting table made " + fromTheTable);

		ItemStack mended = sword.copy();
		mended.enchant(enchantments.getOrThrow(Enchantments.MENDING), 1);
		mended.setDamageValue(900);
		owner.getInventory().setItem(0, mended);
		ExperienceOrb orb = new ExperienceOrb(level, owner.getX(), owner.getY(), owner.getZ(), 50);
		level.addFreshEntity(orb);
		owner.takeXpDelay = 0;
		orb.playerTouch(owner);
		assertThat(owner.getInventory().getItem(0) == mended && mended.getDamageValue() < 900, "mending didn't repair " + mended);
		assertThat(fx.skins.liveSkin(mended).isPresent(), "mending lost the skin");

		SmithingRecipeInput upgrade = new SmithingRecipeInput(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), sword.copy(),
				new ItemStack(Items.NETHERITE_INGOT));
		ItemStack netherite = level.getServer().getRecipeManager().getRecipeFor(RecipeType.SMITHING, upgrade, level)
				.orElseThrow(() -> SkinFixtures.fail("no netherite upgrade")).value().assemble(upgrade);
		assertThat(netherite.is(Items.NETHERITE_SWORD) && fx.skins.liveSkin(netherite).isPresent(), "the smithing upgrade made " + netherite);
		helper.succeed();
	}

	/** Crafting-grid repair builds a new item, so the skin is lost (and that's accepted). */
	@GameTest
	public void craftingGridRepairLosesTheSkin(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		ItemStack skinned = owner.getInventory().getItem(0);
		skinned.setDamageValue(900);
		ItemStack plain = new ItemStack(Items.DIAMOND_SWORD);
		plain.setDamageValue(900);

		CraftingInput grid = CraftingInput.of(2, 1, List.of(skinned, plain));
		ServerLevel level = helper.getLevel();
		ItemStack repaired = level.getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, level)
				.orElseThrow(() -> SkinFixtures.fail("no repair recipe")).value().assemble(grid);
		assertThat(repaired.is(Items.DIAMOND_SWORD) && repaired.getDamageValue() < 900, "the grid made " + repaired);
		assertThat(!repaired.has(SkinMarker.TYPE) && fx.skins.liveSkin(repaired).isEmpty(), "crafting-grid repair kept the skin");
		helper.succeed();
	}

	/**
	 * Smithing keeps the skin only while the result still fits it: a recipe (a data pack's, say)
	 * that turns the item into another Kind drops the skin, which becomes Unapplied.
	 */
	@GameTest
	public void aSmithingResultOfAnotherKindDropsTheSkin(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(owner.nameAndId(), "candy_sword_test");
		fx.skins.apply(owner, "candy_sword_test", ItemLocation.hotbar(0));
		// What every smithing transform does: the result is the base's components on another item.
		ItemStack pickaxe = owner.getInventory().getItem(0).transmuteCopy(Items.DIAMOND_PICKAXE);
		owner.getInventory().setItem(0, pickaxe);

		assertThat(fx.skins.liveSkin(pickaxe).isEmpty(), "a sword skin is live on a pickaxe");
		assertThat(fx.skins.tidy(owner) == 1 && !pickaxe.has(SkinMarker.TYPE), "the pickaxe kept the sword skin's marker");
		assertThat(state(fx.skins, owner) == OwnedSkin.State.UNAPPLIED, "the skin that fell off is " + state(fx.skins, owner));
		assertThat(state(fx.restart(), owner) == OwnedSkin.State.UNAPPLIED, "the skin falling off wasn't saved");
		helper.succeed();
	}

	/**
	 * The netherite upgrade keeps a skin only if the skin allows netherite: a diamond-only skin
	 * falls off (plain at once, Unapplied when the Owner's items are next tidied), and one listing diamond
	 * and netherite survives it.
	 */
	@GameTest
	public void theNetheriteUpgradeKeepsASkinOnlyIfItAllowsNetherite(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().limited("diamond_blade", "sword", "[\"diamond\"]")
				.limited("fine_blade", "sword", "[\"diamond\", \"netherite\"]");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(owner.nameAndId(), "diamond_blade");
		fx.skins.grant(owner.nameAndId(), "fine_blade");
		fx.skins.apply(owner, "diamond_blade", ItemLocation.hotbar(0));
		fx.skins.apply(owner, "fine_blade", ItemLocation.hotbar(1));
		ItemStack upgradedDiamondOnly = upgrade(helper, owner.getInventory().getItem(0));
		ItemStack upgradedFine = upgrade(helper, owner.getInventory().getItem(1));
		owner.getInventory().setItem(0, upgradedDiamondOnly);
		owner.getInventory().setItem(1, upgradedFine);

		assertThat(upgradedDiamondOnly.is(Items.NETHERITE_SWORD) && fx.skins.liveSkin(upgradedDiamondOnly).isEmpty(),
				"the diamond-only skin is live on " + upgradedDiamondOnly);
		assertThat(upgradedFine.is(Items.NETHERITE_SWORD) && fx.skins.liveSkin(upgradedFine).isPresent(), "the skin allowing netherite fell off");
		assertThat(state(fx.skins, owner, "diamond_blade") == OwnedSkin.State.APPLIED, "the skin went Unapplied before its item was tidied");
		assertThat(fx.skins.tidy(owner) == 1 && !upgradedDiamondOnly.has(SkinMarker.TYPE), "the upgraded sword kept the diamond-only marker");
		assertThat(upgradedFine.has(SkinMarker.TYPE), "the tidy took the netherite-allowing skin off");
		assertThat(state(fx.restart(), owner, "diamond_blade") == OwnedSkin.State.UNAPPLIED, "the skin that fell off: " + state(fx.skins, owner, "diamond_blade"));
		assertThat(state(fx.skins, owner, "fine_blade") == OwnedSkin.State.APPLIED, "the skin that survived: " + state(fx.skins, owner, "fine_blade"));
		helper.succeed();
	}

	/**
	 * Narrowing {@code materials} takes the skin off only the items that no longer fit, as each
	 * Owner's items are tidied; widening the list again before an item is tidied leaves it live, as
	 * if nothing happened (unlike a Kind change).
	 */
	@GameTest
	public void narrowingMaterialsTakesTheSkinOffOnlyItemsThatNoLongerFit(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("cap", "helmet");
		fx.reload();
		ServerPlayer alice = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_HELMET));
		ServerPlayer bob = ApplyTests.carrying(helper, new ItemStack(Items.LEATHER_HELMET));
		ServerPlayer carol = ApplyTests.carrying(helper, new ItemStack(Items.IRON_HELMET));
		for (ServerPlayer player : List.of(alice, bob, carol)) {
			fx.skins.grant(player.nameAndId(), "cap");
			fx.skins.apply(player, "cap", ItemLocation.hotbar(0));
		}
		ItemStack alices = alice.getInventory().getItem(0), bobs = bob.getInventory().getItem(0), carols = carol.getInventory().getItem(0);

		fx.limited("cap", "helmet", "[\"leather\"]");
		fx.reload();
		assertThat(fx.skins.liveSkin(alices).isEmpty() && fx.skins.liveSkin(carols).isEmpty(), "a narrowed skin is live on a helmet it no longer fits");
		assertThat(fx.skins.liveSkin(bobs).isPresent(), "narrowing took the skin off the leather helmet");
		assertThat(fx.skins.tidy(alice) == 1 && !alices.has(SkinMarker.TYPE), "Alice's diamond helmet kept its marker");
		assertThat(fx.skins.tidy(bob) == 0 && fx.skins.liveSkin(bobs).isPresent(), "tidying Bob's leather helmet took the skin off");
		assertThat(state(fx.skins, alice, "cap") == OwnedSkin.State.UNAPPLIED, "Alice's skin that fell off is " + state(fx.skins, alice, "cap"));
		assertThat(state(fx.skins, bob, "cap") == OwnedSkin.State.APPLIED, "Bob's skin is " + state(fx.skins, bob, "cap"));

		// Carol's helmet wasn't tidied while the list was narrow, so widening it brings her skin back.
		fx.limited("cap", "helmet", "[\"leather\", \"iron\"]");
		fx.reload();
		assertThat(fx.skins.liveSkin(carols).isPresent() && fx.skins.tidy(carol) == 0, "widening didn't leave Carol's iron helmet live");
		assertThat(state(fx.skins, carol, "cap") == OwnedSkin.State.APPLIED, "Carol's skin is " + state(fx.skins, carol, "cap"));
		assertThat(fx.skins.liveSkin(alices).isEmpty(), "Alice's tidied diamond helmet came back");
		helper.succeed();
	}

	/** The netherite upgrade, as the smithing table makes it. */
	private static ItemStack upgrade(GameTestHelper helper, ItemStack base) {
		ServerLevel level = helper.getLevel();
		SmithingRecipeInput input = new SmithingRecipeInput(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE), base.copy(),
				new ItemStack(Items.NETHERITE_INGOT));
		return level.getServer().getRecipeManager().getRecipeFor(RecipeType.SMITHING, input, level)
				.orElseThrow(() -> SkinFixtures.fail("no netherite upgrade")).value().assemble(input);
	}

	/** The state of one skin the player owns. */
	private static OwnedSkin.State state(Skins skins, ServerPlayer player, String skin) {
		PlayerSkins owned = skins.ownedSkins(player.getUUID());
		return owned.skins().stream().filter(s -> s.id().equals(skin)).findFirst()
				.orElseThrow(() -> SkinFixtures.fail(player.getGameProfile().name() + " doesn't own " + skin + ": " + owned)).state();
	}

	private static Identifier model(GameTestHelper helper, SkinFixtures fx, ItemStack stack, ServerPlayer viewer) {
		return ViewTests.view(helper, fx, stack, viewer, viewer.getUUID()).get(DataComponents.ITEM_MODEL);
	}

	/** The state of the one skin the player owns. */
	private static OwnedSkin.State state(Skins skins, ServerPlayer player) {
		PlayerSkins owned = skins.ownedSkins(player.getUUID());
		assertThat(owned.damage().isEmpty() && owned.skins().size() == 1, "owns " + owned);
		return owned.skins().getFirst().state();
	}
}
