package se.metacraft.skins.gametest;

import se.metacraft.skins.ItemLocation;
import se.metacraft.skins.SkinMarker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.List;

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
}
