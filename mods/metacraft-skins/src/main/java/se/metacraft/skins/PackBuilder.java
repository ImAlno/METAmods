package se.metacraft.skins;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.util.InclusiveRange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips the loaded skins' files into the skins pack, in memory. Entries go in sorted order with
 * one fixed time, so the same skins always give the same bytes and the same SHA-1 (ovvar's
 * ComboPack does the same).
 */
final class PackBuilder {
	private PackBuilder() {}

	/** The earliest time a zip entry can hold; no clock, and no time zone, gets into the bytes. */
	private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

	/** The shared Skin Token model: a placeholder (vanilla's paper) until the art pass draws one. */
	private static final String TOKEN_MODEL = "{ \"model\": { \"type\": \"minecraft:model\", \"model\": \"minecraft:item/paper\" } }";

	static SkinsPack build(Collection<SkinFolder.Loaded> skins) {
		Map<String, byte[]> files = new TreeMap<>();
		files.put("pack.mcmeta", mcmeta().getBytes(StandardCharsets.UTF_8));
		files.put("assets/" + SkinToken.MODEL.getNamespace() + "/items/" + SkinToken.MODEL.getPath() + ".json", TOKEN_MODEL.getBytes(StandardCharsets.UTF_8));
		for (SkinFolder.Loaded skin : skins) files.putAll(skin.files());

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (var file : files.entrySet()) {
				ZipEntry entry = new ZipEntry(file.getKey());
				entry.setTimeLocal(ENTRY_TIME);
				zip.putNextEntry(entry);
				zip.write(file.getValue());
				zip.closeEntry();
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		byte[] data = bytes.toByteArray();
		try {
			return new SkinsPack(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data)), data);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String mcmeta() {
		var format = SharedConstants.getCurrentVersion().packVersion(PackType.CLIENT_RESOURCES);
		var section = new PackMetadataSection(Component.literal("METAcraft skins"), new InclusiveRange<>(format));
		JsonObject root = new JsonObject();
		root.add(PackMetadataSection.CLIENT_TYPE.name(), PackMetadataSection.CLIENT_TYPE.codec().encodeStart(JsonOps.INSTANCE, section).getOrThrow());
		return new GsonBuilder().setPrettyPrinting().create().toJson(root);
	}
}
