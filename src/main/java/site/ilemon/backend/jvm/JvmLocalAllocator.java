package site.ilemon.backend.jvm;

import site.ilemon.ir.BasicBlock;
import site.ilemon.ir.IrFunction;
import site.ilemon.ir.IrInstruction;
import site.ilemon.ir.IrType;
import site.ilemon.ir.IrValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Assigns JVM local-variable slots to a LemonIR function's parameters,
 * instruction results, and variable operands.
 *
 * <p>Parameters occupy the first slots (slot 0 is the first parameter of a
 * static method); {@code long}/{@code double} consume two consecutive slots.
 * Values that are never stored to a local (numeric/string constants) are
 * materialized directly with {@code ldc} and never allocated.</p>
 */
final class JvmLocalAllocator {

    /** A value's local-slot assignment. */
    record Local(IrValue value, int slot, IrType type) {
        String name() {
            return value.name();
        }
    }

    private final JvmTypeMapper mapper;

    JvmLocalAllocator(JvmTypeMapper mapper) {
        this.mapper = mapper;
    }

    Map<String, Local> allocate(IrFunction function) {
        LinkedHashMap<String, Local> locals = new LinkedHashMap<>();
        int nextSlot = 0;

        for (IrValue parameter : function.parameters()) {
            if (mapper.slots(parameter.type()) == 0 || locals.containsKey(parameter.name())) {
                continue;
            }
            locals.put(parameter.name(), new Local(parameter, nextSlot, parameter.type()));
            nextSlot += mapper.slots(parameter.type());
        }

        for (BasicBlock block : function.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                if (instruction.result() != null
                        && mapper.slots(instruction.result().type()) > 0
                        && !locals.containsKey(instruction.result().name())) {
                    locals.put(instruction.result().name(),
                            new Local(instruction.result(), nextSlot, instruction.result().type()));
                    nextSlot += mapper.slots(instruction.result().type());
                }
                if (instruction.op() == IrInstruction.Op.CONST) {
                    continue; // the operand is the literal value itself
                }
                for (IrValue operand : instruction.operands()) {
                    if (!isConstantLiteral(operand.name())
                            && mapper.slots(operand.type()) > 0
                            && !locals.containsKey(operand.name())) {
                        locals.put(operand.name(), new Local(operand, nextSlot, operand.type()));
                        nextSlot += mapper.slots(operand.type());
                    }
                }
            }
        }
        return locals;
    }

    /** Number of local slots used (also the max_locals for non-main methods). */
    int slotCount(Map<String, Local> locals) {
        int max = 0;
        for (Local local : locals.values()) {
            max = Math.max(max, local.slot() + mapper.slots(local.type()));
        }
        return max;
    }

    /** True when the operand is a literal constant (materialized via ldc), not a local. */
    static boolean isConstantLiteral(String name) {
        if (name == null || name.isEmpty()) {
            return true;
        }
        if (name.startsWith("\"")) {
            return true;
        }
        if (name.equals("true") || name.equals("false")) {
            return true;
        }
        char first = name.charAt(0);
        if (Character.isDigit(first)
                || (first == '-' && name.length() > 1 && Character.isDigit(name.charAt(1)))) {
            String stripped = stripNumericSuffix(name);
            try {
                Double.parseDouble(stripped);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private static String stripNumericSuffix(String value) {
        String stripped = value;
        int length = value.length();
        if (length > 1) {
            char last = value.charAt(length - 1);
            if (last == 'f' || last == 'F' || last == 'd' || last == 'D' || last == 'l' || last == 'L') {
                stripped = value.substring(0, length - 1);
            }
        }
        return stripped;
    }
}