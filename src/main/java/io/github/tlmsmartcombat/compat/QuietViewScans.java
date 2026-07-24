package io.github.tlmsmartcombat.compat;

import studio.fantasyit.maid_storage_manager.storage.ItemHandler.SimulateTargetInteractHelper;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 登记"静默查看"的容器交互助手（仅被本模组的 mixin 在 maid_storage_manager 存在时使用）。
 * <p>
 * 使用 WeakHashMap 避免助手对象泄漏。
 */
public final class QuietViewScans {
    private static final Set<SimulateTargetInteractHelper> QUIET =
            Collections.newSetFromMap(new WeakHashMap<>());

    private QuietViewScans() {
    }

    public static void markQuiet(SimulateTargetInteractHelper helper) {
        synchronized (QUIET) {
            QUIET.add(helper);
        }
    }

    public static boolean consumeQuiet(SimulateTargetInteractHelper helper) {
        synchronized (QUIET) {
            return QUIET.remove(helper);
        }
    }
}
