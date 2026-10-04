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
import net.minecraft.util.EnumHandSide;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiContainerEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
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
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

@Mod(modid = SlotGuard.MODID, name = SlotGuard.NAME, version = SlotGuard.VERSION, clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]")
public class SlotGuard {
    public static final String MODID = "slotguard";
    public static final String NAME = "SlotGuard";
    public static final String VERSION = "1.0.1";

    private static final ResourceLocation LOCKED_TEXTURE = new ResourceLocation(MODID, "textures/gui/locked.png");
    private static final int DEFAULT_LOCK_KEY = Keyboard.KEY_L;
    private static final int DEFAULT_UNLOCK_KEY = Keyboard.KEY_U;
    private static final long CLEAR_ALL_HOLD_DURATION = 2000L;
    private static final int LOCK_RESET_GREEN = 0x80008000;
    private static final KeyBinding LOCK_KEY = new KeyBinding("key.slotguard.lock", DEFAULT_LOCK_KEY, "key.categories.slotguard");
    private static final KeyBinding UNLOCK_KEY = new KeyBinding("key.slotguard.unlock", DEFAULT_UNLOCK_KEY, "key.categories.slotguard");
    private static final KeyBinding CLEAR_ALL_KEY = new KeyBinding(
            "key.slotguard.clear_all",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.CONTROL,
            DEFAULT_UNLOCK_KEY,
            "key.categories.slotguard");
    private static final LockStore LOCK_STORE = new LockStore();
    private static final int[] SLOT_DESIGN_ROWS = {
            0x01E00,
            0x03FF8,
            0x03FFC,
            0x07FFC,
            0x07FFE,
            0x0FFFE,
            0x0FFFE,
            0x1FFFE,
            0x1FFFE,
            0x3FFFE,
            0x1FFFF,
            0x0FFFF,
            0x07FFF,
            0x03FFE,
            0x01FF8,
            0x00FE0,
            0x00780,
            0x00200
    };
    private static boolean ENDLESS_SUPPORT = false;
    private static boolean HOTBAR_KEYBIND_ENABLED = true;
    private static boolean SHOW_PADLOCK_ICONS = true;
    private static final Field GUI_DRAG_SPLITTING_SLOTS =
            ObfuscationReflectionHelper.findField(GuiContainer.class, "field_147008_s");
    private static boolean blockedMouseGesture;
    private static long clearAllHoldStart = -1L;
    private static boolean clearAllTriggered;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();
        ENDLESS_SUPPORT = config.getBoolean(
                "EndlessSupport",
                Configuration.CATEGORY_GENERAL,
                false,
                "Use the Endless Texture Pack inventory-slot shape for the reset progress animation.");
        HOTBAR_KEYBIND_ENABLED = config.getBoolean(
                "enableHotbarKeybind",
                Configuration.CATEGORY_GENERAL,
                true,
                "Allow to lock and unlock keybinds to work outside inventory screen");
        SHOW_PADLOCK_ICONS = config.getBoolean(
                "showPadlockIcons",
                Configuration.CATEGORY_GENERAL,
                true,
                "Show padlock icons on the hotbar.");
        if (config.hasChanged()) {
            config.save();
        }

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

        if (isTextFieldFocused(gui)) {
            drainKeyBinding(LOCK_KEY);
            drainKeyBinding(UNLOCK_KEY);
            drainKeyBinding(CLEAR_ALL_KEY);
            resetClearAllHold();
            return;
        }

        if (CLEAR_ALL_KEY.isActiveAndMatches(keyCode)) {
            event.setCanceled(true);
            drainKeyBinding(UNLOCK_KEY);
            drainKeyBinding(CLEAR_ALL_KEY);
            return;
        }

        if (Keyboard.getEventKeyState() && LOCK_KEY.isActiveAndMatches(keyCode)) {
            if (handleLockBinding(minecraft, true)) {
                event.setCanceled(true);
                consumeKeyBinding(LOCK_KEY);
                return;
            }
        }
        if (Keyboard.getEventKeyState() && UNLOCK_KEY.isActiveAndMatches(keyCode)) {
            if (handleLockBinding(minecraft, false)) {
                event.setCanceled(true);
                consumeKeyBinding(UNLOCK_KEY);
                return;
            }
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
            drainKeyBinding(CLEAR_ALL_KEY);
            resetClearAllHold();
            blockedMouseGesture = false;
            return;
        }

        if (minecraft.currentScreen instanceof GuiContainer) {
            sanitizeLockedDragSlots((GuiContainer) minecraft.currentScreen, minecraft);
        }

        boolean containerOpen = minecraft.currentScreen instanceof GuiContainer;
        if (minecraft.currentScreen != null && !containerOpen) {
            drainKeyBinding(LOCK_KEY);
            drainKeyBinding(UNLOCK_KEY);
            drainKeyBinding(CLEAR_ALL_KEY);
            resetClearAllHold();
            return;
        }

        boolean textFieldFocused = containerOpen
                && isTextFieldFocused((GuiContainer) minecraft.currentScreen);
        if (textFieldFocused) {
            drainKeyBinding(LOCK_KEY);
            drainKeyBinding(UNLOCK_KEY);
            drainKeyBinding(CLEAR_ALL_KEY);
            resetClearAllHold();
            return;
        }

        boolean clearAllDown = isClearAllKeyDown();
        if (clearAllDown) {
            drainKeyBinding(LOCK_KEY);
            drainKeyBinding(UNLOCK_KEY);
            drainKeyBinding(CLEAR_ALL_KEY);
            updateClearAllHold(minecraft);
        } else {
            resetClearAllHold();
            if (containerOpen || HOTBAR_KEYBIND_ENABLED) {
                while (LOCK_KEY.isPressed()) {
                    handleLockBinding(minecraft, true);
                }
                while (UNLOCK_KEY.isPressed()) {
                    handleLockBinding(minecraft, false);
                }
            } else {
                drainKeyBinding(LOCK_KEY);
                drainKeyBinding(UNLOCK_KEY);
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
                    || isLockedSlot(hovered, minecraft.player.inventory)) {
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

    private boolean handleLockBinding(Minecraft minecraft, boolean locked) {
        if (minecraft.currentScreen instanceof GuiContainer) {
            Slot hovered = ((GuiContainer) minecraft.currentScreen).getSlotUnderMouse();
            PlayerSlotKey key = PlayerSlotKey.fromSlot(hovered, minecraft.player.inventory);
            if (key == null || (!HOTBAR_KEYBIND_ENABLED && isHotbarKey(key))) {
                return false;
            }
            if (!LOCK_STORE.ensureCurrentContext(minecraft)) {
                return false;
            }
            LOCK_STORE.setLocked(key, locked);
            return true;
        }
        if (minecraft.currentScreen == null && minecraft.world != null && HOTBAR_KEYBIND_ENABLED) {
            PlayerSlotKey key = PlayerSlotKey.main(minecraft.player.inventory.currentItem);
            if (key != null && LOCK_STORE.ensureCurrentContext(minecraft)) {
                LOCK_STORE.setLocked(key, locked);
                return true;
            }
        }
        return false;
    }

    private boolean isHotbarKey(PlayerSlotKey key) {
        int inventoryIndex = key.getInventoryIndex();
        return inventoryIndex >= 0 && inventoryIndex < 9;
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

    private boolean isTextFieldFocused(GuiContainer gui) {
        if (gui == null) {
            return false;
        }
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        return isTextFieldFocused(gui, visited, 6, true);
    }

    private boolean isTextFieldFocused(Object object, Set<Object> visited, int depth, boolean inspectMinecraftObject) {
        if (object == null || depth < 0 || visited.contains(object)) {
            return false;
        }
        visited.add(object);

        if (object instanceof GuiTextField) {
            return ((GuiTextField) object).isFocused();
        }

        Class<?> type = object.getClass();
        if (type.isArray()) {
            int length = Array.getLength(object);
            for (int index = 0; index < length; index++) {
                if (isTextFieldFocused(Array.get(object, index), visited, depth - 1, false)) {
                    return true;
                }
            }
            return false;
        }

        if (object instanceof Iterable) {
            for (Object value : (Iterable<?>) object) {
                if (isTextFieldFocused(value, visited, depth - 1, false)) {
                    return true;
                }
            }
            return false;
        }

        if (object instanceof Map) {
            for (Object value : ((Map<?, ?>) object).values()) {
                if (isTextFieldFocused(value, visited, depth - 1, false)) {
                    return true;
                }
            }
            return false;
        }

        Package objectPackage = type.getPackage();
        String packageName = objectPackage == null ? "" : objectPackage.getName();
        boolean inspectGuiObject = object instanceof Gui;
        if (!inspectMinecraftObject && !inspectGuiObject
                && (packageName.startsWith("java.") || packageName.startsWith("javax.")
                || packageName.startsWith("net.minecraft.") || packageName.startsWith("net.minecraftforge."))) {
            return false;
        }
        if (packageName.startsWith("java.") || packageName.startsWith("javax.")) {
            return false;
        }

        Class<?> currentType = type;
        while (currentType != null && currentType != Object.class) {
            Field[] fields = currentType.getDeclaredFields();
            for (Field field : fields) {
                int modifiers = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(modifiers)
                        || field.getType().isPrimitive()) {
                    continue;
                }
                try {
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    if (isTextFieldFocused(field.get(object), visited, depth - 1, false)) {
                        return true;
                    }
                } catch (IllegalAccessException exception) {
                } catch (SecurityException exception) {
                }
            }
            currentType = currentType.getSuperclass();
        }
        return false;
    }

    private void startClearAllHold() {
        if (clearAllHoldStart < 0L) {
            clearAllHoldStart = Minecraft.getSystemTime();
            clearAllTriggered = false;
        }
    }

    private void updateClearAllHold(Minecraft minecraft) {
        if (clearAllHoldStart < 0L) {
            startClearAllHold();
            return;
        }
        long now = Minecraft.getSystemTime();
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
        int progressHeight = getClearAllProgressHeight();
        for (Slot slot : gui.inventorySlots.inventorySlots) {
            PlayerSlotKey key = PlayerSlotKey.fromSlot(slot, minecraft.player.inventory);
            if (key != null && LOCK_STORE.isLocked(key)) {
                if (progressHeight > 0) {
                    drawRectangularLockResetProgress(slot.xPos, slot.yPos, progressHeight);
                }
                drawLockedIcon(slot.xPos + 10, slot.yPos + 1);
            }
        }
    }

    @SubscribeEvent
    public void onHotbar(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.HOTBAR || !SHOW_PADLOCK_ICONS) {
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
                int x = hotbarX + index * 20;
                drawLockedIcon(x + 13, hotbarY + 4);
            }
        }

        if (LOCK_STORE.isLocked(PlayerSlotKey.offhand())
                && !minecraft.player.getHeldItemOffhand().isEmpty()) {
            int itemX = minecraft.player.getPrimaryHand() == EnumHandSide.RIGHT
                    ? hotbarX - 26
                    : hotbarX + 101;
            drawLockedIcon(itemX + 11, hotbarY + 4);
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

    private static int getClearAllProgressHeight() {
        if (clearAllHoldStart < 0L || clearAllTriggered) {
            return clearAllTriggered ? 16 : 0;
        }
        long elapsed = Minecraft.getSystemTime() - clearAllHoldStart;
        if (elapsed <= 0L) {
            return 1;
        }
        return (int) Math.min(16L, (elapsed * 16L + CLEAR_ALL_HOLD_DURATION - 1L)
                / CLEAR_ALL_HOLD_DURATION);
    }

    private static void drawRectangularLockResetProgress(int x, int y, int height) {
        int progressHeight = Math.max(0, Math.min(16, height));
        if (progressHeight <= 0) {
            return;
        }
        Gui.drawRect(x, y + 16 - progressHeight, x + 16, y + 16, LOCK_RESET_GREEN);
    }

    public static void renderHotbarLockResetProgress(ScaledResolution resolution) {
        if (resolution == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player == null || minecraft.world == null || !LOCK_STORE.ensureCurrentContext(minecraft)) {
            return;
        }
        int progressHeight = getClearAllProgressHeight();
        if (progressHeight <= 0) {
            return;
        }

        int hotbarX = resolution.getScaledWidth() / 2 - 91;
        int hotbarY = resolution.getScaledHeight() - 22;
        GlStateManager.disableDepth();
        GlStateManager.disableLighting();
        for (int index = 0; index < 9; index++) {
            if (LOCK_STORE.isLocked(PlayerSlotKey.main(index))) {
                int x = hotbarX + index * 20;
                drawHotbarLockResetProgress(
                        ENDLESS_SUPPORT ? x + 1 : x + 3,
                        hotbarY + 3,
                        progressHeight);
            }
        }

        if (LOCK_STORE.isLocked(PlayerSlotKey.offhand())
                && !minecraft.player.getHeldItemOffhand().isEmpty()) {
            int itemX = minecraft.player.getPrimaryHand() == EnumHandSide.RIGHT
                    ? hotbarX - 26
                    : hotbarX + 101;
            int progressX = ENDLESS_SUPPORT ? itemX - 1 : itemX + 1;
            drawHotbarLockResetProgress(progressX, hotbarY + 3, progressHeight);
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
        GlStateManager.enableBlend();
    }

    private static void drawHotbarLockResetProgress(int x, int y, int height) {
        int progressHeight = Math.max(0, Math.min(16, height));
        if (progressHeight <= 0) {
            return;
        }
        if (!ENDLESS_SUPPORT) {
            drawRectangularLockResetProgress(x, y, progressHeight);
            return;
        }

        int top = SLOT_DESIGN_ROWS.length - progressHeight;
        for (int row = 0; row < SLOT_DESIGN_ROWS.length; row++) {
            if (row < top) {
                continue;
            }

            int mask = SLOT_DESIGN_ROWS[row];
            int runStart = -1;
            for (int column = 0; column < 18; column++) {
                boolean filled = (mask & (1 << column)) != 0;
                if (filled && runStart < 0) {
                    runStart = column;
                } else if (!filled && runStart >= 0) {
                    Gui.drawRect(
                            x + runStart,
                            y + row,
                            x + column,
                            y + row + 1,
                            LOCK_RESET_GREEN);
                    runStart = -1;
                }
            }
            if (runStart >= 0) {
                Gui.drawRect(
                        x + runStart,
                        y + row,
                        x + 18,
                        y + row + 1,
                        LOCK_RESET_GREEN);
            }
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
