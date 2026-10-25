package se.metacraft.skins;

import blue.endless.jankson.JsonArray;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads a skin definition's {@code perks} (ticket 05's catalogue) and checks them: each Perk's
 * Trigger must be one its Kind allows, and each effect one its Trigger allows, with only the
 * fields that effect takes. Anything wrong rejects the whole skin, saying which Perk and why.
 */
final class PerkReader {
	private PerkReader() {}

	private static final Set<String> PERK_FIELDS = Set.of("trigger", "effects", "chance", "cooldown", "tooltip");
	/** The fields each effect type takes, besides {@code type}. */
	private static final Map<String, Set<String>> EFFECT_FIELDS = Map.of(
			"potion", Set.of("effect", "level", "duration", "target"),
			"attribute", Set.of("attribute", "amount", "operation"),
			"fire", Set.of("duration", "target"),
			"knockback", Set.of("strength", "lift", "target"),
			"heal", Set.of("amount", "target"),
			"particle", Set.of("particle", "count", "interval", "target"),
			"sound", Set.of("sound", "volume", "pitch", "interval", "target"));
	/** A lasting aura's and ambient sound's default interval, in ticks. */
	static final int AURA_INTERVAL = 20, SOUND_INTERVAL = 100;

	static List<Perk> read(JsonElement element, Kind kind, Path file) throws SkinFolder.Rejected {
		if (!(element instanceof JsonArray perks)) throw new SkinFolder.Rejected(file, "\"perks\" must be a list of Perks");
		List<Perk> read = new ArrayList<>();
		for (int i = 0; i < perks.size(); i++) read.add(perk(perks.get(i), kind, new Where(file, "perks[" + i + "]")));
		return List.copyOf(read);
	}

	/** Where in the definition a value is, for the rejection's reason. */
	private record Where(Path file, String path) {
		Where in(String child) {
			return new Where(file, path + "." + child);
		}

		SkinFolder.Rejected reject(String reason) {
			return new SkinFolder.Rejected(file, path + ": " + reason);
		}
	}

	private static Perk perk(JsonElement element, Kind kind, Where at) throws SkinFolder.Rejected {
		if (!(element instanceof JsonObject perk)) throw at.reject("a Perk must be an object { trigger, effects }");
		onlyFields(perk, PERK_FIELDS, at);
		String triggerId = string(perk, "trigger", at).orElseThrow(() -> at.reject("missing field \"trigger\""));
		Perk.Trigger trigger = Perk.Trigger.byId(triggerId).orElseThrow(() -> at.reject("unknown trigger \"" + triggerId + "\" (one of: "
				+ Arrays.stream(Perk.Trigger.values()).map(Perk.Trigger::id).collect(Collectors.joining(", ")) + ")"));
		Set<Perk.Trigger> allowed = Perk.Trigger.allowedFor(kind);
		if (!allowed.contains(trigger)) {
			throw at.reject("a " + kind.id() + " skin's Perks can't be \"" + trigger.id() + "\" (allowed: "
					+ Arrays.stream(Perk.Trigger.values()).filter(allowed::contains).map(Perk.Trigger::id).collect(Collectors.joining(", ")) + ")");
		}
		if (trigger.lasting() && (perk.containsKey("chance") || perk.containsKey("cooldown"))) {
			throw at.reject("chance and cooldown are for event Perks (hit, kill, block_break, hurt), not \"" + trigger.id() + "\"");
		}
		double chance = number(perk, "chance", at).orElse(1.0);
		if (chance < 0 || chance > 1) throw at.in("chance").reject("must be between 0 and 1");
		double cooldown = number(perk, "cooldown", at).orElse(0.0);
		if (cooldown < 0) throw at.in("cooldown").reject("must be 0 or more seconds");
		Optional<String> tooltip = string(perk, "tooltip", at);

		if (!(perk.get("effects") instanceof JsonArray effects) || effects.isEmpty()) throw at.reject("\"effects\" must be a list of at least one effect");
		List<Perk.Effect> read = new ArrayList<>();
		for (int i = 0; i < effects.size(); i++) read.add(effect(effects.get(i), trigger, at.in("effects[" + i + "]")));
		return new Perk(trigger, List.copyOf(read), chance, cooldown, tooltip);
	}

	private static Perk.Effect effect(JsonElement element, Perk.Trigger trigger, Where at) throws SkinFolder.Rejected {
		if (!(element instanceof JsonObject effect)) throw at.reject("an effect must be an object { type, ... }");
		String type = string(effect, "type", at).orElseThrow(() -> at.reject("missing field \"type\""));
		Set<String> fields = EFFECT_FIELDS.get(type);
		if (fields == null) throw at.reject("unknown effect type \"" + type + "\" (one of: " + String.join(", ", EFFECT_FIELDS.keySet().stream().sorted().toList()) + ")");
		Set<String> withType = new java.util.HashSet<>(fields);
		withType.add("type");
		onlyFields(effect, withType, at);
		Set<String> allowed = allowedEffects(trigger);
		if (!allowed.contains(type)) {
			throw at.reject("a \"" + trigger.id() + "\" Perk can't have a " + type + " effect (allowed: " + String.join(", ", allowed.stream().sorted().toList()) + ")");
		}
		if (trigger.lasting() && effect.containsKey("duration")) throw at.reject("a \"" + trigger.id() + "\" potion lasts while the item is in place, so it takes no duration");
		if (!trigger.lasting() && effect.containsKey("interval")) throw at.reject("interval is for held and worn Perks only");

		return switch (type) {
			case "potion" -> {
				Holder<MobEffect> potion = registered(effect, "effect", BuiltInRegistries.MOB_EFFECT, at);
				int level = (int) whole(effect, "level", at).orElse(1L).longValue();
				if (level < 1) throw at.in("level").reject("starts at 1");
				double duration = 0;
				if (!trigger.lasting()) {
					duration = number(effect, "duration", at).orElseThrow(() -> at.reject("a timed potion needs a duration (seconds)"));
					if (duration <= 0) throw at.in("duration").reject("must be more than 0 seconds");
				}
				Perk.Target target = target(effect, trigger, at, potionTargets(trigger));
				yield new Perk.Potion(potion, level, duration, target);
			}
			case "attribute" -> {
				Holder<Attribute> attribute = registered(effect, "attribute", BuiltInRegistries.ATTRIBUTE, at);
				double amount = number(effect, "amount", at).orElseThrow(() -> at.reject("missing field \"amount\""));
				String op = string(effect, "operation", at).orElse("add_value");
				AttributeModifier.Operation operation = Arrays.stream(AttributeModifier.Operation.values()).filter(o -> o.getSerializedName().equals(op)).findFirst()
						.orElseThrow(() -> at.in("operation").reject("unknown operation \"" + op + "\" (one of: add_value, add_multiplied_base, add_multiplied_total)"));
				yield new Perk.AttributeChange(attribute, amount, operation);
			}
			case "fire" -> {
				target(effect, trigger, at, Set.of(Perk.Target.OTHER));
				double seconds = number(effect, "duration", at).orElseThrow(() -> at.reject("fire needs a duration (seconds)"));
				if (seconds <= 0) throw at.in("duration").reject("must be more than 0 seconds");
				yield new Perk.Fire(seconds);
			}
			case "knockback" -> {
				target(effect, trigger, at, Set.of(Perk.Target.OTHER));
				double strength = number(effect, "strength", at).orElse(0.5);
				double lift = number(effect, "lift", at).orElse(0.0);
				if (strength < 0) throw at.in("strength").reject("must be 0 or more");
				yield new Perk.Knockback(strength, lift);
			}
			case "heal" -> {
				target(effect, trigger, at, Set.of(Perk.Target.SELF));
				double amount = number(effect, "amount", at).orElseThrow(() -> at.reject("missing field \"amount\" (health points)"));
				if (amount <= 0) throw at.in("amount").reject("must be more than 0");
				yield new Perk.Heal(amount);
			}
			case "particle" -> {
				ParticleOptions particle = particle(effect, at);
				int count = (int) whole(effect, "count", at).orElse(1L).longValue();
				if (count < 1) throw at.in("count").reject("must be 1 or more");
				int interval = trigger.lasting() ? interval(effect, at, AURA_INTERVAL) : 0;
				yield new Perk.Particle(particle, count, interval, target(effect, trigger, at, sideTargets(trigger)));
			}
			case "sound" -> {
				String id = string(effect, "sound", at).orElseThrow(() -> at.reject("missing field \"sound\""));
				Identifier sound = Identifier.tryParse(id);
				if (sound == null) throw at.in("sound").reject("\"" + id + "\" is not an id");
				// A sound needn't be vanilla's: a resource pack may add it.
				Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.get(sound).<Holder<SoundEvent>>map(h -> h)
						.orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(sound)));
				float volume = (float) number(effect, "volume", at).orElse(1.0).doubleValue();
				float pitch = (float) number(effect, "pitch", at).orElse(1.0).doubleValue();
				if (volume <= 0 || pitch <= 0) throw at.reject("volume and pitch must be more than 0");
				int interval = trigger.lasting() ? interval(effect, at, SOUND_INTERVAL) : 0;
				yield new Perk.Sound(holder, volume, pitch, interval, target(effect, trigger, at, sideTargets(trigger)));
			}
			default -> throw new IllegalStateException(type);
		};
	}

	/** Ticket 05's table: the effects each Trigger allows. */
	private static Set<String> allowedEffects(Perk.Trigger trigger) {
		return switch (trigger) {
			case HELD, WORN -> Set.of("potion", "attribute", "particle", "sound");
			case HIT, HURT -> Set.of("potion", "fire", "knockback", "heal", "particle", "sound");
			case KILL, BLOCK_BREAK -> Set.of("potion", "heal", "particle", "sound");
		};
	}

	/** A potion is on the holder, except on hit (the victim too) and when hurt (the attacker too). */
	private static Set<Perk.Target> potionTargets(Perk.Trigger trigger) {
		return trigger == Perk.Trigger.HIT || trigger == Perk.Trigger.HURT ? Set.of(Perk.Target.SELF, Perk.Target.OTHER) : Set.of(Perk.Target.SELF);
	}

	/**
	 * Particles and sounds: around the holder while lasting; at the target on hit, at the victim or
	 * block on kill and block break; at the wearer or the attacker when hurt.
	 */
	private static Set<Perk.Target> sideTargets(Perk.Trigger trigger) {
		if (trigger.lasting()) return Set.of(Perk.Target.SELF);
		return trigger == Perk.Trigger.HURT ? Set.of(Perk.Target.SELF, Perk.Target.OTHER) : Set.of(Perk.Target.OTHER);
	}

	/** {@code target}, defaulting to the only one allowed, or to {@code self}. */
	private static Perk.Target target(JsonObject effect, Perk.Trigger trigger, Where at, Set<Perk.Target> allowed) throws SkinFolder.Rejected {
		Optional<String> written = string(effect, "target", at);
		Perk.Target fallback = allowed.size() == 1 ? allowed.iterator().next() : Perk.Target.SELF;
		if (written.isEmpty()) return fallback;
		Perk.Target target = Arrays.stream(Perk.Target.values()).filter(t -> t.id().equals(written.get())).findFirst()
				.orElseThrow(() -> at.in("target").reject("is \"self\" or \"other\", not \"" + written.get() + "\""));
		if (!allowed.contains(target)) {
			throw at.in("target").reject("a \"" + trigger.id() + "\" Perk's " + string(effect, "type", at).orElse("") + " can only target "
					+ allowed.stream().map(Perk.Target::id).sorted().collect(Collectors.joining(" or ")));
		}
		return target;
	}

	private static int interval(JsonObject effect, Where at, int fallback) throws SkinFolder.Rejected {
		long ticks = whole(effect, "interval", at).orElse((long) fallback);
		if (ticks < 1) throw at.in("interval").reject("must be 1 tick or more");
		return (int) Math.min(ticks, Integer.MAX_VALUE);
	}

	private static ParticleOptions particle(JsonObject effect, Where at) throws SkinFolder.Rejected {
		JsonElement written = effect.get("particle");
		if (!(written instanceof JsonObject)) throw at.reject("\"particle\" must be particle options, as in a data pack: { type: \"minecraft:...\" }");
		var json = JsonParser.parseString(written.toJson(false, false));
		var parsed = ParticleTypes.CODEC.parse(JsonOps.INSTANCE, json);
		if (parsed.error().isPresent()) throw at.in("particle").reject(parsed.error().get().message());
		return parsed.getOrThrow();
	}

	private static <T> Holder<T> registered(JsonObject object, String key, net.minecraft.core.Registry<T> registry, Where at) throws SkinFolder.Rejected {
		String id = string(object, key, at).orElseThrow(() -> at.reject("missing field \"" + key + "\""));
		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) throw at.in(key).reject("\"" + id + "\" is not an id");
		return registry.get(parsed).<Holder<T>>map(h -> h).orElseThrow(() -> at.in(key).reject("there's no " + key + " \"" + id + "\""));
	}

	private static void onlyFields(JsonObject object, Set<String> allowed, Where at) throws SkinFolder.Rejected {
		for (String key : object.keySet()) {
			if (!allowed.contains(key)) throw at.reject("unknown field \"" + key + "\" (allowed: " + String.join(", ", allowed.stream().sorted().toList()) + ")");
		}
	}

	private static Optional<String> string(JsonObject object, String key, Where at) throws SkinFolder.Rejected {
		if (!object.containsKey(key)) return Optional.empty();
		if (object.get(key) instanceof JsonPrimitive p && p.getValue() instanceof String s && !s.isBlank()) return Optional.of(s);
		throw at.in(key).reject("must be a non-empty string");
	}

	private static Optional<Double> number(JsonObject object, String key, Where at) throws SkinFolder.Rejected {
		if (!object.containsKey(key)) return Optional.empty();
		if (object.get(key) instanceof JsonPrimitive p && p.getValue() instanceof Number n && Double.isFinite(n.doubleValue())) return Optional.of(n.doubleValue());
		throw at.in(key).reject("must be a number");
	}

	private static Optional<Long> whole(JsonObject object, String key, Where at) throws SkinFolder.Rejected {
		Optional<Double> n = number(object, key, at);
		if (n.isPresent() && n.get() != Math.rint(n.get())) throw at.in(key).reject("must be a whole number");
		return n.map(Double::longValue);
	}
}
