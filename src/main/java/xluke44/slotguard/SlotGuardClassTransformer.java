package xluke44.slotguard;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public class SlotGuardClassTransformer implements IClassTransformer {

    private static final String HOOK_OWNER =
            "xluke44/slotguard/SlotGuardHooks";

    private static final String DEOBF_SLOT =
            "net.minecraft.inventory.Slot";

    private static final String OBF_SLOT =
            "agr";

    private static final String DEOBF_PLAYER =
            "net.minecraft.entity.player.EntityPlayer";

    private static final String OBF_PLAYER =
            "aed";

    private static final String DEOBF_CREATIVE_CONTAINER =
            "net.minecraft.client.gui.inventory.GuiContainerCreative$ContainerCreative";

    private static final String OBF_CREATIVE_CONTAINER =
            "bmp$b";

    private static final String DEOBF_CREATIVE_SLOT =
            "net.minecraft.client.gui.inventory.GuiContainerCreative$CreativeSlot";

    private static final String OBF_CREATIVE_SLOT =
            "bmp$c";

    @Override
    public byte[] transform(
            String name,
            String transformedName,
            byte[] basicClass) {

        if (basicClass == null) {
            return null;
        }

        if (DEOBF_SLOT.equals(transformedName)) {
            return transformSlot(name, basicClass);
        }

        if (DEOBF_CREATIVE_CONTAINER.equals(transformedName)) {
            return transformCreativeContainer(name, basicClass);
        }

        return basicClass;
    }

    private byte[] transformSlot(
            String name,
            byte[] basicClass) {

        boolean obfuscated =
                !DEOBF_SLOT.equals(name);

        String playerClass =
                obfuscated
                        ? OBF_PLAYER
                        : DEOBF_PLAYER;

        String canTakeDescriptor =
                "(L" + playerClass + ";)Z";

        String hookDescriptor =
                "(Lnet/minecraft/inventory/Slot;L"
                        + playerClass
                        + ";)Z";

        ClassNode node = read(basicClass);

        for (MethodNode method : node.methods) {

            if (!canTakeDescriptor.equals(method.desc)
                    || method.instructions == null) {
                continue;
            }

            InsnList guard = new InsnList();

            LabelNode allowed =
                    new LabelNode();

            guard.add(
                    new VarInsnNode(
                            Opcodes.ALOAD,
                            0
                    )
            );

            guard.add(
                    new VarInsnNode(
                            Opcodes.ALOAD,
                            1
                    )
            );

            guard.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESTATIC,
                            HOOK_OWNER,
                            "isLockedPlayerSlot",
                            hookDescriptor,
                            false
                    )
            );

            guard.add(
                    new JumpInsnNode(
                            Opcodes.IFEQ,
                            allowed
                    )
            );

            guard.add(
                    new InsnNode(
                            Opcodes.ICONST_0
                    )
            );

            guard.add(
                    new InsnNode(
                            Opcodes.IRETURN
                    )
            );

            guard.add(allowed);

            method.instructions.insert(guard);

            method.maxStack =
                    Math.max(
                            method.maxStack,
                            2
                    );

            return write(node);
        }

        return basicClass;
    }

    private byte[] transformCreativeContainer(
            String name,
            byte[] basicClass) {

        boolean obfuscated =
                !DEOBF_CREATIVE_CONTAINER.equals(name);

        String slotClass =
                obfuscated
                        ? OBF_SLOT
                        : DEOBF_SLOT.replace('.', '/');

        String creativeSlotClass =
                obfuscated
                        ? OBF_CREATIVE_SLOT
                        : DEOBF_CREATIVE_SLOT;

        String yPosField =
                obfuscated
                        ? "g"
                        : "yPos";

        String mergeDescriptor =
                "(L"
                        + (obfuscated
                        ? "aip"
                        : "net/minecraft/item/ItemStack")
                        + ";L"
                        + slotClass
                        + ";)Z";

        ClassNode node =
                read(basicClass);

        for (MethodNode method : node.methods) {

            if (!mergeDescriptor.equals(method.desc)) {
                continue;
            }

            method.instructions =
                    new InsnList();

            if (method.tryCatchBlocks != null) {
                method.tryCatchBlocks.clear();
            }

            if (method.localVariables != null) {
                method.localVariables.clear();
            }

            LabelNode useVanillaRule =
                    new LabelNode();

            LabelNode allow =
                    new LabelNode();

            method.instructions.add(
                    new VarInsnNode(
                            Opcodes.ALOAD,
                            2
                    )
            );

            method.instructions.add(
                    new TypeInsnNode(
                            Opcodes.INSTANCEOF,
                            creativeSlotClass
                    )
            );

            method.instructions.add(
                    new JumpInsnNode(
                            Opcodes.IFNE,
                            allow
                    )
            );

            method.instructions.add(
                    new VarInsnNode(
                            Opcodes.ALOAD,
                            2
                    )
            );

            method.instructions.add(
                    new FieldInsnNode(
                            Opcodes.GETFIELD,
                            slotClass,
                            yPosField,
                            "I"
                    )
            );

            method.instructions.add(
                    new IntInsnNode(
                            Opcodes.BIPUSH,
                            90
                    )
            );

            method.instructions.add(
                    new JumpInsnNode(
                            Opcodes.IF_ICMPLE,
                            useVanillaRule
                    )
            );

            method.instructions.add(
                    new JumpInsnNode(
                            Opcodes.GOTO,
                            allow
                    )
            );

            method.instructions.add(
                    useVanillaRule
            );

            method.instructions.add(
                    new InsnNode(
                            Opcodes.ICONST_0
                    )
            );

            method.instructions.add(
                    new InsnNode(
                            Opcodes.IRETURN
                    )
            );

            method.instructions.add(
                    allow
            );

            method.instructions.add(
                    new InsnNode(
                            Opcodes.ICONST_1
                    )
            );

            method.instructions.add(
                    new InsnNode(
                            Opcodes.IRETURN
                    )
            );

            method.maxStack = 2;

            return write(node);
        }

        return basicClass;
    }

    private ClassNode read(byte[] bytes) {
        ClassReader reader =
                new ClassReader(bytes);

        ClassNode node =
                new ClassNode();

        reader.accept(
                node,
                0
        );

        return node;
    }

    private byte[] write(ClassNode node) {
        ClassWriter writer =
                new ClassWriter(
                        ClassWriter.COMPUTE_FRAMES
                                | ClassWriter.COMPUTE_MAXS
                );

        node.accept(writer);

        return writer.toByteArray();
    }
}
