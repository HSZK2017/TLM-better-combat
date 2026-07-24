package io.github.tlmsmartcombat.mixin;

import io.github.tlmsmartcombat.compat.QuietViewScans;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.fantasyit.maid_storage_manager.storage.ItemHandler.SimulateTargetInteractHelper;

/**
 * 与 {@link MsmViewScanQuietMixin} 配套：被标记为静默查看的交互助手，
 * 其 stop() 不再执行（既不触发关箱动画，也不清理从未登记的开箱计数）。
 */
@Mixin(SimulateTargetInteractHelper.class)
public abstract class MsmViewScanStopMixin {

    @Inject(method = "stop", at = @At("HEAD"), cancellable = true)
    private void tlmSmartCombat$quietViewStop(CallbackInfo ci) {
        if (QuietViewScans.consumeQuiet((SimulateTargetInteractHelper) (Object) this)) {
            ci.cancel();
        }
    }
}
