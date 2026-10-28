package se.metacraft.skins;

import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/**
 * An item on a player that a skin fits, as the apply screen lists it: where it is, the item, and
 * the name of the skin applying would replace, if it carries one (a live skin, or a Dormant one,
 * named by its id); none if it carries this very skin of theirs ({@code alreadyHasIt}).
 */
public record Candidate(ItemLocation where, ItemStack item, Optional<String> replaces, boolean alreadyHasIt) {}
