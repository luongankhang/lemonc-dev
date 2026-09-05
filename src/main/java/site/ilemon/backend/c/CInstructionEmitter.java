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

    private String emitPrintf(IrInstruction instruction, CTypeEmitter types, String result) {
        List<IrValue> operands = instruction.operands();
        if (operands.isEmpty()) {
            return result + "printf(\"\");";
        }
        
        // First operand is the format string - parse and update specifiers to match argument types
        String format = operands.get(0).name();
        // Remove surrounding quotes if present
        if (format.startsWith("\"") && format.endsWith("\"")) {
            format = format.substring(1, format.length() - 1);
        }
        
        // Parse format string and rebuild with correct specifiers for each argument
        StringBuilder cFormat = new StringBuilder();
        StringBuilder argsBuilder = new StringBuilder();
        int argIndex = 1; // Start from 1 (0 is format string)
        
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);
            if (c == '%' && i + 1 < format.length()) {
                char next = format.charAt(i + 1);
                if (next == '%') {
                    // Escaped percent sign
                    cFormat.append("%%");
                    i += 2;
                } else {
                    // Format specifier - consume it and replace with correct one based on argument type
                    int j = i + 1;
                    // Skip flags, width, precision
                    while (j < format.length() && "0-+ #".indexOf(format.charAt(j)) >= 0) j++;
                    while (j < format.length() && Character.isDigit(format.charAt(j))) j++;
                    if (j < format.length() && format.charAt(j) == '.') {
                        j++;
                        while (j < format.length() && Character.isDigit(format.charAt(j))) j++;
                    }
                    // Length modifier (h, hh, l, ll, etc.) - we'll replace
                    while (j < format.length() && "hlLjtz".indexOf(format.charAt(j)) >= 0) j++;
                    // Specifier character
                    char specifier = j < format.length() ? format.charAt(j) : 'd';
                    String rest = j + 1 < format.length() ? format.substring(j + 1) : "";
                    
                    // Get the corresponding argument type
                    String argName = null;
                    String castPrefix = "";
                    if (argIndex < operands.size()) {
                        IrValue arg = operands.get(argIndex);
                        argName = arg.name();
                        IrType argType = arg.type();
                        if (argType != null) {
                            switch (argType.kind()) {
                                case BYTE, SHORT, CHAR, INT, BOOL -> cFormat.append("%d");
                                case LONG -> {
                                    cFormat.append("%lld");
                                    castPrefix = "(long long)";
                                }
                                case FLOAT -> cFormat.append("%f");
                                case DOUBLE -> cFormat.append("%lf");
                                case STRING -> cFormat.append("%s");
                                default -> cFormat.append("%d");
                            }
                        } else {
                            cFormat.append("%d");
                        }
                        argIndex++;
                    } else {
                        // No corresponding argument, keep original specifier
                        cFormat.append('%').append(specifier);
                    }
                    
                    if (argName != null) {
                        if (argsBuilder.length() > 0) {
                            argsBuilder.append(", ");
                        }
                        argsBuilder.append(castPrefix).append(argName);
                    }
                    
                    i = j + 1;
                }
            } else {
                cFormat.append(c);
                i++;
            }
        }
        
        // Add remaining arguments that don't have format specifiers
        while (argIndex < operands.size()) {
            IrValue arg = operands.get(argIndex);
            String argName = arg.name();
            IrType argType = arg.type();
            String castPrefix = "";
            String cSpecifier;
            
            if (argType != null) {
                switch (argType.kind()) {
                    case BYTE, SHORT, CHAR, INT, BOOL -> cSpecifier = "%d";
                    case LONG -> {
                        cSpecifier = "%lld";
                        castPrefix = "(long long)";
                    }
                    case FLOAT -> cSpecifier = "%f";
                    case DOUBLE -> cSpecifier = "%lf";
                    case STRING -> cSpecifier = "%s";
                    default -> cSpecifier = "%d";
                }
            } else {
                cSpecifier = "%d";
            }
            
            cFormat.append(" ").append(cSpecifier);
            if (argsBuilder.length() > 0) {
                argsBuilder.append(", ");
            }
            argsBuilder.append(castPrefix).append(argName);
            argIndex++;
        }
        
        // Escape for C string literal - only escape quotes and backslashes that are NOT part of escape sequences
        // The format string already has C escape sequences like \n, \t, etc.
        // We need to escape: " -> \", \ -> \\ (but not when followed by a valid escape char)
        StringBuilder escaped = new StringBuilder();
        String formatStr = cFormat.toString();
        for (int k = 0; k < formatStr.length(); k++) {
            char ch = formatStr.charAt(k);
            if (ch == '"') {
                escaped.append("\\\"");
            } else if (ch == '\\') {
                // Check if this is part of a valid C escape sequence
                if (k + 1 < formatStr.length()) {
                    char next = formatStr.charAt(k + 1);
                    if ("ntrfvab?\"'\\01234567".indexOf(next) >= 0) {
                        // Valid escape sequence - keep as-is
                        escaped.append(ch);
                    } else {
                        // Not a valid escape - escape the backslash
                        escaped.append("\\\\");
                    }
                } else {
                    // Backslash at end - escape it
                    escaped.append("\\\\");
                }
            } else {
                escaped.append(ch);
            }
        }
        String cFormatStr = escaped.toString();
        
        if (argsBuilder.length() > 0) {
            return result + "printf(\"" + cFormatStr + "\", " + argsBuilder + ");";
        } else {
            return result + "printf(\"" + cFormatStr + "\");";
        }
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
