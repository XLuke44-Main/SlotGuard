package xluke44.slotguard;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;

public final class PlayerSlotKey {
    private final String value;

    private PlayerSlotKey(String value) {
        this.value = value;
    }

    public static PlayerSlotKey fromSlot(Slot slot, InventoryPlayer inventory) {
        if (slot == null || inventory == null || slot.inventory != inventory) {
            return null;
        }
        int index = slot.getSlotIndex();
        if (index >= 0 && index < 36) {
            return new PlayerSlotKey("main:" + index);
        }
        if (index >= 36 && index < 40) {
            return new PlayerSlotKey("armor:" + (index - 36));
        }
        if (index == 40) {
            return new PlayerSlotKey("offhand:0");
        }
        return null;
    }

    public static PlayerSlotKey main(int index) {
        if (index < 0 || index > 35) {
            return null;
        }
        return new PlayerSlotKey("main:" + index);
    }

    public static PlayerSlotKey offhand() {
        return new PlayerSlotKey("offhand:0");
    }

    public String getValue() {
        return value;
    }

    public int getInventoryIndex() {
        if (value.startsWith("main:")) {
            return Integer.parseInt(value.substring(5));
        }
        if (value.startsWith("armor:")) {
            return 36 + Integer.parseInt(value.substring(6));
        }
        return 40;
    }

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        if (value.startsWith("main:")) {
            return isIntegerInRange(value.substring(5), 0, 35);
        }
        if (value.startsWith("armor:")) {
            return isIntegerInRange(value.substring(6), 0, 3);
        }
        return "offhand:0".equals(value);
    }

    private static boolean isIntegerInRange(String text, int min, int max) {
        try {
            int value = Integer.parseInt(text);
            return value >= min && value <= max;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof PlayerSlotKey)) {
            return false;
        }
        PlayerSlotKey other = (PlayerSlotKey) object;
        return value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
