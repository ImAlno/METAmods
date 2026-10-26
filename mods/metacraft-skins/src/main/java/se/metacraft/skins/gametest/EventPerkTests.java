package se.metacraft.skins.gametest;

import se.metacraft.skins.ItemLocation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static se.metacraft.skins.gametest.PerkTests.assertEffect;
import static se.metacraft.skins.gametest.SkinFixtures.assertThat;

/**
 * Event Perks through the Skins module: what each Trigger ({@code hit}, {@code kill},
 * {@code block_break}, {@code hurt}) fires, on whom, how often (chance, cooldown), and when
 * never. The tests report the events themselves, with their own Skins; a real melee hit and a
 * real block break reaching the server's Skins are SkinsCommandTests'.
 */
public final class EventPerkTests {

	/** A hit fires the main-hand item's hit Perks: a potion on the victim, a potion and healing on the holder. Not from the offhand. */
	@GameTest
	public void aHitFiresTheMainHandItemsHitPerks(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("frost_blade", "sword", """
				[ { trigger: "hit", effects: [
				{ type: "potion", target: "other", effect: "minecraft:slowness", level: 2, duration: 3 },
				{ type: "potion", effect: "minecraft:speed", duration: 5 },
				{ type: "heal", amount: 4 } ] } ]""");
		fx.reload();
		ServerPlayer player = survivor(helper, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.IRON_SWORD));
		fx.skins.grant(player.nameAndId(), "frost_blade");
		fx.skins.apply(player, "frost_blade", ItemLocation.hotbar(1));
		LivingEntity pig = victim(helper);
		player.setHealth(10);

		fx.skins.hit(player, pig);
		assertThat(!pig.hasEffect(MobEffects.SLOWNESS) && !player.hasEffect(MobEffects.SPEED) && player.getHealth() == 10,
				"a hit with a plain sword fired the skinned one's Perk");
		player.setItemSlot(EquipmentSlot.OFFHAND, player.getInventory().removeItemNoUpdate(1));
		fx.skins.hit(player, pig);
		assertThat(!pig.hasEffect(MobEffects.SLOWNESS) && player.getHealth() == 10, "the skinned sword in the offhand fired its hit Perk");

		player.getInventory().setItem(0, player.getItemBySlot(EquipmentSlot.OFFHAND));
		player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		fx.skins.hit(player, pig);
		assertEffect(pig, MobEffects.SLOWNESS, 1, 60, "the victim");
		assertEffect(player, MobEffects.SPEED, 0, 100, "the holder");
		assertThat(player.getHealth() == 14, "the hit healed the holder to " + player.getHealth() + ", not 14");
		helper.succeed();
	}

	/** On hit, fire and knockback land on the victim, and particles and a sound play at it, for whoever is near. */
	@GameTest
	public void aHitSetsTheVictimAlightKnocksItBackAndPlaysAtIt(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("ember_blade", "sword", """
				[ { trigger: "hit", effects: [ { type: "fire", duration: 4 }, { type: "knockback", strength: 1, lift: 0.5 },
				{ type: "particle", target: "other", particle: { type: "minecraft:flame" }, count: 5 },
				{ type: "sound", target: "other", sound: "minecraft:block.anvil.land", pitch: 1.5 } ] } ]""");
		fx.reload();
		LivingEntity pig = victim(helper);
		SkinFixtures.Listening player = listening(helper, fx, "ember_blade", new ItemStack(Items.IRON_SWORD));
		List<Object> sent;
		try {
			player.sent(Object.class);
			fx.skins.hit(player, pig);
			sent = player.sent(Object.class);
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
		assertThat(pig.getRemainingFireTicks() == 80, "the victim burns for " + pig.getRemainingFireTicks() + " ticks, not 80");
		Vec3 push = pig.getDeltaMovement();
		assertThat(push.x > 0.5 && Math.abs(push.z) < 0.1 && push.y > 0.3, "the victim, east of the holder, was pushed " + push);
		assertPlayedAt(sent, pig.getX(), pig.getZ(), ParticleTypes.FLAME, 5, "block.anvil.land");
		helper.succeed();
	}

	/** A kill fires the weapon's kill Perks: the holder heals and gets their potion, and particles and a sound play at the victim, a player here. */
	@GameTest
	public void aKillFiresTheWeaponsKillPerksAndPlayerVictimsCount(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("reaper_axe", "axe", """
				[ { trigger: "kill", effects: [ { type: "heal", amount: 6 }, { type: "potion", effect: "minecraft:strength", level: 2, duration: 10 },
				{ type: "particle", target: "other", particle: { type: "minecraft:soul" }, count: 7 },
				{ type: "sound", target: "other", sound: "minecraft:entity.wither.spawn" } ] } ]""");
		fx.reload();
		ServerPlayer victim = helper.makeMockServerPlayerInLevel();
		victim.setPos(helper.absoluteVec(new Vec3(3.5, 1, 1.5)));
		SkinFixtures.Listening player = listening(helper, fx, "reaper_axe", new ItemStack(Items.IRON_AXE));
		List<Object> sent;
		try {
			player.setHealth(4);
			player.sent(Object.class);
			fx.skins.hit(player, victim);
			assertThat(player.getHealth() == 4 && !player.hasEffect(MobEffects.STRENGTH), "a kill Perk fired on a hit");
			fx.skins.killed(player, victim);
			sent = player.sent(Object.class);
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
		assertThat(player.getHealth() == 10, "the kill healed the holder to " + player.getHealth() + ", not 10");
		assertEffect(player, MobEffects.STRENGTH, 1, 200, "the holder");
		assertPlayedAt(sent, victim.getX(), victim.getZ(), ParticleTypes.SOUL, 7, "entity.wither.spawn");
		helper.succeed();
	}

	/** A block break fires the tool's Perks: on the holder, and particles and a sound at the block's centre. */
	@GameTest
	public void aBlockBreakFiresTheToolsPerksAtTheBlock(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("lucky_pick", "pickaxe", """
				[ { trigger: "block_break", effects: [ { type: "potion", effect: "minecraft:haste", level: 3, duration: 4 },
				{ type: "particle", target: "other", particle: { type: "minecraft:happy_villager" }, count: 12 },
				{ type: "sound", target: "other", sound: "minecraft:block.amethyst_block.chime" } ] } ]""");
		fx.reload();
		BlockPos block = helper.absolutePos(new BlockPos(4, 1, 4));
		SkinFixtures.Listening player = listening(helper, fx, "lucky_pick", new ItemStack(Items.IRON_PICKAXE));
		List<Object> sent;
		try {
			player.sent(Object.class);
			fx.skins.brokeBlock(player, block);
			sent = player.sent(Object.class);
		} finally {
			helper.getLevel().getServer().getPlayerList().remove(player);
		}
		assertEffect(player, MobEffects.HASTE, 2, 80, "the holder");
		assertPlayedAt(sent, block.getX() + 0.5, block.getZ() + 0.5, ParticleTypes.HAPPY_VILLAGER, 12, "block.amethyst_block.chime");
		helper.succeed();
	}

	/**
	 * Hurt fires the worn item's hurt Perks: fire and a potion on the attacker, a potion and healing
	 * on the wearer. With no attacker (a fall, say), only the wearer's own effects fire. A helmet in the hand fires nothing.
	 */
	@GameTest
	public void hurtTargetsTheAttackerAndWithoutOneOnlyTheWearer(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("thorn_crown", "helmet", """
				[ { trigger: "hurt", effects: [ { type: "fire", duration: 3 }, { type: "potion", target: "other", effect: "minecraft:weakness", duration: 4 },
				{ type: "potion", effect: "minecraft:resistance", duration: 5 }, { type: "heal", amount: 2 } ] } ]""");
		fx.reload();
		ServerPlayer player = survivor(helper, new ItemStack(Items.IRON_HELMET));
		fx.skins.grant(player.nameAndId(), "thorn_crown");
		fx.skins.apply(player, "thorn_crown", ItemLocation.hotbar(0));
		LivingEntity pig = victim(helper);
		player.setHealth(10);

		fx.skins.hurt(player, pig);
		assertThat(!player.hasEffect(MobEffects.RESISTANCE) && pig.getRemainingFireTicks() <= 0, "a helmet in the hand fired its hurt Perk");
		player.setItemSlot(EquipmentSlot.HEAD, player.getInventory().removeItemNoUpdate(0));
		fx.skins.hurt(player, pig);
		assertThat(pig.getRemainingFireTicks() == 60, "the attacker burns for " + pig.getRemainingFireTicks() + " ticks, not 60");
		assertEffect(pig, MobEffects.WEAKNESS, 0, 80, "the attacker");
		assertEffect(player, MobEffects.RESISTANCE, 0, 100, "the wearer");
		assertThat(player.getHealth() == 12, "the hurt Perk healed the wearer to " + player.getHealth() + ", not 12");

		player.removeEffect(MobEffects.RESISTANCE);
		fx.skins.hurt(player, null);
		assertEffect(player, MobEffects.RESISTANCE, 0, 100, "the wearer, hurt by nothing");
		assertThat(player.getHealth() == 14, "with no attacker the wearer was healed to " + player.getHealth() + ", not 14");
		helper.succeed();
	}

	/** {@code chance} is rolled for every event: a 50% Perk fires on about half of 400 hits, a 0% one never. */
	@GameTest
	public void chanceIsRolledForEachEvent(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("coin_blade", "sword", """
				[ { trigger: "hit", chance: 0.5, effects: [ { type: "heal", amount: 1 } ] },
				{ trigger: "hit", chance: 0, effects: [ { type: "potion", effect: "minecraft:speed", duration: 1 } ] } ]""");
		fx.reload();
		ServerPlayer player = holding(helper, fx, "coin_blade", new ItemStack(Items.IRON_SWORD));
		LivingEntity pig = victim(helper);
		int fired = 0;
		for (int i = 0; i < 400; i++) {
			player.setHealth(10);
			fx.skins.hit(player, pig);
			if (player.getHealth() > 10) fired++;
		}
		// 200 expected, with a standard deviation of 10: this range fails once in about a billion runs.
		assertThat(fired >= 140 && fired <= 260, "a 50% Perk fired on " + fired + " of 400 hits");
		assertThat(!player.hasEffect(MobEffects.SPEED), "a 0% Perk fired");
		helper.succeed();
	}

	/**
	 * {@code cooldown} is the player's: after it fires, the Owner waits out the cooldown even after
	 * handing the sword over and taking it back. In the other player's hand it fires nothing at all.
	 */
	@GameTest(maxTicks = 100)
	public void aCooldownIsPerPlayerAndFollowsTheHolder(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("slow_blade", "sword", """
				[ { trigger: "hit", cooldown: 1, effects: [ { type: "heal", amount: 1 } ] } ]""");
		fx.reload();
		ServerPlayer owner = holding(helper, fx, "slow_blade", new ItemStack(Items.IRON_SWORD));
		ServerPlayer other = survivor(helper);
		LivingEntity pig = victim(helper);
		assertThat(fires(fx, owner, pig), "the first hit didn't fire");
		assertThat(!fires(fx, owner, pig), "a hit during the cooldown fired");
		other.getInventory().setItem(0, owner.getInventory().removeItemNoUpdate(0));
		assertThat(!fires(fx, other, pig), "the sword's Perk fired for a player who isn't its Owner");
		owner.getInventory().setItem(0, other.getInventory().removeItemNoUpdate(0));
		assertThat(!fires(fx, owner, pig), "handing the sword over and back reset the Owner's cooldown");
		helper.runAfterDelay(21, () -> {
			assertThat(fires(fx, owner, pig), "the cooldown didn't end after a second");
			other.getInventory().setItem(0, owner.getInventory().removeItemNoUpdate(0));
			assertThat(!fires(fx, other, pig), "the sword's Perk fired for another player once the cooldown was over");
			helper.succeed();
		});
	}

	/** No event Perk fires for a player who isn't the item's Owner: hit, kill, block break and hurt; back with the Owner, all four do. */
	@GameTest
	public void eventPerksFireOnlyForTheOwner(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("busy_pick", "pickaxe", """
				[ { trigger: "hit", effects: [ { type: "heal", amount: 1 } ] }, { trigger: "kill", effects: [ { type: "heal", amount: 1 } ] },
				{ trigger: "block_break", effects: [ { type: "heal", amount: 1 } ] } ]""")
				.perky("busy_cap", "helmet", "[ { trigger: \"hurt\", effects: [ { type: \"heal\", amount: 1 } ] } ]");
		fx.reload();
		ServerPlayer owner = holding(helper, fx, "busy_pick", new ItemStack(Items.IRON_PICKAXE));
		wear(fx, owner, "busy_cap", new ItemStack(Items.IRON_HELMET));
		ServerPlayer other = survivor(helper);
		LivingEntity pig = victim(helper);
		for (ServerPlayer[] handing : List.of(new ServerPlayer[] {owner, other}, new ServerPlayer[] {other, owner})) {
			ServerPlayer from = handing[0], to = handing[1];
			to.getInventory().setItem(0, from.getInventory().removeItemNoUpdate(0));
			to.setItemSlot(EquipmentSlot.HEAD, from.getItemBySlot(EquipmentSlot.HEAD));
			from.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			to.setHealth(10);
			fx.skins.hit(to, pig);
			fx.skins.killed(to, pig);
			fx.skins.brokeBlock(to, helper.absolutePos(new BlockPos(4, 1, 4)));
			fx.skins.hurt(to, pig);
			float expected = to == owner ? 14 : 10;
			assertThat(to.getHealth() == expected, (to == owner ? "back with the Owner" : "with another player") + " the four events healed them to "
					+ to.getHealth() + ", not " + expected);
		}
		helper.succeed();
	}

	/** Did a hit fire the heal Perk? */
	private static boolean fires(SkinFixtures fx, ServerPlayer player, LivingEntity victim) {
		player.setHealth(10);
		fx.skins.hit(player, victim);
		return player.getHealth() > 10;
	}

	/** No event Perk fires for a player in creative or spectator; in survival and adventure, all four do. */
	@GameTest
	public void eventPerksNeverFireInCreativeOrSpectator(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("busy_pick", "pickaxe", """
				[ { trigger: "hit", effects: [ { type: "heal", amount: 1 } ] }, { trigger: "kill", effects: [ { type: "heal", amount: 1 } ] },
				{ trigger: "block_break", effects: [ { type: "heal", amount: 1 } ] } ]""")
				.perky("busy_cap", "helmet", "[ { trigger: \"hurt\", effects: [ { type: \"heal\", amount: 1 } ] } ]");
		fx.reload();
		ServerPlayer player = holding(helper, fx, "busy_pick", new ItemStack(Items.IRON_PICKAXE));
		wear(fx, player, "busy_cap", new ItemStack(Items.IRON_HELMET));
		LivingEntity pig = victim(helper);
		for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR, GameType.SURVIVAL, GameType.ADVENTURE)) {
			player.setGameMode(mode);
			player.setHealth(10);
			fx.skins.hit(player, pig);
			fx.skins.killed(player, pig);
			fx.skins.brokeBlock(player, helper.absolutePos(new BlockPos(4, 1, 4)));
			fx.skins.hurt(player, pig);
			float expected = mode.isSurvival() ? 14 : 10;
			assertThat(player.getHealth() == expected, "in " + mode.getName() + " the four events healed the player to " + player.getHealth() + ", not " + expected);
		}
		helper.succeed();
	}

	/**
	 * With {@code perks-affect-players} off, effects on the target skip players (a mob still gets
	 * them, and the holder's own effects still fire); with {@code perks-enabled} off, no event Perk fires.
	 */
	@GameTest
	public void theSettingsSwitchesGovernEventPerks(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().settings("{ \"perks-affect-players\": false }")
				.perky("frost_blade", "sword", """
						[ { trigger: "hit", effects: [ { type: "potion", target: "other", effect: "minecraft:slowness", duration: 3 },
						{ type: "fire", duration: 2 }, { type: "heal", amount: 2 } ] } ]""")
				.perky("thorn_crown", "helmet", "[ { trigger: \"hurt\", effects: [ { type: \"fire\", duration: 3 }, { type: \"heal\", amount: 2 } ] } ]");
		fx.reload();
		ServerPlayer player = holding(helper, fx, "frost_blade", new ItemStack(Items.IRON_SWORD));
		wear(fx, player, "thorn_crown", new ItemStack(Items.IRON_HELMET));
		ServerPlayer rival = survivor(helper);
		LivingEntity pig = victim(helper);

		player.setHealth(10);
		fx.skins.hit(player, rival);
		fx.skins.hurt(player, rival);
		assertThat(!rival.hasEffect(MobEffects.SLOWNESS) && rival.getRemainingFireTicks() <= 0, "with perks-affect-players off, the player target was affected");
		assertThat(player.getHealth() == 14, "with perks-affect-players off, the holder's own healing went too: " + player.getHealth());
		fx.skins.hit(player, pig);
		assertThat(pig.hasEffect(MobEffects.SLOWNESS) && pig.getRemainingFireTicks() > 0, "with perks-affect-players off, a mob was spared");

		fx.settings("{ \"perks-affect-players\": true }").reload();
		fx.skins.hit(player, rival);
		assertThat(rival.hasEffect(MobEffects.SLOWNESS), "with perks-affect-players on, the player target was spared");

		fx.settings("{ \"perks-enabled\": false }").reload();
		rival.removeAllEffects();
		player.setHealth(10);
		fx.skins.hit(player, rival);
		fx.skins.hurt(player, rival);
		assertThat(!rival.hasEffect(MobEffects.SLOWNESS) && player.getHealth() == 10, "with perks-enabled off, an event Perk fired");
		helper.succeed();
	}

	/** Puts an item skinned with {@code skin} on the player's head. */
	private static void wear(SkinFixtures fx, ServerPlayer player, String skin, ItemStack helmet) {
		player.setItemSlot(EquipmentSlot.HEAD, helmet);
		fx.skins.grant(player.nameAndId(), skin);
		assertThat(fx.skins.apply(player, skin, ItemLocation.worn(EquipmentSlot.HEAD)).applied(), "the " + skin + " helmet wasn't skinned");
	}

	/** A timed potion fired again resets to its full duration, at its own level: no added time, no added levels. */
	@GameTest(maxTicks = 100)
	public void aTimedPotionRefreshesToFullWhenItFiresAgain(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().perky("frost_blade", "sword", """
				[ { trigger: "hit", effects: [ { type: "potion", target: "other", effect: "minecraft:slowness", level: 2, duration: 3 } ] } ]""");
		fx.reload();
		ServerPlayer player = holding(helper, fx, "frost_blade", new ItemStack(Items.IRON_SWORD));
		LivingEntity pig = victim(helper);
		fx.skins.hit(player, pig);
		assertEffect(pig, MobEffects.SLOWNESS, 1, 60, "after the first hit");
		helper.runAfterDelay(10, () -> {
			int left = pig.getEffect(MobEffects.SLOWNESS).getDuration();
			assertThat(left < 60, "the victim's Slowness didn't run down: " + left);
			fx.skins.hit(player, pig);
			assertEffect(pig, MobEffects.SLOWNESS, 1, 60, "after a second hit with " + left + " ticks left");
			helper.succeed();
		});
	}

	/** Among the packets sent, one burst of {@code count} {@code particle}s and one {@code sound}, each at (x, z). */
	static void assertPlayedAt(List<Object> sent, double x, double z, ParticleType<?> particle, int count, String sound) {
		List<ClientboundLevelParticlesPacket> bursts = sent.stream().filter(ClientboundLevelParticlesPacket.class::isInstance)
				.map(ClientboundLevelParticlesPacket.class::cast).filter(p -> p.particle().getType() == particle).toList();
		assertThat(bursts.size() == 1 && bursts.getFirst().count() == count && Math.abs(bursts.getFirst().x() - x) < 0.01 && Math.abs(bursts.getFirst().z() - z) < 0.01,
				"expected one burst of " + count + " at x " + x + ", z " + z + ": " + bursts);
		List<ClientboundSoundPacket> sounds = sent.stream().filter(ClientboundSoundPacket.class::isInstance).map(ClientboundSoundPacket.class::cast)
				.filter(p -> p.getSound().is(Identifier.withDefaultNamespace(sound))).toList();
		// A sound packet's position is a float: at a game test's coordinates (millions of blocks out) it's only good to a block.
		assertThat(sounds.size() == 1 && Math.abs(sounds.getFirst().getX() - x) <= 1 && Math.abs(sounds.getFirst().getZ() - z) <= 1,
				"expected one " + sound + " at x " + x + ", z " + z + ": " + sounds.stream().map(s -> s.getX() + "," + s.getZ()).toList());
	}

	/** A real player in survival, holding (in the main hand) an item skinned with {@code skin}, standing inside the test; remove them afterwards. */
	static SkinFixtures.Listening listening(GameTestHelper helper, SkinFixtures fx, String skin, ItemStack item) {
		SkinFixtures.Listening player = SkinFixtures.join(helper);
		try {
			player.getInventory().clearContent();
			player.setGameMode(GameType.SURVIVAL);
			player.setPos(helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
			player.getInventory().setItem(0, item);
			fx.skins.grant(player.nameAndId(), skin);
			assertThat(fx.skins.apply(player, skin, ItemLocation.hotbar(0)).applied(), "the " + skin + " item wasn't skinned");
			return player;
		} catch (RuntimeException | Error e) {
			helper.getLevel().getServer().getPlayerList().remove(player);
			throw e;
		}
	}

	/** A mock player in survival, holding (in the main hand) an item skinned with {@code skin}. */
	static ServerPlayer holding(GameTestHelper helper, SkinFixtures fx, String skin, ItemStack item) {
		ServerPlayer player = survivor(helper, item);
		fx.skins.grant(player.nameAndId(), skin);
		assertThat(fx.skins.apply(player, skin, ItemLocation.hotbar(0)).applied(), "the " + skin + " item wasn't skinned");
		return player;
	}

	/** A mock player in survival, carrying these in their hotbar, standing inside the test. */
	static ServerPlayer survivor(GameTestHelper helper, ItemStack... items) {
		ServerPlayer player = ApplyTests.carrying(helper, items);
		player.setGameMode(GameType.SURVIVAL);
		player.setPos(helper.absoluteVec(new Vec3(1.5, 1, 1.5)));
		return player;
	}

	/** A pig that stands still, two blocks from the survivor. */
	static Pig victim(GameTestHelper helper) {
		return helper.spawnWithNoFreeWill(EntityTypes.PIG, new BlockPos(3, 1, 1));
	}
}
