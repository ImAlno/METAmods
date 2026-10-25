package se.metacraft.skins.gametest;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.util.UndashedUuid;
import io.netty.buffer.Unpooled;
import se.metacraft.skins.ItemLocation;
import se.metacraft.skins.MetacraftSkins;
import se.metacraft.skins.OwnershipChange;
import se.metacraft.skins.SkinMarker;
import se.metacraft.skins.SkinToken;
import se.metacraft.skins.Skins;
import se.metacraft.skins.SkinsPack;
import se.metacraft.skins.compat.resource_packs.ResourcePacksCompat;
import se.metacraft.skins.pack.PackDelivery;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static se.metacraft.skins.gametest.SkinFixtures.assertThat;

/**
 * Wiring only, against the server's own Skins pointed at a temporary root for the test:
 * {@code /skins list} and {@code /skins reload} run through the server's command dispatcher and
 * are refused below their default permission levels, and the pack reload built downloads from
 * metacraft-resource-packs' pack server at the address players are pushed; {@code /skins grant},
 * {@code revoke} and {@code info} work on online and offline players, tell an online one, and
 * tab-complete skin ids; {@code /skins} opens the Skin Menu; a Skinned Item, a vanilla diamond
 * sword, reaches every viewer through Polymer's per-viewer pipeline wearing its Look; and
 * {@code /skins give} hands out Skin Tokens, which a real right-click claims; and the server keeps
 * a worn Perk up on its own, ending it soon after the item comes off; and a real melee hit, kill,
 * block break and hurt fire their event Perks. One test, because it swaps
 * the server's Skins. The rules behind it are SkinDefinitionTests', SkinsPackTests',
 * OwnershipTests', ApplyTests', ViewTests', TokenTests' and PerkTests'.
 */
public final class SkinsCommandTests {

	// The downloads take real time, but a lagging test server runs ticks back to back to catch
	// up, so 200 ticks can pass before the first HTTP request is answered.
	@GameTest(maxTicks = 2000)
	public void commandsAndThePackServerAreWired(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		SkinFixtures fx = SkinFixtures.create().lookOnly("candy_cane_test", "sword").lookOnly("hat", "hat");
		Skins previous = MetacraftSkins.use(fx.skins);
		List<CompletableFuture<HttpResponse<byte[]>>> downloads = new ArrayList<>();
		SkinsPack pack;
		StaleItems stale;
		WornPerk worn;
		try {
			List<String> reload = run(server, LevelBasedPermissionSet.ADMIN, "skins reload");
			assertThat(reload.stream().anyMatch(l -> l.contains("1 skin loaded")), "reload did not report the loaded skin: " + reload);
			assertThat(reload.stream().anyMatch(l -> l.contains("hat") && l.contains("unknown kind")), "reload did not report the rejection: " + reload);
			pack = fx.skins.pack().orElseThrow(() -> SkinFixtures.fail("reload built no pack"));

			List<String> list = run(server, LevelBasedPermissionSet.GAMEMASTER, "skins list");
			assertThat(list.stream().anyMatch(l -> l.contains("candy_cane_test") && l.contains("sword")), "list lacks candy_cane_test: " + list);
			assertThat(list.stream().anyMatch(l -> l.contains("hat") && l.contains("Dormant") && l.contains("unknown kind")), "list lacks the Dormant hat: " + list);
			ownershipCommands(helper, fx);
			skinMenuAndSkinnedItemsReachPlayers(helper, fx);
			tokensAndClaims(helper, fx);

			// Below the defaults: a gamemaster can't reload, a moderator can't list.
			fx.lookOnly("poop_hat_test", "helmet");
			run(server, LevelBasedPermissionSet.GAMEMASTER, "skins reload");
			assertThat(fx.skins.allSkins().size() == 2, "a gamemaster reloaded the skins");
			List<String> refused = run(server, LevelBasedPermissionSet.MODERATOR, "skins list");
			assertThat(refused.stream().noneMatch(l -> l.contains("candy_cane_test")), "a moderator got the list: " + refused);
			stale = new StaleItems(helper, fx);
			worn = new WornPerk(helper, fx);
			realEventsFireTheirPerks(helper, fx);

			assertThat(PackDelivery.SEPARATE_PACK, "metacraft-resource-packs is not in the test runtime");
			String url = ResourcePacksCompat.push(pack).url();
			downloads.add(get(url));
			downloads.add(get(url.substring(0, url.lastIndexOf('/')) + "/" + "0".repeat(40) + ".zip"));
		} catch (RuntimeException | Error e) {
			MetacraftSkins.use(previous);
			throw e;
		}
		SkinsPack built = pack;
		StaleItems removed = stale;
		WornPerk boots = worn;
		helper.startSequence()
				.thenWaitUntil(removed::revertedOnTheirOwn)
				.thenExecute(removed::openTheChestAndDropOne)
				.thenWaitUntil(removed::revertedInTheChest)
				.thenExecute(removed::leave)
				.thenWaitUntil(boots::fast)
				.thenExecute(boots::takeOff)
				.thenExecuteAfter(20, boots::slowAgain)
				.thenWaitUntil(() -> assertThat(downloads.stream().allMatch(CompletableFuture::isDone), "still downloading"))
				.thenExecute(() -> {
					MetacraftSkins.use(previous);
					HttpResponse<byte[]> response = downloads.getFirst().join();
					assertThat(response.statusCode() == 200, "the pack server answered " + response.statusCode());
					assertThat(Arrays.equals(response.body(), built.zip()), "the download is not the pack that was built");
					assertThat(downloads.getLast().join().statusCode() != 200, "the pack server served a pack that was never built");
				})
				.thenSucceed();
	}

	/**
	 * Stale items revert with nobody asking: after a remove, the server's next ticks clear the
	 * marker on an online player's sword, a dropped copy and an ender chest copy; a chest copy goes
	 * when a player opens the chest; a stale copy dropped later goes as it enters the world.
	 */
	private static final class StaleItems {
		private final GameTestHelper helper;
		private final SkinFixtures.Listening player;
		private final ItemEntity dropped;
		private final ItemStack spare;
		private final ChestBlockEntity chest;

		StaleItems(GameTestHelper helper, SkinFixtures fx) {
			this.helper = helper;
			player = SkinFixtures.join(helper);
			player.getInventory().clearContent();
			player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			fx.skins.grant(player.nameAndId(), "candy_cane_test");
			assertThat(fx.skins.apply(player, "candy_cane_test", ItemLocation.hotbar(0)).applied(), "the sword wasn't skinned");
			ItemStack sword = player.getInventory().getItem(0);
			player.getEnderChestInventory().setItem(0, sword.copy());
			spare = sword.copy();
			Vec3 at = helper.absoluteVec(new Vec3(2.5, 2, 2.5));
			dropped = new ItemEntity(helper.getLevel(), at.x, at.y, at.z, sword.copy());
			dropped.setNeverPickUp();
			helper.getLevel().addFreshEntity(dropped);
			BlockPos pos = new BlockPos(4, 1, 4);
			helper.setBlock(pos, Blocks.CHEST);
			chest = helper.getBlockEntity(pos, ChestBlockEntity.class);
			assertThat(fx.skins.remove(player.nameAndId(), "candy_cane_test") == OwnershipChange.DONE, "the remove was refused");
			assertThat(player.getInventory().getItem(0).has(SkinMarker.TYPE), "the marker went before the server touched the sword");
		}

		void revertedOnTheirOwn() {
			assertThat(!player.getInventory().getItem(0).has(SkinMarker.TYPE), "the online player's stale sword wasn't reverted");
			assertThat(!player.getEnderChestInventory().getItem(0).has(SkinMarker.TYPE), "the ender chest's stale copy wasn't reverted");
			assertThat(!dropped.getItem().has(SkinMarker.TYPE), "the dropped stale copy wasn't reverted");
		}

		void openTheChestAndDropOne() {
			chest.setItem(0, spare.copy());
			player.openMenu(chest);
			Vec3 at = helper.absoluteVec(new Vec3(1.5, 2, 1.5));
			ItemEntity late = new ItemEntity(helper.getLevel(), at.x, at.y, at.z, spare.copy());
			helper.getLevel().addFreshEntity(late);
			assertThat(!late.getItem().has(SkinMarker.TYPE), "a stale item entering the world kept its marker");
		}

		void revertedInTheChest() {
			assertThat(!chest.getItem(0).has(SkinMarker.TYPE), "opening the chest didn't revert its stale copy");
		}

		void leave() {
			player.closeContainer();
			helper.getLevel().getServer().getPlayerList().remove(player);
			dropped.discard();
		}
	}

	/**
	 * The server keeps a worn Perk up by itself: skinned boots on a player give Speed, with no
	 * swirls, and taking them off ends it within a second.
	 */
	private static final class WornPerk {
		private final GameTestHelper helper;
		private final ServerPlayer player;

		WornPerk(GameTestHelper helper, SkinFixtures fx) {
			this.helper = helper;
			fx.perky("smoke_boots", "boots", "[ { trigger: \"worn\", effects: [ { type: \"potion\", effect: \"minecraft:speed\" } ] } ]");
			fx.skins.reload();
			player = helper.makeMockServerPlayerInLevel();
			player.getInventory().clearContent();
			player.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
			fx.skins.grant(player.nameAndId(), "smoke_boots");
			assertThat(fx.skins.apply(player, "smoke_boots", ItemLocation.worn(EquipmentSlot.FEET)).applied(), "the boots weren't skinned");
		}

		void fast() {
			var speed = player.getEffect(MobEffects.SPEED);
			assertThat(speed != null && speed.getAmplifier() == 0 && !speed.isVisible(), "the worn boots give " + speed);
		}

		void takeOff() {
			player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
		}

		void slowAgain() {
			helper.getLevel().getServer().getPlayerList().remove(player);
			assertThat(!player.hasEffect(MobEffects.SPEED), "a second after taking the boots off: " + player.getEffect(MobEffects.SPEED));
		}
	}

	/**
	 * Event Perks fire from the game's own events: a real swing at a pig, a real killing blow, a
	 * real block break and real damage to a wearer each reach the server's Skins.
	 */
	private static void realEventsFireTheirPerks(GameTestHelper helper, SkinFixtures fx) {
		fx.perky("smoke_blade", "sword", """
				[ { trigger: "hit", effects: [ { type: "potion", target: "other", effect: "minecraft:slowness", duration: 3 } ] },
				{ trigger: "kill", effects: [ { type: "heal", amount: 4 } ] } ]""")
				.perky("smoke_pick", "pickaxe", "[ { trigger: \"block_break\", effects: [ { type: \"potion\", effect: \"minecraft:haste\", duration: 4 } ] } ]")
				.perky("smoke_cap", "helmet", "[ { trigger: \"hurt\", effects: [ { type: \"fire\", duration: 2 } ] } ]");
		fx.skins.reload();
		SkinFixtures.Listening player = SkinFixtures.join(helper);
		try {
			player.arrive(helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
			player.setGameMode(GameType.SURVIVAL);
			player.getInventory().clearContent();
			player.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD));
			player.getInventory().setItem(1, new ItemStack(Items.IRON_PICKAXE));
			player.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
			for (String skin : List.of("smoke_blade", "smoke_pick", "smoke_cap")) fx.skins.grant(player.nameAndId(), skin);
			fx.skins.apply(player, "smoke_blade", ItemLocation.hotbar(0));
			fx.skins.apply(player, "smoke_pick", ItemLocation.hotbar(1));
			fx.skins.apply(player, "smoke_cap", ItemLocation.worn(EquipmentSlot.HEAD));

			Pig pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(3, 1, 1));
			player.attack(pig);
			assertThat(pig.hasEffect(MobEffects.SLOWNESS), "a real hit with the skinned sword didn't fire its hit Perk");
			Pig doomed = helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(1, 1, 3));
			// A player who never ticks swings uncharged and without the sword's attack damage (equipment modifiers apply on tick): 0.2 damage.
			doomed.setHealth(0.1f);
			player.setHealth(10);
			player.attack(doomed);
			assertThat(doomed.isDeadOrDying() && player.getHealth() == 14, "a real killing blow healed the player to " + player.getHealth() + ", not 14 (the pig: " + doomed.getHealth() + " health, dead " + doomed.isDeadOrDying() + ")");

			player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(pig), 2);
			assertThat(pig.getRemainingFireTicks() > 0, "real damage from a pig didn't set it alight through the worn cap");

			BlockPos stone = new BlockPos(4, 1, 4);
			helper.setBlock(stone, Blocks.STONE);
			player.getInventory().setSelectedSlot(1);
			assertThat(player.gameMode.destroyBlock(helper.absolutePos(stone)), "the player couldn't break the stone");
			assertThat(player.hasEffect(MobEffects.HASTE), "a real block break with the skinned pick didn't fire its Perk");
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
	}

	/** grant, revoke and info through the dispatcher, for an offline player known to the user cache and an online one. */
	private static void ownershipCommands(GameTestHelper helper, SkinFixtures fx) {
		MinecraftServer server = helper.getLevel().getServer();
		String offline = "skins" + (int) (Math.random() * 1_000_000);
		UUID offlineId = UUID.randomUUID();
		server.services().nameToIdCache().add(new NameAndId(offlineId, offline));

		List<String> granted = run(server, LevelBasedPermissionSet.ADMIN, "skins grant " + offline + " candy_cane_test");
		assertThat(fx.skins.ownedSkins(offlineId).owns("candy_cane_test"), "grant didn't grant an offline player: " + granted);
		List<String> info = run(server, LevelBasedPermissionSet.GAMEMASTER, "skins info " + offline);
		assertThat(info.stream().anyMatch(l -> l.contains("candy_cane_test") && l.contains("Unapplied") && l.contains(LocalDate.now(ZoneOffset.UTC).toString())),
				"info lacks the skin, its state or its claim date: " + info);
		List<String> refused = run(server, LevelBasedPermissionSet.ADMIN, "skins grant " + offline + " hat");
		assertThat(refused.stream().anyMatch(l -> l.contains("Dormant")) && !fx.skins.ownedSkins(offlineId).owns("hat"), "granted a Dormant skin: " + refused);
		run(server, LevelBasedPermissionSet.GAMEMASTER, "skins revoke " + offline + " candy_cane_test");
		assertThat(fx.skins.ownedSkins(offlineId).owns("candy_cane_test"), "a gamemaster revoked a skin");
		run(server, LevelBasedPermissionSet.ADMIN, "skins revoke " + offline + " candy_cane_test");
		assertThat(!fx.skins.ownedSkins(offlineId).owns("candy_cane_test"), "revoke didn't revoke from an offline player");

		SkinFixtures.Listening online = SkinFixtures.join(helper);
		try {
			String name = online.getGameProfile().name();
			run(server, LevelBasedPermissionSet.ADMIN, "skins grant " + name + " candy_cane_test");
			assertThat(fx.skins.ownedSkins(online.getUUID()).owns("candy_cane_test"), "grant didn't grant an online player");
			assertThat(online.heard.stream().anyMatch(l -> l.contains("You now own candy_cane_test skin")), "the online player wasn't told of the grant: " + online.heard);
			run(server, LevelBasedPermissionSet.ADMIN, "skins revoke " + name + " candy_cane_test");
			assertThat(!fx.skins.ownedSkins(online.getUUID()).owns("candy_cane_test"), "revoke didn't revoke from an online player");
			assertThat(online.heard.stream().anyMatch(l -> l.contains("candy_cane_test skin was removed from your skins by an admin")),
					"the online player wasn't told of the revoke: " + online.heard);
		} finally {
			server.getPlayerList().remove(online);
		}

		for (String command : List.of("skins grant " + offline + " ", "skins revoke " + offline + " c")) {
			List<String> suggested = suggestions(server, command);
			assertThat(suggested.contains("candy_cane_test"), "\"" + command + "\" suggests " + suggested);
		}
	}

	/**
	 * {@code /skins} opens "Your Skins". And the first build question of ticket 16: does Polymer's
	 * per-viewer client stack reach a vanilla item that carries our hidden marker? The skinned
	 * sword is encoded with the item codec as a packet to each viewer is (with that viewer's packet
	 * context) and decoded as a vanilla client would: it arrives as a diamond sword wearing the
	 * Look and tooltip block, without the marker component.
	 */
	private static void skinMenuAndSkinnedItemsReachPlayers(GameTestHelper helper, SkinFixtures fx) {
		MinecraftServer server = helper.getLevel().getServer();
		SkinFixtures.Listening owner = SkinFixtures.join(helper);
		SkinFixtures.Listening viewer = SkinFixtures.join(helper);
		try {
			owner.getInventory().clearContent();
			owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
			fx.skins.grant(owner.nameAndId(), "candy_cane_test");

			server.getCommands().performPrefixedCommand(owner.createCommandSourceStack(), "skins");
			assertThat(SkinMenuTests.title(owner).equals("Your Skins"), "/skins opened " + SkinMenuTests.title(owner));
			SkinMenuTests.click(owner, 0);
			SkinMenuTests.click(owner, SkinMenuTests.FIRST_ITEM);
			assertThat(fx.skins.liveSkin(owner.getInventory().getItem(0)).isPresent(), "applying from /skins didn't skin the sword");

			ItemStack sword = owner.getInventory().getItem(0);
			for (ServerPlayer to : List.of(owner, viewer)) {
				ItemStack received = sentTo(to, sword, server);
				assertThat(received.is(Items.DIAMOND_SWORD), "the sword reached " + to.getGameProfile().name() + " as " + received);
				assertThat(Identifier.parse("skins:candy_cane_test").equals(received.get(DataComponents.ITEM_MODEL)), "the sword reached a viewer with the Look "
						+ received.get(DataComponents.ITEM_MODEL));
				List<String> lore = received.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().map(Component::getString).toList();
				assertThat(lore.contains("Skin: candy_cane_test skin") && lore.getLast().equals("Owned by " + owner.getGameProfile().name()),
						"the sword reached a viewer with the lore " + lore);
				assertThat(!received.has(SkinMarker.TYPE), "the hidden marker reached a client as a component");
			}
		} finally {
			server.getPlayerList().remove(owner);
			server.getPlayerList().remove(viewer);
		}
	}

	/**
	 * {@code /skins give} through the dispatcher; a token reaching its holder as paper with their
	 * own text; and real right-clicks, as packets through the player's connection: the main hand on
	 * air opens the claim menu once however many packets the click arrives as; the offhand opens
	 * nothing; a chest or an item frame clicked with a token opens the claim menu instead of doing
	 * what it would, with a swing and a page turn. A grant re-sends the player's inventory.
	 */
	private static void tokensAndClaims(GameTestHelper helper, SkinFixtures fx) {
		MinecraftServer server = helper.getLevel().getServer();
		SkinFixtures.Listening air = SkinFixtures.join(helper);
		SkinFixtures.Listening offhand = SkinFixtures.join(helper);
		SkinFixtures.Listening blocks = SkinFixtures.join(helper);
		SkinFixtures.Listening entities = SkinFixtures.join(helper);
		try {
			for (SkinFixtures.Listening player : List.of(air, offhand, blocks, entities)) {
				player.getInventory().clearContent();
				player.setGameMode(GameType.SURVIVAL);
				player.arrive(helper.absoluteVec(new Vec3(2.5, 1, 2.5)));
			}
			String name = air.getGameProfile().name();
			List<String> gave = run(server, LevelBasedPermissionSet.GAMEMASTER, "skins give " + name + " candy_cane_test 3");
			assertThat(SkinToken.skinOf(air.getMainHandItem()).equals(Optional.of("candy_cane_test")) && air.getMainHandItem().getCount() == 3,
					"give gave " + air.getMainHandItem() + ": " + gave);
			assertThat(gave.contains("Gave 3 Skin Tokens: candy_cane_test skin to " + name + "."), "give confirmed " + gave);
			List<String> refused = run(server, LevelBasedPermissionSet.GAMEMASTER, "skins give " + name + " hat");
			assertThat(refused.stream().anyMatch(l -> l.contains("Dormant")) && air.getInventory().getItem(1).isEmpty(), "gave a Dormant skin's token: " + refused);
			run(server, LevelBasedPermissionSet.MODERATOR, "skins give " + name + " candy_cane_test");
			assertThat(air.getMainHandItem().getCount() == 3, "a moderator gave tokens");

			ItemStack paper = sentTo(air, air.getMainHandItem(), server);
			assertThat(paper.is(Items.PAPER) && paper.getHoverName().getString().equals("Skin Token: candy_cane_test skin"), "the token reached its holder as " + paper
					+ " named " + paper.getHoverName().getString());

			// Air: one click arrives as a main hand use and then an offhand one, and a held button repeats.
			air.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.ENDER_PEARL));
			air.sent(Object.class);
			air.tickEnd();
			air.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 1, 0, 0));
			assertThat(SkinMenuTests.title(air).equals("Claim candy_cane_test skin?"), "a right-click on air opened " + SkinMenuTests.title(air));
			var menu = air.containerMenu;
			air.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.OFF_HAND, 2, 0, 0));
			air.tickEnd();
			air.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.OFF_HAND, 3, 0, 0));
			air.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 4, 0, 0));
			assertThat(air.containerMenu == menu && air.sent(ClientboundOpenScreenPacket.class).size() == 1, "one click didn't open the claim menu exactly once");
			assertThat(air.getOffhandItem().is(Items.ENDER_PEARL), "the offhand pearl was thrown with the token in the main hand");
			air.connection.handleContainerClose(new ServerboundContainerClosePacket(menu.containerId));
			air.tickEnd();
			air.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, 5, 0, 0));
			assertThat(air.containerMenu == air.inventoryMenu, "a held button reopened the claim menu at once");

			offhand.setItemSlot(EquipmentSlot.OFFHAND, SkinToken.of("candy_cane_test", 1));
			offhand.tickEnd();
			offhand.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.OFF_HAND, 1, 0, 0));
			assertThat(offhand.containerMenu == offhand.inventoryMenu, "a token in the offhand opened " + SkinMenuTests.title(offhand));

			// A chest clicked with a token: the claim menu, not the chest, with a swing and a page turn.
			BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 3));
			helper.getLevel().setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
			blocks.getInventory().setItem(0, SkinToken.of("candy_cane_test", 1));
			blocks.sent(Object.class);
			blocks.tickEnd();
			blocks.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,
					new BlockHitResult(Vec3.atCenterOf(chest).add(0, 0.5, 0), Direction.UP, chest, false), 1));
			assertThat(SkinMenuTests.title(blocks).equals("Claim candy_cane_test skin?"), "a chest clicked with a token opened " + blocks.containerMenu);
			List<Object> sent = blocks.sent(Object.class);
			assertThat(sent.stream().anyMatch(p -> p instanceof ClientboundSwingAnimationPacket a && a.entityId() == blocks.getId() && a.hand() == InteractionHand.MAIN_HAND), "the arm didn't swing: " + sent);
			assertThat(sent.stream().anyMatch(p -> p instanceof ClientboundSoundPacket s && s.getSound().value().equals(SoundEvents.BOOK_PAGE_TURN)),
					"no page turned: " + sent);
			blocks.connection.handleContainerClose(new ServerboundContainerClosePacket(blocks.containerMenu.containerId));

			// An item frame clicked with a token: the claim menu, and the frame stays empty.
			BlockPos wall = helper.absolutePos(new BlockPos(1, 1, 1));
			helper.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
			ItemFrame frame = new ItemFrame(helper.getLevel(), wall.above(), Direction.UP);
			helper.getLevel().addFreshEntity(frame);
			entities.getInventory().setItem(0, SkinToken.of("candy_cane_test", 1));
			entities.tickEnd();
			entities.connection.handleInteract(new ServerboundInteractPacket(frame.getId(), InteractionHand.MAIN_HAND, Vec3.ZERO, false));
			assertThat(SkinMenuTests.title(entities).equals("Claim candy_cane_test skin?"), "an item frame clicked with a token opened " + entities.containerMenu);
			assertThat(frame.getItem().isEmpty() && entities.getMainHandItem().getCount() == 1, "the token went into the item frame");
			frame.discard();

			// A grant re-sends the inventory, so tokens for the skin say it's owned.
			blocks.sent(Object.class);
			run(server, LevelBasedPermissionSet.ADMIN, "skins grant " + blocks.getGameProfile().name() + " candy_cane_test");
			assertThat(blocks.sent(ClientboundContainerSetContentPacket.class).stream().anyMatch(p -> p.containerId() == 0), "a grant didn't re-send the inventory");
		} finally {
			for (ServerPlayer player : List.of(air, offhand, blocks, entities)) server.getPlayerList().remove(player);
		}
	}

	/** The stack as a vanilla client decodes it from a packet sent to {@code viewer}. */
	private static ItemStack sentTo(ServerPlayer viewer, ItemStack stack, MinecraftServer server) {
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
		PacketContext.runWithContext(viewer.connection, () -> ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack));
		return ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
	}

	private static List<String> suggestions(MinecraftServer server, String command) {
		CommandSourceStack source = server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.ADMIN);
		var dispatcher = server.getCommands().getDispatcher();
		return dispatcher.getCompletionSuggestions(dispatcher.parse(command, source)).join().getList().stream().map(Suggestion::getText).toList();
	}

	/** Runs a command as the console with these permissions; returns every line it answered with. */
	private static List<String> run(MinecraftServer server, LevelBasedPermissionSet permissions, String command) {
		List<String> lines = new ArrayList<>();
		CommandSource capture = new CommandSource() {
			@Override
			public void sendSystemMessage(Component message) {
				lines.add(message.getString());
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		CommandSourceStack source = server.createCommandSourceStack().withSource(capture).withPermission(permissions);
		server.getCommands().performPrefixedCommand(source, command);
		return lines;
	}

	/** A GET the way a client sends it; the pack server refuses anything else. */
	private static CompletableFuture<HttpResponse<byte[]>> get(String url) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("User-Agent", "Minecraft Java/26.3")
				.header("X-Minecraft-Username", "Tester")
				.header("X-Minecraft-UUID", UndashedUuid.toString(UUID.randomUUID()))
				.build();
		return HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
	}
}
