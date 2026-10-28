package se.metacraft.skins;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Codecs that refuse a field they don't read, where DFU's own skip it: a field a newer version of
 * the mod wrote must not be dropped by saving over it.
 */
final class StrictCodec {
	private StrictCodec() {}

	/** {@code codec}, but an object holding a key it doesn't read is an error naming that key and the ones allowed. */
	static <A> Codec<A> of(MapCodec<A> codec) {
		return new MapCodec<A>() {
			@Override
			public <T> Stream<T> keys(DynamicOps<T> ops) {
				return codec.keys(ops);
			}

			@Override
			public <T> DataResult<A> decode(DynamicOps<T> ops, MapLike<T> input) {
				Set<String> known = codec.keys(ops).flatMap(k -> ops.getStringValue(k).result().stream()).collect(Collectors.toCollection(TreeSet::new));
				Optional<String> unknown = input.entries().flatMap(e -> ops.getStringValue(e.getFirst()).result().stream())
						.filter(k -> !known.contains(k)).findFirst();
				if (unknown.isPresent()) return DataResult.error(() -> "unknown field \"" + unknown.get() + "\" (allowed: " + String.join(", ", known) + ")");
				return codec.decode(ops, input);
			}

			@Override
			public <T> RecordBuilder<T> encode(A input, DynamicOps<T> ops, RecordBuilder<T> prefix) {
				return codec.encode(input, ops, prefix);
			}
		}.codec();
	}
}
