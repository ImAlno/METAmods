package se.metacraft.skins.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.RemoteSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import se.metacraft.skins.DrawingCheck;

/**
 * After a click, the server compares each slot and the cursor with the client's hash of what it
 * predicted, and only sends again what doesn't match. Polymer hashes its client copy of the item,
 * drawn with no packet being written. While a slot or the cursor is compared, it's bound in
 * {@link DrawingCheck#COMPARED}, so the drawing check draws it as the packet sending it would.
 */
@Mixin(AbstractContainerMenu.class)
public class AbstractContainerMenuMixin {
	@WrapOperation(method = "synchronizeSlotToRemote", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/inventory/RemoteSlot;matches(Lnet/minecraft/world/item/ItemStack;)Z"))
	private boolean bindComparedSlot(RemoteSlot remote, ItemStack stack, Operation<Boolean> original, @Local(argsOnly = true) int slot) {
		return ScopedValue.where(DrawingCheck.COMPARED, new DrawingCheck.MenuSlot((AbstractContainerMenu) (Object) this, slot))
				.call(() -> original.call(remote, stack));
	}

	@WrapOperation(method = "synchronizeCarriedToRemote", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/inventory/RemoteSlot;matches(Lnet/minecraft/world/item/ItemStack;)Z"))
	private boolean bindComparedCursor(RemoteSlot remote, ItemStack stack, Operation<Boolean> original) {
		return ScopedValue.where(DrawingCheck.COMPARED, new DrawingCheck.MenuSlot((AbstractContainerMenu) (Object) this, DrawingCheck.MenuSlot.CURSOR))
				.call(() -> original.call(remote, stack));
	}
}
