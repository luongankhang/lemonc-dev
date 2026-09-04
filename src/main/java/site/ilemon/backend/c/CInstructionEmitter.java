package site.ilemon.backend.c;

import site.ilemon.ir.IrInstruction;
import site.ilemon.ir.IrValue;

/** Emits target-independent LemonIR instructions as readable C statements. */
public final class CInstructionEmitter {
    public String emit(IrInstruction instruction, CTypeEmitter types) {
        String result = instruction.result() == null ? "" : instruction.result().name() + " = ";
        String[] args = instruction.operands().stream().map(IrValue::name).toArray(String[]::new);
        return switch (instruction.op()) {
            case CONST -> result + (args.length == 0 ? "0" : args[0]) + ";";
            case ADD, SUB, MUL, DIV, REM, AND, OR, XOR -> result + binary(instruction.op(), args) + ";";
            case CMP -> result + "(" + binary(instruction.op(), args) + ");";
            case CONVERT -> result + (args.length == 0 ? "0" : args[0]) + ";";
            case LOAD -> result + "*" + args[0] + ";";
            case STORE -> "*" + args[0] + " = " + args[1] + ";";
            case ALLOC -> result + "lemon_alloc(" + (args.length == 0 ? "0" : args[0]) + ");";
            case CALL, EXTERNAL_CALL -> result + instruction.target() + "(" + String.join(", ", args) + ");";
            case RETURN -> args.length == 0 ? "return;" : "return " + args[0] + ";";
            case BRANCH -> "goto " + instruction.target() + ";";
            case COND_BRANCH -> "if (" + args[0] + ") goto " + instruction.target() + ";";
            case PHI -> result + (args.length == 0 ? "0" : args[0]) + "; /* phi lowered by CFG pass */";
            case BOUNDS_CHECK -> "lemon_bounds_check(" + String.join(", ", args) + ");";
        };
    }
    private String binary(IrInstruction.Op op, String[] args) {
        String symbol = switch (op) { case ADD -> "+"; case SUB -> "-"; case MUL -> "*"; case DIV -> "/"; case REM -> "%"; case AND -> "&"; case OR -> "|"; case XOR -> "^"; case CMP -> "=="; default -> "?"; };
        return args.length < 2 ? (args.length == 0 ? "0" : args[0]) : args[0] + " " + symbol + " " + args[1];
    }
}
