package se.metacraft.skins;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import eu.pb4.polymer.core.api.other.PolymerComponent;
import net.minecraft.core.Registry;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * What a Skinned Item stores, and all it stores: which skin, whose, and which application of it.
 * Perks and Looks are never written onto the item; they're looked up from the live skin. The
 * component is server-side only: Polymer never sends it as a component, and an item carrying it is
 * rebuilt for each viewer ({@link Skins#clientView}).
 */
public record SkinMarker(String skin, UUID owner, UUID application) {
	public static final Codec<SkinMarker> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			Codec.STRING.fieldOf("skin").forGetter(SkinMarker::skin),
			UUIDUtil.CODEC.fieldOf("owner").forGetter(SkinMarker::owner),
			UUIDUtil.CODEC.fieldOf("application").forGetter(SkinMarker::application)
	).apply(instance, SkinMarker::new));

	public static final DataComponentType<SkinMarker> TYPE = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath(MetacraftSkins.MOD_ID, "skin"), DataComponentType.<SkinMarker>builder().persistent(CODEC).build());

	/**
	 * Registers the component, at mod initialisation. Without Polymer's hiding, Fabric's registry
	 * sync would kick vanilla clients; with it, any item carrying the marker goes through Polymer's
	 * per-viewer client stack, vanilla items included.
	 */
	static void init() {
		PolymerComponent.registerDataComponent(TYPE);
	}
}
