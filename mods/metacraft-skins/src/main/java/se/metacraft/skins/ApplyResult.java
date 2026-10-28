package se.metacraft.skins;

import java.util.Optional;

/**
 * What an apply did. Anything but {@link Outcome#APPLIED} changed nothing, on the item or on disk.
 * {@code replaced} names the skin the item carried before (by its id if it's Dormant), which is now
 * Unapplied for its Owner.
 */
public record ApplyResult(Outcome outcome, Optional<String> replaced) {
	public enum Outcome {
		APPLIED,
		/** The player doesn't own the skin. */
		NOT_OWNED,
		/** No skin folder has this id. */
		UNKNOWN_SKIN,
		/** The skin's definition didn't load. */
		DORMANT_SKIN,
		/** Nothing is at that place on the player. */
		NO_ITEM,
		/** The item isn't of the skin's Kind, or not of a Material the skin is limited to. */
		DOESNT_FIT,
		/** The item already carries this application of the skin. */
		ALREADY_APPLIED,
		/** The player's file can't be read; it is left untouched until someone fixes it. */
		DAMAGED_FILE,
		/** The player's file couldn't be written; the change was not made. */
		SAVE_FAILED
	}

	static ApplyResult refused(Outcome outcome) {
		return new ApplyResult(outcome, Optional.empty());
	}

	public boolean applied() {
		return outcome == Outcome.APPLIED;
	}
}
