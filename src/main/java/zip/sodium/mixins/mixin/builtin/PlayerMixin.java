package zip.sodium.mixins.mixin.builtin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import zip.sodium.mixins.mixin.annotations.Inject;
import zip.sodium.mixins.mixin.annotations.Mixin;
import zip.sodium.mixins.mixin.annotations.Shadow;
import zip.sodium.mixins.mixin.info.InjectionPoint;

import java.util.Optional;

@Mixin(target = Player.class)
public abstract class PlayerMixin {
    @Shadow(obfuscatedName = "bR")
    public InventoryMenu inventoryMenu;

    @Shadow(obfuscatedName = "fW")
    public abstract int getSleepTimer();

    @Shadow(obfuscatedName = "fB")
    public abstract Optional<BlockPos> getSleepingPos();

    @Inject(method = "l()V", at = InjectionPoint.HEAD)
    public void onInit() {
        System.out.println(inventoryMenu);
        System.out.println(getSleepingPos());
        System.out.println(getSleepTimer());
    }
}
