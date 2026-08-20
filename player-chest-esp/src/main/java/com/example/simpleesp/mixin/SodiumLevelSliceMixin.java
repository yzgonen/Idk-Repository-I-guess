package com.example.simpleesp.mixin;

import com.example.simpleesp.SimpleEspClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.world.LevelSlice", remap = false)
public abstract class SodiumLevelSliceMixin {
    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void simpleesp$xraySliceState(int blockX, int blockY, int blockZ, CallbackInfoReturnable<BlockState> cir) {
        if (!SimpleEspClient.isXrayEnabled()) {
            return;
        }

        BlockState state = cir.getReturnValue();
        if (state != null && !state.isAir() && !SimpleEspClient.shouldRenderInXray(state)) {
            cir.setReturnValue(Blocks.AIR.getDefaultState());
        }
    }
}
