package se.metacraft.skins;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything a player owns, sorted by skin id. A player whose file is damaged owns nothing until
 * it's fixed: {@code damage} then says what's wrong with it, and every skin action is refused.
 */
public record PlayerSkins(UUID player, Optional<String> lastKnownName, List<OwnedSkin> skins, Optional<String> damage) {
	public boolean owns(String skinId) {
		return skins.stream().anyMatch(s -> s.id().equals(skinId));
	}
}
