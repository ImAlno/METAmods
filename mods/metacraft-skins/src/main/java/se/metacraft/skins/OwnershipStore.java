package se.metacraft.skins;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Ownership, one readable JSON file per player: {@code <dir>/<uuid>.json}. It is the only place
 * ownership is recorded.
 * <pre>{@code
 * { "uuid": "…", "name": "Alice",
 *   "skins": { "candy_sword": { "claimed": "2026-10-24T10:00:00Z", "application": { "id": "…", "world": "…", "kind": "sword" } },
 *              "crown": { "claimed": "…", "application": null } } }
 * }</pre>
 * <ul>
 *   <li><b>The running server's copy wins.</b> A file is read once, the first time its player is
 *       needed, and from then on the copy in memory is the truth; every change writes it whole.</li>
 *   <li><b>Saved at once.</b> {@link #save} writes a temporary file and moves it into place before
 *       the change counts. If that fails, the copy in memory is left as it was.</li>
 *   <li><b>Damaged files are never overwritten.</b> A file that doesn't parse, or holds a field this
 *       mod doesn't know, is logged and read again on every look until it's fixed (it's never
 *       cached), and there's no record to save over it.</li>
 * </ul>
 */
final class OwnershipStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create();
	private static final Set<String> RECORD_FIELDS = Set.of("uuid", "name", "skins");
	private static final Set<String> SKIN_FIELDS = Set.of("claimed", "application");
	private static final Set<String> APPLICATION_FIELDS = Set.of("id", "world", "kind");

	/**
	 * An application: its own id (an item carrying another one is stale), the world that stamped it,
	 * and the skin's Kind when it was applied (after a Kind change it's on no item). Files written
	 * before the Kind was kept have none, and count as the skin's current Kind.
	 */
	record Application(UUID id, UUID world, Optional<Kind> kind) {}

	record Owned(Instant claimed, Optional<Application> application) {}

	/** A player's record; {@code skins} is sorted by id. */
	record PlayerRecord(UUID uuid, Optional<String> name, SortedMap<String, Owned> skins) {
		static PlayerRecord empty(UUID uuid) {
			return new PlayerRecord(uuid, Optional.empty(), Collections.emptySortedMap());
		}

		PlayerRecord with(String skinId, Owned owned) {
			SortedMap<String, Owned> next = new TreeMap<>(skins);
			next.put(skinId, owned);
			return new PlayerRecord(uuid, name, Collections.unmodifiableSortedMap(next));
		}

		PlayerRecord without(String skinId) {
			SortedMap<String, Owned> next = new TreeMap<>(skins);
			next.remove(skinId);
			return new PlayerRecord(uuid, name, Collections.unmodifiableSortedMap(next));
		}

		PlayerRecord named(String lastKnownName) {
			return new PlayerRecord(uuid, Optional.of(lastKnownName), skins);
		}
	}

	/** What a look at a player's file found. */
	sealed interface Read permits Readable, Damaged {}

	record Readable(PlayerRecord record) implements Read {}

	/** The file can't be used: why, in words for an admin. */
	record Damaged(String reason) implements Read {}

	private final Path dir;
	private final Map<UUID, PlayerRecord> cache = new HashMap<>();
	/** The damage last logged per player, so a file looked at again and again is logged once per fault. */
	private final Map<UUID, String> loggedDamage = new HashMap<>();

	OwnershipStore(Path dir) {
		this.dir = dir;
	}

	/** Every player with a file, damaged or not. */
	synchronized List<UUID> players() {
		if (!Files.isDirectory(dir)) return List.of();
		List<UUID> players = new ArrayList<>();
		try (Stream<Path> files = Files.list(dir)) {
			for (Path file : files.toList()) {
				String name = file.getFileName().toString();
				if (!name.endsWith(".json")) continue;
				try {
					players.add(UUID.fromString(name.substring(0, name.length() - ".json".length())));
				} catch (IllegalArgumentException e) {
					// not a player's file
				}
			}
		} catch (IOException e) {
			MetacraftSkins.LOGGER.error("[{}] cannot list {}", MetacraftSkins.MOD_ID, dir, e);
		}
		return players;
	}

	Path file(UUID player) {
		return dir.resolve(player + ".json");
	}

	synchronized Read read(UUID player) {
		PlayerRecord cached = cache.get(player);
		if (cached != null) return new Readable(cached);
		Path path = file(player);
		try {
			PlayerRecord record = Files.exists(path) ? parse(player, Files.readString(path, StandardCharsets.UTF_8)) : PlayerRecord.empty(player);
			cache.put(player, record);
			if (loggedDamage.remove(player) != null) MetacraftSkins.LOGGER.info("[{}] {} reads again", MetacraftSkins.MOD_ID, path);
			return new Readable(record);
		} catch (IOException | RuntimeException e) {
			String reason = e.getMessage() != null ? e.getMessage() : e.toString();
			if (!reason.equals(loggedDamage.put(player, reason))) {
				MetacraftSkins.LOGGER.error("[{}] {} is damaged and will not be overwritten; until it's fixed this player has no skins and their "
						+ "skin actions are refused: {}", MetacraftSkins.MOD_ID, path, reason);
			}
			return new Damaged(reason);
		}
	}

	/** Writes the record whole and only then makes it the copy in memory. */
	synchronized void save(PlayerRecord record) throws IOException {
		Path path = file(record.uuid());
		Files.createDirectories(dir);
		Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(encode(record)) + "\n", StandardCharsets.UTF_8);
		try {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		}
		cache.put(record.uuid(), record);
	}

	private static JsonObject encode(PlayerRecord record) {
		JsonObject json = new JsonObject();
		json.addProperty("uuid", record.uuid().toString());
		json.add("name", record.name().<JsonElement>map(JsonPrimitive::new).orElse(JsonNull.INSTANCE));
		JsonObject skins = new JsonObject();
		record.skins().forEach((id, owned) -> {
			JsonObject skin = new JsonObject();
			skin.addProperty("claimed", owned.claimed().toString());
			skin.add("application", owned.application().<JsonElement>map(a -> {
				JsonObject application = new JsonObject();
				application.addProperty("id", a.id().toString());
				application.addProperty("world", a.world().toString());
				a.kind().ifPresent(k -> application.addProperty("kind", k.id()));
				return application;
			}).orElse(JsonNull.INSTANCE));
			skins.add(id, skin);
		});
		json.add("skins", skins);
		return json;
	}

	private static PlayerRecord parse(UUID player, String text) throws IOException {
		JsonObject json = object(JsonParser.parseString(text), "the file");
		onlyFields(json, RECORD_FIELDS, "the file");
		UUID uuid = uuid(json.get("uuid"), "uuid");
		if (!uuid.equals(player)) throw new IOException("its uuid " + uuid + " is not the one in its file name");
		JsonElement name = json.get("name");
		Optional<String> lastKnownName = name == null || name.isJsonNull() ? Optional.empty() : Optional.of(string(name, "name"));
		SortedMap<String, Owned> skins = new TreeMap<>();
		for (Map.Entry<String, JsonElement> entry : object(json.get("skins"), "skins").entrySet()) {
			String where = "skins." + entry.getKey();
			JsonObject skin = object(entry.getValue(), where);
			onlyFields(skin, SKIN_FIELDS, where);
			Instant claimed;
			try {
				claimed = Instant.parse(string(skin.get("claimed"), where + ".claimed"));
			} catch (DateTimeParseException e) {
				throw new IOException(where + ".claimed is not a time: " + e.getParsedString());
			}
			JsonElement app = skin.get("application");
			Optional<Application> application = Optional.empty();
			if (app != null && !app.isJsonNull()) {
				JsonObject a = object(app, where + ".application");
				onlyFields(a, APPLICATION_FIELDS, where + ".application");
				Optional<Kind> kind = Optional.empty();
				if (a.has("kind")) {
					String id = string(a.get("kind"), where + ".application.kind");
					kind = Optional.of(Kind.byId(id).orElseThrow(() -> new IOException(where + ".application.kind is not a Kind: " + id)));
				}
				application = Optional.of(new Application(uuid(a.get("id"), where + ".application.id"), uuid(a.get("world"), where + ".application.world"), kind));
			}
			skins.put(entry.getKey(), new Owned(claimed, application));
		}
		return new PlayerRecord(uuid, lastKnownName, Collections.unmodifiableSortedMap(skins));
	}

	private static JsonObject object(JsonElement element, String where) throws IOException {
		if (element == null || !element.isJsonObject()) throw new IOException(where + " is not a JSON object");
		return element.getAsJsonObject();
	}

	private static String string(JsonElement element, String where) throws IOException {
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IOException(where + " is not a string");
		return element.getAsString();
	}

	private static UUID uuid(JsonElement element, String where) throws IOException {
		String text = string(element, where);
		try {
			return UUID.fromString(text);
		} catch (IllegalArgumentException e) {
			throw new IOException(where + " is not a UUID: " + text);
		}
	}

	private static void onlyFields(JsonObject object, Set<String> known, String where) throws IOException {
		for (String key : object.keySet()) {
			if (!known.contains(key)) throw new IOException(where + " has an unknown field \"" + key + "\"");
		}
	}
}
