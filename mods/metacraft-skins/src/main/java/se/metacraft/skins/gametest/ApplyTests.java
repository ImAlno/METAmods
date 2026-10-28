package se.metacraft.skins.gametest;

import se.metacraft.skins.ApplyResult;
import se.metacraft.skins.Candidate;
import se.metacraft.skins.ItemLocation;
import se.metacraft.skins.LiveSkin;
import se.metacraft.skins.OwnedSkin;
import se.metacraft.skins.OwnershipChange;
import se.metacraft.skins.PlayerSkins;
import se.metacraft.skins.SkinMarker;
import se.metacraft.skins.Skins;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static se.metacraft.skins.gametest.SkinFixtures.assertThat;

/**
 * Applying and removing through the Skins module: which item becomes a Skinned Item, what it
 * stores, whose skin it is, what a second skin over it does, and every refusal. What a viewer
 * sees of a Skinned Item is ViewTests'; the menus that drive this are SkinMenuTests'.
 */
public final class ApplyTests {

	@GameTest
	public void applyingToTheMainHandMakesItASkinnedItemOfThatSkin(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null);
		fx.reload();
		ServerPlayer player = carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(player.nameAndId(), "candy_sword_test");

		ApplyResult result = fx.skins.apply(player, "candy_sword_test", ItemLocation.hotbar(0));
		assertThat(result.applied() && result.replaced().isEmpty(), "apply: " + result);
		ItemStack sword = player.getInventory().getItem(0);
		LiveSkin live = fx.skins.liveSkin(sword).orElseThrow(() -> SkinFixtures.fail("the sword carries no live skin"));
		assertThat(live.id().equals("candy_sword_test") && live.name().equals("Candy Sword"), "the sword carries " + live);
		assertThat(live.owner().equals(player.getUUID()) && live.ownerName().equals(player.getGameProfile().name()), "owned by " + live);
		assertThat(only(fx.skins.ownedSkins(player.getUUID())).state() == OwnedSkin.State.APPLIED, "the skin isn't Applied");
		assertThat(only(fx.restart().ownedSkins(player.getUUID())).state() == OwnedSkin.State.APPLIED, "the application wasn't saved");

		// The item stores the marker and nothing else: no Look, no lore, no Perks.
		var added = sword.getComponentsPatch().split().added().stream().map(c -> c.type()).toList();
		assertThat(added.equals(List.of(SkinMarker.TYPE)), "the Skinned Item stores " + added);
		SkinMarker marker = sword.get(SkinMarker.TYPE);
		assertThat(marker.skin().equals("candy_sword_test") && marker.owner().equals(player.getUUID()), "marker " + marker);
		helper.succeed();
	}

	@GameTest
	public void aSkinGoesOnWornArmourAndTheOffhandToo(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("crown_test", "helmet").skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer player = carrying(helper);
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TURTLE_HELMET));
		player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.IRON_SWORD));
		fx.skins.grant(player.nameAndId(), "crown_test");
		fx.skins.grant(player.nameAndId(), "candy_sword_test");

		assertThat(fx.skins.apply(player, "crown_test", ItemLocation.worn(EquipmentSlot.HEAD)).applied(), "the worn turtle shell refused the crown_test");
		assertThat(fx.skins.liveSkin(player.getItemBySlot(EquipmentSlot.HEAD)).map(LiveSkin::id).equals(Optional.of("crown_test")), "the worn turtle shell has no crown_test");
		assertThat(fx.skins.apply(player, "candy_sword_test", ItemLocation.offhand()).applied(), "the offhand sword refused the skin");
		assertThat(fx.skins.liveSkin(player.getItemBySlot(EquipmentSlot.OFFHAND)).map(LiveSkin::id).equals(Optional.of("candy_sword_test")),
				"the offhand sword has no skin");
		helper.succeed();
	}

	/** One skin, one item: applying it to a second item leaves the first one plain. */
	@GameTest
	public void applyingASkinToAnotherItemMovesIt(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer player = carrying(helper, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.NETHERITE_SWORD));
		fx.skins.grant(player.nameAndId(), "candy_sword_test");
		fx.skins.apply(player, "candy_sword_test", ItemLocation.hotbar(0));
		assertThat(fx.skins.apply(player, "candy_sword_test", ItemLocation.hotbar(1)).applied(), "the second apply was refused");
		assertThat(fx.skins.liveSkin(player.getInventory().getItem(0)).isEmpty(), "the first sword still carries the skin");
		assertThat(fx.skins.liveSkin(player.getInventory().getItem(1)).isPresent(), "the second sword doesn't carry it");
		assertThat(fx.skins.apply(player, "candy_sword_test", ItemLocation.hotbar(1)).outcome() == ApplyResult.Outcome.ALREADY_APPLIED,
				"applied twice to the same item");
		helper.succeed();
	}

	/** Over someone else's skin: theirs is replaced and becomes Unapplied, still theirs. */
	@GameTest
	public void applyingOverAnotherPlayersSkinReplacesIt(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null).skin("frost", "sword", "Frost Blade", null);
		fx.reload();
		ServerPlayer alice = carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		ServerPlayer bob = carrying(helper);
		fx.skins.grant(alice.nameAndId(), "candy_sword_test");
		fx.skins.grant(bob.nameAndId(), "frost");
		fx.skins.apply(alice, "candy_sword_test", ItemLocation.hotbar(0));
		bob.getInventory().setItem(4, alice.getInventory().removeItemNoUpdate(0));   // traded

		ApplyResult result = fx.skins.apply(bob, "frost", ItemLocation.hotbar(4));
		assertThat(result.applied() && result.replaced().equals(Optional.of("Candy Sword")), "apply over Alice's skin: " + result);
		assertThat(fx.skins.liveSkin(bob.getInventory().getItem(4)).map(LiveSkin::id).equals(Optional.of("frost")), "the sword doesn't carry Bob's skin");
		PlayerSkins alices = fx.restart().ownedSkins(alice.getUUID());
		assertThat(alices.owns("candy_sword_test") && only(alices).state() == OwnedSkin.State.UNAPPLIED, "Alice's replaced skin: " + alices);
		helper.succeed();
	}

	@GameTest
	public void applyingOverYourOwnOtherSkinReplacesIt(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null).skin("frost", "sword", "Frost Blade", null);
		fx.reload();
		ServerPlayer player = carrying(helper, new ItemStack(Items.STONE_SWORD));
		fx.skins.grant(player.nameAndId(), "candy_sword_test");
		fx.skins.grant(player.nameAndId(), "frost");
		fx.skins.apply(player, "candy_sword_test", ItemLocation.hotbar(0));
		ApplyResult result = fx.skins.apply(player, "frost", ItemLocation.hotbar(0));
		assertThat(result.applied() && result.replaced().equals(Optional.of("Candy Sword")), "apply over their own skin: " + result);
		PlayerSkins after = fx.restart().ownedSkins(player.getUUID());
		assertThat(after.skins().stream().filter(s -> s.state() == OwnedSkin.State.APPLIED).map(OwnedSkin::id).toList().equals(List.of("frost")),
				"after the overwrite: " + after);
		helper.succeed();
	}

	/**
	 * Over a Dormant skin, theirs or another player's: the item takes the new skin, and the Dormant
	 * one is Unapplied for its Owner, who keeps it, so it doesn't come back Applied when it's fixed.
	 * Its name is unknown while it's Dormant, so the apply screen and the result name it by its id.
	 */
	@GameTest
	public void applyingOverADormantSkinMakesItUnappliedForItsOwner(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null)
				.skin("frost", "sword", "Frost Blade", null).skin("ember", "sword", "Ember Blade", null);
		fx.reload();
		ServerPlayer alice = carrying(helper, new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.IRON_SWORD));
		ServerPlayer bob = carrying(helper);
		for (String skin : List.of("candy_sword_test", "frost", "ember")) fx.skins.grant(alice.nameAndId(), skin);
		fx.skins.grant(bob.nameAndId(), "candy_sword_test");
		fx.skins.apply(alice, "frost", ItemLocation.hotbar(0));
		fx.skins.apply(alice, "ember", ItemLocation.hotbar(1));
		bob.getInventory().setItem(4, alice.getInventory().removeItemNoUpdate(1));   // traded
		fx.file("frost", "skin.json5", "{ name: ").file("ember", "skin.json5", "{ name: ");
		fx.reload();

		List<Candidate> candidates = fx.skins.candidates(alice, "candy_sword_test");
		assertThat(candidates.getFirst().replaces().equals(Optional.of("frost")), "the sword with Dormant frost: " + candidates.getFirst());
		ApplyResult own = fx.skins.apply(alice, "candy_sword_test", ItemLocation.hotbar(0));
		assertThat(own.applied() && own.replaced().equals(Optional.of("frost")), "apply over their own Dormant skin: " + own);
		ApplyResult theirs = fx.skins.apply(bob, "candy_sword_test", ItemLocation.hotbar(4));
		assertThat(theirs.applied() && theirs.replaced().equals(Optional.of("ember")), "apply over Alice's Dormant skin: " + theirs);

		fx.skin("frost", "sword", "Frost Blade", null).skin("ember", "sword", "Ember Blade", null);
		PlayerSkins alices = fx.restart().ownedSkins(alice.getUUID());
		for (String skin : List.of("frost", "ember")) {
			assertThat(alices.skins().stream().anyMatch(s -> s.id().equals(skin) && s.state() == OwnedSkin.State.UNAPPLIED),
					"Alice's replaced " + skin + " once it's fixed: " + alices);
		}
		fx.reload();
		assertThat(fx.skins.liveSkin(alice.getInventory().getItem(0)).map(LiveSkin::id).equals(Optional.of("candy_sword_test")), "Alice's sword");
		assertThat(fx.skins.liveSkin(bob.getInventory().getItem(4)).map(LiveSkin::id).equals(Optional.of("candy_sword_test")), "Bob's sword");
		helper.succeed();
	}

	/** Over a skin whose Owner's file is damaged: the item takes the new skin, and that file is left alone (and logged). */
	@GameTest
	public void applyingOverASkinWhoseOwnersFileIsDamagedLeavesThatFileAlone(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null).skin("frost", "sword", "Frost Blade", null);
		fx.reload();
		ServerPlayer alice = carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		ServerPlayer bob = carrying(helper);
		fx.skins.grant(alice.nameAndId(), "frost");
		fx.skins.grant(bob.nameAndId(), "candy_sword_test");
		fx.skins.apply(alice, "frost", ItemLocation.hotbar(0));
		bob.getInventory().setItem(4, alice.getInventory().removeItemNoUpdate(0));   // traded
		String damaged = "{ \"uuid\": \"" + alice.getUUID() + "\", \"skins\": { \"frost\": ";
		fx.playerFile(alice.getUUID(), damaged);
		Skins skins = fx.restart();   // the running server's copy wins over a hand edit, so a fresh start reads the damage

		List<Candidate> candidates = skins.candidates(bob, "candy_sword_test");
		assertThat(candidates.getFirst().replaces().equals(Optional.of("Frost Blade")), "the sword with Alice's skin: " + candidates.getFirst());
		ApplyResult result = skins.apply(bob, "candy_sword_test", ItemLocation.hotbar(4));
		assertThat(result.applied() && result.replaced().equals(Optional.of("Frost Blade")), "apply over Alice's skin: " + result);
		assertThat(skins.liveSkin(bob.getInventory().getItem(4)).map(LiveSkin::id).equals(Optional.of("candy_sword_test")), "Bob's sword");
		assertThat(SkinFixtures.read(fx.playerFile(alice.getUUID())).equals(damaged), "Alice's damaged file was overwritten");
		helper.succeed();
	}

	@GameTest
	public void removingMakesTheSkinUnappliedAndItsItemPlain(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword").skin("crown_test", "helmet");
		fx.reload();
		ServerPlayer player = carrying(helper, new ItemStack(Items.WOODEN_SWORD));
		NameAndId profile = player.nameAndId();
		fx.skins.grant(profile, "candy_sword_test");
		fx.skins.apply(player, "candy_sword_test", ItemLocation.hotbar(0));

		assertThat(fx.skins.remove(profile, "candy_sword_test") == OwnershipChange.DONE, "remove was refused");
		assertThat(fx.skins.liveSkin(player.getInventory().getItem(0)).isEmpty(), "the item still carries the removed skin");
		assertThat(only(fx.restart().ownedSkins(player.getUUID())).state() == OwnedSkin.State.UNAPPLIED, "the remove wasn't saved");
		assertThat(fx.skins.remove(profile, "candy_sword_test") == OwnershipChange.NOT_APPLIED, "removed a skin that is on no item");
		assertThat(fx.skins.remove(profile, "crown_test") == OwnershipChange.NOT_OWNED, "removed a skin that isn't owned");
		helper.succeed();
	}

	@GameTest
	public void applyIsRefusedForWhatTheOwnerCantApply(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword").skin("emerald_pick_test", "pickaxe").skin("gone", "sword");
		fx.reload();
		ServerPlayer player = carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(player.nameAndId(), "candy_sword_test");
		fx.skins.grant(player.nameAndId(), "emerald_pick_test");
		fx.skins.grant(player.nameAndId(), "gone");
		fx.delete("gone");
		fx.reload();

		assertRefused(fx.skins.apply(player, "no_such_skin", ItemLocation.hotbar(0)), ApplyResult.Outcome.UNKNOWN_SKIN);
		assertRefused(fx.skins.apply(player, "gone", ItemLocation.hotbar(0)), ApplyResult.Outcome.UNKNOWN_SKIN);
		assertRefused(fx.skins.apply(player, "emerald_pick_test", ItemLocation.hotbar(0)), ApplyResult.Outcome.DOESNT_FIT);
		assertRefused(fx.skins.apply(player, "candy_sword_test", ItemLocation.hotbar(1)), ApplyResult.Outcome.NO_ITEM);
		ServerPlayer stranger = carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		assertRefused(fx.skins.apply(stranger, "candy_sword_test", ItemLocation.hotbar(0)), ApplyResult.Outcome.NOT_OWNED);
		fx.lookOnly("broken", "no_such_kind");
		fx.reload();
		assertRefused(fx.skins.apply(player, "broken", ItemLocation.hotbar(0)), ApplyResult.Outcome.DORMANT_SKIN);
		assertThat(!player.getInventory().getItem(0).has(SkinMarker.TYPE) && !stranger.getInventory().getItem(0).has(SkinMarker.TYPE),
				"a refused apply marked the item");
		helper.succeed();
	}

	@GameTest
	public void aDamagedFileRefusesApplyAndRemoveAndIsLeftUntouched(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer player = carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		String damaged = "{ \"uuid\": \"" + player.getUUID() + "\", \"skins\": { \"candy_sword_test\": ";
		fx.playerFile(player.getUUID(), damaged);

		assertRefused(fx.skins.apply(player, "candy_sword_test", ItemLocation.hotbar(0)), ApplyResult.Outcome.DAMAGED_FILE);
		assertThat(fx.skins.remove(player.nameAndId(), "candy_sword_test") == OwnershipChange.DAMAGED_FILE, "removed from a damaged file");
		assertThat(SkinFixtures.read(fx.playerFile(player.getUUID())).equals(damaged), "the damaged file was overwritten");
		assertThat(!player.getInventory().getItem(0).has(SkinMarker.TYPE), "the item was marked");
		helper.succeed();
	}

	@GameTest
	public void anApplyThatCantBeSavedIsNotMade(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		ServerPlayer player = carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		fx.skins.grant(player.nameAndId(), "candy_sword_test");
		try (Stream<java.nio.file.Path> files = Files.list(fx.root.resolve("skins-data/players"))) {
			for (var file : files.toList()) Files.delete(file);
			Files.delete(fx.root.resolve("skins-data/players"));
			Files.writeString(fx.root.resolve("skins-data/players"), "not a folder");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		assertRefused(fx.skins.apply(player, "candy_sword_test", ItemLocation.hotbar(0)), ApplyResult.Outcome.SAVE_FAILED);
		assertThat(!player.getInventory().getItem(0).has(SkinMarker.TYPE), "an apply that couldn't be saved marked the item");
		assertThat(only(fx.skins.ownedSkins(player.getUUID())).state() == OwnedSkin.State.UNAPPLIED, "an apply that couldn't be saved counted");
		helper.succeed();
	}

	/** Every material of a Kind fits it, whatever else the player carries, from the hotbar to the offhand. */
	@GameTest
	public void aKindFitsEveryMaterialOfItAndNothingElse(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("blade", "sword").skin("hat", "helmet").skin("wings", "elytra").skin("plate", "chestplate")
				.skin("lance", "spear").skin("hammer", "mace").skin("chopper", "axe");
		fx.reload();
		ItemStack stackingSword = new ItemStack(Items.DIAMOND_SWORD);
		stackingSword.set(DataComponents.MAX_STACK_SIZE, 16);
		ServerPlayer player = carrying(helper, new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.STONE_SWORD), new ItemStack(Items.COPPER_SWORD),
				new ItemStack(Items.IRON_SWORD), new ItemStack(Items.GOLDEN_SWORD), new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.NETHERITE_SWORD),
				stackingSword, new ItemStack(Items.BOW), new ItemStack(Items.CROSSBOW), new ItemStack(Items.TRIDENT), new ItemStack(Items.SHIELD),
				new ItemStack(Items.FISHING_ROD), new ItemStack(Items.SHEARS), new ItemStack(Items.FLINT_AND_STEEL), new ItemStack(Items.BRUSH),
				new ItemStack(Items.CARROT_ON_A_STICK), new ItemStack(Items.WARPED_FUNGUS_ON_A_STICK), new ItemStack(Items.STICK, 5),
				new ItemStack(Items.LEATHER_HELMET), new ItemStack(Items.CARVED_PUMPKIN), new ItemStack(Items.PLAYER_HEAD),
				new ItemStack(Items.ELYTRA), new ItemStack(Items.IRON_CHESTPLATE), new ItemStack(Items.WOODEN_SPEAR), new ItemStack(Items.NETHERITE_SPEAR),
				new ItemStack(Items.MACE), new ItemStack(Items.GOLDEN_AXE));
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.TURTLE_HELMET));
		player.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.DIAMOND_SWORD));
		for (String id : List.of("blade", "hat", "wings", "plate", "lance", "hammer", "chopper")) fx.skins.grant(player.nameAndId(), id);

		assertCandidates(fx, player, "blade", List.of("Hotbar slot 1", "Hotbar slot 2", "Hotbar slot 3", "Hotbar slot 4", "Hotbar slot 5",
				"Hotbar slot 6", "Hotbar slot 7", "Offhand"));
		assertCandidates(fx, player, "hat", List.of("Inventory", "Worn (head)"));
		assertCandidates(fx, player, "wings", List.of("Inventory"));
		assertCandidates(fx, player, "plate", List.of("Inventory"));
		assertCandidates(fx, player, "lance", List.of("Inventory", "Inventory"));
		assertCandidates(fx, player, "hammer", List.of("Inventory"));
		assertCandidates(fx, player, "chopper", List.of("Inventory"));
		assertThat(fx.skins.candidates(player, "wings").getFirst().item().is(Items.ELYTRA), "the elytra skin fits " + fx.skins.candidates(player, "wings"));
		assertThat(fx.skins.candidates(player, "plate").getFirst().item().is(Items.IRON_CHESTPLATE), "the chestplate skin fits "
				+ fx.skins.candidates(player, "plate"));
		helper.succeed();
	}

	/**
	 * A skin limited to Materials fits only items of its Kind made of one of them: a leather-only
	 * helmet skin goes on a leather helmet and nothing else, a diamond-and-netherite sword skin on
	 * both of those swords. Applying it to anything else is refused.
	 */
	@GameTest
	public void aLimitedSkinFitsOnlyItsMaterials(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().limited("cap", "helmet", "[\"leather\"]").limited("fine_blade", "sword", "[\"netherite\", \"diamond\"]");
		fx.reload();
		ServerPlayer player = carrying(helper, new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.LEATHER_HELMET), new ItemStack(Items.TURTLE_HELMET),
				new ItemStack(Items.WOODEN_SWORD), new ItemStack(Items.IRON_SWORD), new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.GOLDEN_SWORD),
				new ItemStack(Items.NETHERITE_SWORD), new ItemStack(Items.LEATHER_BOOTS));
		player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
		fx.skins.grant(player.nameAndId(), "cap");
		fx.skins.grant(player.nameAndId(), "fine_blade");

		assertCandidates(fx, player, "cap", List.of("Hotbar slot 2"));
		assertCandidates(fx, player, "fine_blade", List.of("Hotbar slot 6", "Hotbar slot 8"));
		assertRefused(fx.skins.apply(player, "cap", ItemLocation.hotbar(0)), ApplyResult.Outcome.DOESNT_FIT);
		assertRefused(fx.skins.apply(player, "cap", ItemLocation.worn(EquipmentSlot.HEAD)), ApplyResult.Outcome.DOESNT_FIT);
		assertRefused(fx.skins.apply(player, "fine_blade", ItemLocation.hotbar(4)), ApplyResult.Outcome.DOESNT_FIT);
		assertThat(!player.getInventory().getItem(0).has(SkinMarker.TYPE) && !player.getInventory().getItem(4).has(SkinMarker.TYPE),
				"a refused apply marked the item");
		assertThat(fx.skins.apply(player, "cap", ItemLocation.hotbar(1)).applied(), "the leather helmet refused the leather-only skin");
		assertThat(fx.skins.liveSkin(player.getInventory().getItem(1)).isPresent(), "the leather helmet's skin isn't live");
		assertThat(fx.skins.apply(player, "fine_blade", ItemLocation.hotbar(7)).applied(), "the netherite sword refused the skin");
		assertThat(fx.skins.liveSkin(player.getInventory().getItem(7)).isPresent(), "the netherite sword's skin isn't live");
		helper.succeed();
	}

	/**
	 * An item of the Kind that isn't one of vanilla's (here a totem tagged into
	 * {@code #minecraft:swords}, as another mod's ruby sword would be) has no Material it's known by:
	 * a skin with no {@code materials} fits it, and one listing every Material doesn't.
	 */
	@GameTest
	public void onlyAnUnlimitedSkinFitsAnItemOfAnUnrecognisedMaterial(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("any_blade", "sword")
				.limited("every_blade", "sword", "[\"wood\", \"stone\", \"copper\", \"iron\", \"gold\", \"diamond\", \"netherite\"]");
		fx.reload();
		ServerPlayer player = carrying(helper, new ItemStack(Items.TOTEM_OF_UNDYING), new ItemStack(Items.STONE_SWORD));
		fx.skins.grant(player.nameAndId(), "any_blade");
		fx.skins.grant(player.nameAndId(), "every_blade");
		Holder.Reference<Item> totem = Items.TOTEM_OF_UNDYING.builtInRegistryHolder();
		List<TagKey<Item>> tags = totem.tags().toList();
		bindTags(totem, Stream.concat(tags.stream(), Stream.of(ItemTags.SWORDS)).toList());
		try {
			assertCandidates(fx, player, "any_blade", List.of("Hotbar slot 1", "Hotbar slot 2"));
			assertCandidates(fx, player, "every_blade", List.of("Hotbar slot 2"));
			assertRefused(fx.skins.apply(player, "every_blade", ItemLocation.hotbar(0)), ApplyResult.Outcome.DOESNT_FIT);
			assertThat(fx.skins.apply(player, "any_blade", ItemLocation.hotbar(0)).applied(), "the unlimited skin refused the totem sword");
		} finally {
			bindTags(totem, tags);
		}
		helper.succeed();
	}

	/** Item tags are bound by the server on each data reload; a test binds one more for a moment. */
	private static void bindTags(Holder.Reference<Item> item, List<TagKey<Item>> tags) {
		try {
			Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
			bind.setAccessible(true);
			bind.invoke(item, tags);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The apply screen says what clicking each item would do. */
	@GameTest
	public void eachCandidateSaysWhatApplyingWouldDo(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword", "Candy Sword", null).skin("frost", "sword", "Frost Blade", null);
		fx.reload();
		ServerPlayer player = carrying(helper, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.GOLDEN_SWORD), new ItemStack(Items.STONE_SWORD));
		fx.skins.grant(player.nameAndId(), "candy_sword_test");
		fx.skins.grant(player.nameAndId(), "frost");
		fx.skins.apply(player, "candy_sword_test", ItemLocation.hotbar(0));
		fx.skins.apply(player, "frost", ItemLocation.hotbar(1));

		List<Candidate> candidates = fx.skins.candidates(player, "candy_sword_test");
		assertThat(candidates.size() == 3, "candidates " + candidates);
		assertThat(candidates.get(0).alreadyHasIt(), "the sword with this skin doesn't say so");
		assertThat(candidates.get(0).replaces().isEmpty(), "the sword with this skin says it'd be replaced: " + candidates.get(0));
		assertThat(!candidates.get(1).alreadyHasIt() && candidates.get(1).replaces().equals(Optional.of("Frost Blade")),
				"the sword with Frost Blade doesn't say it'd be replaced: " + candidates.get(1));
		assertThat(!candidates.get(2).alreadyHasIt() && candidates.get(2).replaces().isEmpty(), "the plain sword: " + candidates.get(2));
		helper.succeed();
	}

	@GameTest
	public void theOwnersNameIsKeptForWhenTheyreOffline(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().skin("candy_sword_test", "sword");
		fx.reload();
		UUID id = UUID.randomUUID();
		fx.skins.grant(new NameAndId(id, "Old"), "candy_sword_test");
		fx.skins.seen(new NameAndId(id, "New"));
		assertThat(fx.restart().ownedSkins(id).lastKnownName().equals(Optional.of("New")), "the new name wasn't kept");
		UUID stranger = UUID.randomUUID();
		fx.skins.seen(new NameAndId(stranger, "Stranger"));
		assertThat(!Files.exists(fx.playerFile(stranger)), "a player who owns nothing got a file");
		helper.succeed();
	}

	private static void assertCandidates(SkinFixtures fx, ServerPlayer player, String skin, List<String> where) {
		List<String> found = fx.skins.candidates(player, skin).stream().map(c -> c.where().label()).toList();
		assertThat(found.equals(where), skin + " fits " + found + ", not " + where);
	}

	private static void assertRefused(ApplyResult result, ApplyResult.Outcome expected) {
		assertThat(result.outcome() == expected, "expected " + expected + ", got " + result);
	}

	/** A mock player carrying exactly these items, from hotbar slot 1 on. */
	static ServerPlayer carrying(GameTestHelper helper, ItemStack... items) {
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.getInventory().clearContent();
		for (int i = 0; i < items.length; i++) player.getInventory().setItem(i, items[i]);
		return player;
	}

	private static OwnedSkin only(PlayerSkins skins) {
		assertThat(skins.damage().isEmpty(), "the file is damaged: " + skins.damage());
		assertThat(skins.skins().size() == 1, "owns " + skins.skins());
		return skins.skins().getFirst();
	}
}
