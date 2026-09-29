package site.ilemon.ir;

import java.util.List;

/** Minimal target-independent instruction vocabulary; target lowering is a later phase. */
public record IrInstruction(Op op, IrValue result, List<IrValue> operands, String target) {
    public enum Op {
        CONST, ADD, SUB, MUL, DIV, REM, AND, OR, XOR, CMP, CONVERT, LOAD, STORE, ALLOC, ADDRESS_OF,
        CALL, RETURN, BRANCH, COND_BRANCH, PHI, BOUNDS_CHECK, EXTERNAL_CALL, BIT_NOT,
        /** Load a field: {@code result = receiver.field}. {@code target} carries the field name. */
        FIELD_LOAD,
        /** Store a field: {@code receiver.field = operands[1]}. {@code target} carries the field name. */
        FIELD_STORE,
        /** By-value struct copy: {@code result = clone(operands[0])}. */
        STRUCT_COPY,
        /** Zero-initialize a struct local: {@code result = all-fields-zero}. */
        STRUCT_ZERO,
    }
    public IrInstruction {
        if (op == null) throw new IllegalArgumentException("IR opcode is null");
        operands = operands == null ? List.of() : List.copyOf(operands);
        if ((op == Op.BRANCH || op == Op.COND_BRANCH) && (target == null || target.isBlank())) throw new IllegalArgumentException("branch target is empty");
        if (op == Op.RETURN && operands.size() > 1) throw new IllegalArgumentException("return has too many operands");
    }
    public boolean isTerminator() { return op == Op.RETURN || op == Op.BRANCH || op == Op.COND_BRANCH; }
}
