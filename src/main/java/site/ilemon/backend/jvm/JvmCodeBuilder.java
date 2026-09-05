package site.ilemon.backend.jvm;

import site.ilemon.exception.CompilerException;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Accumulates JVM instructions with symbolic labels, then lays them out into
 * bytecode and patches branch offsets. This is the single place that knows the
 * binary encoding of the emitted instruction vocabulary.
 */
final class JvmCodeBuilder {

    /** A laid-out instruction. {@code label} is non-null only for branch instructions. */
    static final class Insn {
        final int opcode;
        final int[] operands;
        final String target;
        final int extra;
        final boolean wide;

        Insn(int opcode, int[] operands, String target, int extra, boolean wide) {
            this.opcode = opcode;
            this.operands = operands;
            this.target = target;
            this.extra = extra;
            this.wide = wide;
        }

        @Override
        public String toString() {
            return "Insn[0x" + Integer.toHexString(opcode)
                    + (target != null ? " -> " + target : "") + "]";
        }
    }

    private static final int WIDE = 0xC4;

    private final List<Insn> insns = new ArrayList<>();
    private final Map<String, Integer> labelPositions = new HashMap<>();

    void label(String name) {
        labelPositions.put(name, insns.size());
        insns.add(new Insn(0, null, name, 0, false));
    }

    void simple(int opcode) {
        insns.add(new Insn(opcode, null, null, 0, false));
    }

    void ldc(int constantPoolIndex, int slots) {
        insns.add(new Insn(constantPoolIndex > 0xFF ? 0x13 /* ldc_w */ : 0x12 /* ldc */,
                new int[]{constantPoolIndex}, null, slots, false));
    }

    /** Long/double constants must be loaded with ldc2_w (0x14), never ldc/ldc_w. */
    void ldc2w(int constantPoolIndex) {
        insns.add(new Insn(0x14, new int[]{constantPoolIndex}, null, 2, false));
    }

    void load(int opcode, int index) {
        insns.add(new Insn(opcode, new int[]{index}, null, 0, index > 0xFF));
    }

    void store(int opcode, int index) {
        insns.add(new Insn(opcode, new int[]{index}, null, 0, index > 0xFF));
    }

    void cpRef(int opcode, int constantPoolIndex) {
        insns.add(new Insn(opcode, new int[]{constantPoolIndex}, null, 0, false));
    }

    void newarray(int atype) {
        insns.add(new Insn(0xBC, new int[]{atype}, null, 0, false));
    }

    void branch(int opcode, String targetLabel) {
        insns.add(new Insn(opcode, null, targetLabel, 0, false));
    }

    void invoke(int opcode, int constantPoolIndex, int argSlots, int returnSlots) {
        insns.add(new Insn(opcode, new int[]{constantPoolIndex}, null,
                (argSlots << 8) | returnSlots, false));
    }

    List<Insn> insns() {
        return insns;
    }

    /** Lays out instructions, resolves labels, and patches branch offsets. */
    byte[] toBytecode() {
        int[] positions = new int[insns.size()];
        int pc = 0;
        for (int i = 0; i < insns.size(); i++) {
            positions[i] = pc;
            pc += size(insns.get(i));
        }
        if (pc > 0xFFFF) {
            throw new CompilerException("Method bytecode exceeds the 64K JVM limit: " + pc + " bytes");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(pc);
        List<int[]> branchFixups = new ArrayList<>(); // {insnIndex, offsetFieldPosition}
        for (int i = 0; i < insns.size(); i++) {
            Insn insn = insns.get(i);
            if (insn.opcode == 0) {
                continue; // label marker
            }
            if (insn.target != null) {
                // goto / if<cond>: opcode + s2 offset
                out.write(insn.opcode);
                branchFixups.add(new int[]{i, out.size()});
                out.write(0);
                out.write(0);
                continue;
            }
            if (insn.wide) {
                out.write(WIDE);
                out.write(insn.opcode);
                writeU2(out, insn.operands[0]);
                continue;
            }
            int opcode = insn.opcode;
            out.write(opcode);
            if (insn.operands == null) {
                continue;
            }
            if (opcode == 0xBC) { // newarray: u1 atype
                out.write(insn.operands[0]);
            } else if (opcode == 0x12 || opcode == 0x13 || opcode == 0x14) { // ldc / ldc_w / ldc2_w
                if (opcode == 0x12) {
                    out.write(insn.operands[0]);
                } else {
                    writeU2(out, insn.operands[0]);
                }
            } else if (isLocalOpcode(opcode)) { // iload..aload / istore..astore: u1 index
                out.write(insn.operands[0]);
            } else {
                writeU2(out, insn.operands[0]);
            }
        }

        byte[] code = out.toByteArray();
        for (int[] fixup : branchFixups) {
            int insnIndex = fixup[0];
            int offsetField = fixup[1];
            String target = insns.get(insnIndex).target;
            Integer targetInsn = labelPositions.get(target);
            if (targetInsn == null) {
                throw new CompilerException("Missing JVM label target: " + target);
            }
            int here = positions[insnIndex];
            // JVM branch offsets are measured from the address of the branch
            // opcode itself (verified against javac output), not from pc+3.
            int offset = positions[targetInsn] - here;
            if (offset < Short.MIN_VALUE || offset > Short.MAX_VALUE) {
                throw new CompilerException("JVM branch offset out of range at " + here);
            }
            code[offsetField] = (byte) (offset >>> 8);
            code[offsetField + 1] = (byte) offset;
        }
        return code;
    }

    private static int size(Insn insn) {
        if (insn.opcode == 0) {
            return 0;
        }
        if (insn.target != null) {
            return 3;
        }
        if (insn.wide) {
            return 4;
        }
        int opcode = insn.opcode;
        if (insn.operands == null) {
            return 1;
        }
        if (opcode == 0xBC || opcode == 0x12 || isLocalOpcode(opcode)) {
            return 2; // newarray/ldc/loads/stores: opcode + u1
        }
        return 3; // ldc_w/ldc2_w, getstatic, invoke*, anewarray
    }

    /** Loads/stores take a u1 index (wide prefix handled separately). */
    private static boolean isLocalOpcode(int opcode) {
        return (opcode >= 0x15 && opcode <= 0x19) || (opcode >= 0x36 && opcode <= 0x3A);
    }

    private static void writeU2(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}