package xluke44.slotguard;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class LockStore {
    private final Set<String> locks = new HashSet<String>();
    private File directory;
    private File currentFile;

    public void setDirectory(File directory) {
        this.directory = directory;
    }

    public boolean ensureCurrentContext(Minecraft minecraft) {
        File file = getContextFile(minecraft);
        if (file == null) {
            currentFile = null;
            locks.clear();
            SlotGuardHooks.replaceLockedKeys(locks);
            return false;
        }
        if (file.equals(currentFile)) {
            return true;
        }
        currentFile = file;
        load(currentFile);
        return true;
    }

    public boolean isLocked(PlayerSlotKey key) {
        return key != null && locks.contains(key.getValue());
    }

    public void setLocked(PlayerSlotKey key, boolean locked) {
        if (key == null || currentFile == null) {
            return;
        }
        boolean changed;
        if (locked) {
            changed = locks.add(key.getValue());
        } else {
            changed = locks.remove(key.getValue());
        }
        if (changed) {
            save(currentFile);
            SlotGuardHooks.replaceLockedKeys(locks);
        }
    }

    public boolean clearAll() {
        if (currentFile == null || locks.isEmpty()) {
            return false;
        }
        locks.clear();
        save(currentFile);
        SlotGuardHooks.replaceLockedKeys(locks);
        return true;
    }

    private File getContextFile(Minecraft minecraft) {
        if (directory == null || minecraft == null || minecraft.world == null || minecraft.player == null) {
            return null;
        }

        if (minecraft.isSingleplayer()) {
            File worldDirectory = minecraft.world.getSaveHandler() == null
                    ? null
                    : minecraft.world.getSaveHandler().getWorldDirectory();
            String worldId = worldDirectory == null ? null : worldDirectory.getName();

            if (worldId == null || worldId.length() == 0) {
                if (minecraft.getIntegratedServer() != null) {
                    worldId = minecraft.getIntegratedServer().getFolderName();
                }
            }
            if (worldId == null || worldId.length() == 0) {
                return null;
            }

            return new File(new File(directory, "worlds"), safeFileName(worldId) + ".lock");
        }

        ServerData serverData = minecraft.getCurrentServerData();
        if (serverData == null || serverData.serverIP == null) {
            return null;
        }
        String serverIP = serverData.serverIP.trim();
        if (serverIP.length() == 0) {
            return null;
        }

        return new File(new File(directory, "servers"), safeFileName(serverIP) + ".lock");
    }

    private String safeFileName(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\' || character == '/' || character == ':' || character == '*' || character == '?'
                    || character == '"' || character == '<' || character == '>' || character == '|') {
                builder.append('_');
            } else {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private void load(File file) {
        locks.clear();
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (PlayerSlotKey.isValid(line)) {
                        locks.add(line);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException exception) {
            locks.clear();
        }
        SlotGuardHooks.replaceLockedKeys(locks);
    }

    private void save(File file) {
        if (file == null) {
            return;
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            return;
        }
        List<String> values = new ArrayList<String>(locks);
        Collections.sort(values);
        File temporary = new File(parent, file.getName() + ".tmp");
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8));
            try {
                for (String value : values) {
                    writer.write(value);
                    writer.newLine();
                }
            } finally {
                writer.close();
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            temporary.delete();
        }
    }
}
