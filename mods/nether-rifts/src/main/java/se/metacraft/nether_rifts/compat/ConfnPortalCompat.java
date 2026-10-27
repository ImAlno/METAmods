package se.metacraft.nether_rifts.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.portal.PortalShape;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class ConfnPortalCompat {

	private static final Field PORTAL_SHAPE_BLOCKS;
	private static final Field PORTAL_SHAPE_DIRECTION;

	static {
		Field blocks = null;
		Field direction = null;
		try {
			blocks = PortalShape.class.getDeclaredField("blocks");
			if (!blocks.getType().equals(ArrayList.class)) {
				blocks = null;
			}
			direction = PortalShape.class.getDeclaredField("direction");
			if (!direction.getType().equals(Direction.Axis.class)) {
				direction = null;
			}
			if (blocks != null) {
				blocks.setAccessible(true);
			}
			if (direction != null) {
				direction.setAccessible(true);
			}
		} catch (NoSuchFieldException ignored) {}
		PORTAL_SHAPE_BLOCKS = blocks;
		PORTAL_SHAPE_DIRECTION = direction;
	}

	public static Direction.Axis getDirection(PortalShape shape) {
		if (PORTAL_SHAPE_DIRECTION == null) return Direction.Axis.X;
		try {
			return (Direction.Axis) PORTAL_SHAPE_DIRECTION.get(shape);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

	public static List<BlockPos> getBlocks(PortalShape shape) {
		if (PORTAL_SHAPE_BLOCKS == null) return List.of();
		try {
			var list = (List<?>) PORTAL_SHAPE_BLOCKS.get(shape);
			if (list.isEmpty()) return List.of();
			for (var e : list) {
				if (!(e instanceof BlockPos)) {
					return List.of();
				}
			}
			//noinspection unchecked
			return (List<BlockPos>) list;
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}

}
