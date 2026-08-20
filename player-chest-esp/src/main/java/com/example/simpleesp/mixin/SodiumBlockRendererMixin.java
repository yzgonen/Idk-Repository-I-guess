package com.example.simpleesp.mixin;

import com.example.simpleesp.SimpleEspClient;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.model.BlockStateModel;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class SodiumBlockRendererMixin {
    @Inject(method = "renderModel", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void simpleesp$filterXrayBlocks(
            BlockStateModel model,
            BlockState state,
            BlockPos pos,
            BlockPos origin,
            CallbackInfo ci
    ) {
        if (SimpleEspClient.isXrayEnabled() && !SimpleEspClient.shouldRenderInXray(state)) {
            ci.cancel();
        }
    }

    @ModifyArg(
            method = "renderModel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/model/AbstractBlockRenderContext;prepareCulling(Z)V"
            ),
            index = 0,
            require = 0,
            remap = false
    )
    private boolean simpleesp$disableXrayFaceCulling(boolean original) {
        return SimpleEspClient.isXrayEnabled() ? false : original;
    }
}
