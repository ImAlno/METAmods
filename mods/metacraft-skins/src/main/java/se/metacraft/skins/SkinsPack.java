package se.metacraft.skins;

/**
 * One build of the skins resource pack: the zip and its SHA-1. The same skins always give the
 * same bytes, so the same SHA-1, and a client that has it cached never downloads it again.
 */
public record SkinsPack(String sha1, byte[] zip) {
	static final String PATH_PREFIX = MetacraftSkins.MOD_ID + "/";

	/** Where the pack server has it (no leading '/'). */
	public String path() {
		return PATH_PREFIX + sha1 + ".zip";
	}
}
