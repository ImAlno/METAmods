package se.metacraft.skins;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;
import blue.endless.jankson.api.SyntaxError;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads one skin folder ({@code skins/<id>/}: {@code skin.json5} plus a Blockbench export) into
 * the skin's definition and its files in the pack, cleaning the export up on the way: texture
 * references rewritten to {@code skins:item/<id>/<file name>}, a missing particle texture filled
 * in, {@code .png.mcmeta} files carried along, and missing display transforms warned about (never
 * invented). Anything wrong rejects the whole skin with the file at fault and the reason.
 * Public only for {@link #NAMESPACE}.
 */
public final class SkinFolder {
	private SkinFolder() {}

	static final String DEFINITION = "skin.json5";
	/** The namespace of every skin's pack files and item model ({@code skins:<id>}). */
	public static final String NAMESPACE = "skins";

	private static final Jankson JANKSON = Jankson.builder().build();
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	/** What a resource path segment may hold; ids, model and texture names all become one. */
	private static final Pattern PATH_SEGMENT = Pattern.compile("[a-z0-9_.-]+");
	private static final Set<String> FIELDS = Set.of("name", "kind", "look", "description", "perks", "materials");
	/** Unknown Material names that are vanilla's item-name adjectives for a known one. */
	private static final Map<String, String> MATERIAL_HINTS = Map.of("wooden", "wood", "golden", "gold");
	private static final List<String> SPEAR_ICON_CONTEXTS = List.of("gui", "ground", "fixed", "on_shelf");
	private static final double SPEAR_SWAP_ANIMATION_SCALE = 1.95;

	/**
	 * A skin that loaded: what later tickets need of its definition, and its files in the pack by
	 * path. {@code materials} is the Materials it's limited to, in vanilla order; empty if it fits every one.
	 */
	record Loaded(String id, String name, Kind kind, Optional<List<Material>> materials, Optional<String> description, List<Perk> perks,
				Map<String, byte[]> files) {
		Fit fit() {
			return new Fit(kind, materials);
		}
	}

	static final class Rejected extends Exception {
		final Path file;

		Rejected(Path file, String reason) {
			super(reason, null, false, false);
			this.file = file;
		}
	}

	/** What a definition got as far as naming, for a rejected skin's line in {@code /skins list}. */
	static Optional<Kind> kindIfReadable(Path folder) {
		try {
			JsonElement kind = JANKSON.load(Files.readString(folder.resolve(DEFINITION))).get("kind");
			return kind instanceof JsonPrimitive p && p.getValue() instanceof String s ? Kind.byId(s) : Optional.empty();
		} catch (IOException | SyntaxError | RuntimeException e) {
			return Optional.empty();
		}
	}

	static Loaded load(Path folder, Consumer<ReloadReport.Warning> warnings) throws Rejected {
		String id = folder.getFileName().toString();
		Path definitionFile = folder.resolve(DEFINITION);
		if (!PATH_SEGMENT.matcher(id).matches()) {
			throw new Rejected(folder, "the folder name is the skin id and may only hold a-z, 0-9, _, - and .");
		}
		if (!Files.isRegularFile(definitionFile)) throw new Rejected(definitionFile, "the folder has no " + DEFINITION);

		JsonObject definition;
		try {
			definition = JANKSON.load(Files.readString(definitionFile, StandardCharsets.UTF_8));
		} catch (SyntaxError e) {
			throw new Rejected(definitionFile, "broken json5: " + e.getCompleteMessage());
		} catch (IOException | RuntimeException e) {
			throw new Rejected(definitionFile, "broken json5: " + e.getMessage());
		}
		if (definition == null) throw new Rejected(definitionFile, "broken json5: not an object");
		for (String key : definition.keySet()) {
			if (!FIELDS.contains(key)) {
				throw new Rejected(definitionFile, "unknown field \"" + key + "\" (allowed: " + String.join(", ", FIELDS.stream().sorted().toList()) + ")");
			}
		}
		String name = requiredString(definition, "name", definitionFile);
		String kindId = requiredString(definition, "kind", definitionFile);
		Kind kind = Kind.byId(kindId).orElseThrow(() -> new Rejected(definitionFile, "unknown kind \"" + kindId + "\" (one of: "
				+ Arrays.stream(Kind.values()).map(Kind::id).collect(Collectors.joining(", ")) + ")"));
		Optional<List<Material>> materials = definition.containsKey("materials")
				? Optional.of(materials(definition.get("materials"), kind, definitionFile)) : Optional.empty();
		Optional<String> description = definition.containsKey("description")
				? Optional.of(requiredString(definition, "description", definitionFile)) : Optional.empty();
		// A Look-only skin simply leaves "perks" out.
		List<Perk> perks = definition.containsKey("perks") ? PerkReader.read(definition.get("perks"), kind, definitionFile) : List.of();

		if (!(definition.get("look") instanceof JsonObject look)) throw new Rejected(definitionFile, lookShape(kind) + ", and look is missing or not an object");
		Set<String> expected = kind.isSpear() ? Set.of("icon", "in_hand") : Set.of("model");
		if (!look.keySet().equals(expected)) throw new Rejected(definitionFile, lookShape(kind) + ", not look: { " + String.join(", ", look.keySet()) + " }");

		Map<String, byte[]> files = new TreeMap<>();
		Map<String, String> modelIds = new LinkedHashMap<>();
		for (String slot : kind.isSpear() ? List.of("icon", "in_hand") : List.of("model")) {
			String file = requiredString(look, slot, definitionFile);
			modelIds.put(slot, model(folder, id, kind, slot, file, definitionFile, files, warnings));
		}
		files.put("assets/" + NAMESPACE + "/items/" + id + ".json", itemDefinition(kind, modelIds).getBytes(StandardCharsets.UTF_8));
		return new Loaded(id, name, kind, materials, description, perks, files);
	}

	/**
	 * A skin's {@code materials}: one or more of the Materials its Kind comes in, each once, returned
	 * in vanilla order. A mace or elytra skin takes none at all.
	 */
	private static List<Material> materials(JsonElement element, Kind kind, Path file) throws Rejected {
		List<Material> allowed = Material.of(kind);
		if (allowed.isEmpty()) throw new Rejected(file, "the " + kind.id() + " Kind has no materials, so it takes no \"materials\"");
		String names = allowed.stream().map(Material::id).collect(Collectors.joining(", "));
		if (!(element instanceof blue.endless.jankson.JsonArray list)) throw new Rejected(file, "\"materials\" must be a list of names (one or more of: " + names + ")");
		if (list.isEmpty()) throw new Rejected(file, "\"materials\" is empty: leave it out to fit every material, or list some of: " + names);
		Set<Material> listed = EnumSet.noneOf(Material.class);
		for (JsonElement entry : list) {
			if (!(entry instanceof JsonPrimitive p && p.getValue() instanceof String name)) {
				throw new Rejected(file, "\"materials\" must be a list of names (one or more of: " + names + ")");
			}
			Optional<Material> material = Material.byId(name);
			if (material.isEmpty()) {
				String hint = MATERIAL_HINTS.containsKey(name) ? "did you mean \"" + MATERIAL_HINTS.get(name) + "\"?" : "one of: " + names;
				throw new Rejected(file, "unknown material \"" + name + "\" (" + hint + ")");
			}
			if (!allowed.contains(material.get())) throw new Rejected(file, "\"" + name + "\" isn't a material of the " + kind.id() + " Kind (it comes in: " + names + ")");
			if (!listed.add(material.get())) throw new Rejected(file, "\"" + name + "\" is listed twice in \"materials\"");
		}
		return allowed.stream().filter(listed::contains).toList();
	}

	private static String lookShape(Kind kind) {
		return kind.isSpear() ? "a spear takes look: { icon, in_hand }" : "a " + kind.id() + " takes look: { model }";
	}

	private static String requiredString(JsonObject object, String key, Path file) throws Rejected {
		if (!object.containsKey(key)) throw new Rejected(file, "missing field \"" + key + "\"");
		if (object.get(key) instanceof JsonPrimitive p && p.getValue() instanceof String s && !s.isBlank()) return s;
		throw new Rejected(file, "field \"" + key + "\" must be a non-empty string");
	}

	/** Cleans up one model of the Look and adds it and its textures to {@code files}; returns its model id. */
	private static String model(Path folder, String id, Kind kind, String slot, String fileName, Path definitionFile,
								Map<String, byte[]> files, Consumer<ReloadReport.Warning> warnings) throws Rejected {
		if (!fileName.endsWith(".json") || !PATH_SEGMENT.matcher(fileName).matches()) {
			throw new Rejected(definitionFile, "look." + slot + " must name a .json file in the skin folder (a-z, 0-9, _, - and .), not \"" + fileName + "\"");
		}
		Path modelFile = folder.resolve(fileName);
		if (!Files.isRegularFile(modelFile)) throw new Rejected(definitionFile, "model file \"" + fileName + "\" (look." + slot + ") is not in the skin folder");
		com.google.gson.JsonObject model;
		try {
			model = JsonParser.parseString(Files.readString(modelFile, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (IOException | JsonParseException | IllegalStateException e) {
			throw new Rejected(modelFile, "not a model: " + e.getMessage());
		}

		if (model.has("textures")) {
			if (!model.get("textures").isJsonObject()) throw new Rejected(modelFile, "\"textures\" is not an object");
			com.google.gson.JsonObject textures = model.getAsJsonObject("textures");
			String first = null;
			for (String key : List.copyOf(textures.keySet())) {
				var value = textures.get(key);
				// 26.1+ also allows { sprite, force_translucent }; the sprite is what gets rewritten.
				com.google.gson.JsonObject holder = textures;
				String field = key;
				if (value.isJsonObject() && value.getAsJsonObject().has("sprite")) {
					holder = value.getAsJsonObject();
					field = "sprite";
					value = holder.get("sprite");
				}
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
					throw new Rejected(modelFile, "texture \"" + key + "\" is neither a name nor { sprite }");
				}
				String ref = value.getAsString();
				String rewritten = ref.startsWith("#") ? ref : texture(folder, id, key, ref, modelFile, files);
				holder.addProperty(field, rewritten);
				if (first == null) first = rewritten;
			}
			if (!textures.has("particle") && first != null) textures.addProperty("particle", first);
		}

		if (!model.has("parent")) warnAboutDisplay(id, kind, slot, model, modelFile, warnings);

		String base = fileName.substring(0, fileName.length() - ".json".length());
		files.put("assets/" + NAMESPACE + "/models/item/" + id + "/" + fileName, GSON.toJson(model).getBytes(StandardCharsets.UTF_8));
		return NAMESPACE + ":item/" + id + "/" + base;
	}

	/** Matches a texture reference to a .png in the folder by file name, copies it (and its .mcmeta), returns the new reference. */
	private static String texture(Path folder, String id, String key, String ref, Path modelFile, Map<String, byte[]> files) throws Rejected {
		String name = ref.substring(ref.lastIndexOf(':') + 1);
		name = name.substring(name.lastIndexOf('/') + 1);
		if (name.endsWith(".png")) name = name.substring(0, name.length() - ".png".length());
		Path png = folder.resolve(name + ".png");
		if (name.isEmpty() || !PATH_SEGMENT.matcher(name).matches() || !Files.isRegularFile(png)) {
			throw new Rejected(modelFile, "texture \"" + ref + "\" (\"" + key + "\") has no matching " + name + ".png in the skin folder"
					+ " (texture file names may only hold a-z, 0-9, _, - and .)");
		}
		String path = "assets/" + NAMESPACE + "/textures/item/" + id + "/" + name + ".png";
		try {
			files.put(path, Files.readAllBytes(png));
			Path mcmeta = folder.resolve(name + ".png.mcmeta");
			if (Files.isRegularFile(mcmeta)) files.put(path + ".mcmeta", Files.readAllBytes(mcmeta));
		} catch (IOException e) {
			throw new Rejected(png, "unreadable: " + e.getMessage());
		}
		return NAMESPACE + ":item/" + id + "/" + name;
	}

	/**
	 * Display transforms the client would otherwise draw untransformed. A model with a parent may
	 * inherit them, so only parentless models (every Blockbench export) are checked.
	 */
	private static void warnAboutDisplay(String id, Kind kind, String slot, com.google.gson.JsonObject model, Path modelFile,
										Consumer<ReloadReport.Warning> warnings) {
		if (slot.equals("in_hand")) return;   // the spear's icon model covers gui, ground, fixed and on_shelf
		Set<String> present = model.has("display") && model.get("display").isJsonObject() ? model.getAsJsonObject("display").keySet() : Set.of();
		List<String> prominent = new ArrayList<>(List.of("gui"));
		if (kind == Kind.HELMET) prominent.add("head");
		for (String context : prominent) {
			if (!present.contains(context)) {
				warnings.accept(new ReloadReport.Warning(id, modelFile, "no \"" + context + "\" display transform: it will look wrong"
						+ (context.equals("gui") ? " in inventories" : " on players' heads"), true));
			}
		}
		for (String context : List.of("fixed", "on_shelf")) {
			if (!present.contains(context)) {
				warnings.accept(new ReloadReport.Warning(id, modelFile, "no \"" + context + "\" display transform"
						+ (context.equals("fixed") ? " (item frames)" : " (shelves)"), false));
			}
		}
	}

	/** The item model definition at {@code assets/skins/items/<id>.json}; a spear's switches on display context, as vanilla's does. */
	private static String itemDefinition(Kind kind, Map<String, String> models) {
		var root = new com.google.gson.JsonObject();
		if (kind.isSpear()) {
			var select = new com.google.gson.JsonObject();
			select.addProperty("type", "minecraft:select");
			select.addProperty("property", "minecraft:display_context");
			var iconCase = new com.google.gson.JsonObject();
			JsonArray when = new JsonArray();
			SPEAR_ICON_CONTEXTS.forEach(when::add);
			iconCase.add("when", when);
			iconCase.add("model", plainModel(models.get("icon")));
			JsonArray cases = new JsonArray();
			cases.add(iconCase);
			select.add("cases", cases);
			select.add("fallback", plainModel(models.get("in_hand")));
			root.add("model", select);
			root.addProperty("swap_animation_scale", SPEAR_SWAP_ANIMATION_SCALE);
		} else {
			root.add("model", plainModel(models.get("model")));
		}
		return GSON.toJson(root);
	}

	private static com.google.gson.JsonObject plainModel(String model) {
		var object = new com.google.gson.JsonObject();
		object.addProperty("type", "minecraft:model");
		object.addProperty("model", model);
		return object;
	}
}
