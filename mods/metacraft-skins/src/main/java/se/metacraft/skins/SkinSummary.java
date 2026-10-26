package se.metacraft.skins;

import java.util.List;
import java.util.Optional;

/**
 * One skin folder as {@code /skins list} shows it: its id, its name and Kind (the name only once
 * it loads; the Kind when the definition got far enough to name a known one), the Materials it's
 * limited to (once it loads, in vanilla order; empty if it fits every one), its optional
 * description and Perks (once it loads; no Perks while the settings switch them all off) and, for a
 * Dormant skin, why it didn't load.
 */
public record SkinSummary(String id, Optional<String> name, Optional<Kind> kind, Optional<List<Material>> materials, Optional<String> description,
							List<Perk> perks,
							Optional<String> dormantReason) {
	public boolean dormant() {
		return dormantReason.isPresent();
	}

	/** What players read: the name, or the id while the skin is Dormant. */
	public String displayName() {
		return name.orElse(id);
	}
}
