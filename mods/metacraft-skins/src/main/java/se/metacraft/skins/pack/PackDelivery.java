package se.metacraft.skins.pack;

import eu.pb4.polymer.autohost.api.ResourcePackDataProvider;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.impl.PolymerResourcePackMod;
import se.metacraft.skins.MetacraftSkins;
import se.metacraft.skins.Skins;
import se.metacraft.skins.SkinsPack;
import se.metacraft.skins.compat.resource_packs.ResourcePacksCompat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;

/**
 * Gets the skins pack to players. With metacraft-resource-packs installed it is a pack of its own,
 * served by that mod and sent at join ({@link ResourcePacksCompat}), so adding a skin never
 * changes the main pack's hash. Without it the same files go into the Polymer pack.
 */
public final class PackDelivery {
	private PackDelivery() {}

	/** Is the pack served by metacraft-resource-packs? Otherwise its files go into the Polymer pack. */
	public static final boolean SEPARATE_PACK = FabricLoader.getInstance().isModLoaded("metacraft-resource-packs");

	public static void init(Supplier<Skins> skins) {
		if (SEPARATE_PACK) {
			ResourcePacksCompat.init(skins);
			return;
		}
		PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> {
			Skins current = skins.get();
			if (current == null) return;
			current.pack().ifPresent(pack -> {
				try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(pack.zip()))) {
					for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
						if (!entry.getName().equals("pack.mcmeta")) builder.addData(entry.getName(), in.readAllBytes());
					}
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		});
	}

	/**
	 * Sends the newest pack to everyone online, after {@code /itemskins reload} changed it; returns how
	 * many players it went to. Without metacraft-resource-packs the Polymer pack is rebuilt first.
	 */
	public static int pushToAll(MinecraftServer server, Skins skins) {
		List<ServerPlayer> players = List.copyOf(server.getPlayerList().getPlayers());
		if (SEPARATE_PACK) {
			Optional<SkinsPack> pack = skins.pack();
			if (pack.isEmpty()) return 0;
			for (ServerPlayer player : players) player.connection.send(ResourcePacksCompat.push(pack.get()));
			return players.size();
		}
		if (PolymerResourcePackMod.alreadyGeneration) {
			MetacraftSkins.LOGGER.warn("[{}] the Polymer pack is being built already; run /itemskins reload again once it is done", MetacraftSkins.MOD_ID);
			return 0;
		}
		PolymerResourcePackMod.generateAndCall(server, false, message -> MetacraftSkins.LOGGER.info("[{}] {}", MetacraftSkins.MOD_ID, message.getString()),
				result -> server.execute(() -> {
					ResourcePackDataProvider provider = ResourcePackDataProvider.getActive();
					if (provider == null) return;
					for (ServerPlayer player : server.getPlayerList().getPlayers()) {
						var context = player.connection.getPacketContext();
						if (!provider.isReady(context)) continue;
						for (var info : provider.getProperties(context)) {
							player.connection.send(new ClientboundResourcePackPushPacket(info.id(), info.url(), info.hash(),
									PolymerResourcePackUtils.isRequired(), Optional.empty()));
						}
					}
				}));
		return players.size();
	}
}
