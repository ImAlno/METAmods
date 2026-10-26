package se.metacraft.skins.gametest;

import se.metacraft.skins.Kind;
import se.metacraft.skins.ReloadReport;
import se.metacraft.skins.SkinSummary;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.List;
import java.util.Optional;

import static se.metacraft.skins.gametest.SkinFixtures.assertThat;

/**
 * Reading skin folders: what loads, and every way a definition is rejected. A rejection sets
 * only that skin aside (Dormant, with its reason) and names the file at fault; the rest load.
 */
public final class SkinDefinitionTests {

	@GameTest
	public void brokenJson5IsRejected(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().file("broken", "skin.json5", "{ name: \"Broken\", kind: \"sword\", look: { model: \"broken.json\" ");
		assertRejected(fx.reload(), "broken", "skin.json5", "json5");
		helper.succeed();
	}

	@GameTest
	public void aFolderWithoutSkinJson5IsRejected(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().png("empty", "empty");
		assertRejected(fx.reload(), "empty", "skin.json5", "no skin.json5");
		helper.succeed();
	}

	@GameTest
	public void unknownFieldsAreRejected(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().lookOnly("typo", "sword")
				.file("typo", "skin.json5", "{ name: \"Typo\", kind: \"sword\", colour: \"red\", look: { model: \"typo.json\" } }");
		assertRejected(fx.reload(), "typo", "skin.json5", "colour");
		helper.succeed();
	}

	@GameTest
	public void missingRequiredFieldsAreRejected(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().lookOnly("nameless", "sword")
				.file("nameless", "skin.json5", "{ kind: \"sword\", look: { model: \"nameless.json\" } }");
		assertRejected(fx.reload(), "nameless", "skin.json5", "name");
		helper.succeed();
	}

	@GameTest
	public void anUnknownKindIsRejected(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().lookOnly("hat", "hat");
		assertRejected(fx.reload(), "hat", "skin.json5", "unknown kind");
		helper.succeed();
	}

	/** A spear takes an icon and an in-hand model; every other Kind one model. Either the other way round is wrong. */
	@GameTest
	public void theWrongLookShapeIsRejected(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour()
				.lookOnly("one_model_spear", "spear")
				.file("two_model_sword", "skin.json5", "{ name: \"Sword\", kind: \"sword\", look: { icon: \"a.json\", in_hand: \"a.json\" } }")
				.file("two_model_sword", "a.json", SkinFixtures.model("a")).png("two_model_sword", "a");
		ReloadReport report = fx.reload();
		assertRejected(report, "one_model_spear", "skin.json5", "look: { icon, in_hand }");
		assertRejected(report, "two_model_sword", "skin.json5", "look: { model }");
		helper.succeed();
	}

	@GameTest
	public void aMissingModelIsRejected(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour()
				.file("modelless", "skin.json5", "{ name: \"Modelless\", kind: \"axe\", look: { model: \"modelless.json\" } }");
		assertRejected(fx.reload(), "modelless", "skin.json5", "modelless.json");
		helper.succeed();
	}

	@GameTest
	public void aTextureWithoutItsPngIsRejected(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().lookOnly("pale", "hoe");
		fx.file("pale", "pale.json", SkinFixtures.model("block/pale_paint"));
		assertRejected(fx.reload(), "pale", "pale.json", "pale_paint.png");
		helper.succeed();
	}

	/** {@code materials} must be a list of one or more names; anything else rejects the skin. */
	@GameTest
	public void aMaterialsFieldThatIsntANonEmptyListIsRejected(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().limited("bare", "sword", "\"diamond\"").limited("none", "sword", "[]")
				.limited("numbered", "sword", "[ 4 ]");
		ReloadReport report = fx.reload();
		assertRejected(report, "bare", "skin.json5", "\"materials\" must be a list");
		assertRejected(report, "none", "skin.json5", "\"materials\" is empty");
		assertRejected(report, "numbered", "skin.json5", "\"materials\" must be a list");
		helper.succeed();
	}

	/**
	 * A Material must be one of the fixed names: {@code wooden} and {@code golden} get a hint, any
	 * other unknown name lists what the Kind comes in, as an unknown Kind lists the Kinds.
	 */
	@GameTest
	public void anUnknownMaterialIsRejectedWithAHint(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().limited("wooden", "sword", "[\"wooden\"]").limited("golden", "helmet", "[\"leather\", \"golden\"]")
				.limited("ruby", "pickaxe", "[\"ruby\"]").limited("shell", "helmet", "[\"Diamond\"]");
		ReloadReport report = fx.reload();
		assertRejected(report, "wooden", "skin.json5", "unknown material \"wooden\" (did you mean \"wood\"?)");
		assertRejected(report, "golden", "skin.json5", "unknown material \"golden\" (did you mean \"gold\"?)");
		assertRejected(report, "ruby", "skin.json5", "unknown material \"ruby\" (one of: wood, stone, copper, iron, gold, diamond, netherite)");
		assertRejected(report, "shell", "skin.json5",
				"unknown material \"Diamond\" (one of: leather, chainmail, copper, iron, gold, diamond, netherite, turtle)");
		helper.succeed();
	}

	/** A Material the Kind doesn't come in, any {@code materials} on a mace or elytra, and a duplicate are rejected. */
	@GameTest
	public void anImpossibleOrRepeatedMaterialIsRejected(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().limited("turtle_sword", "sword", "[\"diamond\", \"turtle\"]").limited("wooden_boots", "boots", "[\"wood\"]")
				.limited("hammer", "mace", "[\"diamond\"]").limited("wings", "elytra", "[\"leather\"]")
				.limited("twice", "sword", "[\"diamond\", \"netherite\", \"diamond\"]");
		ReloadReport report = fx.reload();
		assertRejected(report, "turtle_sword", "skin.json5", "\"turtle\" isn't a material of the sword Kind (it comes in: wood, stone, copper, iron, gold, diamond, netherite)");
		assertRejected(report, "wooden_boots", "skin.json5", "\"wood\" isn't a material of the boots Kind (it comes in: leather, chainmail, copper, iron, gold, diamond, netherite)");
		assertRejected(report, "hammer", "skin.json5", "the mace Kind has no materials, so it takes no \"materials\"");
		assertRejected(report, "wings", "skin.json5", "the elytra Kind has no materials, so it takes no \"materials\"");
		assertRejected(report, "twice", "skin.json5", "\"diamond\" is listed twice in \"materials\"");
		helper.succeed();
	}

	/** Every Material of the Kind may be listed, in any order, with no warning. */
	@GameTest
	public void aSkinMayListEveryMaterialOfItsKind(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create()
				.limited("all_helmets", "helmet", "[\"turtle\", \"netherite\", \"diamond\", \"gold\", \"iron\", \"copper\", \"chainmail\", \"leather\"]");
		ReloadReport report = fx.reload();
		assertThat(report.loaded().equals(List.of("all_helmets")) && report.warnings().isEmpty(), "every helmet Material: " + report);
		helper.succeed();
	}

	/** {@code /skins list}'s source: every folder with its Kind, and the reason a Dormant one failed. */
	@GameTest
	public void allSkinsListsEveryFolderWithDormantReasons(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().lookOnly("pale", "boots");
		fx.file("pale", "pale.json", SkinFixtures.model("pale_paint"));
		fx.reload();
		List<SkinSummary> all = fx.skins.allSkins();
		assertThat(all.stream().map(SkinSummary::id).toList().equals(List.of("healthy", "pale")), "listed " + all);
		SkinSummary healthy = all.get(0), pale = all.get(1);
		assertThat(healthy.kind().equals(Optional.of(Kind.SWORD)) && healthy.dormantReason().isEmpty(), "healthy: " + healthy);
		assertThat(pale.kind().equals(Optional.of(Kind.BOOTS)), "pale's Kind: " + pale);
		assertThat(pale.dormantReason().map(r -> r.contains("pale_paint.png")).orElse(false), "pale's reason: " + pale);
		helper.succeed();
	}

	/** Fixing a Dormant skin's folder and reloading brings it back; deleting a folder removes it from the list. */
	@GameTest
	public void reloadPicksUpFixedAndDeletedFolders(GameTestHelper helper) {
		SkinFixtures fx = withHealthyNeighbour().lookOnly("hat", "hat");
		assertRejected(fx.reload(), "hat", "skin.json5", "unknown kind");
		fx.lookOnly("hat", "helmet");
		ReloadReport fixed = fx.reload();
		assertThat(fixed.rejections().isEmpty() && fixed.loaded().equals(List.of("hat", "healthy")), "after the fix: " + fixed);
		fx.delete("hat");
		ReloadReport deleted = fx.reload();
		assertThat(deleted.loaded().equals(List.of("healthy")), "after deleting: " + deleted);
		assertThat(fx.skins.allSkins().size() == 1, "a deleted folder is still listed: " + fx.skins.allSkins());
		helper.succeed();
	}

	/**
	 * Missing display transforms are warned about and never invented: a missing gui (or head, on a
	 * helmet) loudly, a missing fixed or on_shelf quietly. The skin still loads.
	 */
	@GameTest
	public void missingDisplayTransformsAreWarnedAbout(GameTestHelper helper) {
		SkinFixtures fx = SkinFixtures.create()
				.file("santa_hat_test", "skin.json5", "{ name: \"Santa Hat\", kind: \"helmet\", look: { model: \"santa_hat_test.json\" } }")
				.file("santa_hat_test", "santa_hat_test.json", """
						{ "textures": { "0": "santa_hat_test" }, "elements": [], "display": { "ground": { "scale": [1, 1, 1] }, "on_shelf": {} } }""")
				.png("santa_hat_test", "santa_hat_test");
		ReloadReport report = fx.reload();
		assertThat(report.loaded().equals(List.of("santa_hat_test")), "the hat did not load: " + report);
		assertThat(warned(report, "gui", true), "no prominent gui warning: " + report.warnings());
		assertThat(warned(report, "head", true), "no prominent head warning: " + report.warnings());
		assertThat(warned(report, "fixed", false), "no minor fixed warning: " + report.warnings());
		assertThat(!warned(report, "on_shelf", false) && !warned(report, "on_shelf", true), "warned about on_shelf, which is there: " + report.warnings());
		String model = SkinFixtures.text(SkinFixtures.unzip(fx.pack().zip()), "assets/skins/models/item/santa_hat_test/santa_hat_test.json");
		assertThat(!model.contains("\"gui\"") && !model.contains("\"head\""), "a display transform was invented: " + model);
		helper.succeed();
	}

	private static boolean warned(ReloadReport report, String slot, boolean prominent) {
		return report.warnings().stream().anyMatch(w -> w.skinId().equals("santa_hat_test") && w.prominent() == prominent && w.message().contains("\"" + slot + "\""));
	}

	/** A skin that loads, next to the broken one: a rejection must never take the others down. */
	private static SkinFixtures withHealthyNeighbour() {
		return SkinFixtures.create().lookOnly("healthy", "sword");
	}

	private static void assertRejected(ReloadReport report, String id, String file, String reasonFragment) {
		assertThat(report.loaded().contains("healthy"), "the healthy neighbour did not load: " + report);
		assertThat(!report.loaded().contains(id), id + " loaded but should be rejected");
		var rejection = report.rejections().stream().filter(r -> r.skinId().equals(id)).findFirst()
				.orElseThrow(() -> SkinFixtures.fail(id + " was not rejected: " + report));
		assertThat(rejection.file().getFileName().toString().equals(file), id + " blamed " + rejection.file() + ", not " + file);
		assertThat(rejection.reason().contains(reasonFragment), id + "'s reason \"" + rejection.reason() + "\" does not mention " + reasonFragment);
	}
}
