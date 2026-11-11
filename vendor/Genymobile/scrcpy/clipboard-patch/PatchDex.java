import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.DexFileFactory;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.OneRegisterInstruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.reference.StringReference;
import org.jf.dexlib2.immutable.ImmutableDexFile;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction31c;
import org.jf.dexlib2.immutable.reference.ImmutableStringReference;
import org.jf.dexlib2.rewriter.*;

/** Replace only the clipboard class and server version strings in the existing DEX. */
public final class PatchDex {
    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException("Usage: PatchDex <input.dex> <clipboard.dex> <output.dex> <old-version> <new-version>");
        }
        String oldVersion = args[3];
        String newVersion = args[4];
        String target = "Lcom/genymobile/scrcpy/wrappers/ClipboardManager;";
        DexFile original = DexFileFactory.loadDexFile(new File(args[0]), Opcodes.getDefault());
        DexFile patch = DexFileFactory.loadDexFile(new File(args[1]), Opcodes.getDefault());
        List<ClassDef> classes = new ArrayList<>();
        int removed = 0, added = 0;
        for (ClassDef c : original.getClasses()) {
            if (c.getType().equals(target)) { removed++; } else { classes.add(c); }
        }
        for (ClassDef c : patch.getClasses()) {
            classes.removeIf(existing -> existing.getType().equals(c.getType()));
            classes.add(c);
            if (c.getType().equals(target)) { added++; }
        }
        if (removed != 1 || added != 1) { throw new IllegalStateException("Clipboard replacement failed"); }
        final int[] versions = {0};
        DexRewriter rewriter = new DexRewriter(new RewriterModule() {
            @Override public Rewriter<Instruction> getInstructionRewriter(Rewriters rewriters) {
                return instruction -> {
                    Opcode op = instruction.getOpcode();
                    if (op != Opcode.CONST_STRING && op != Opcode.CONST_STRING_JUMBO) { return instruction; }
                    String value = ((StringReference) ((ReferenceInstruction) instruction).getReference()).getString();
                    if (!value.contains(oldVersion)) { return instruction; }
                    versions[0]++;
                    ImmutableStringReference ref = new ImmutableStringReference(value.replace(oldVersion, newVersion));
                    int register = ((OneRegisterInstruction) instruction).getRegisterA();
                    return op == Opcode.CONST_STRING ? new ImmutableInstruction21c(op, register, ref)
                        : new ImmutableInstruction31c(op, register, ref);
                };
            }
        });
        DexFile result = rewriter.getDexFileRewriter().rewrite(new ImmutableDexFile(original.getOpcodes(), classes));
        DexFileFactory.writeDexFile(args[2], result);
        if (versions[0] == 0) { throw new IllegalStateException("Server version was not updated"); }
        System.out.println("Patched clipboard; preserved " + (classes.size() - 1) + " other classes; version " + newVersion);
    }
}
