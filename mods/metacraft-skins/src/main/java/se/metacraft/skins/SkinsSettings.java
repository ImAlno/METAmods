package se.metacraft.skins;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import se.metacraft.config.container.ConfigContainer;
import se.metacraft.config.util.CommentCodec;

import java.nio.file.Path;

/**
 * The server-wide settings, {@code config/metacraft-skins.json5} (metacraft-config). Re-read by
 * {@code /itemskins reload} only, never by vanilla {@code /reload}. {@code perks-enabled} off empties every
 * skin's Perks (and their tooltip lines) at once; {@code perks-affect-players} is for event Perks'
 * target effects. Keys with a hyphen must be quoted in the file (Jankson), or the file doesn't parse
 * and the settings before stay.
 */
public record SkinsSettings(boolean perksAffectPlayers, boolean perksEnabled) {
	public static final SkinsSettings DEFAULTS = new SkinsSettings(true, true);

	public static final MapCodec<SkinsSettings> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			CommentCodec.comment(
					Codec.BOOL.optionalFieldOf("perks-affect-players", true),
					"Whether Perks that hit a target (on hit, on kill, when hurt) affect players too. Off: mobs only."
			).forGetter(SkinsSettings::perksAffectPlayers),
			CommentCodec.comment(
					Codec.BOOL.optionalFieldOf("perks-enabled", true),
					"Off turns every Perk off; Looks keep working."
			).forGetter(SkinsSettings::perksEnabled)
	).apply(instance, SkinsSettings::new));

	/** The settings file of one server root; reloads only when asked. */
	static ConfigContainer<SkinsSettings> container(Path file) {
		return ConfigContainer.Builder.create(CODEC, () -> DEFAULTS).withPath(file).build(MetacraftSkins.MOD_ID);
	}
}
