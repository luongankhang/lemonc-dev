package site.ilemon.backend.jvm;

import site.ilemon.exception.CompilerException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes the exact operand-stack limit of an emitted JVM method and validates
 * that control-flow joins meet at equal stack heights (invalid JVM bytecode is
 * rejected before the {@code .class} is written).
 *
 * <p>Unlike a plain height counter, this tracker simulates the actual stack
 * width composition so {@code dup_x2} (used by 2-slot {@code System.out.print}
 * lowering) gets the correct effect.</p>
 */
final class JvmStackTracker {

    private static final int WIDE = 0xC4;

    private JvmStackTracker() {
    }

    /** @return the maximum operand-stack height (in slots) of the method. */
    static int computeMaxStack(List<JvmCodeBuilder.Insn> insns, JvmClassWriter pool) {
        Map<String, Integer> labelIndexes = new HashMap<>();
        for (int i = 0; i < insns.size(); i++) {
            JvmCodeBuilder.Insn insn = insns.get(i);
            if (insn.opcode == 0) {
                labelIndexes.put(insn.target, i);
            }
        }

        int[] inHeights = new int[insns.size()];
        Arrays.fill(inHeights, -1);
        Deque<WorkItem> worklist = new ArrayDeque<>();
        inHeights[0] = 0;
        worklist.add(new WorkItem(0, new ArrayList<>()));

        int max = 0;
        while (!worklist.isEmpty()) {
            WorkItem item = worklist.removeFirst();
            int index = item.index;
            List<Integer> stack = item.stack;

            int height = 0;
            for (int w : stack) {
                height += w;
            }
            if (height > max) {
                max = height;
            }

            JvmCodeBuilder.Insn insn = insns.get(index);

            // Apply the instruction's stack effect in place; successors then
            // receive the post-instruction stack (branch targets must not see
            // the consumed condition operands).
            if (insn.opcode == 0) {
                // label marker: no effect
            } else if (insn.target != null) {
                applyBranch(insn.opcode, stack);
            } else {
                apply(insn, stack, pool);
            }

            if (heightOf(stack) < 0) {
                throw new CompilerException("Operand stack underflow after "
                        + opcodeName(insn.opcode) + " at IR index " + index);
            }

            List<Integer> successors = successors(insns, index, labelIndexes);
            for (Integer successor : successors) {
                if (successor == null) {
                    continue;
                }
                int nextHeight = heightOf(stack);
                int oldHeight = inHeights[successor];
                if (oldHeight == -1) {
                    inHeights[successor] = nextHeight;
                    worklist.add(new WorkItem(successor, new ArrayList<>(stack)));
                } else if (oldHeight != nextHeight) {
                    throw new CompilerException("Inconsistent operand stack height at IR index "
                            + successor + ": expected " + oldHeight + ", got " + nextHeight);
                }
            }
        }
        return max;
    }

    private record WorkItem(int index, List<Integer> stack) {
    }

    private static List<Integer> successors(List<JvmCodeBuilder.Insn> insns, int index,
                                            Map<String, Integer> labelIndexes) {
        JvmCodeBuilder.Insn insn = insns.get(index);
        List<Integer> result = new ArrayList<>(2);
        if (insn.opcode == 0) {
            if (index + 1 < insns.size()) {
                result.add(index + 1);
            }
            return result;
        }
        if (insn.target != null) {
            Integer target = labelIndexes.get(insn.target);
            if (target == null) {
                throw new CompilerException("Missing JVM label target: " + insn.target);
            }
            result.add(target);
            if (!isUnconditional(insn.opcode)) {
                if (index + 1 < insns.size()) {
                    result.add(index + 1);
                }
            }
            return result;
        }
        if (isReturn(insn.opcode)) {
            return result;
        }
        if (index + 1 < insns.size()) {
            result.add(index + 1);
        }
        return result;
    }

    private static boolean isUnconditional(int opcode) {
        return opcode == 0xA7; // goto
    }

    private static boolean isReturn(int opcode) {
        return opcode == 0xAC || opcode == 0xAD || opcode == 0xAE
                || opcode == 0xAF || opcode == 0xB0 || opcode == 0xB1;
    }

    /** Applies a branch instruction in place: pops the condition operands (goto pops nothing). */
    private static void applyBranch(int opcode, List<Integer> stack) {
        if (opcode >= 0x99 && opcode <= 0x9E) {
            // if<cond>: pops one int
            pop(stack, 1);
        } else if (opcode >= 0x9F && opcode <= 0xA4) {
            // if_icmp<cond>: pops two ints
            pop(stack, 2);
        }
    }

    private static int apply(JvmCodeBuilder.Insn insn, List<Integer> stack, JvmClassWriter pool) {
        int opcode = insn.opcode;
        switch (opcode) {
            case 0x12, 0x13, 0x14: // ldc, ldc_w, ldc2_w
                stack.add(insn.extra);
                return heightOf(stack);
            case 0x15, 0x17, 0x19: // iload, fload, aload
                stack.add(1);
                return heightOf(stack);
            case 0x16, 0x18: // lload, dload
                stack.add(2);
                return heightOf(stack);
            case 0x36, 0x38, 0x3A: // istore, fstore, astore
                pop(stack, 1);
                return heightOf(stack);
            case 0x37, 0x39: // lstore, dstore
                pop(stack, 2);
                return heightOf(stack);
            case 0x57: // pop
                pop(stack, 1);
                return heightOf(stack);
            case 0x58: // pop2
                pop(stack, 2);
                return heightOf(stack);
            case 0x59: // dup
                stack.add(stack.get(stack.size() - 1));
                return heightOf(stack);
            case 0x5B: // dup_x2 (only used for 2-slot print values below System.out)
                return dupX2(stack);
            case 0x5F: // swap
                swap(stack);
                return heightOf(stack);
            case 0x60, 0x64, 0x68, 0x6C, 0x70, 0x7E, 0x7F, 0x80: // iadd/isub/imul/idiv/irem/iand/ior/ixor
                pop2Push1(stack);
                return heightOf(stack);
            case 0x61, 0x65, 0x69, 0x6D, 0x71: // ladd/lsub/lmul/ldiv/lrem
                pop(stack, 4);
                stack.add(2);
                return heightOf(stack);
            case 0x62, 0x66, 0x6A, 0x6E, 0x72: // fadd/fsub/fmul/fdiv/frem
                pop2Push1(stack);
                return heightOf(stack);
            case 0x63, 0x67, 0x6B, 0x6F, 0x73: // dadd/dsub/dmul/ddiv/drem
                pop(stack, 4);
                stack.add(2);
                return heightOf(stack);
            case 0x94: // lcmp
                pop(stack, 4);
                stack.add(1);
                return heightOf(stack);
            case 0x95, 0x96: // fcmpl, fcmpg
                pop2Push1(stack);
                return heightOf(stack);
            case 0x97, 0x98: // dcmpl, dcmpg
                pop(stack, 4);
                stack.add(1);
                return heightOf(stack);
            case 0x85, 0x87: // i2l, i2d
                pop(stack, 1);
                stack.add(2);
                return heightOf(stack);
            case 0x86: // i2f
                return heightOf(stack);
            case 0x88, 0x89: // l2i, l2f
                pop(stack, 2);
                stack.add(1);
                return heightOf(stack);
            case 0x8A: // l2d
                return heightOf(stack);
            case 0x8B, 0x91, 0x92, 0x93: // f2i, i2b, i2c, i2s
                return heightOf(stack);
            case 0x8C, 0x8D: // f2l, f2d
                pop(stack, 1);
                stack.add(2);
                return heightOf(stack);
            case 0x8E, 0x8F: // d2l, d2f
                pop(stack, 2);
                stack.add(1);
                return heightOf(stack);
            case 0x90: // d2f
                pop(stack, 2);
                stack.add(1);
                return heightOf(stack);
            case 0xB2: // getstatic
                stack.add(1);
                return heightOf(stack);
            case 0xB6, 0xB8: // invokevirtual, invokestatic
                int argSlots = (insn.extra >>> 8) & 0xFF;
                int returnSlots = insn.extra & 0xFF;
                pop(stack, argSlots);
                if (returnSlots > 0) {
                    stack.add(returnSlots);
                }
                return heightOf(stack);
            case 0xBC: // newarray
                pop(stack, 1);
                stack.add(1);
                return heightOf(stack);
            case 0xBD: // anewarray
                pop(stack, 1);
                stack.add(1);
                return heightOf(stack);
            case 0xBE: // arraylength
                return heightOf(stack);
            case 0x2E, 0x30, 0x33, 0x34, 0x35, 0x32: // iaload/faload/baload/caload/saload/aaload
                pop(stack, 2);
                stack.add(1);
                return heightOf(stack);
            case 0x2F, 0x31: // laload, daload
                pop(stack, 2);
                stack.add(2);
                return heightOf(stack);
            case 0x4F, 0x51, 0x54, 0x55, 0x56, 0x53: // iastore/fastore/bastore/castore/sastore/aastore
                pop(stack, 3);
                return heightOf(stack);
            case 0x50, 0x52: // lastore, dastore
                pop(stack, 4);
                return heightOf(stack);
            case 0x01: // aconst_null
                stack.add(1);
                return heightOf(stack);
            case 0xAC, 0xAE, 0xB0: // ireturn/freturn/areturn
                pop(stack, 1);
                return heightOf(stack);
            case 0xAD, 0xAF: // lreturn/dreturn
                pop(stack, 2);
                return heightOf(stack);
            case 0xB1: // return
                return heightOf(stack);
            case 0xA7: // goto (handled as branch)
                return heightOf(stack);
            default:
                if (opcode == WIDE) {
                    return heightOf(stack);
                }
                throw new CompilerException("Missing stack effect for JVM opcode 0x"
                        + Integer.toHexString(opcode));
        }
    }

    /** dup_x2: our only use is [value(2 slots), System.out(1 slot)] → [System.out, value, System.out]. */
    private static int dupX2(List<Integer> stack) {
        if (stack.size() < 2) {
            throw new CompilerException("dup_x2 requires at least two stack values");
        }
        int top = stack.get(stack.size() - 1);
        int next = stack.get(stack.size() - 2);
        if (top == 1 && next == 2) {
            // form 2: [.., value2(2), value1(1)] → [.., value1, value2, value1]
            stack.remove(stack.size() - 1);           // pop value1
            stack.add(stack.size() - 1, 1);           // insert value1 before value2
            stack.add(1);                              // duplicate value1 on top
            return heightOf(stack);
        }
        if (top == 1 && next == 1) {
            // form 1: [.., v3, v2, v1] → [.., v1, v3, v2, v1]
            stack.remove(stack.size() - 1);
            stack.add(stack.size() - 2, 1);
            stack.add(1);
            return heightOf(stack);
        }
        if (top == 2 && next == 1) {
            // form 3: [.., value2(1), value1(2)] → [.., value1, value2, value1]
            stack.remove(stack.size() - 1);
            stack.add(stack.size() - 1, 2);
            stack.add(2);
            return heightOf(stack);
        }
        throw new CompilerException("Unsupported dup_x2 stack composition");
    }

    private static void swap(List<Integer> stack) {
        int top = stack.remove(stack.size() - 1);
        int next = stack.remove(stack.size() - 1);
        if (top != 1 || next != 1) {
            throw new CompilerException("swap requires two 1-slot values");
        }
        stack.add(top);
        stack.add(next);
    }

    private static void pop2Push1(List<Integer> stack) {
        pop(stack, 2);
        stack.add(1);
    }

    private static void pop(List<Integer> stack, int slots) {
        int remaining = slots;
        while (remaining > 0 && !stack.isEmpty()) {
            int top = stack.get(stack.size() - 1);
            if (top <= remaining) {
                stack.remove(stack.size() - 1);
                remaining -= top;
            } else {
                throw new CompilerException("Operand stack underflow (wide value straddles pop boundary)");
            }
        }
        if (remaining > 0) {
            throw new CompilerException("Operand stack underflow");
        }
    }

    private static int heightOf(List<Integer> stack) {
        int height = 0;
        for (int w : stack) {
            height += w;
        }
        return height;
    }

    private static String opcodeName(int opcode) {
        return "0x" + Integer.toHexString(opcode);
    }
}