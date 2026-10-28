package se.metacraft.skins;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The skin an item carries while that application is live: the skin's current definition, and its
 * Owner. {@code perks} are the ones in effect: none while the settings switch all Perks off.
 * {@code dyeable}: the skin is a Dyeable Hat.
 */
public record LiveSkin(String id, String name, Kind kind, boolean dyeable, Optional<String> description, List<Perk> perks, UUID owner, String ownerName) {}
