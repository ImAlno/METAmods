package se.metacraft.skins;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

/**
 * The adapter that has the server touch stale items ({@link Skins#revertStale}) soon after they go
 * stale, wherever it can reach them, so their markers are cleared and every client that shows
 * them is sent the plain item:
 * <ul>
 *   <li>at the end of the tick after any change to who owns or applies what, or a reload: every
 *       loaded entity, so online players (inventory, ender chest, open menu), dropped items, item
 *       frames, armour stands, mobs and minecarts;</li>
 *   <li>a menu a player opens (a chest, a shulker box, a barrel), the tick it opens;</li>
 *   <li>an entity entering the world (a chunk loading, an item dropped);</li>
 *   <li>a player joining ({@link Skins#joined}, wired in {@link MetacraftSkins}).</li>
 * </ul>
 * Whatever it can't reach (a chest nobody opens) stays plain all the same: every view and every
 * Perk lookup checks liveness first.
 */
final class StaleReverts {
	private static @Nullable Skins swept;
	private static long sweptAt;
	private static final Map<ServerPlayer, AbstractContainerMenu> MENUS = new WeakHashMap<>();

	private StaleReverts() {}

	static void init(Supplier<@Nullable Skins> skins) {
		ServerTickEvents.END_SERVER_TICK.register(server -> tick(server, skins.get()));
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			Skins current = skins.get();
			if (current != null) current.revertStale(entity);
		});
	}

	private static void tick(MinecraftServer server, @Nullable Skins skins) {
		if (skins == null) return;
		long changes = skins.changes();
		boolean changed = skins != swept || changes != sweptAt;
		if (changed) {
			swept = skins;
			sweptAt = changes;
			for (ServerLevel level : server.getAllLevels()) {
				for (Entity entity : level.getAllEntities()) skins.revertStale(entity);
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			AbstractContainerMenu menu = player.containerMenu;
			if (MENUS.put(player, menu) == menu || changed) continue;
			for (Slot slot : menu.slots) skins.revertStale(slot.getItem());
		}
	}
}
