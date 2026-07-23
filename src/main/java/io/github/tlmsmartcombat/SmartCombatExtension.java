package io.github.tlmsmartcombat;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import io.github.tlmsmartcombat.task.TaskSmartCombat;

/**
 * Touhou Little Maid 扩展入口。
 * <p>
 * 带有 {@link LittleMaidExtension} 注解的类会被女仆模组自动扫描实例化，
 * 并通过 {@link ILittleMaid#addMaidTask(TaskManager)} 回调注册我们的自定义任务。
 */
@LittleMaidExtension
public class SmartCombatExtension implements ILittleMaid {
    @Override
    public void addMaidTask(TaskManager manager) {
        manager.add(new TaskSmartCombat());
    }
}
