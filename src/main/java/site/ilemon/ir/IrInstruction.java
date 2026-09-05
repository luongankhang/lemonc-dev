package site.ilemon.ir;

import java.util.List;

/** Minimal target-independent instruction vocabulary; target lowering is a later phase. */
public record IrInstruction(Op op, IrValue result, List<IrValue> operands, String target) {
    public enum Op { CONST, ADD, SUB, MUL, DIV, REM, AND, OR, XOR, CMP, CONVERT, LOAD, STORE, ALLOC, ADDRESS_OF, CALL, RETURN, BRANCH, COND_BRANCH, PHI, BOUNDS_CHECK, EXTERNAL_CALL }
    public IrInstruction {
        if (op == null) throw new IllegalArgumentException("IR opcode is null");
        operands = operands == null ? List.of() : List.copyOf(operands);
        if ((op == Op.BRANCH || op == Op.COND_BRANCH) && (target == null || target.isBlank())) throw new IllegalArgumentException("branch target is empty");
        if (op == Op.RETURN && operands.size() > 1) throw new IllegalArgumentException("return has too many operands");
    }
    public boolean isTerminator() { return op == Op.RETURN || op == Op.BRANCH || op == Op.COND_BRANCH; }
}
