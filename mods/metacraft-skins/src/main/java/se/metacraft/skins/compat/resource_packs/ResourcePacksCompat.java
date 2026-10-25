package se.metacraft.skins.compat.resource_packs;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import se.metacraft.skins.Skins;
import se.metacraft.skins.SkinsPack;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import se.metacraft.resource_packs.EarlyPackPacketsCallback;
import se.metacraft.resource_packs.ResourcePackConfig;
import se.metacraft.resource_packs.ServePackCallback;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Serves the skins pack from metacraft-resource-packs' pack server and sends it to joining
 * players with the other packs. The only class that names that mod's types; reached only when
 * {@link se.metacraft.skins.pack.PackDelivery#SEPARATE_PACK} says it is installed.
 */
public final class ResourcePacksCompat {
	private ResourcePacksCompat() {}

	public static void init(Supplier<Skins> skins) {
		ServePackCallback.EVENT.register((path, verified) -> {
			Skins current = skins.get();
			return current == null ? Optional.empty() : current.servePack(path);
		});
		EarlyPackPacketsCallback.EVENT.register((server, profile, packets) -> {
			Skins current = skins.get();
			if (current != null) current.pack().ifPresent(pack -> packets.accept(push(pack)));
		});
	}

	/** The push for one build; required exactly when the server's packs are. */
	public static ClientboundResourcePackPushPacket push(SkinsPack pack) {
		return new ClientboundResourcePackPushPacket(Skins.PACK_ID, ResourcePackConfig.getConfig().getBaseUrl() + "/" + pack.path(),
				pack.sha1(), PolymerResourcePackUtils.isRequired(), Optional.empty());
	}
}
