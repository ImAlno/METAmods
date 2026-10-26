package se.metacraft.skins.gametest;

import se.metacraft.skins.ItemLocation;
import se.metacraft.skins.MetacraftSkins;
import se.metacraft.skins.OwnershipChange;
import se.metacraft.skins.SkinMarker;
import se.metacraft.skins.Skins;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static se.metacraft.skins.gametest.SkinFixtures.assertThat;

/** Dyeing a Dyeable Hat as players do, through the game's own recipes. */
public final class DyeingTests {

	/**
	 * A Dyeable Hat on a leather helmet dyes through vanilla's dye recipe: the result keeps the skin
	 * (the same application, still live) and takes the dye's colour, and its Owner sees the hat's Look
	 * on it with that colour.
	 */
	@GameTest
	public void aLeatherDyeableHatDyedThroughVanillasRecipeStaysLive(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().dyeableHat("cowboy_hat_test", "[\"leather\"]");
		fx.reload();
		ServerPlayer player = helper.makeMockServerPlayerInLevel();
		player.getInventory().clearContent();
		player.getInventory().setItem(0, new ItemStack(Items.LEATHER_HELMET));
		fx.skins.grant(player.nameAndId(), "cowboy_hat_test");
		assertThat(fx.skins.apply(player, "cowboy_hat_test", ItemLocation.hotbar(0)).applied(), "the hat wasn't applied");
		ItemStack hat = player.getInventory().getItem(0);

		CraftingInput input = CraftingInput.of(2, 1, List.of(hat, new ItemStack(Items.DYE.red())));
		var recipe = helper.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
				.orElseThrow(() -> SkinFixtures.fail("no recipe dyes the skinned leather helmet"));
		ItemStack dyed = recipe.value().assemble(input);
		assertThat(dyed.is(Items.LEATHER_HELMET), "dyeing made " + dyed);
		assertThat(hat.get(SkinMarker.TYPE).equals(dyed.get(SkinMarker.TYPE)), "the dyed hat carries " + dyed.get(SkinMarker.TYPE) + ", not " + hat.get(SkinMarker.TYPE));
		assertThat(fx.skins.liveSkin(dyed).filter(s -> s.id().equals("cowboy_hat_test")).isPresent(), "the dyed hat isn't live");
		DyedItemColor colour = dyed.get(DataComponents.DYED_COLOR);
		// Red dye alone on an undyed helmet gives red dye's own colour.
		assertThat(colour != null && colour.rgb() == 0xB02E26, "the dyed hat's colour: " + colour);

		player.getInventory().setItem(0, dyed);
		ItemStack seen = ViewTests.view(helper, fx, dyed, player, player.getUUID());
		assertThat("skins:cowboy_hat_test".equals(String.valueOf(seen.get(DataComponents.ITEM_MODEL))), "its Owner sees " + seen.get(DataComponents.ITEM_MODEL));
		assertThat(seen.get(DataComponents.DYED_COLOR) != null && seen.get(DataComponents.DYED_COLOR).rgb() == 0xB02E26,
				"its Owner sees the colour " + seen.get(DataComponents.DYED_COLOR));
		helper.succeed();
	}

	/**
	 * A live Dyeable Hat on a diamond helmet dyes in the crafting grid as a leather one does: the
	 * result is the same diamond helmet, still carrying the same live application, in vanilla's mixed
	 * colour (and dyeing it again mixes the old colour in, as vanilla does). A plain diamond helmet, a
	 * hat skin that isn't dyeable and a Dormant Dyeable Hat refuse the dye, and so does a grid holding
	 * anything but the helmet and dyes.
	 */
	@GameTest
	public void aLiveDyeableHatDyesOnADiamondHelmet(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().dyeableHat("cowboy_hat_test").dyeableHat("sleepy_hat_test").skin("crown_test", "helmet");
		fx.reload();
		ServerPlayer player = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_HELMET),
				new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_HELMET));
		List<String> skins = List.of("cowboy_hat_test", "crown_test", "sleepy_hat_test");
		for (int i = 0; i < skins.size(); i++) {
			fx.skins.grant(player.nameAndId(), skins.get(i));
			assertThat(fx.skins.apply(player, skins.get(i), ItemLocation.hotbar(i)).applied(), skins.get(i) + " wasn't applied");
		}
		ItemStack hat = player.getInventory().getItem(0);
		hat.set(DataComponents.CUSTOM_NAME, Component.literal("Stetson"));

		ItemStack red = crafted(helper, fx, hat, new ItemStack(Items.DYE.red()))
				.orElseThrow(() -> SkinFixtures.fail("no recipe dyes the Dyeable Hat on a diamond helmet"));
		assertThat(red.is(Items.DIAMOND_HELMET) && red.getCount() == 1, "dyeing made " + red);
		assertThat(hat.get(SkinMarker.TYPE).equals(red.get(SkinMarker.TYPE)), "the dyed hat carries " + red.get(SkinMarker.TYPE));
		assertThat(fx.skins.liveSkin(red).filter(s -> s.id().equals("cowboy_hat_test")).isPresent(), "the dyed hat isn't live");
		assertThat("Stetson".equals(red.getHoverName().getString()), "the dyed hat lost its other components: " + red);
		assertThat(red.get(DataComponents.DYED_COLOR) != null && red.get(DataComponents.DYED_COLOR).rgb() == 0xB02E26, "the dyed hat's colour: " + red.get(DataComponents.DYED_COLOR));
		assertThat(!hat.has(DataComponents.DYED_COLOR), "dyeing changed the hat in the grid");

		// Dyed again, and with two dyes at once, it mixes exactly as a plain leather helmet does.
		ItemStack leather = crafted(helper, fx, new ItemStack(Items.LEATHER_HELMET), new ItemStack(Items.DYE.red())).orElseThrow();
		DyedItemColor mixed = crafted(helper, fx, leather, new ItemStack(Items.DYE.blue())).orElseThrow().get(DataComponents.DYED_COLOR);
		ItemStack purple = crafted(helper, fx, red, new ItemStack(Items.DYE.blue())).orElseThrow(() -> SkinFixtures.fail("a dyed hat can't be dyed again"));
		assertThat(mixed != null && mixed.equals(purple.get(DataComponents.DYED_COLOR)), "dyeing again gave " + purple.get(DataComponents.DYED_COLOR) + ", not " + mixed);
		DyedItemColor two = crafted(helper, fx, new ItemStack(Items.LEATHER_HELMET), new ItemStack(Items.DYE.red()), new ItemStack(Items.DYE.yellow()))
				.orElseThrow().get(DataComponents.DYED_COLOR);
		ItemStack orange = crafted(helper, fx, hat, new ItemStack(Items.DYE.red()), new ItemStack(Items.DYE.yellow()))
				.orElseThrow(() -> SkinFixtures.fail("two dyes don't dye the hat"));
		assertThat(two != null && two.equals(orange.get(DataComponents.DYED_COLOR)), "two dyes gave " + orange.get(DataComponents.DYED_COLOR) + ", not " + two);

		assertThat(crafted(helper, fx, player.getInventory().getItem(3), new ItemStack(Items.DYE.red())).isEmpty(), "a plain diamond helmet took the dye");
		assertThat(crafted(helper, fx, player.getInventory().getItem(1), new ItemStack(Items.DYE.red())).isEmpty(), "a hat that isn't dyeable took the dye");
		assertThat(crafted(helper, fx, hat).isEmpty(), "the hat alone made something");
		assertThat(crafted(helper, fx, hat, new ItemStack(Items.DYE.red()), new ItemStack(Items.STICK)).isEmpty(), "a stick in the grid was dyed in");
		assertThat(crafted(helper, fx, hat, hat.copy(), new ItemStack(Items.DYE.red())).isEmpty(), "two hats were dyed at once");

		fx.file("sleepy_hat_test", "sleepy_hat_test.json", "{ broken");
		fx.reload();
		assertThat(crafted(helper, fx, player.getInventory().getItem(2), new ItemStack(Items.DYE.red())).isEmpty(), "a Dormant hat took the dye");
		helper.succeed();
	}

	/**
	 * A cauldron washes the dye off the seven non-leather vanilla helmets, as off leather: they're
	 * added to {@code #minecraft:cauldron_can_remove_dye} by name (not as {@code #head_armor}, which
	 * would override another mod's helmets' own cauldron behaviour). Washing a dyed Dyeable Hat on a
	 * diamond helmet takes its colour and keeps the skin, live.
	 */
	@GameTest
	public void aCauldronWashesTheDyeOffAnyVanillaHelmetAndKeepsTheSkin(GameTestHelper helper) {
		Set<Item> washed = new HashSet<>();
		BuiltInRegistries.ITEM.getTagOrEmpty(ItemTags.CAULDRON_CAN_REMOVE_DYE).forEach(item -> washed.add(item.value()));
		Set<Item> expected = Set.of(Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS, Items.LEATHER_HORSE_ARMOR,
				Items.WOLF_ARMOR, Items.CHAINMAIL_HELMET, Items.COPPER_HELMET, Items.IRON_HELMET, Items.GOLDEN_HELMET, Items.DIAMOND_HELMET,
				Items.NETHERITE_HELMET, Items.TURTLE_HELMET);
		assertThat(washed.equals(expected), "the cauldron washes " + washed);

		SkinFixtures fx = SkinFixtures.create().dyeableHat("cowboy_hat_test");
		fx.reload();
		ServerPlayer player = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_HELMET));
		fx.skins.grant(player.nameAndId(), "cowboy_hat_test");
		assertThat(fx.skins.apply(player, "cowboy_hat_test", ItemLocation.hotbar(0)).applied(), "the hat wasn't applied");
		ItemStack hat = crafted(helper, fx, player.getInventory().getItem(0), new ItemStack(Items.DYE.red())).orElseThrow();
		player.getInventory().setItem(0, hat);
		player.getInventory().setSelectedSlot(0);

		BlockPos cauldron = new BlockPos(1, 1, 1);
		helper.setBlock(cauldron, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
		BlockPos at = helper.absolutePos(cauldron);
		player.gameMode.useItemOn(player, helper.getLevel(), hat, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
		ItemStack washedHat = player.getInventory().getItem(0);
		assertThat(!washedHat.has(DataComponents.DYED_COLOR), "the cauldron didn't wash the hat: " + washedHat.get(DataComponents.DYED_COLOR));
		assertThat(fx.skins.liveSkin(washedHat).filter(s -> s.id().equals("cowboy_hat_test")).isPresent(), "the washed hat isn't live");
		assertThat(helper.getLevel().getBlockState(at).getValue(LayeredCauldronBlock.LEVEL) == 2, "washing used no water");
		helper.succeed();
	}

	/**
	 * A non-leather helmet may carry a colour only while it carries a Dyeable Hat. When the hat leaves
	 * a diamond helmet, removed, moved to another helmet, revoked or overwritten by a skin that isn't
	 * dyeable, the colour goes with it, at the tidy that clears the marker (or the apply itself). Swapping
	 * one Dyeable Hat for another keeps it.
	 */
	@GameTest
	public void whenAHatLeavesADiamondHelmetItsColourGoesWithIt(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().dyeableHat("removed_hat_test").dyeableHat("moved_hat_test").dyeableHat("revoked_hat_test")
				.dyeableHat("overwritten_hat_test").dyeableHat("swapped_hat_test").dyeableHat("other_hat_test").skin("crown_test", "helmet");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_HELMET),
				new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_HELMET),
				new ItemStack(Items.DIAMOND_HELMET));
		List<String> hats = List.of("removed_hat_test", "moved_hat_test", "revoked_hat_test", "overwritten_hat_test", "swapped_hat_test");
		for (int i = 0; i < hats.size(); i++) dyedHat(fx, owner, hats.get(i), i);
		for (String skin : List.of("other_hat_test", "crown_test")) fx.skins.grant(owner.nameAndId(), skin);

		assertThat(fx.skins.remove(owner.nameAndId(), "removed_hat_test") == OwnershipChange.DONE, "the hat couldn't be removed");
		assertThat(fx.skins.apply(owner, "moved_hat_test", ItemLocation.hotbar(5)).applied(), "the hat couldn't be moved");
		assertThat(fx.skins.revoke(owner.nameAndId(), "revoked_hat_test") == OwnershipChange.DONE, "the hat couldn't be revoked");
		fx.skins.tidy(owner);
		for (int i = 0; i < 3; i++) {
			ItemStack helmet = owner.getInventory().getItem(i);
			assertThat(!helmet.has(SkinMarker.TYPE) && !helmet.has(DataComponents.DYED_COLOR), hats.get(i) + "'s old helmet kept " + helmet.getComponentsPatch());
		}

		assertThat(fx.skins.apply(owner, "crown_test", ItemLocation.hotbar(3)).applied(), "the crown wasn't applied over the hat");
		ItemStack crowned = owner.getInventory().getItem(3);
		assertThat(!crowned.has(DataComponents.DYED_COLOR), "the crown took the hat's colour: " + crowned.get(DataComponents.DYED_COLOR));

		assertThat(fx.skins.apply(owner, "other_hat_test", ItemLocation.hotbar(4)).applied(), "the other hat wasn't applied over the hat");
		ItemStack swapped = owner.getInventory().getItem(4);
		assertThat(RED.equals(swapped.get(DataComponents.DYED_COLOR)), "swapping hats lost the colour: " + swapped.get(DataComponents.DYED_COLOR));
		helper.succeed();
	}

	/**
	 * A leather helmet keeps its dye whatever happens to its hat (vanilla dyes and washes leather
	 * anyway), and a Dormant hat isn't taken off, so it keeps its colour and comes back with it.
	 */
	@GameTest
	public void leatherAndDormantHatsKeepTheirColour(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().dyeableHat("removed_hat_test").dyeableHat("overwritten_hat_test").dyeableHat("sleepy_hat_test")
				.skin("crown_test", "helmet");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.LEATHER_HELMET), new ItemStack(Items.LEATHER_HELMET),
				new ItemStack(Items.DIAMOND_HELMET));
		dyedHat(fx, owner, "removed_hat_test", 0);
		dyedHat(fx, owner, "overwritten_hat_test", 1);
		dyedHat(fx, owner, "sleepy_hat_test", 2);
		fx.skins.grant(owner.nameAndId(), "crown_test");

		fx.skins.remove(owner.nameAndId(), "removed_hat_test");
		fx.skins.tidy(owner);
		ItemStack removed = owner.getInventory().getItem(0);
		assertThat(!removed.has(SkinMarker.TYPE) && RED.equals(removed.get(DataComponents.DYED_COLOR)), "the leather helmet the hat left: " + removed.getComponentsPatch());
		assertThat(fx.skins.apply(owner, "crown_test", ItemLocation.hotbar(1)).applied(), "the crown wasn't applied over the hat");
		assertThat(RED.equals(owner.getInventory().getItem(1).get(DataComponents.DYED_COLOR)), "the crowned leather helmet lost its dye");

		fx.file("sleepy_hat_test", "sleepy_hat_test.json", "{ broken");
		fx.reload();
		fx.skins.tidy(owner);
		ItemStack sleepy = owner.getInventory().getItem(2);
		assertThat(sleepy.has(SkinMarker.TYPE) && RED.equals(sleepy.get(DataComponents.DYED_COLOR)), "the Dormant hat's helmet: " + sleepy.getComponentsPatch());
		fx.file("sleepy_hat_test", "sleepy_hat_test.json", SkinFixtures.TINTED_MODEL);
		fx.reload();
		assertThat(fx.skins.liveSkin(sleepy).isPresent() && RED.equals(sleepy.get(DataComponents.DYED_COLOR)), "the hat didn't come back dyed");
		helper.succeed();
	}

	/**
	 * Turning {@code dyeable} off takes the colour off its live non-leather hats at the next tidy, in a
	 * bundle the Owner carries too; the skin stays on. A leather helmet keeps its dye.
	 */
	@GameTest
	public void turningDyeableOffTakesTheColourAtTheNextTidy(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().dyeableHat("cowboy_hat_test").dyeableHat("bundled_hat_test").dyeableHat("leather_hat_test");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.IRON_HELMET),
				new ItemStack(Items.LEATHER_HELMET));
		dyedHat(fx, owner, "cowboy_hat_test", 0);
		dyedHat(fx, owner, "bundled_hat_test", 1);
		dyedHat(fx, owner, "leather_hat_test", 2);
		ItemStack bundle = new ItemStack(Items.BUNDLE);
		bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(ItemStackTemplate.fromNonEmptyStack(owner.getInventory().getItem(1)))));
		owner.getInventory().setItem(1, bundle);

		for (String hat : List.of("cowboy_hat_test", "bundled_hat_test", "leather_hat_test")) fx.tintedHat(hat, "{ model: \"" + hat + ".json\" }");
		fx.reload();
		ItemStack hat = owner.getInventory().getItem(0);
		assertThat(RED.equals(hat.get(DataComponents.DYED_COLOR)), "the colour went before the tidy");
		fx.skins.tidy(owner);
		assertThat(!hat.has(DataComponents.DYED_COLOR) && fx.skins.liveSkin(hat).isPresent(), "the hat no longer dyeable: " + hat.getComponentsPatch());
		ItemStack bundled = owner.getInventory().getItem(1).get(DataComponents.BUNDLE_CONTENTS).itemCopies().findFirst().orElseThrow();
		assertThat(!bundled.has(DataComponents.DYED_COLOR) && fx.skins.liveSkin(bundled).isPresent(), "the bundled hat: " + bundled.getComponentsPatch());
		ItemStack leather = owner.getInventory().getItem(2);
		assertThat(RED.equals(leather.get(DataComponents.DYED_COLOR)), "the leather hat lost its dye");
		helper.succeed();
	}

	/**
	 * A non-leather item shows its colour only while it's drawn with its Dyeable Hat's Look: then it
	 * has the colour and vanilla's "Dyed" line. Away, stale, Dormant, no longer fitting or no longer
	 * dyeable, its client copy has neither, though the server's item keeps the colour until a tidy (or
	 * for good, Away or Dormant). A leather hat shows its dye Away too, as vanilla does.
	 */
	@GameTest
	public void aNonLeatherHatShowsItsColourOnlyWithItsLook(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create().dyeableHat("cowboy_hat_test").dyeableHat("removed_hat_test").dyeableHat("sleepy_hat_test")
				.dyeableHat("plain_hat_test").dyeableHat("narrowed_hat_test").dyeableHat("leather_hat_test");
		fx.reload();
		ServerPlayer owner = ApplyTests.carrying(helper, new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.NETHERITE_HELMET),
				new ItemStack(Items.TURTLE_HELMET), new ItemStack(Items.CHAINMAIL_HELMET), new ItemStack(Items.IRON_HELMET),
				new ItemStack(Items.LEATHER_HELMET));
		ItemStack hat = dyedHat(fx, owner, "cowboy_hat_test", 0);
		ItemStack removed = dyedHat(fx, owner, "removed_hat_test", 1);
		ItemStack sleepy = dyedHat(fx, owner, "sleepy_hat_test", 2);
		ItemStack plain = dyedHat(fx, owner, "plain_hat_test", 3);
		ItemStack narrowed = dyedHat(fx, owner, "narrowed_hat_test", 4);
		ItemStack leather = dyedHat(fx, owner, "leather_hat_test", 5);
		ServerPlayer other = helper.makeMockServerPlayerInLevel();

		ItemStack working = ViewTests.view(helper, fx, hat, owner, owner.getUUID());
		assertThat("skins:cowboy_hat_test".equals(String.valueOf(working.get(DataComponents.ITEM_MODEL))), "the working hat shows " + working.get(DataComponents.ITEM_MODEL));
		assertThat(RED.equals(working.get(DataComponents.DYED_COLOR)) && hasDyedLine(working), "the working hat: " + working.getComponents());
		for (ServerPlayer viewer : List.of(owner, other)) {
			ItemStack away = ViewTests.view(helper, fx, hat, viewer, other.getUUID());
			assertThat(Identifier.parse("minecraft:diamond_helmet").equals(away.get(DataComponents.ITEM_MODEL)), "the Away hat shows " + away.get(DataComponents.ITEM_MODEL));
			assertThat(!away.has(DataComponents.DYED_COLOR) && !hasDyedLine(away), "the Away hat: " + away.getComponents());
			assertThat(lore(away).getLast().equals("cowboy_hat_test skin skin, only works for " + owner.getGameProfile().name()), "the Away hat reads " + lore(away));
		}
		ItemStack nowhere = ViewTests.view(helper, fx, hat, other, null);
		assertThat(!nowhere.has(DataComponents.DYED_COLOR) && !hasDyedLine(nowhere), "the hat with no one: " + nowhere.getComponents());
		ItemStack leatherAway = ViewTests.view(helper, fx, leather, other, other.getUUID());
		assertThat(RED.equals(leatherAway.get(DataComponents.DYED_COLOR)) && hasDyedLine(leatherAway), "the Away leather hat: " + leatherAway.getComponents());

		// Stale (removed, not yet tidied), Dormant, no longer dyeable and no longer fitting, all as the Owner sees them.
		fx.skins.remove(owner.nameAndId(), "removed_hat_test");
		fx.file("sleepy_hat_test", "sleepy_hat_test.json", "{ broken");
		fx.tintedHat("plain_hat_test", "{ model: \"plain_hat_test.json\" }");
		fx.dyeableHat("narrowed_hat_test", "[\"leather\", \"diamond\"]");
		fx.reload();
		for (ItemStack item : List.of(removed, sleepy, plain, narrowed)) {
			assertThat(RED.equals(item.get(DataComponents.DYED_COLOR)), "the server's item lost its colour untidied: " + item.getComponentsPatch());
			ItemStack seen = ViewTests.view(helper, fx, item, owner, owner.getUUID());
			assertThat(!seen.has(DataComponents.DYED_COLOR) && !hasDyedLine(seen), item.get(SkinMarker.TYPE).skin() + "'s helmet is sent as " + seen.getComponents());
		}
		ItemStack plainSeen = ViewTests.view(helper, fx, plain, owner, owner.getUUID());
		assertThat("skins:plain_hat_test".equals(String.valueOf(plainSeen.get(DataComponents.ITEM_MODEL))), "the hat no longer dyeable lost its Look");
		helper.succeed();
	}

	/** Does the client copy carry vanilla's "Dyed" line (or, with advanced tooltips, "Color: #…") among its lore? */
	private static boolean hasDyedLine(ItemStack client) {
		return lore(client).stream().anyMatch(line -> line.equals("Dyed") || line.startsWith("Color: #"));
	}

	private static List<String> lore(ItemStack client) {
		return client.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().stream().map(Component::getString).toList();
	}

	private static final DyedItemColor RED = new DyedItemColor(0xB02E26);

	/** Applies the owner's (newly granted) skin to the item in hotbar slot {@code slot} and dyes it red. */
	private static ItemStack dyedHat(SkinFixtures fx, ServerPlayer owner, String skin, int slot) {
		fx.skins.grant(owner.nameAndId(), skin);
		assertThat(fx.skins.apply(owner, skin, ItemLocation.hotbar(slot)).applied(), skin + " wasn't applied");
		ItemStack hat = owner.getInventory().getItem(slot);
		hat.set(DataComponents.DYED_COLOR, RED);
		return hat;
	}

	/**
	 * What the crafting grid makes of these items, in one row, as the server's own recipes see them
	 * with the test's Skins. The server's Skins is swapped for the test's only within this call, on the
	 * server thread, so no other test sees it.
	 */
	static Optional<ItemStack> crafted(GameTestHelper helper, SkinFixtures fx, ItemStack... grid) {
		CraftingInput input = CraftingInput.of(grid.length, 1, List.of(grid));
		Skins previous = MetacraftSkins.use(fx.skins);
		try {
			return helper.getLevel().getServer().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, helper.getLevel())
					.map(recipe -> recipe.value().assemble(input));
		} finally {
			MetacraftSkins.use(previous);
		}
	}
}
