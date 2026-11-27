package xluke44.slotguard;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;

import java.util.HashSet;
import java.util.Set;

public final class SlotGuardHooks {
    private static final Set<String> LOCKED_KEYS = new HashSet<String>();

    private SlotGuardHooks() {
    }

    public static synchronized void replaceLockedKeys(Set<String> values) {
        LOCKED_KEYS.clear();
        if (values != null) {
            LOCKED_KEYS.addAll(values);
        }
    }

    public static synchronized boolean isLockedPlayerSlot(Slot slot, EntityPlayer player) {
        if (slot == null || player == null || slot.inventory != player.inventory) {
            return false;
        }

        int index = slot.getSlotIndex();
        if (index >= 0 && index < 36) {
            return LOCKED_KEYS.contains("main:" + index);
        }
        if (index >= 36 && index < 40) {
            return LOCKED_KEYS.contains("armor:" + (index - 36));
        }
        if (index == 40) {
            return LOCKED_KEYS.contains("offhand:0");
        }
        return false;
    }
}
