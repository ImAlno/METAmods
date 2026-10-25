package se.metacraft.skins;

import java.nio.file.Path;
import java.util.List;

/**
 * What {@link Skins#reload()} did: the skins that loaded (ids, sorted), the ones rejected and why,
 * the warnings for skins that loaded anyway, and the resulting pack's SHA-1 and whether it differs
 * from the pack before (only then is it pushed to players).
 */
public record ReloadReport(List<String> loaded, List<Rejection> rejections, List<Warning> warnings, String sha1, boolean changed) {

	/** A skin set aside as Dormant: the file at fault and the reason, in words for an admin. */
	public record Rejection(String skinId, Path file, String reason) {}

	/**
	 * Something an admin should fix in a skin that loaded anyway. {@code prominent} ones show in
	 * the inventory (a missing gui transform, or head on a helmet); the rest are minor.
	 */
	public record Warning(String skinId, Path file, String message, boolean prominent) {}
}
