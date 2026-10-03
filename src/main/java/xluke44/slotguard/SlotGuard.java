package xluke44.slotguard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.client.event.GuiContainerEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Set;

@Mod(modid = SlotGuard.MODID, name = SlotGuard.NAME, version = SlotGuard.VERSION, clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]")
public class SlotGuard {
    public static final String MODID = "slotguard";
    public static final String NAME = "SlotGuard";
    public static final String VERSION = "1.0.0";

    private static final ResourceLocation LOCKED_TEXTURE = new ResourceLocation(MODID, "textures/gui/locked.png");
    private static final int DEFAULT_LOCK_KEY = Keyboard.KEY_L;
    private static final int DEFAULT_UNLOCK_KEY = Keyboard.KEY_U;
    private static final long CLEAR_ALL_HOLD_DURATION = 2000L;
    private static final KeyBinding LOCK_KEY = new KeyBinding("key.slotguard.lock", DEFAULT_LOCK_KEY, "key.categories.slotguard");
    private static final KeyBinding UNLOCK_KEY = new KeyBinding("key.slotguard.unlock", DEFAULT_UNLOCK_KEY, "key.categories.slotguard");
    private static final KeyBinding CLEAR_ALL_KEY = new KeyBinding(
            "key.slotguard.clear_all",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.CONTROL,
            DEFAULT_UNLOCK_KEY,
            "key.categories.slotguard");
    private static final LockStore LOCK_STORE = new LockStore();
    private static final Field GUI_DRAG_SPLITTING_SLOTS =
            ObfuscationReflectionHelper.findField(GuiContainer.class, "field_147008_s");
    private static boolean blockedMouseGesture;
    private static long clearAllHoldStart = -1L;
    private static boolean clearAllTriggered;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOCK_STORE.setDirectory(new File(event.getModConfigurationDirectory(), MODID));
        ClientRegistry.registerKeyBinding(LOCK_KEY);
        ClientRegistry.registerKeyBinding(UNLOCK_KEY);
        ClientRegistry.registerKeyBinding(CLEAR_ALL_KEY);
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGuiKeyboard(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!(event.getGui() instanceof GuiContainer)) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null) {
            return;
        }
        GuiContainer gui = (GuiContainer) event.getGui();
        int keyCode = Keyboard.getEventKey();

        if (CLEAR_ALL_KEY.isActiveAndMatches(keyCode)) {
            event.setCanceled(true);
            drainKeyBinding(UNLOCK_KEY);
            return;
        }

        if (isTextFieldFocused(gui)) {
            drainKeyBinding(LOCK_KEY);
            drainKeyBinding(UNLOCK_KEY);
            return;
        }

        if (Keyboard.getEventKeyState() && LOCK_KEY.isActiveAndMatches(keyCode)) {
            handleLockBinding(minecraft, true);
            event.setCanceled(true);
            consumeKeyBinding(LOCK_KEY);
            return;
        }
        if (Keyboard.getEventKeyState() && UNLOCK_KEY.isActiveAndMatches(keyCode)) {
            handleLockBinding(minecraft, false);
            event.setCanceled(true);
            consumeKeyBinding(UNLOCK_KEY);
            return;
        }

        if (minecraft.gameSettings.keyBindSwapHands.isActiveAndMatches(keyCode)
                && (isHotbarSlotLocked(minecraft.player.inventory, minecraft.player.inventory.currentItem)
                || isLocked(minecraft.player.inventory, PlayerSlotKey.offhand()))) {
            event.setCanceled(true);
            return;
        }

        Slot hovered = gui.getSlotUnderMouse();
        if (isLockedWithItem(hovered, minecraft.player.inventory)) {
            if (minecraft.gameSettings.keyBindDrop.isActiveAndMatches(keyCode)
                    || getHotbarIndexForKey(minecraft, keyCode) >= 0) {
                event.setCanceled(true);
                return;
            }
        }

        int hotbarIndex = getHotbarIndexForKey(minecraft, keyCode);
        if (hotbarIndex >= 0) {
            PlayerSlotKey hoveredKey = PlayerSlotKey.fromSlot(hovered, minecraft.player.inventory);
            PlayerSlotKey targetKey = PlayerSlotKey.main(hotbarIndex);
            if (targetKey != null && LOCK_STORE.isLocked(targetKey) && !targetKey.equals(hoveredKey)) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null || minecraft.world == null) {
            drainKeyBinding(LOCK_KEY);
            drainKeyBinding(UNLOCK_KEY);
            resetClearAllHold();
            blockedMouseGesture = false;
            return;
        }

        if (minecraft.currentScreen instanceof GuiContainer) {
            sanitizeLockedDragSlots((GuiContainer) minecraft.currentScreen, minecraft);
        }

        boolean containerOpen = minecraft.currentScreen instanceof GuiContainer;
        boolean textFieldFocused = containerOpen
                && isTextFieldFocused((GuiContainer) minecraft.currentScreen);
        boolean clearAllDown = isClearAllKeyDown();
        if (!clearAllDown && containerOpen && !textFieldFocused) {
            clearAllDown = isUnlockKeyHeldWithoutModifier();
        }
        if (clearAllDown) {
            drainKeyBinding(LOCK_KEY);
            drainKeyBinding(UNLOCK_KEY);
            updateClearAllHold(minecraft);
        } else if (textFieldFocused) {
            drainKeyBinding(LOCK_KEY);
            drainKeyBinding(UNLOCK_KEY);
            resetClearAllHold();
        } else {
            resetClearAllHold();
            while (LOCK_KEY.isPressed()) {
                handleLockBinding(minecraft, true);
            }
            while (UNLOCK_KEY.isPressed()) {
                handleLockBinding(minecraft, false);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGuiMouseInput(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!(event.getGui() instanceof GuiContainer)) {
            blockedMouseGesture = false;
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null || !LOCK_STORE.ensureCurrentContext(minecraft)) {
            blockedMouseGesture = false;
            return;
        }
        GuiContainer gui = (GuiContainer) event.getGui();
        int button = Mouse.getEventButton();
        boolean buttonState = Mouse.getEventButtonState();
        Slot hovered = getSlotAtMouseEvent(gui);

        if (button != 0 && button != 1) {
            sanitizeLockedDragSlots(gui, minecraft);
            if ((Mouse.isButtonDown(0) || Mouse.isButtonDown(1))
                    && isLockedSlot(hovered, minecraft.player.inventory)) {
                event.setCanceled(true);
            }
            return;
        }

        sanitizeLockedDragSlots(gui, minecraft);

        if (buttonState) {
            if (isShiftClickGuarded(gui, minecraft)
                    || shouldBlockMousePress(hovered, button, minecraft)) {
                blockedMouseGesture = true;
                event.setCanceled(true);
                return;
            }
            blockedMouseGesture = false;
            return;
        }

        if (blockedMouseGesture) {
            event.setCanceled(true);
            blockedMouseGesture = false;
            return;
        }
        blockedMouseGesture = false;
    }

    private void handleLockBinding(Minecraft minecraft, boolean locked) {
        if (minecraft.currentScreen instanceof GuiContainer) {
            lockHoveredSlot((GuiContainer) minecraft.currentScreen, locked);
            return;
        }
        if (minecraft.currentScreen == null && minecraft.world != null) {
            PlayerSlotKey key = PlayerSlotKey.main(minecraft.player.inventory.currentItem);
            if (key != null && LOCK_STORE.ensureCurrentContext(minecraft)) {
                LOCK_STORE.setLocked(key, locked);
            }
        }
    }

    private void drainKeyBinding(KeyBinding keyBinding) {
        while (keyBinding.isPressed()) {
        }
    }

    private boolean isClearAllKeyDown() {
        int keyCode = CLEAR_ALL_KEY.getKeyCode();
        boolean keyDown;
        if (keyCode < 0) {
            keyDown = Mouse.isButtonDown(keyCode + 100);
        } else {
            keyDown = Keyboard.isKeyDown(keyCode);
        }
        if (!keyDown) {
            return false;
        }
        KeyModifier modifier = CLEAR_ALL_KEY.getKeyModifier();
        return modifier == KeyModifier.NONE || modifier.isActive();
    }

    private boolean isUnlockKeyHeldWithoutModifier() {
        int keyCode = UNLOCK_KEY.getKeyCode();
        if (keyCode < 0) {
            return false;
        }
        return Keyboard.isKeyDown(keyCode)
                && !Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)
                && !Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)
                && !Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                && !Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)
                && !Keyboard.isKeyDown(Keyboard.KEY_LMENU)
                && !Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    private boolean isTextFieldFocused(GuiContainer gui) {
        if (gui == null) {
            return false;
        }

        Class<?> type = gui.getClass();
        while (type != null && GuiContainer.class.isAssignableFrom(type)) {
            Field[] fields = type.getDeclaredFields();
            for (Field field : fields) {
                if (!GuiTextField.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    Object value = field.get(gui);
                    if (value instanceof GuiTextField && ((GuiTextField) value).isFocused()) {
                        return true;
                    }
                } catch (IllegalAccessException exception) {
                } catch (SecurityException exception) {
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private void updateClearAllHold(Minecraft minecraft) {
        long now = Minecraft.getSystemTime();
        if (clearAllHoldStart < 0L) {
            clearAllHoldStart = now;
            clearAllTriggered = false;
            return;
        }
        if (!clearAllTriggered && now - clearAllHoldStart >= CLEAR_ALL_HOLD_DURATION) {
            clearAllTriggered = true;
            if (LOCK_STORE.ensureCurrentContext(minecraft)) {
                LOCK_STORE.clearAll();
            }
        }
    }

    private void resetClearAllHold() {
        clearAllHoldStart = -1L;
        clearAllTriggered = false;
    }

    @SubscribeEvent
    public void onGameplayKey(InputEvent.KeyInputEvent event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != null || minecraft.player == null || !Keyboard.getEventKeyState()) {
            return;
        }
        int keyCode = Keyboard.getEventKey();
        InventoryPlayer inventory = minecraft.player.inventory;
        if (minecraft.gameSettings.keyBindDrop.isActiveAndMatches(keyCode)
                && isHotbarSlotLockedWithItem(inventory, inventory.currentItem)) {
            consumeKeyBinding(minecraft.gameSettings.keyBindDrop);
            return;
        }
        if (minecraft.gameSettings.keyBindSwapHands.isActiveAndMatches(keyCode)
                && (isHotbarSlotLocked(inventory, inventory.currentItem)
                || isLocked(inventory, PlayerSlotKey.offhand()))) {
            consumeKeyBinding(minecraft.gameSettings.keyBindSwapHands);
        }
    }

    @SubscribeEvent
    public void onGuiForeground(GuiContainerEvent.DrawForeground event) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null || !LOCK_STORE.ensureCurrentContext(minecraft)) {
            return;
        }
        GuiContainer gui = event.getGuiContainer();
        for (Slot slot : gui.inventorySlots.inventorySlots) {
            PlayerSlotKey key = PlayerSlotKey.fromSlot(slot, minecraft.player.inventory);
            if (key != null && LOCK_STORE.isLocked(key)) {
                drawLockedIcon(slot.xPos + 10, slot.yPos + 1);
            }
        }
    }

    @SubscribeEvent
    public void onHotbar(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null || minecraft.world == null || !LOCK_STORE.ensureCurrentContext(minecraft)) {
            return;
        }
        ScaledResolution resolution = event.getResolution();
        int hotbarX = resolution.getScaledWidth() / 2 - 91;
        int hotbarY = resolution.getScaledHeight() - 22;
        for (int index = 0; index < 9; index++) {
            if (LOCK_STORE.isLocked(PlayerSlotKey.main(index))) {
                drawLockedIcon(hotbarX + index * 20 + 13, hotbarY + 4);
            }
        }
    }

    private void lockHoveredSlot(GuiContainer gui, boolean locked) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null || !LOCK_STORE.ensureCurrentContext(minecraft)) {
            return;
        }
        Slot hovered = gui.getSlotUnderMouse();
        PlayerSlotKey key = PlayerSlotKey.fromSlot(hovered, minecraft.player.inventory);
        if (key != null) {
            LOCK_STORE.setLocked(key, locked);
        }
    }

    private Slot getSlotAtMouseEvent(GuiContainer gui) {
        Minecraft minecraft = Minecraft.getMinecraft();
        int mouseX = Mouse.getEventX() * gui.width / minecraft.displayWidth;
        int mouseY = gui.height - Mouse.getEventY() * gui.height / minecraft.displayHeight - 1;
        int left = gui.getGuiLeft();
        int top = gui.getGuiTop();
        for (Slot slot : gui.inventorySlots.inventorySlots) {
            int slotX = left + slot.xPos;
            int slotY = top + slot.yPos;
            if (mouseX >= slotX - 1 && mouseX < slotX + 17
                    && mouseY >= slotY - 1 && mouseY < slotY + 17) {
                return slot;
            }
        }
        return null;
    }

    private boolean shouldBlockMousePress(Slot slot, int button, Minecraft minecraft) {
        return shouldBlockLockedSlotAction(slot, button, minecraft);
    }

    private boolean shouldBlockLockedSlotAction(Slot slot, int button, Minecraft minecraft) {
        if (!isLockedSlot(slot, minecraft.player.inventory)) {
            return false;
        }
        return true;
    }

    private boolean isLockedSlot(Slot slot, InventoryPlayer inventory) {
        if (slot == null || inventory == null) {
            return false;
        }
        PlayerSlotKey key = PlayerSlotKey.fromSlot(slot, inventory);
        return isLocked(inventory, key);
    }

    private boolean isShiftClickGuarded(GuiContainer gui, Minecraft minecraft) {
        if (!(Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT))) {
            return false;
        }
        for (Slot slot : gui.inventorySlots.inventorySlots) {
            PlayerSlotKey key = PlayerSlotKey.fromSlot(slot, minecraft.player.inventory);
            if (key != null && LOCK_STORE.isLocked(key) && !slot.getHasStack()) {
                return true;
            }
        }
        return false;
    }

    private boolean isLockedWithItem(Slot slot, InventoryPlayer inventory) {
        if (slot == null || !slot.getHasStack()) {
            return false;
        }
        PlayerSlotKey key = PlayerSlotKey.fromSlot(slot, inventory);
        return isLocked(inventory, key);
    }

    private boolean isHotbarSlotLocked(InventoryPlayer inventory, int index) {
        PlayerSlotKey key = PlayerSlotKey.main(index);
        return key != null && isLocked(inventory, key);
    }

    private boolean isLockedWithItem(InventoryPlayer inventory, PlayerSlotKey key) {
        if (inventory == null || key == null) {
            return false;
        }
        if (!isLocked(inventory, key)) {
            return false;
        }
        return !inventory.getStackInSlot(key.getInventoryIndex()).isEmpty();
    }

    private boolean isLocked(InventoryPlayer inventory, PlayerSlotKey key) {
        Minecraft minecraft = Minecraft.getMinecraft();
        return minecraft.player != null
                && inventory == minecraft.player.inventory
                && key != null
                && LOCK_STORE.ensureCurrentContext(minecraft)
                && LOCK_STORE.isLocked(key);
    }

    private boolean isHotbarSlotLockedWithItem(InventoryPlayer inventory, int index) {
        PlayerSlotKey key = PlayerSlotKey.main(index);
        return key != null && isLockedWithItem(inventory, key);
    }

    private int getHotbarIndexForKey(Minecraft minecraft, int keyCode) {
        for (int index = 0; index < minecraft.gameSettings.keyBindsHotbar.length; index++) {
            if (minecraft.gameSettings.keyBindsHotbar[index].isActiveAndMatches(keyCode)) {
                return index;
            }
        }
        return -1;
    }


    private void sanitizeLockedDragSlots(GuiContainer gui, Minecraft minecraft) {
        if (gui == null || minecraft.player == null || !LOCK_STORE.ensureCurrentContext(minecraft)) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Set<Slot> dragSlots = (Set<Slot>) GUI_DRAG_SPLITTING_SLOTS.get(gui);
            Iterator<Slot> iterator = dragSlots.iterator();
            while (iterator.hasNext()) {
                Slot slot = iterator.next();
                if (isLockedSlot(slot, minecraft.player.inventory)) {
                    iterator.remove();
                }
            }
        } catch (IllegalAccessException exception) {

        }
    }


    private void consumeKeyBinding(KeyBinding keyBinding) {
        KeyBinding.setKeyBindState(keyBinding.getKeyCode(), false);
        while (keyBinding.isPressed()) {
        }
    }

    private void drawLockedIcon(int x, int y) {
        Minecraft minecraft = Minecraft.getMinecraft();
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        minecraft.getTextureManager().bindTexture(LOCKED_TEXTURE);
        Gui.drawModalRectWithCustomSizedTexture(x, y, 0.0F, 0.0F, 5, 7, 5.0F, 7.0F);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

}
