package io.github.tlmsmartcombat.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin 插件：本模组所有 mixin 均针对 maid_storage_manager（软依赖），
 * 仅当该 mod 存在时才应用，避免缺失目标类导致的启动失败。
 * <p>
 * 注意：mixin 准备阶段 {@code ModList.get()} 尚未初始化（返回 null），
 * 必须使用更早构建的 {@code LoadingModList} 判断 mod 是否存在。
 */
public class MixinPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            return net.minecraftforge.fml.loading.LoadingModList.get()
                    .getModFileById("maid_storage_manager") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
