package se.metacraft.cutscenes.compat;

import me.drex.antixray.common.util.Arguments;
import me.drex.antixray.common.util.ChunkPacketInfo;
import net.minecraft.world.level.chunk.LevelChunk;

public class AntiXray {

	public static void runWithChunkPacketInfo(Runnable toRun, LevelChunk chunk) {
		ScopedValue.where(
			Arguments.PACKET_INFO, new ChunkPacketInfo<>(chunk)
		).run(toRun);
	}

}
