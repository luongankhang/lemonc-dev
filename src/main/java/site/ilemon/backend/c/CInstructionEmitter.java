package site.ilemon.backend.c;

import site.ilemon.ir.IrInstruction;
import site.ilemon.ir.IrType;
import site.ilemon.ir.IrValue;

import java.util.Arrays;
import java.util.List;

/** Emits target-independent LemonIR instructions as readable C statements. */
public final class CInstructionEmitter {
    private final java.util.Set<String> constNames;

    public CInstructionEmitter() {
        this(java.util.Set.of());
    }

    public CInstructionEmitter(java.util.Set<String> constNames) {
        this.constNames = constNames == null ? java.util.Set.of() : constNames;
    }

    public String emit(IrInstruction instruction, CTypeEmitter types) {
        String result = instruction.result() == null ? "" : instruction.result().name() + " = ";
        String[] args = instruction.operands().stream().map(IrValue::name).toArray(String[]::new);
        return switch (instruction.op()) {
            case CONST -> {
                String val = args.length == 0 ? "0" : args[0];
                if (constNames.contains(val)) {
                    // Global constant read: reference the declared identifier as-is.
                    yield result + CFunctionEmitter.safe(val) + ";";
                } else if (instruction.result() != null && instruction.result().type().kind() == IrType.Kind.STRING) {
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
                } else if (instruction.result() != null && instruction.result().type().kind() == IrType.Kind.POINTER) {
                    if ("null".equals(val)) {
                        val = "NULL";
                    } else if (!"0".equals(val)) {
                        // Pointer constants other than null/0 cannot be
                        // spelled literally in C; materialize a null and let
                        // later pointer-typed CONVERTs cast it.
                        val = "NULL";
                    }
                } else if (instruction.result() != null && instruction.result().type().kind() == IrType.Kind.LONG) {
                    // Handle LONG_MIN (-9223372036854775808) which cannot be
                    // written as a literal in C because the positive value
                    // overflows signed 64-bit. Use LLONG_MIN macro instead.
                    if ("-9223372036854775808".equals(val)) {
                        val = "LLONG_MIN";
                    } else if ("9223372036854775807".equals(val)) {
                        val = "LLONG_MAX";
                    }
                }
                yield result + val + ";";
            }
            case ADDRESS_OF -> result + "&(" + (args.length == 0 ? "0" : args[0]) + ");";
            case ADD, SUB, MUL -> result + binary(instruction.op(), args) + ";";
            case DIV -> {
                if (args.length >= 2) {
                    String rhs = args[1];
                    IrType divType = instruction.result() != null ? instruction.result().type() : null;
                    boolean fp = divType != null
                            && (divType.kind() == IrType.Kind.FLOAT || divType.kind() == IrType.Kind.DOUBLE);
                    if (fp) {
                        // IEEE floating point division: 0.0/0.0 is NaN, x/0.0 is
                        // +/-Infinity; only integer division is a runtime error.
                        yield result + "(" + args[0] + " / " + rhs + ");";
                    } else {
                        yield result + "((" + rhs + ") == 0 ? (lemon_panic_divzero(\"division by zero\"), 0) : (" + args[0] + " / " + rhs + "))" + ";";
                    }
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
                    // Pointer dereference: null dereference is a defined
                    // runtime error (diagnosed, then abort), never UB.
                    yield result + "*(lemon_require_ptr(" + args[0] + "), " + args[0] + ");";
                }
                String elemType = instruction.result() != null ? types.emit(instruction.result().type()) : "void*";
                yield result + "*((" + elemType + "*)lemon_array_at(" + args[0] + ", (size_t)(" + args[1] + ")));";
            }
            case STORE -> {
                if (args.length <= 2) {
                    // Pointer store: guarded against null, like the LOAD path.
                    yield "*(lemon_require_ptr(" + args[0] + "), " + args[0] + ") = " + args[1] + ";";
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
            case EXTERNAL_CALL -> {
                if ("printf".equals(instruction.target())) {
                    yield emitPrintf(instruction, types, result);
                }
                yield result + instruction.target() + "(" + String.join(", ", args) + ");";
            }
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

    /**
     * Lowers printf the same way the JVM backend does: literal text segments
     * are printed as-is and each %d/%f value is printed with the exact output
     * semantics of that type (ints via %d, longs via %lld, floats/doubles via
     * the Java-compatible runtime printers). This is what makes stdout of a
     * C-built program byte-for-byte identical to the JVM-built one.
     */
    private String emitPrintf(IrInstruction instruction, CTypeEmitter types, String result) {
        List<IrValue> operands = instruction.operands();
        if (operands.isEmpty()) {
            return result + "printf(\"\");";
        }

        // First operand is the format string (as written in the Lemon source,
        // still containing C escape sequences such as \n and \t).
        String format = operands.get(0).name();
        if (format.startsWith("\"") && format.endsWith("\"")) {
            format = format.substring(1, format.length() - 1);
        }

        List<String> statements = new java.util.ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int valueIndex = 1;

        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%') {
                literal.append(c);
                continue;
            }
            flushLiteral(statements, literal);
            if (i + 1 >= format.length()) {
                // Trailing '%' cannot be printed portably; mirror JVM behaviour.
                literal.append('%');
                break;
            }
            char placeholder = format.charAt(++i);
            if (placeholder == '%') {
                // Escaped percent sign in the source string: printed as '%'.
                literal.append('%');
                continue;
            }
            if (valueIndex >= operands.size()) {
                literal.append('%').append(placeholder);
                continue;
            }
            IrValue value = operands.get(valueIndex);
            valueIndex++;
            appendValuePrint(statements, value);
        }
        flushLiteral(statements, literal);
        return String.join("\n    ", statements);
    }

    private void flushLiteral(List<String> statements, StringBuilder literal) {
        if (literal.length() == 0) {
            return;
        }
        String text = literal.toString();
        literal.setLength(0);
        statements.add("printf(\"" + cLiteral(text) + "\");");
    }

    private void appendValuePrint(List<String> statements, IrValue value) {
        String argName = value.name();
        IrType argType = value.type();
        if (argType != null) {
            switch (argType.kind()) {
                case BYTE, SHORT, CHAR, INT, BOOL -> statements.add("printf(\"%d\", " + argName + ");");
                case LONG -> statements.add("printf(\"%lld\", (long long)" + argName + ");");
                case FLOAT -> statements.add("lemon_print_float(" + argName + ");");
                case DOUBLE -> statements.add("lemon_print_double(" + argName + ");");
                case STRING -> statements.add("printf(\"%s\", " + argName + ");");
                default -> statements.add("printf(\"%d\", " + argName + ");");
            }
        } else {
            statements.add("printf(\"%d\", " + argName + ");");
        }
    }

    /**
     * Escapes literal text for a C string literal. The text already carries
     * C escape sequences (\n, \t, ...) from the Lemon source; those are kept
     * as-is, while quotes and stray backslashes are escaped.
     */
    private String cLiteral(String raw) {
        StringBuilder escaped = new StringBuilder();
        for (int k = 0; k < raw.length(); k++) {
            char ch = raw.charAt(k);
            if (ch == '"') {
                escaped.append("\\\"");
            } else if (ch == '\\') {
                if (k + 1 < raw.length() && "ntrfvab?\"'\\01234567".indexOf(raw.charAt(k + 1)) >= 0) {
                    escaped.append(ch); // valid C escape sequence: keep as-is
                } else {
                    escaped.append("\\\\");
                }
            } else {
                escaped.append(ch);
            }
        }
        return escaped.toString();
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
