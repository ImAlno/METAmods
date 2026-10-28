package se.metacraft.skins.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import se.metacraft.skins.OwnedSkin;
import se.metacraft.skins.OwnershipChange;
import se.metacraft.skins.ItemLocation;
import se.metacraft.skins.PlayerSkins;
import se.metacraft.skins.Skins;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static se.metacraft.skins.gametest.SkinFixtures.assertThat;

/**
 * Who owns what: grant and revoke for online and offline players, their refusals, each owned
 * skin's state, and the per-player files behind it (saved on every change, never overwritten
 * when damaged, and the running server's copy winning over hand edits).
 */
public final class OwnershipTests {

	@GameTest
	public void aGrantToAnOfflinePlayerIsOwnedAndSavedAtOnce(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword");
		fx.reload();
		NameAndId alice = new NameAndId(UUID.randomUUID(), "Alice");
		Instant before = Instant.now();

		assertThat(fx.skins.grant(alice, "candy_sword_test") == OwnershipChange.DONE, "the grant was refused");
		OwnedSkin owned = only(fx.skins.ownedSkins(alice.id()));
		assertThat(owned.id().equals("candy_sword_test"), "owns " + owned.id());
		assertThat(owned.state() == OwnedSkin.State.UNAPPLIED, "a granted skin is " + owned.state());
		assertThat(!owned.claimed().isBefore(before.minusSeconds(1)) && !owned.claimed().isAfter(Instant.now().plusSeconds(1)),
				"claimed at " + owned.claimed() + ", not just now");

		// Nothing else is saved: a server started from these files (a crash, say) has it.
		PlayerSkins afterRestart = fx.restart().ownedSkins(alice.id());
		OwnedSkin reloaded = only(afterRestart);
		assertThat(reloaded.id().equals("candy_sword_test") && Duration.between(reloaded.claimed(), owned.claimed()).abs().toSeconds() < 1,
				"after a restart: " + afterRestart);
		assertThat(afterRestart.lastKnownName().orElse("").equals("Alice"), "the last known name is " + afterRestart.lastKnownName());
		helper.succeed();
	}

	@GameTest
	public void grantAndRevokeWorkForAnOnlinePlayer(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("crown_test", "helmet");
		fx.reload();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		assertThat(fx.skins.grant(player.nameAndId(), "crown_test") == OwnershipChange.DONE, "the grant was refused");
		assertThat(fx.restart().ownedSkins(player.getUUID()).owns("crown_test"), "the grant to an online player wasn't saved");
		assertThat(fx.skins.revoke(player.nameAndId(), "crown_test") == OwnershipChange.DONE, "the revoke was refused");
		assertThat(!fx.restart().ownedSkins(player.getUUID()).owns("crown_test"), "the revoke from an online player wasn't saved");
		helper.succeed();
	}

	@GameTest
	public void aGrantIsRefusedForAnOwnedUnknownOrDormantSkin(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword").lookOnly("hat", "hat");
		fx.reload();
		NameAndId bob = new NameAndId(UUID.randomUUID(), "Bob");
		assertThat(fx.skins.grant(bob, "candy_sword_test") == OwnershipChange.DONE, "the first grant was refused");
		assertThat(fx.skins.grant(bob, "candy_sword_test") == OwnershipChange.ALREADY_OWNED, "granted an owned skin twice");
		assertThat(fx.skins.grant(bob, "no_such_skin") == OwnershipChange.UNKNOWN_SKIN, "granted an unknown skin");
		assertThat(fx.skins.grant(bob, "hat") == OwnershipChange.DORMANT_SKIN, "granted a Dormant skin");
		assertThat(fx.restart().ownedSkins(bob.id()).skins().size() == 1, "a refused grant changed the file");
		helper.succeed();
	}

	@GameTest
	public void aRevokeDeletesTheRecordOfAnOfflinePlayer(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword").lookOnly("crown_test", "helmet");
		fx.reload();
		NameAndId carol = new NameAndId(UUID.randomUUID(), "Carol");
		fx.skins.grant(carol, "candy_sword_test");
		fx.skins.grant(carol, "crown_test");

		assertThat(fx.skins.revoke(carol, "candy_sword_test") == OwnershipChange.DONE, "the revoke was refused");
		assertThat(!fx.skins.ownedSkins(carol.id()).owns("candy_sword_test"), "still owns the revoked skin");
		assertThat(fx.skins.revoke(carol, "candy_sword_test") == OwnershipChange.NOT_OWNED, "revoked a skin that isn't owned");
		PlayerSkins afterRestart = fx.restart().ownedSkins(carol.id());
		assertThat(!afterRestart.owns("candy_sword_test") && afterRestart.owns("crown_test"), "after a restart: " + afterRestart);
		helper.succeed();
	}

	/** Revoking is how an admin cleans up a skin whose folder is gone for good. */
	@GameTest
	public void aDormantSkinIsShownAsDormantAndCanBeRevoked(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword");
		fx.reload();
		NameAndId dave = new NameAndId(UUID.randomUUID(), "Dave");
		fx.skins.grant(dave, "candy_sword_test");
		fx.delete("candy_sword_test");
		fx.reload();

		assertThat(only(fx.skins.ownedSkins(dave.id())).state() == OwnedSkin.State.DORMANT, "a skin with no folder isn't Dormant");
		assertThat(fx.skins.revoke(dave, "candy_sword_test") == OwnershipChange.DONE, "the Dormant skin can't be revoked");
		assertThat(fx.restart().ownedSkins(dave.id()).skins().isEmpty(), "the Dormant skin is still owned after a restart");
		helper.succeed();
	}

	/** The file is for people too: an admin answering a support question can read who owns what. */
	@GameTest
	public void aPlayersFileHoldsTheirUuidNameAndEachSkinsClaimAndApplication(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword");
		fx.reload();
		NameAndId erin = new NameAndId(UUID.randomUUID(), "Erin");
		fx.skins.grant(erin, "candy_sword_test");

		JsonObject file = JsonParser.parseString(SkinFixtures.read(fx.playerFile(erin.id()))).getAsJsonObject();
		assertThat(file.get("uuid").getAsString().equals(erin.id().toString()), "uuid: " + file);
		assertThat(file.get("name").getAsString().equals("Erin"), "name: " + file);
		JsonObject skin = file.getAsJsonObject("skins").getAsJsonObject("candy_sword_test");
		assertThat(skin != null, "no candy_sword_test: " + file);
		assertThat(Instant.parse(skin.get("claimed").getAsString()) != null, "claimed: " + file);
		assertThat(!skin.has("application"), "an Unapplied skin should have no application: " + file);
		helper.succeed();
	}

	/** Files written before codecs held "name": null and "application": null; they read as no name and Unapplied. */
	@GameTest
	public void anOlderFileWithNullsStillReads(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword");
		fx.reload();
		UUID harry = UUID.randomUUID();
		fx.playerFile(harry, "{ \"uuid\": \"" + harry + "\", \"name\": null, "
				+ "\"skins\": { \"candy_sword_test\": { \"claimed\": \"2026-01-01T12:00:00Z\", \"application\": null } } }");
		PlayerSkins skins = fx.skins.ownedSkins(harry);
		assertThat(skins.damage().isEmpty() && skins.owns("candy_sword_test"), "an older file reads as " + skins);
		helper.succeed();
	}

	/** One broken skin damages the whole file: a record without it, saved, would lose that skin for good. */
	@GameTest
	public void aFileWithOneBrokenSkinIsDamagedWhole(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword").lookOnly("crown_test", "helmet");
		fx.reload();
		NameAndId ivy = new NameAndId(UUID.randomUUID(), "Ivy");
		String broken = "{ \"uuid\": \"" + ivy.id() + "\", \"name\": \"Ivy\", \"skins\": { "
				+ "\"candy_sword_test\": { \"claimed\": \"2026-01-01T12:00:00Z\" }, \"crown_test\": { \"claimed\": \"yesterday\" } } }";
		fx.playerFile(ivy.id(), broken);
		PlayerSkins skins = fx.skins.ownedSkins(ivy.id());
		assertThat(skins.damage().isPresent() && skins.skins().isEmpty(), "a file with a broken skin reads as " + skins);
		assertThat(fx.skins.revoke(ivy, "candy_sword_test") == OwnershipChange.DAMAGED_FILE, "revoked from a damaged file");
		assertThat(SkinFixtures.read(fx.playerFile(ivy.id())).equals(broken), "the damaged file was overwritten");
		helper.succeed();
	}

	@GameTest
	public void aDamagedFileIsLeftUntouchedAndItsPlayersActionsAreRefused(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword").lookOnly("crown_test", "helmet");
		fx.reload();
		NameAndId frank = new NameAndId(UUID.randomUUID(), "Frank");
		String damaged = "{ \"uuid\": \"" + frank.id() + "\", \"name\": \"Frank\", \"skins\": { \"crown_test\": { \"claimed\": ";
		fx.playerFile(frank.id(), damaged);

		PlayerSkins skins = fx.skins.ownedSkins(frank.id());
		assertThat(skins.damage().isPresent() && skins.skins().isEmpty(), "a damaged file reads as " + skins);
		assertThat(fx.skins.grant(frank, "candy_sword_test") == OwnershipChange.DAMAGED_FILE, "granted over a damaged file");
		assertThat(fx.skins.revoke(frank, "crown_test") == OwnershipChange.DAMAGED_FILE, "revoked from a damaged file");
		assertThat(SkinFixtures.read(fx.playerFile(frank.id())).equals(damaged), "the damaged file was overwritten");

		// Fixed by hand, it counts at once: there was no copy in memory to win over it.
		fx.playerFile(frank.id(), SkinFixtures.appliedIn(frank.id(), "Frank", "crown_test", UUID.randomUUID()));
		assertThat(fx.skins.ownedSkins(frank.id()).owns("crown_test"), "the fixed file isn't read");
		assertThat(fx.skins.grant(frank, "candy_sword_test") == OwnershipChange.DONE, "a fixed file still refuses grants");
		helper.succeed();
	}

	/** A file this mod can't fully understand (a newer version's, say) is damaged too: saving over it would lose what it doesn't know. */
	@GameTest
	public void aFileWithAnUnknownFieldIsDamaged(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword");
		fx.reload();
		NameAndId gina = new NameAndId(UUID.randomUUID(), "Gina");
		String newer = "{ \"uuid\": \"" + gina.id() + "\", \"name\": \"Gina\", \"skins\": {}, \"favourites\": [\"candy_sword_test\"] }";
		fx.playerFile(gina.id(), newer);
		assertThat(fx.skins.ownedSkins(gina.id()).damage().map(d -> d.contains("favourites")).orElse(false),
				"damage: " + fx.skins.ownedSkins(gina.id()).damage());
		assertThat(fx.skins.grant(gina, "candy_sword_test") == OwnershipChange.DAMAGED_FILE, "granted over a file it can't read whole");
		assertThat(SkinFixtures.read(fx.playerFile(gina.id())).equals(newer), "the file was overwritten");
		helper.succeed();
	}

	@GameTest
	public void whileTheServerRunsItsCopyWinsOverHandEdits(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword").lookOnly("crown_test", "helmet");
		fx.reload();
		NameAndId hugo = new NameAndId(UUID.randomUUID(), "Hugo");
		fx.skins.grant(hugo, "candy_sword_test");
		// Edited while running: candy_sword_test swapped for crown_test.
		fx.playerFile(hugo.id(), SkinFixtures.appliedIn(hugo.id(), "Hugo", "crown_test", UUID.randomUUID()));

		PlayerSkins running = fx.skins.ownedSkins(hugo.id());
		assertThat(running.owns("candy_sword_test") && !running.owns("crown_test"), "the hand edit counted: " + running);
		assertThat(fx.skins.grant(hugo, "crown_test") == OwnershipChange.DONE, "crown_test is owned only in the hand edit, yet the grant was refused");
		PlayerSkins afterRestart = fx.restart().ownedSkins(hugo.id());
		assertThat(afterRestart.owns("candy_sword_test") && afterRestart.owns("crown_test")
				&& afterRestart.skins().stream().allMatch(s -> s.state() == OwnedSkin.State.UNAPPLIED), "the next save didn't write the running copy: " + afterRestart);
		helper.succeed();
	}

	@GameTest
	public void aChangeThatCantBeSavedIsNotMade(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword");
		fx.reload();
		NameAndId ida = new NameAndId(UUID.randomUUID(), "Ida");
		try {
			// A file where the players folder should be: nothing can be written there.
			Files.createDirectories(fx.root.resolve("skins-data"));
			Files.writeString(fx.root.resolve("skins-data/players"), "not a folder");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		assertThat(fx.skins.grant(ida, "candy_sword_test") == OwnershipChange.SAVE_FAILED, "a grant that couldn't be saved didn't say so");
		assertThat(!fx.skins.ownedSkins(ida.id()).owns("candy_sword_test"), "a grant that couldn't be saved was made anyway");
		helper.succeed();
	}

	/**
	 * Applications are stamped with their world: the same world (moved along with the server, say)
	 * shows them Applied and their item live, and any other world, a fresh one included, "Applied in
	 * another world", with the item plain there, until the skin is applied again in that world.
	 */
	@GameTest
	public void anApplicationCountsOnlyInTheWorldThatStampedIt(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_sword_test", "sword");
		Path homeWorld = fx.root.resolve("home");
		Path elsewhereWorld = fx.root.resolve("elsewhere");
		Skins home = fx.restart(homeWorld);
		ServerPlayer jo = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_SWORD));
		home.grant(jo.nameAndId(), "candy_sword_test");
		assertThat(home.apply(jo, "candy_sword_test", ItemLocation.hotbar(0)).applied(), "the apply at home was refused");
		ItemStack sword = jo.getInventory().getItem(0);
		assertThat(Files.exists(homeWorld.resolve("metacraft-skins.json")), "the world that stamped an application has no marker");

		Skins elsewhere = fx.restart(elsewhereWorld);
		assertThat(only(elsewhere.ownedSkins(jo.getUUID())).state() == OwnedSkin.State.APPLIED_IN_ANOTHER_WORLD, "Applied in a fresh world");
		assertThat(elsewhere.liveSkin(sword).isEmpty(), "the item is live in another world");
		assertThat(elsewhere.remove(jo.nameAndId(), "candy_sword_test") == OwnershipChange.NOT_APPLIED, "removed in another world");
		assertThat(Files.exists(elsewhereWorld.resolve("metacraft-skins.json")), "the fresh world got no marker");

		Skins backHome = fx.restart(homeWorld);
		assertThat(only(backHome.ownedSkins(jo.getUUID())).state() == OwnedSkin.State.APPLIED, "not Applied back home");
		assertThat(backHome.liveSkin(sword).isPresent(), "the item isn't live back home");

		// Applied again in the other world, it's that world's application now, and home's no more.
		assertThat(fx.restart(elsewhereWorld).apply(jo, "candy_sword_test", ItemLocation.hotbar(0)).applied(), "the apply elsewhere was refused");
		assertThat(only(fx.restart(homeWorld).ownedSkins(jo.getUUID())).state() == OwnedSkin.State.APPLIED_IN_ANOTHER_WORLD,
				"still Applied at home after an apply elsewhere");
		helper.succeed();
	}

	private static OwnedSkin only(PlayerSkins skins) {
		assertThat(skins.damage().isEmpty(), "the file is damaged: " + skins.damage());
		assertThat(skins.skins().size() == 1, "owns " + skins.skins());
		return skins.skins().getFirst();
	}
}
