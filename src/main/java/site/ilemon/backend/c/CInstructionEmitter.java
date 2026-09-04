package site.ilemon.backend.c;

import site.ilemon.ir.IrInstruction;
import site.ilemon.ir.IrType;
import site.ilemon.ir.IrValue;

/** Emits target-independent LemonIR instructions as readable C statements. */
public final class CInstructionEmitter {
    public String emit(IrInstruction instruction, CTypeEmitter types) {
        String result = instruction.result() == null ? "" : instruction.result().name() + " = ";
        String[] args = instruction.operands().stream().map(IrValue::name).toArray(String[]::new);
        return switch (instruction.op()) {
            case CONST -> {
                String val = args.length == 0 ? "0" : args[0];
                if (instruction.result() != null && instruction.result().type().kind() == IrType.Kind.STRING) {
                    if (!val.startsWith("\"")) {
                        val = "\"" + val + "\"";
                    }
                } else if (instruction.result() != null && instruction.result().type().kind() == IrType.Kind.FLOAT) {
                    if (!val.contains(".")) {
                        val = val + ".0f";
                    } else if (!val.endsWith("f") && !val.endsWith("F")) {
                        val = val + "f";
                    }
                } else if (instruction.result() != null && instruction.result().type().kind() == IrType.Kind.DOUBLE) {
                    if (!val.contains(".")) {
                        val = val + ".0";
                    }
                }
                yield result + val + ";";
            }
            case ADD, SUB, MUL -> result + binary(instruction.op(), args) + ";";
            case DIV -> {
                if (args.length >= 2) {
                    String rhs = args[1];
                    yield result + "((" + rhs + ") == 0 ? (lemon_panic_divzero(\"division by zero\"), 0) : (" + args[0] + " / " + rhs + "))" + ";";
                }
                yield result + (args.length == 0 ? "0" : args[0]) + ";";
            }
            case REM -> {
                if (instruction.result() != null && instruction.result().type().kind() == IrType.Kind.FLOAT) {
                    yield result + "fmodf(" + args[0] + ", " + args[1] + ");";
                } else if (instruction.result() != null && instruction.result().type().kind() == IrType.Kind.DOUBLE) {
                    yield result + "fmod(" + args[0] + ", " + args[1] + ");";
                } else if (args.length >= 2) {
                    String rhs = args[1];
                    yield result + "((" + rhs + ") == 0 ? (lemon_panic_divzero(\"remainder by zero\"), 0) : (" + args[0] + " % " + rhs + "))" + ";";
                }
                yield result + (args.length < 2 ? (args.length == 0 ? "0" : args[0]) : args[0] + " % " + args[1]) + ";";
            }
            case AND, OR -> {
                boolean isBool = instruction.result() != null && instruction.result().type().kind() == IrType.Kind.BOOL;
                String sym = instruction.op() == IrInstruction.Op.AND ? (isBool ? "&&" : "&") : (isBool ? "||" : "|");
                yield result + (args.length < 2 ? (args.length == 0 ? "0" : args[0]) : args[0] + " " + sym + " " + args[1]) + ";";
            }
            case XOR -> {
                boolean isBool = instruction.result() != null && instruction.result().type().kind() == IrType.Kind.BOOL;
                if (isBool) {
                    yield result + "(" + (args.length < 2 ? args[0] : args[0] + " != " + args[1]) + ");";
                }
                yield result + (args.length < 2 ? (args.length == 0 ? "0" : args[0]) : args[0] + " ^ " + args[1]) + ";";
            }
            case CMP -> {
                String cmpOp = instruction.target() != null && !instruction.target().isBlank() ? instruction.target() : "==";
                yield result + "(" + (args.length < 2 ? (args.length == 0 ? "0" : args[0]) : args[0] + " " + cmpOp + " " + args[1]) + ");";
            }
            case CONVERT -> {
                String targetType = instruction.result() != null ? types.emit(instruction.result().type()) : "int32_t";
                yield result + "((" + targetType + ")(" + (args.length == 0 ? "0" : args[0]) + "));";
            }
            case LOAD -> {
                if (args.length <= 1) {
                    if ("length".equals(instruction.target())) {
                        yield result + "(int32_t)(" + args[0] + "->length);";
                    }
                    yield result + "*" + args[0] + ";";
                }
                String elemType = instruction.result() != null ? types.emit(instruction.result().type()) : "void*";
                yield result + "*((" + elemType + "*)lemon_array_at(" + args[0] + ", (size_t)(" + args[1] + ")));";
            }
            case STORE -> {
                if (args.length <= 2) {
                    yield "*" + args[0] + " = " + args[1] + ";";
                }
                String elemType = instruction.operands().size() > 2 ? types.emit(instruction.operands().get(2).type()) : "int32_t";
                yield "*((" + elemType + "*)lemon_array_at(" + args[0] + ", (size_t)(" + args[1] + "))) = " + args[2] + ";";
            }
            case ALLOC -> {
                if (instruction.result() != null && instruction.result().type().kind() == IrType.Kind.ARRAY) {
                    String elemType = types.emitElement(instruction.result().type());
                    yield result + "lemon_array_new((size_t)(" + (args.length == 0 ? "0" : args[0]) + "), sizeof(" + elemType + "), NULL);";
                } else if (args.length >= 2) {
                    yield result + "lemon_array_new((size_t)(" + args[0] + "), (size_t)(" + args[1] + "), NULL);";
                } else {
                    yield result + "lemon_alloc((size_t)(" + (args.length == 0 ? "0" : args[0]) + "));";
                }
            }
            case CALL -> result + CFunctionEmitter.safe(instruction.target()) + "(" + String.join(", ", args) + ");";
            case EXTERNAL_CALL -> result + instruction.target() + "(" + String.join(", ", args) + ");";
            case RETURN -> args.length == 0 ? "return;" : "return " + args[0] + ";";
            case BRANCH -> "goto " + CFunctionEmitter.safe(instruction.target()) + ";";
            case COND_BRANCH -> "if (" + args[0] + ") goto " + CFunctionEmitter.safe(instruction.target()) + ";";
            case PHI -> result + (args.length == 0 ? "0" : args[0]) + "; /* phi lowered by CFG pass */";
            case BOUNDS_CHECK -> {
                if (args.length == 2) {
                    yield "lemon_bounds_check(" + args[0] + ", " + args[0] + "->length, (size_t)(" + args[1] + "));";
                } else if (args.length >= 3) {
                    yield "lemon_bounds_check(" + args[0] + ", (size_t)(" + args[1] + "), (size_t)(" + args[2] + "));";
                } else {
                    yield "lemon_bounds_check(" + String.join(", ", args) + ");";
                }
            }
        };
    }

    private String binary(IrInstruction.Op op, String[] args) {
        String symbol = switch (op) {
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            default -> "?";
        };
        return args.length < 2 ? (args.length == 0 ? "0" : args[0]) : args[0] + " " + symbol + " " + args[1];
    }
}
