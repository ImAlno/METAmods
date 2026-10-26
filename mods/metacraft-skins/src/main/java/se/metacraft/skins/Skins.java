package se.metacraft.skins;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.InventoryCarrier;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ContainerComponent;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import se.metacraft.config.container.ConfigContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * The skins domain, behind one small interface: commands, the pack server and event listeners
 * are thin adapters that call it. Everything it reads and writes lives under one server root
 * ({@link #at}), so each game test can give it a temporary directory:
 * <ul>
 *   <li>{@code config/metacraft-skins/skins/<id>/}: one folder per skin, {@code skin.json5} plus its Blockbench export;</li>
 *   <li>{@code config/metacraft-skins.json5}: the server-wide settings;</li>
 *   <li>{@code skins-data/players/<uuid>.json}: who owns what, one file per player ({@link OwnershipStore}).</li>
 * </ul>
 * The world folder (its marker stamps applications, {@link WorldMarker}) is given separately.
 * Skins are read only on {@link #reload()}: at server start and on {@code /skins reload}. Player
 * files are read the first time each player is needed and written on every change.
 *
 * <p>A Skinned Item stores only its {@link SkinMarker}. Whether it shows a Look is decided every
 * time it's sent to a viewer ({@link #liveSkin}, {@link #clientView}), so an item that went stale
 * (its skin moved, removed, overwritten, revoked, changed Kind, or from another world) or no longer
 * fits its skin (a netherite upgrade past the skin's Materials) is plain at
 * once wherever it is. A live item is <b>working</b> only while it's with its Owner (in their
 * inventory, worn, in their offhand, on their cursor or in their ender chest); anywhere else it's
 * Away, drawn plain, with no Perks. The server also clears a stale marker when it next touches the item
 * ({@link #revertStale}, driven by {@link StaleReverts}); a Dormant skin's markers are kept, so its
 * items come back when its definition does.
 *
 * <p>Perks go through liveness too, and work only for the Owner: {@link #perks} reads them from the
 * live skin's definition each time, and {@link #updateLastingPerks} (every tick, for every player) keeps held and worn Perks in
 * step with what each player holds and wears. Event Perks fire as the server reports each event
 * ({@link #hit}, {@link #killed}, {@link #brokeBlock}, {@link #hurt}), read from the live skin at that
 * moment. Tokens, menus and Skinned Items list them with {@link PerkLines}.
 */
public final class Skins {
	/** The skins pack's id on clients: the same for every build, so a push replaces the one before. */
	public static final UUID PACK_ID = UUID.nameUUIDFromBytes((MetacraftSkins.MOD_ID + ":skins").getBytes(StandardCharsets.UTF_8));
	/** How many builds stay downloadable: a player pushed one still gets it if a newer build lands before their download starts. */
	private static final int KEEP_BUILDS = 3;

	private final Path root;
	private final Path skinsFolder;
	private final ConfigContainer<SkinsSettings> settings;
	private final OwnershipStore ownership;
	private final Path world;
	private @Nullable UUID worldStamp;
	/** Bumped by every saved change and every reload: anything may have gone stale since. */
	private final AtomicLong changes = new AtomicLong();

	/** Every skin folder by id, loaded or Dormant; replaced whole by each reload. */
	private volatile Map<String, Entry> skins = Map.of();
	/** Newest first; guarded by itself (served from the pack server's threads). */
	private final Deque<SkinsPack> builds = new ArrayDeque<>();
	/** What held and worn Perks this keeps up on each player. */
	private final LastingPerks lasting = new LastingPerks();
	/** Fires hit, kill, block break and hurt Perks, and keeps each player's cooldowns. */
	private final EventPerks events = new EventPerks();

	private record Entry(String id, Optional<SkinFolder.Loaded> loaded, Optional<Kind> kind, Optional<String> dormantReason) {}

	private Skins(Path root, Path world) {
		this.root = root;
		this.world = world;
		this.ownership = new OwnershipStore(root.resolve("skins-data").resolve("players"));
		this.skinsFolder = root.resolve("config").resolve(MetacraftSkins.MOD_ID).resolve("skins");
		this.settings = SkinsSettings.container(root.resolve("config").resolve(MetacraftSkins.MOD_ID + ".json5"));
	}

	/**
	 * The skins of the server whose root (the folder holding {@code config/} and {@code skins-data/})
	 * this is, playing the world in {@code world}. Reads nothing until it's needed.
	 */
	public static Skins at(Path root, Path world) {
		return new Skins(root, world);
	}

	/** {@link #at(Path, Path)} with the world in {@code <root>/world}, the dedicated server's default. */
	public static Skins at(Path root) {
		return at(root, root.resolve("world"));
	}

	public Path skinsFolder() {
		return skinsFolder;
	}

	// ---- actions

	/**
	 * Re-reads every skin folder and the settings file, then rebuilds the pack. A broken skin is
	 * set aside as Dormant with its reason and never stops the others; each rejection and warning
	 * is logged with its file. Pushing a changed pack to players is the caller's job.
	 */
	public synchronized ReloadReport reload() {
		try {
			Files.createDirectories(root.resolve("config"));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		settings.reload();
		settings.get();

		Map<String, Entry> next = new TreeMap<>();
		List<String> loaded = new ArrayList<>();
		List<ReloadReport.Rejection> rejections = new ArrayList<>();
		List<ReloadReport.Warning> warnings = new ArrayList<>();
		for (Path folder : folders()) {
			String id = folder.getFileName().toString();
			try {
				SkinFolder.Loaded skin = SkinFolder.load(folder, warnings::add);
				next.put(id, new Entry(id, Optional.of(skin), Optional.of(skin.kind()), Optional.empty()));
				loaded.add(id);
			} catch (SkinFolder.Rejected e) {
				next.put(id, new Entry(id, Optional.empty(), SkinFolder.kindIfReadable(folder), Optional.of(e.getMessage())));
				rejections.add(new ReloadReport.Rejection(id, e.file, e.getMessage()));
			} catch (RuntimeException e) {
				// A bug of ours must not take the server down either: the skin goes Dormant all the same.
				MetacraftSkins.LOGGER.error("[{}] unexpected error loading skin {}", MetacraftSkins.MOD_ID, id, e);
				next.put(id, new Entry(id, Optional.empty(), Optional.empty(), Optional.of("internal error: " + e)));
				rejections.add(new ReloadReport.Rejection(id, folder, "internal error: " + e));
			}
		}
		for (var r : rejections) MetacraftSkins.LOGGER.warn("[{}] skin {} is Dormant: {}: {}", MetacraftSkins.MOD_ID, r.skinId(), r.file(), r.reason());
		for (var w : warnings) {
			if (w.prominent()) MetacraftSkins.LOGGER.warn("[{}] !! skin {}: {}: {}", MetacraftSkins.MOD_ID, w.skinId(), w.file(), w.message());
			else MetacraftSkins.LOGGER.info("[{}] skin {}: {}: {}", MetacraftSkins.MOD_ID, w.skinId(), w.file(), w.message());
		}

		SkinsPack pack = PackBuilder.build(next.values().stream().flatMap(e -> e.loaded().stream()).toList());
		boolean changed;
		synchronized (builds) {
			SkinsPack previous = builds.peekFirst();
			changed = previous == null || !previous.sha1().equals(pack.sha1());
			builds.removeIf(b -> b.sha1().equals(pack.sha1()));
			builds.addFirst(pack);
			while (builds.size() > KEEP_BUILDS) builds.removeLast();
		}
		skins = next;
		dropKindChangedApplications();
		changes.incrementAndGet();
		MetacraftSkins.LOGGER.info("[{}] {} skin(s) loaded, {} Dormant; pack {}{}", MetacraftSkins.MOD_ID, loaded.size(), rejections.size(),
				pack.sha1(), changed ? " (changed)" : "");
		return new ReloadReport(List.copyOf(loaded), List.copyOf(rejections), List.copyOf(warnings), pack.sha1(), changed);
	}

	/**
	 * Makes the player own the skin, as a Claim would, whether or not they're online, and writes
	 * their file before returning. Refused if they already own it, the skin is unknown or Dormant,
	 * or their file is damaged. Telling an online player is the caller's job.
	 */
	public synchronized OwnershipChange grant(NameAndId profile, String skinId) {
		Entry skin = skins.get(skinId);
		if (skin == null) return OwnershipChange.UNKNOWN_SKIN;
		if (skin.dormantReason().isPresent()) return OwnershipChange.DORMANT_SKIN;
		return changing(profile.id(), record -> record.skins().containsKey(skinId) ? OwnershipChange.ALREADY_OWNED
				: save(record.with(skinId, new OwnershipStore.Owned(Instant.now().truncatedTo(ChronoUnit.SECONDS), Optional.empty())).named(profile.name())));
	}

	/**
	 * Deletes the player's record of the skin, online or not, Dormant skins included, and writes
	 * their file before returning. An item still carrying it no longer matches any record, so it's
	 * stale. Refused if they don't own it or their file is damaged. Telling an online player is the caller's job.
	 */
	public synchronized OwnershipChange revoke(NameAndId profile, String skinId) {
		return changing(profile.id(), record -> !record.skins().containsKey(skinId) ? OwnershipChange.NOT_OWNED
				: save(record.without(skinId).named(profile.name())));
	}

	/** A change to the player's record, made by {@code change}; refused, the file left alone, if it's damaged. */
	private OwnershipChange changing(UUID player, Function<OwnershipStore.PlayerRecord, OwnershipChange> change) {
		return switch (ownership.read(player)) {
			case OwnershipStore.Damaged d -> OwnershipChange.DAMAGED_FILE;
			case OwnershipStore.Readable(var record) -> change.apply(record);
		};
	}

	/**
	 * Puts the player's skin on the item at {@code where}, writing their file before the item
	 * changes. The new application supersedes every earlier one, so any other item carrying the
	 * skin is plain from now on. A skin already on the item (theirs or another player's, live or
	 * Dormant) is replaced, and becomes Unapplied for its Owner, who keeps owning it; if that Owner's
	 * file is damaged, it's left alone (and logged). Refused, changing nothing,
	 * if the skin is unknown or Dormant, they don't own it, nothing that {@linkplain #fits fits} is there
	 * (Material included), it already carries this application, or their file is damaged or can't be saved.
	 */
	public synchronized ApplyResult apply(ServerPlayer player, String skinId, ItemLocation where) {
		Entry skin = skins.get(skinId);
		if (skin == null) return ApplyResult.refused(ApplyResult.Outcome.UNKNOWN_SKIN);
		if (skin.loaded().isEmpty()) return ApplyResult.refused(ApplyResult.Outcome.DORMANT_SKIN);
		SkinFolder.Loaded loaded = skin.loaded().get();
		Kind kind = loaded.kind();
		if (!(ownership.read(player.getUUID()) instanceof OwnershipStore.Readable(var record))) {
			return ApplyResult.refused(ApplyResult.Outcome.DAMAGED_FILE);
		}
		OwnershipStore.Owned owned = record.skins().get(skinId);
		if (owned == null) return ApplyResult.refused(ApplyResult.Outcome.NOT_OWNED);
		ItemStack item = where.in(player.getInventory());
		if (item.isEmpty()) return ApplyResult.refused(ApplyResult.Outcome.NO_ITEM);
		if (!fits(loaded, item)) return ApplyResult.refused(ApplyResult.Outcome.DOESNT_FIT);
		Optional<Displaced> current = displaced(item);
		if (current.filter(c -> c.is(player.getUUID(), skinId)).isPresent()) return ApplyResult.refused(ApplyResult.Outcome.ALREADY_APPLIED);

		UUID application = UUID.randomUUID();
		OwnershipStore.PlayerRecord next = record.with(skinId, new OwnershipStore.Owned(owned.claimed(),
				Optional.of(new OwnershipStore.Application(application, worldStamp(), Optional.of(kind))))).named(player.getGameProfile().name());
		// Replacing one of their own other skins: it's Unapplied in the same save.
		boolean replacingTheirOwn = current.filter(c -> c.ownedBy(player.getUUID())).isPresent();
		if (replacingTheirOwn) next = unapplied(next, current.get().marker().skin());
		if (save(next) != OwnershipChange.DONE) return ApplyResult.refused(ApplyResult.Outcome.SAVE_FAILED);
		if (current.isPresent() && !replacingTheirOwn) unapplyReplaced(current.get().marker());
		item.set(SkinMarker.TYPE, new SkinMarker(skinId, player.getUUID(), application));
		return new ApplyResult(ApplyResult.Outcome.APPLIED, current.map(Displaced::name));
	}

	/** The skin an apply would take off an item, named as the player is told of it (by its id while Dormant). */
	private record Displaced(SkinMarker marker, String name) {
		boolean ownedBy(UUID player) {
			return marker.owner().equals(player);
		}

		/** This very skin of theirs: applying it here again would change nothing. */
		boolean is(UUID player, String skinId) {
			return ownedBy(player) && marker.skin().equals(skinId);
		}
	}

	/**
	 * The skin on this item that an apply would replace: its Owner's current application of it,
	 * whether it's live or Dormant (its folder broken or gone). With the Owner's file damaged there's
	 * no telling, so it counts. A stale marker (moved, removed, overwritten, revoked, another
	 * world's) displaces nothing.
	 */
	private Optional<Displaced> displaced(ItemStack item) {
		SkinMarker marker = item.get(SkinMarker.TYPE);
		if (marker == null) return Optional.empty();
		if (ownership.read(marker.owner()) instanceof OwnershipStore.Readable(var record) && !isCurrent(record, marker)) return Optional.empty();
		Entry skin = skins.get(marker.skin());
		String name = skin == null ? marker.skin() : skin.loaded().map(SkinFolder.Loaded::name).orElse(marker.skin());
		return Optional.of(new Displaced(marker, name));
	}

	/** Is this marker its Owner's current application of the skin, in this world? */
	private boolean isCurrent(OwnershipStore.PlayerRecord owner, SkinMarker marker) {
		OwnershipStore.Owned owned = owner.skins().get(marker.skin());
		return owned != null && appliedHere(marker.skin(), owned).filter(a -> a.id().equals(marker.application())).isPresent();
	}

	/** Another player's skin this apply replaced: Unapplied in their file, if it's still that application. */
	private void unapplyReplaced(SkinMarker replaced) {
		if (!(ownership.read(replaced.owner()) instanceof OwnershipStore.Readable(var record))) {
			MetacraftSkins.LOGGER.warn("[{}] {}'s file is damaged, so their replaced skin {} still reads as applied there",
					MetacraftSkins.MOD_ID, replaced.owner(), replaced.skin());
			return;
		}
		if (isCurrent(record, replaced)) save(unapplied(record, replaced.skin()));
	}

	private static OwnershipStore.PlayerRecord unapplied(OwnershipStore.PlayerRecord record, String skinId) {
		OwnershipStore.Owned owned = record.skins().get(skinId);
		return record.with(skinId, new OwnershipStore.Owned(owned.claimed(), Optional.empty()));
	}

	/**
	 * Takes the Owner's skin off its item, wherever that item is, without anyone holding it: the
	 * skin is Unapplied and the item it was on is plain from now on. Only the Owner can; refused if
	 * they don't own it, it's on no item in this world, or their file is damaged or can't be saved.
	 */
	public synchronized OwnershipChange remove(NameAndId profile, String skinId) {
		return changing(profile.id(), record -> {
			OwnershipStore.Owned owned = record.skins().get(skinId);
			if (owned == null) return OwnershipChange.NOT_OWNED;
			if (appliedHere(skinId, owned).isEmpty()) return OwnershipChange.NOT_APPLIED;
			return save(unapplied(record, skinId).named(profile.name()));
		});
	}

	/**
	 * A player joined: their file (if they have one) keeps the name they have now, which a
	 * Skinned Item of theirs shows as its Owner's while they're offline.
	 */
	public synchronized void seen(NameAndId profile) {
		if (!(ownership.read(profile.id()) instanceof OwnershipStore.Readable(var record))) return;
		if (record.skins().isEmpty() && record.name().isEmpty()) return;   // no file, and none needed
		if (!record.name().equals(Optional.of(profile.name()))) save(record.named(profile.name()));
	}

	// ---- Skin Tokens

	/**
	 * Claims the skin a token the player carries is for: the player becomes its Owner, their file
	 * is written, and only then is one token from {@code token}'s stack used up. Refused, keeping the
	 * token and changing nothing, if the stack isn't a token on the player, the skin is unknown or
	 * Dormant, they already own it (the token is theirs to trade), or their file is damaged or can't
	 * be saved. Telling the player, and re-sending their inventory, is the caller's job.
	 */
	public synchronized OwnershipChange claim(ServerPlayer player, ItemStack token) {
		Optional<String> id = SkinToken.skinOf(token);
		if (id.isEmpty() || token.isEmpty() || !carries(player, token)) return OwnershipChange.NO_TOKEN;
		OwnershipChange result = grant(player.nameAndId(), id.get());
		if (result == OwnershipChange.DONE) token.shrink(1);
		return result;
	}

	/** Is this very stack in the player's inventory (a copy of it, or a stack they dropped, isn't)? */
	private static boolean carries(ServerPlayer player, ItemStack stack) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			if (player.getInventory().getItem(slot) == stack) return true;
		}
		return false;
	}

	/**
	 * Gives each player {@code count} tokens for the skin (an admin's {@code /skins give}); what
	 * doesn't fit in an inventory drops at that player's feet, for them only to pick up. Refused,
	 * giving nothing, for an unknown or Dormant skin.
	 */
	public OwnershipChange giveTokens(List<ServerPlayer> players, String skinId, int count) {
		Entry skin = skins.get(skinId);
		if (skin == null) return OwnershipChange.UNKNOWN_SKIN;
		if (skin.loaded().isEmpty()) return OwnershipChange.DORMANT_SKIN;
		for (ServerPlayer player : players) {
			ItemStack tokens = SkinToken.of(skinId, count);
			boolean added = player.getInventory().add(tokens);
			if (added && tokens.isEmpty()) continue;
			// As vanilla's /give drops what doesn't fit.
			ItemEntity drop = player.createItemStackToDrop(tokens, false, false);
			if (drop == null) continue;
			drop.setNoPickUpDelay();
			drop.setTarget(player.getUUID());
			drop.level().addFreshEntity(drop);
		}
		return OwnershipChange.DONE;
	}

	/**
	 * What {@code viewer} is sent of a token: Polymer's client copy ({@code client}) named
	 * "Skin Token: <Skin>" (gold, no glint), with the lore "Fits any <Kind>", the Perk lines, the description, a
	 * blank line and "Right-click to claim", or, for a viewer who owns the skin, "✔ You already own
	 * this skin — trade it away". A token for an unknown or Dormant skin reads "Unknown skin token: <id>".
	 */
	public ItemStack tokenView(ItemStack token, ItemStack client, @Nullable UUID viewer) {
		String id = SkinToken.skinOf(token).orElse("");
		Entry skin = skins.get(id);
		client.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
		if (skin == null || skin.loaded().isEmpty()) {
			client.set(DataComponents.ITEM_NAME, plain("Unknown skin token: " + id, ChatFormatting.GRAY));
			client.set(DataComponents.LORE, new ItemLore(List.of(plain("Its skin isn't on this server.", ChatFormatting.DARK_GRAY))));
			return client;
		}
		SkinFolder.Loaded loaded = skin.loaded().get();
		boolean owned = viewer != null && ownership.read(viewer) instanceof OwnershipStore.Readable(var record) && record.skins().containsKey(id);
		List<Component> lines = new ArrayList<>();
		lines.add(plain("Fits any " + loaded.kind().id(), ChatFormatting.GRAY));
		lines.addAll(PerkLines.of(inEffect(loaded.perks())));
		loaded.description().ifPresent(d -> lines.add(plain(d, ChatFormatting.GRAY)));
		lines.add(Component.empty());
		lines.add(plain(owned ? "✔ You already own this skin — trade it away" : "Right-click to claim", ChatFormatting.GRAY));
		client.set(DataComponents.ITEM_NAME, plain("Skin Token: " + loaded.name(), ChatFormatting.GOLD));
		client.set(DataComponents.LORE, new ItemLore(lines));
		return client;
	}

	// ---- Skinned Items

	/**
	 * The skin this item carries, if its application is live: the Owner's record still owns the
	 * skin, with this very application, stamped by this world; the skin isn't Dormant; and the
	 * item still {@linkplain #fits fits} it. Anything else (moved, removed, overwritten, revoked, another
	 * world's, a Kind change, a Material the skin no longer allows) is a plain item. Safe from any thread
	 * (Polymer asks while encoding packets).
	 */
	public synchronized Optional<LiveSkin> liveSkin(ItemStack stack) {
		SkinMarker marker = stack.get(SkinMarker.TYPE);
		if (marker == null || standing(stack, marker) != Standing.LIVE) return Optional.empty();
		SkinFolder.Loaded loaded = skins.get(marker.skin()).loaded().orElseThrow();
		Optional<String> ownerName = ownership.read(marker.owner()) instanceof OwnershipStore.Readable(var record) ? record.name() : Optional.empty();
		return Optional.of(new LiveSkin(loaded.id(), loaded.name(), loaded.kind(), loaded.description(), inEffect(loaded.perks()),
				marker.owner(), ownerName.orElse(marker.owner().toString())));
	}

	/** What a marker on an item amounts to now. */
	private enum Standing {
		/** The skin shows, and its Perks work. */
		LIVE,
		/** Plain for now, but the marker stays: the skin is Dormant, or its Owner's file is damaged; either can come back. */
		ON_HOLD,
		/** Plain for good: moved, removed, overwritten, revoked, another world's, or a Kind change. */
		STALE,
		/**
		 * The current application, on an item that no longer fits the skin (a smithing recipe into another
		 * Kind or Material, or a narrowed {@code materials} list): the skin falls off.
		 */
		NO_LONGER_FITS
	}

	private Standing standing(ItemStack stack, SkinMarker marker) {
		Entry skin = skins.get(marker.skin());
		if (skin == null || skin.loaded().isEmpty()) return Standing.ON_HOLD;
		if (!(ownership.read(marker.owner()) instanceof OwnershipStore.Readable(var record))) return Standing.ON_HOLD;
		if (!isCurrent(record, marker)) return Standing.STALE;
		return fits(skin.loaded().get(), stack) ? Standing.LIVE : Standing.NO_LONGER_FITS;
	}

	/**
	 * Does the skin fit this item? It must be of the skin's Kind and, if the skin is limited to
	 * Materials, made of one of them; an item of no Material the mod knows (another mod's) fits only
	 * a skin that isn't limited.
	 */
	private static boolean fits(SkinFolder.Loaded skin, ItemStack item) {
		if (!skin.kind().fits(item)) return false;
		return skin.materials().map(allowed -> Material.of(item).filter(allowed::contains).isPresent()).orElse(true);
	}

	/** The skin's application, if it's on an item in this world (stamped here) and the skin hasn't changed Kind since. */
	private Optional<OwnershipStore.Application> appliedHere(String skinId, OwnershipStore.Owned owned) {
		return owned.application().filter(a -> a.world().equals(worldStamp()) && !kindChanged(skinId, a));
	}

	/** Has the (loaded) skin a Kind other than the one it was applied as? */
	private boolean kindChanged(String skinId, OwnershipStore.Application application) {
		Entry skin = skins.get(skinId);
		return skin != null && skin.loaded().isPresent() && application.kind().map(k -> k != skin.loaded().get().kind()).orElse(false);
	}

	/**
	 * After a reload: an application of a skin whose Kind has changed since is dropped from its
	 * Owner's file, so the skin stays Unapplied even if the Kind is changed back. Every player file
	 * is looked at; damaged ones are left alone (until fixed, their applications read as Unapplied all the same).
	 */
	private void dropKindChangedApplications() {
		for (UUID player : ownership.players()) {
			if (!(ownership.read(player) instanceof OwnershipStore.Readable(var record))) continue;
			OwnershipStore.PlayerRecord next = record;
			for (var e : record.skins().entrySet()) {
				if (e.getValue().application().filter(a -> kindChanged(e.getKey(), a)).isPresent()) next = unapplied(next, e.getKey());
			}
			if (next != record) {
				MetacraftSkins.LOGGER.info("[{}] {}'s skins that changed Kind are Unapplied now", MetacraftSkins.MOD_ID, player);
				save(next);
			}
		}
	}

	/**
	 * The server touching an item: if it carries a stale marker, the marker is cleared, so the item
	 * is plain for good. A Dormant skin's marker (or one whose Owner's file is damaged) is kept, and
	 * so is a live one. The items inside a shulker box or bundle item are touched too. An item that
	 * carries a skin's current application but no longer fits it loses the skin, which becomes
	 * Unapplied for its Owner. Returns how many items it reverted. Server thread only.
	 */
	public synchronized int revertStale(ItemStack stack) {
		int reverted = 0;
		SkinMarker marker = stack.get(SkinMarker.TYPE);
		if (marker != null) {
			Standing standing = standing(stack, marker);
			if (standing == Standing.STALE || standing == Standing.NO_LONGER_FITS) {
				stack.remove(SkinMarker.TYPE);
				reverted++;
				if (standing == Standing.NO_LONGER_FITS && ownership.read(marker.owner()) instanceof OwnershipStore.Readable(var record)) {
					save(unapplied(record, marker.skin()));
				}
			}
		}
		ItemContainerContents box = stack.get(DataComponents.CONTAINER);
		if (box != null) reverted += revertInside(stack, DataComponents.CONTAINER, box);
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) reverted += revertInside(stack, DataComponents.BUNDLE_CONTENTS, bundle);
		return reverted;
	}

	/** The items inside a shulker box or bundle item are copies; any reverted one is written back. */
	private <T extends ContainerComponent<T>> int revertInside(ItemStack holder, DataComponentType<T> type, T contents) {
		List<ItemStack> copies = contents.itemCopies().toList();
		if (copies.stream().noneMatch(Skins::mayHoldAMarker)) return 0;
		int reverted = 0;
		for (ItemStack copy : copies) reverted += revertStale(copy);
		if (reverted > 0) holder.set(type, contents.copyWithContents(copies.stream()));
		return reverted;
	}

	private static boolean mayHoldAMarker(ItemStack stack) {
		return stack.has(SkinMarker.TYPE) || stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS);
	}

	/** {@link #revertStale(ItemStack)} for every item in a container: a chest, an ender chest, a minecart's. */
	public synchronized int revertStale(Container container) {
		int reverted = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) reverted += revertStale(container.getItem(slot));
		return reverted;
	}

	/**
	 * {@link #revertStale(ItemStack)} for whatever an entity holds. A player: their inventory,
	 * worn armour, offhand, ender chest, the menu they have open and the item on their cursor. A
	 * dropped item, an item frame's item, a mob's or armour stand's equipment, a minecart's or
	 * villager's inventory. Server thread only.
	 */
	public synchronized int revertStale(Entity entity) {
		int reverted = 0;
		if (entity instanceof ServerPlayer player) {
			reverted += revertStale(player.getInventory());
			reverted += revertStale(player.getEnderChestInventory());
			for (Slot slot : player.containerMenu.slots) reverted += revertStale(slot.getItem());
			reverted += revertStale(player.containerMenu.getCarried());
			return reverted;
		}
		if (entity instanceof ItemEntity dropped) reverted += sentAgain(entity, revertStale(dropped.getItem()));
		if (entity instanceof ItemFrame frame) reverted += sentAgain(entity, revertStale(frame.getItem()));
		// Vanilla sends a changed piece of equipment to those who see it by itself.
		if (entity instanceof LivingEntity living) for (EquipmentSlot slot : EquipmentSlot.VALUES) reverted += revertStale(living.getItemBySlot(slot));
		if (entity instanceof Container container) reverted += revertStale(container);
		if (entity instanceof InventoryCarrier carrier) reverted += revertStale(carrier.getInventory());
		return reverted;
	}

	/**
	 * A dropped item or a framed one, changed in place, isn't sent again by itself: its item is
	 * re-sent to everyone who can see it, so their clients drop the Look too.
	 */
	private static int sentAgain(Entity entity, int reverted) {
		if (reverted == 0 || !(entity.level() instanceof ServerLevel level)) return reverted;
		List<SynchedEntityData.DataValue<?>> values = entity.getEntityData().getNonDefaultValues();
		if (values == null) return reverted;
		List<SynchedEntityData.DataValue<?>> items = values.stream().filter(v -> v.value() instanceof ItemStack).toList();
		if (!items.isEmpty()) level.getChunkSource().sendToTrackingPlayers(entity, new ClientboundSetEntityDataPacket(entity.getId(), items));
		return reverted;
	}

	/** A player joined: their name is kept ({@link #seen}), and whatever they carry is touched, so a skin moved while they were away is gone. */
	public synchronized void joined(ServerPlayer player) {
		seen(player.nameAndId());
		revertStale(player);
	}

	/**
	 * Every item on the player the skin {@linkplain #fits fits}, Material included, in the apply screen's
	 * order (hotbar, inventory, worn armour head to feet, offhand), each with the skin applying would replace (live or Dormant).
	 * Empty for an unknown or Dormant skin.
	 */
	public synchronized List<Candidate> candidates(ServerPlayer player, String skinId) {
		Entry skin = skins.get(skinId);
		if (skin == null || skin.loaded().isEmpty()) return List.of();
		List<Candidate> candidates = new ArrayList<>();
		for (ItemLocation where : ItemLocation.ON_A_PLAYER) {
			ItemStack item = where.in(player.getInventory());
			if (!fits(skin.loaded().get(), item)) continue;
			Optional<Displaced> current = displaced(item);
			boolean mine = current.filter(c -> c.is(player.getUUID(), skinId)).isPresent();
			candidates.add(new Candidate(where, item, mine ? Optional.empty() : current.map(Displaced::name), mine));
		}
		return candidates;
	}

	/**
	 * What a viewer is sent of this item: Polymer's client copy of it ({@code client}), as it is
	 * {@code with} that player (null: with no one, such as in a chest, on the ground or in a frame;
	 * the drawing check works it out, {@link DrawingCheck}).
	 * <ul>
	 *   <li><b>Working</b> (live, and with its Owner): dressed in the skin's Look and tooltip block. The
	 *       Look is {@code item_model} {@code skins:<id>}; a hat is also equippable on the head with no
	 *       equipment asset, so the client draws the Look there (the server's item, with its armour and
	 *       abilities, is untouched). The tooltip block follows vanilla's lines: "Skin: <Skin>" (gold),
	 *       the Perk lines, the description (gray), "Owned by <player>" (gray).</li>
	 *   <li><b>Away</b> (live, with anyone else or no one): plain, with one gray line in place of the
	 *       tooltip block, "<Skin> skin, only works for <Owner>".</li>
	 *   <li>A plain, stale, Dormant or no longer fitting item comes back as it was.</li>
	 * </ul>
	 * The name and glint are left alone.
	 */
	public ItemStack clientView(ItemStack stack, ItemStack client, @Nullable UUID with) {
		Optional<LiveSkin> live = liveSkin(stack);
		if (live.isEmpty()) return client;
		LiveSkin skin = live.get();
		if (!skin.owner().equals(with)) {
			List<Component> lines = new ArrayList<>(client.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
			lines.add(plain(skin.name() + " skin, only works for " + skin.ownerName(), ChatFormatting.GRAY));
			client.set(DataComponents.LORE, new ItemLore(lines));
			return client;
		}
		client.set(DataComponents.ITEM_MODEL, Identifier.fromNamespaceAndPath(SkinFolder.NAMESPACE, skin.id()));
		if (skin.kind().isHat()) {
			Equippable worn = client.get(DataComponents.EQUIPPABLE);
			client.set(DataComponents.EQUIPPABLE, worn == null ? Equippable.builder(EquipmentSlot.HEAD).build()
					: new Equippable(EquipmentSlot.HEAD, worn.equipSound(), Optional.empty(), worn.cameraOverlay(), worn.allowedEntities(),
							worn.dispensable(), worn.swappable(), worn.damageOnHurt(), worn.equipOnInteract(), worn.canBeSheared(), worn.shearingSound()));
		}
		List<Component> lines = new ArrayList<>(client.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines());
		lines.add(plain("Skin: " + skin.name(), ChatFormatting.GOLD));
		lines.addAll(PerkLines.of(skin.perks()));
		skin.description().ifPresent(d -> lines.add(plain(d, ChatFormatting.GRAY)));
		lines.add(plain("Owned by " + skin.ownerName(), ChatFormatting.GRAY));
		client.set(DataComponents.LORE, new ItemLore(lines));
		return client;
	}

	// ---- Perks

	/** A skin's Perks as they stand: none at all while the settings switch Perks off (Looks stay). */
	private List<Perk> inEffect(List<Perk> perks) {
		return settings().perksEnabled() ? perks : List.of();
	}

	/**
	 * The Perks this item gives {@code holder}, who holds or wears it: its live skin's, read from the
	 * skin's definition now (so an edited definition changes every item at once), if the holder is
	 * its Owner. None for anyone else, for a plain, stale or Dormant item, or while the settings
	 * switch Perks off.
	 */
	public List<Perk> perks(ItemStack stack, ServerPlayer holder) {
		return working(stack, holder).map(LiveSkin::perks).orElse(List.of());
	}

	/** The item's live skin, if {@code holder} is its Owner: only then do its Perks work. */
	private Optional<LiveSkin> working(ItemStack stack, ServerPlayer holder) {
		return liveSkin(stack).filter(skin -> skin.owner().equals(holder.getUUID()));
	}

	/**
	 * Brings the player's held and worn Perks in line with what they hold and wear now: held Perks
	 * of the main-hand item (never the offhand), worn Perks of each armour slot, each only if the
	 * player is the item's Owner. Potions and
	 * attribute modifiers whose item went (put away, moved, gone stale, Perks switched off) end at
	 * once; auras and ambient sounds play on their interval. The server runs it for every player
	 * every tick. Server thread only.
	 */
	public synchronized void updateLastingPerks(ServerPlayer player) {
		List<LastingPerks.Placed> placed = new ArrayList<>();
		place(placed, player, "held", Perk.Trigger.HELD, player.getMainHandItem());
		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			place(placed, player, "worn/" + slot.getName(), Perk.Trigger.WORN, player.getItemBySlot(slot));
		}
		lasting.update(player, placed);
	}

	private void place(List<LastingPerks.Placed> placed, ServerPlayer player, String slot, Perk.Trigger trigger, ItemStack item) {
		List<Perk> perks = perks(item, player);
		for (int i = 0; i < perks.size(); i++) if (perks.get(i).trigger() == trigger) placed.add(new LastingPerks.Placed(slot, i, perks.get(i)));
	}

	/** The holder hit {@code victim} in melee: the hit Perks of their main-hand item fire. Server thread only. */
	public synchronized void hit(ServerPlayer holder, LivingEntity victim) {
		fireEvent(holder, holder.getMainHandItem(), Perk.Trigger.HIT, EventPerks.Other.of(victim));
	}

	/** The holder killed {@code victim} with a melee blow: the kill Perks of their main-hand item fire. Server thread only. */
	public synchronized void killed(ServerPlayer holder, LivingEntity victim) {
		fireEvent(holder, holder.getMainHandItem(), Perk.Trigger.KILL, EventPerks.Other.of(victim));
	}

	/** The holder broke the block at {@code pos}: the block break Perks of their main-hand tool fire, "other" being the block's centre. Server thread only. */
	public synchronized void brokeBlock(ServerPlayer holder, BlockPos pos) {
		fireEvent(holder, holder.getMainHandItem(), Perk.Trigger.BLOCK_BREAK, new EventPerks.Other(null, Vec3.atCenterOf(pos)));
	}

	/**
	 * The wearer was hurt, by {@code attacker} if there is one: the hurt Perks of everything they
	 * wear fire, "other" being the attacker. With no living attacker, only effects on the wearer fire. Server thread only.
	 */
	public synchronized void hurt(ServerPlayer wearer, @Nullable Entity attacker) {
		EventPerks.Other other = attacker instanceof LivingEntity living && living != wearer ? EventPerks.Other.of(living) : null;
		for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
			fireEvent(wearer, wearer.getItemBySlot(slot), Perk.Trigger.HURT, other);
		}
	}

	/** Event Perks fire only for the item's Owner, in survival or adventure, never in creative or spectator. */
	private void fireEvent(ServerPlayer holder, ItemStack item, Perk.Trigger trigger, EventPerks.@Nullable Other other) {
		// The game mode itself, not isCreative(): a game test's mock player always says creative there.
		if (!holder.gameMode.getGameModeForPlayer().isSurvival()) return;
		working(item, holder).ifPresent(skin -> events.fire(holder, skin, trigger, other, settings().perksAffectPlayers()));
	}

	/** A player left: every held and worn Perk this keeps up on them ends. Server thread only. */
	public synchronized void left(ServerPlayer player) {
		lasting.end(player);
	}

	private static Component plain(String text, ChatFormatting colour) {
		return Component.literal(text).withStyle(s -> s.withItalic(false).withColor(colour));
	}

	private OwnershipChange save(OwnershipStore.PlayerRecord record) {
		try {
			ownership.save(record);
			changes.incrementAndGet();
			return OwnershipChange.DONE;
		} catch (IOException | RuntimeException e) {
			MetacraftSkins.LOGGER.error("[{}] could not save {}; the change was not made", MetacraftSkins.MOD_ID, ownership.file(record.uuid()), e);
			return OwnershipChange.SAVE_FAILED;
		}
	}

	// ---- queries

	/**
	 * How many changes there have been: saved ownership changes and reloads. Once it moves, items
	 * anywhere may have gone stale, so the server touches what it can reach ({@link StaleReverts}).
	 */
	public long changes() {
		return changes.get();
	}

	/** What the player owns, online or not, with each skin's claim time and state. */
	public synchronized PlayerSkins ownedSkins(UUID player) {
		return switch (ownership.read(player)) {
			case OwnershipStore.Damaged(var reason) -> new PlayerSkins(player, Optional.empty(), List.of(), Optional.of(reason));
			case OwnershipStore.Readable(var record) -> new PlayerSkins(player, record.name(), record.skins().entrySet().stream()
					.map(e -> new OwnedSkin(e.getKey(), e.getValue().claimed(), state(e.getKey(), e.getValue()))).toList(), Optional.empty());
		};
	}

	private OwnedSkin.State state(String skinId, OwnershipStore.Owned owned) {
		Entry skin = skins.get(skinId);
		if (skin == null || skin.dormantReason().isPresent()) return OwnedSkin.State.DORMANT;
		if (appliedHere(skinId, owned).isPresent()) return OwnedSkin.State.APPLIED;
		return owned.application().filter(a -> !kindChanged(skinId, a)).isPresent() ? OwnedSkin.State.APPLIED_IN_ANOTHER_WORLD : OwnedSkin.State.UNAPPLIED;
	}

	/** This world's stamp, read (or made) the first time it's needed. */
	private synchronized UUID worldStamp() {
		if (worldStamp == null) worldStamp = WorldMarker.read(world);
		return worldStamp;
	}

	/** Every skin folder, sorted by id: its Kind and, if Dormant, why. */
	public List<SkinSummary> allSkins() {
		return skins.values().stream().map(this::summary).toList();
	}

	/** One skin folder by id; empty if there's none (an owned skin whose folder is gone is Dormant all the same). */
	public Optional<SkinSummary> skin(String id) {
		return Optional.ofNullable(skins.get(id)).map(this::summary);
	}

	private SkinSummary summary(Entry e) {
		return new SkinSummary(e.id(), e.loaded().map(SkinFolder.Loaded::name), e.kind(), e.loaded().flatMap(SkinFolder.Loaded::materials),
				e.loaded().flatMap(SkinFolder.Loaded::description),
				e.loaded().map(l -> inEffect(l.perks())).orElse(List.of()), e.dormantReason());
	}

	public SkinsSettings settings() {
		SkinsSettings s = settings.get();
		return s != null ? s : SkinsSettings.DEFAULTS;
	}

	/** The newest build of the skins pack; empty before the first {@link #reload()}. */
	public Optional<SkinsPack> pack() {
		synchronized (builds) {
			return Optional.ofNullable(builds.peekFirst());
		}
	}

	/** The bytes of one of the last few builds, by its {@link SkinsPack#path()}; for the pack server, any thread. */
	public Optional<byte[]> servePack(String path) {
		if (!path.startsWith(SkinsPack.PATH_PREFIX)) return Optional.empty();
		synchronized (builds) {
			for (SkinsPack b : builds) if (b.path().equals(path)) return Optional.of(b.zip());
		}
		return Optional.empty();
	}

	private List<Path> folders() {
		if (!Files.isDirectory(skinsFolder)) return List.of();
		try (Stream<Path> children = Files.list(skinsFolder)) {
			return children.filter(Files::isDirectory).filter(p -> !p.getFileName().toString().startsWith(".")).sorted().toList();
		} catch (IOException e) {
			MetacraftSkins.LOGGER.error("[{}] cannot list {}", MetacraftSkins.MOD_ID, skinsFolder, e);
			return List.of();
		}
	}
}
