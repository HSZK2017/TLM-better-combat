package io.github.tlmsmartcombat.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import io.github.tlmsmartcombat.compat.QuietViewScans;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import studio.fantasyit.maid_storage_manager.storage.ItemHandler.AbstractItemHandlerContext;
import studio.fantasyit.maid_storage_manager.storage.ItemHandler.ContextItemHandlerView;
import studio.fantasyit.maid_storage_manager.storage.ItemHandler.SimulateTargetInteractHelper;
import studio.fantasyit.maid_storage_manager.storage.Target;

/**
 * 让"查看"类容器交互（常态内容扫描）不再触发箱子的开合动画与声音。
 * <p>
 * MSM 的 {@code AbstractItemHandlerContext.start} 会调用
 * {@code SimulateTargetInteractHelper.open()} 触发箱子开箱动画与音效，
 * 而查看（View）上下文只是读取内容、并不真正存取物品。
 * 这里把查看上下文对 open() 的调用重定向为静默版本（仅摆动手臂），
 * 并登记该助手，使其 finish() 时的关箱动画同样被跳过；
 * 真正存取物品（自动合成取料 / 存放产物）的上下文不受影响。
 */
@Mixin(AbstractItemHandlerContext.class)
public abstract class MsmViewScanQuietMixin {

    @Redirect(method = "start",
            at = @At(value = "INVOKE",
                    target = "Lstudio/fantasyit/maid_storage_manager/storage/ItemHandler/SimulateTargetInteractHelper;open()V"))
    private void tlmSmartCombat$quietViewOpen(SimulateTargetInteractHelper instance,
                                              EntityMaid maid, ServerLevel level, Target target) {
        if ((Object) this instanceof ContextItemHandlerView) {
            QuietViewScans.markQuiet(instance);
            maid.swing(InteractionHand.MAIN_HAND);
        } else {
            instance.open();
        }
    }
}
