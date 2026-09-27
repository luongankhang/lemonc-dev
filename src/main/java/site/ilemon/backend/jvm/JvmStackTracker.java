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

    static final class StackState {
        int[] slots;
        int size;
        int height;

        StackState() {
            this.slots = new int[16];
            this.size = 0;
            this.height = 0;
        }

        StackState copy() {
            StackState c = new StackState();
            c.slots = Arrays.copyOf(this.slots, Math.max(16, this.size));
            c.size = this.size;
            c.height = this.height;
            return c;
        }

        void push(int width) {
            if (size == slots.length) {
                slots = Arrays.copyOf(slots, slots.length * 2);
            }
            slots[size++] = width;
            height += width;
        }

        void pop(int slotCount) {
            int remaining = slotCount;
            while (remaining > 0 && size > 0) {
                int top = slots[size - 1];
                if (top <= remaining) {
                    size--;
                    height -= top;
                    remaining -= top;
                } else {
                    throw new CompilerException("Operand stack underflow (wide value straddles pop boundary)");
                }
            }
            if (remaining > 0) {
                throw new CompilerException("Operand stack underflow");
            }
        }
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
        worklist.add(new WorkItem(0, new StackState()));

        int max = 0;
        while (!worklist.isEmpty()) {
            WorkItem item = worklist.removeFirst();
            int index = item.index;
            StackState stack = item.stack;

            if (stack.height > max) {
                max = stack.height;
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

            if (stack.height < 0) {
                throw new CompilerException("Operand stack underflow after "
                        + opcodeName(insn.opcode) + " at IR index " + index);
            }

            int nextHeight = stack.height;
            if (insn.opcode == 0) {
                if (index + 1 < insns.size()) {
                    addSuccessor(index + 1, nextHeight, stack, inHeights, worklist);
                }
            } else if (insn.target != null) {
                Integer target = labelIndexes.get(insn.target);
                if (target == null) {
                    throw new CompilerException("Missing JVM label target: " + insn.target);
                }
                if (!isUnconditional(insn.opcode) && index + 1 < insns.size()) {
                    // Fan-out: one edge takes the state itself, the other gets
                    // a copy; heights stay consistent either way.
                    addSuccessor(target, nextHeight, stack, inHeights, worklist);
                    addSuccessor(index + 1, nextHeight, stack.copy(), inHeights, worklist);
                } else {
                    addSuccessor(target, nextHeight, stack, inHeights, worklist);
                }
            } else if (!isReturn(insn.opcode)) {
                if (index + 1 < insns.size()) {
                    addSuccessor(index + 1, nextHeight, stack, inHeights, worklist);
                }
            }
        }
        return max;
    }

    private static void addSuccessor(int successor, int nextHeight, StackState stack,
                                     int[] inHeights, Deque<WorkItem> worklist) {
        int oldHeight = inHeights[successor];
        if (oldHeight == -1) {
            inHeights[successor] = nextHeight;
            // The producer hands its (already computed) post-state to the
            // successor and never touches it again, so no defensive copy is
            // needed along a linear chain. A copy is only required when one
            // state must feed two successors (conditional branch), and the
            // caller makes that copy explicitly for the second edge.
            worklist.add(new WorkItem(successor, stack));
        } else if (oldHeight != nextHeight) {
            throw new CompilerException("Inconsistent operand stack height at IR index "
                    + successor + ": expected " + oldHeight + ", got " + nextHeight);
        }
    }

    private record WorkItem(int index, StackState stack) {
    }

    private static boolean isUnconditional(int opcode) {
        return opcode == 0xA7; // goto
    }

    private static boolean isReturn(int opcode) {
        return opcode == 0xAC || opcode == 0xAD || opcode == 0xAE
                || opcode == 0xAF || opcode == 0xB0 || opcode == 0xB1
                || opcode == 0xBF; // athrow also terminates the block
    }

    /** Applies a branch instruction in place: pops the condition operands (goto pops nothing). */
    private static void applyBranch(int opcode, StackState stack) {
        if (opcode >= 0x99 && opcode <= 0x9E) {
            // if<cond>: pops one int
            stack.pop(1);
        } else if (opcode >= 0x9F && opcode <= 0xA6) {
            // if_icmp<cond> / if_acmp<cond>: pops two values
            stack.pop(2);
        } else if (opcode == 0xC6 || opcode == 0xC7) {
            // ifnull / ifnonnull: pops one reference
            stack.pop(1);
        }
    }

    private static void apply(JvmCodeBuilder.Insn insn, StackState stack, JvmClassWriter pool) {
        int opcode = insn.opcode;
        switch (opcode) {
            case 0x12, 0x13, 0x14: // ldc, ldc_w, ldc2_w
                stack.push(insn.extra);
                break;
            case 0x15, 0x17, 0x19: // iload, fload, aload
                stack.push(1);
                break;
            case 0x16, 0x18: // lload, dload
                stack.push(2);
                break;
            case 0x36, 0x38, 0x3A: // istore, fstore, astore
                stack.pop(1);
                break;
            case 0x37, 0x39: // lstore, dstore
                stack.pop(2);
                break;
            case 0x57: // pop
                stack.pop(1);
                break;
            case 0x58: // pop2
                stack.pop(2);
                break;
            case 0x59: // dup
                stack.push(stack.slots[stack.size - 1]);
                break;
            case 0xBB: // new
                stack.push(1);
                break;
            case 0xBF: // athrow
                stack.pop(1);
                break;
            case 0x5B: // dup_x2 (only used for 2-slot print values below System.out)
                dupX2(stack);
                break;
            case 0x5F: // swap
                swap(stack);
                break;
            case 0x60, 0x64, 0x68, 0x6C, 0x70, 0x7E, 0x7F, 0x80: // iadd/isub/imul/idiv/irem/iand/ior/ixor
                pop2Push1(stack);
                break;
            case 0x61, 0x65, 0x69, 0x6D, 0x71: // ladd/lsub/lmul/ldiv/lrem
                stack.pop(4);
                stack.push(2);
                break;
            case 0x62, 0x66, 0x6A, 0x6E, 0x72: // fadd/fsub/fmul/fdiv/frem
                pop2Push1(stack);
                break;
            case 0x63, 0x67, 0x6B, 0x6F, 0x73: // dadd/dsub/dmul/ddiv/drem
                stack.pop(4);
                stack.push(2);
                break;
            case 0x94: // lcmp
                stack.pop(4);
                stack.push(1);
                break;
            case 0x95, 0x96: // fcmpl, fcmpg
                pop2Push1(stack);
                break;
            case 0x97, 0x98: // dcmpl, dcmpg
                stack.pop(4);
                stack.push(1);
                break;
            case 0x85, 0x87: // i2l, i2d
                stack.pop(1);
                stack.push(2);
                break;
            case 0x86: // i2f
                break;
            case 0x88, 0x89: // l2i, l2f
                stack.pop(2);
                stack.push(1);
                break;
            case 0x8A: // l2d
                break;
            case 0x8B, 0x91, 0x92, 0x93: // f2i, i2b, i2c, i2s
                break;
            case 0x8C, 0x8D: // f2l, f2d
                stack.pop(1);
                stack.push(2);
                break;
            case 0x8E, 0x8F: // d2l, d2f
                stack.pop(2);
                stack.push(1);
                break;
            case 0x90: // d2f
                stack.pop(2);
                stack.push(1);
                break;
            case 0xB2: // getstatic
                stack.push(1);
                break;
            case 0xB6, 0xB7, 0xB8: // invokevirtual, invokespecial, invokestatic
                int argSlots = (insn.extra >>> 8) & 0xFF;
                int returnSlots = insn.extra & 0xFF;
                stack.pop(argSlots);
                if (returnSlots > 0) {
                    stack.push(returnSlots);
                }
                break;
            case 0xBC: // newarray
                stack.pop(1);
                stack.push(1);
                break;
            case 0xBD: // anewarray
                stack.pop(1);
                stack.push(1);
                break;
            case 0xBE: // arraylength
                break;
            case 0x2E, 0x30, 0x33, 0x34, 0x35, 0x32: // iaload/faload/baload/caload/saload/aaload
                stack.pop(2);
                stack.push(1);
                break;
            case 0x2F, 0x31: // laload, daload
                stack.pop(2);
                stack.push(2);
                break;
            case 0x4F, 0x51, 0x54, 0x55, 0x56, 0x53: // iastore/fastore/bastore/castore/sastore/aastore
                stack.pop(3);
                break;
            case 0x50, 0x52: // lastore, dastore
                stack.pop(4);
                break;
            case 0x01: // aconst_null
                stack.push(1);
                break;
            case 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08: // iconst_m1..iconst_5
                stack.push(1);
                break;
            case 0xAC, 0xAE, 0xB0: // ireturn/freturn/areturn
                stack.pop(1);
                break;
            case 0xAD, 0xAF: // lreturn/dreturn
                stack.pop(2);
                break;
            case 0xB1: // return
                break;
            case 0xA7: // goto (handled as branch)
                break;
            default:
                if (opcode == WIDE) {
                    break;
                }
                throw new CompilerException("Missing stack effect for JVM opcode 0x"
                        + Integer.toHexString(opcode));
        }
    }

    /** dup_x2: our only use is [value(2 slots), System.out(1 slot)] → [System.out, value, System.out]. */
    private static void dupX2(StackState stack) {
        if (stack.size < 2) {
            throw new CompilerException("dup_x2 requires at least two stack values");
        }
        int top = stack.slots[stack.size - 1];
        int next = stack.slots[stack.size - 2];
        if (top == 1 && next == 2) {
            // form 2: [.., value2(2), value1(1)] → [.., value1, value2, value1]
            stack.size--;           // pop value1
            stack.slots[stack.size - 1] = 1;
            stack.slots[stack.size] = 2;
            stack.size += 2;
            stack.slots[stack.size - 1] = 1;
            stack.height += 1;
            return;
        }
        if (top == 1 && next == 1) {
            // form 1: [.., v3, v2, v1] → [.., v1, v3, v2, v1]
            if (stack.size < 3) {
                throw new CompilerException("dup_x2 form 1 requires three values");
            }
            int v1 = stack.slots[stack.size - 1];
            int v2 = stack.slots[stack.size - 2];
            int v3 = stack.slots[stack.size - 3];
            stack.slots[stack.size - 3] = v1;
            stack.slots[stack.size - 2] = v3;
            stack.slots[stack.size - 1] = v2;
            stack.push(v1);
            return;
        }
        if (top == 2 && next == 1) {
            // form 3: [.., value2(1), value1(2)] → [.., value1, value2, value1]
            stack.slots[stack.size - 2] = 2;
            stack.slots[stack.size - 1] = 1;
            stack.push(2);
            return;
        }
        throw new CompilerException("Unsupported dup_x2 stack composition");
    }

    private static void swap(StackState stack) {
        if (stack.size < 2) {
            throw new CompilerException("swap requires two values");
        }
        int top = stack.slots[stack.size - 1];
        int next = stack.slots[stack.size - 2];
        if (top != 1 || next != 1) {
            throw new CompilerException("swap requires two 1-slot values");
        }
        stack.slots[stack.size - 1] = next;
        stack.slots[stack.size - 2] = top;
    }

    private static void pop2Push1(StackState stack) {
        stack.pop(2);
        stack.push(1);
    }

    private static String opcodeName(int opcode) {
        return "0x" + Integer.toHexString(opcode);
    }
}