package se.metacraft.skins;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * The world's stamp: a random id kept in {@code <world>/metacraft-skins.json}, made the first time
 * a world needs one. Applications carry the stamp of the world they were made in, so a world
 * moved with its server keeps every application working, and a fresh world (no marker yet) shows
 * every skin Unapplied with no admin step.
 */
final class WorldMarker {
	static final String FILE = MetacraftSkins.MOD_ID + ".json";

	private WorldMarker() {}

	/**
	 * This world's stamp. A marker that can't be read is logged and left alone; the world then
	 * gets a stamp for this run only, so its applications count as another world's until it's fixed.
	 */
	static UUID read(Path world) {
		Path file = world.resolve(FILE);
		try {
			if (Files.exists(file)) {
				JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
				return UUID.fromString(json.get("world").getAsString());
			}
			UUID stamp = UUID.randomUUID();
			JsonObject json = new JsonObject();
			json.addProperty("world", stamp.toString());
			Files.createDirectories(world);
			Files.writeString(file, json + "\n", StandardCharsets.UTF_8);
			return stamp;
		} catch (IOException | RuntimeException e) {
			UUID stamp = UUID.randomUUID();
			MetacraftSkins.LOGGER.error("[{}] cannot use the world marker {}; skins applied in this world count as another world's until it's fixed. "
					+ "Using {} for this run: {}", MetacraftSkins.MOD_ID, file, stamp, e.toString());
			return stamp;
		}
	}
}
