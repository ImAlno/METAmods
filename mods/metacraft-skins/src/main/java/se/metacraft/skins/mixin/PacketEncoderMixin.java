package se.metacraft.skins.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import se.metacraft.skins.DrawingCheck;

/**
 * The mod's one mixin: while a packet is encoded for a client, it's bound in
 * {@link DrawingCheck#PACKET}, so the drawing check can tell who an item being written is with.
 * Polymer and Fabric each wrap the same method the same way.
 */
@Mixin(PacketEncoder.class)
public class PacketEncoderMixin {
	@WrapMethod(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;Lio/netty/buffer/ByteBuf;)V")
	private void bindPacket(ChannelHandlerContext ctx, Packet<?> packet, ByteBuf output, Operation<Void> original) {
		ScopedValue.where(DrawingCheck.PACKET, packet).run(() -> original.call(ctx, packet, output));
	}
}
