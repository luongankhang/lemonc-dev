package site.ilemon.backend.jvm;

import site.ilemon.exception.CompilerException;
import site.ilemon.ir.IrInstruction;
import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrType;
import site.ilemon.ir.IrValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lowers one LemonIR instruction to JVM bytecode.
 *
 * <p>JVM-specific decisions are all made here and nowhere else in the shared
 * pipeline: printf is lowered to {@code System.out.print} calls, ARC
 * retain/release calls are dropped (JVM GC owns array lifetimes), and
 * bounds checks rely on the JVM's native array checks.</p>
 */
final class JvmInstructionEmitter {

    // Opcodes used by this backend.
    private static final int ACONST_NULL = 0x01;
    private static final int IALOAD = 0x2E;
    private static final int LALOAD = 0x2F;
    private static final int FALOAD = 0x30;
    private static final int DALOAD = 0x31;
    private static final int AALOAD = 0x32;
    private static final int BALOAD = 0x33;
    private static final int CALOAD = 0x34;
    private static final int SALOAD = 0x35;
    private static final int IASTORE = 0x4F;
    private static final int LASTORE = 0x50;
    private static final int FASTORE = 0x51;
    private static final int DASTORE = 0x52;
    private static final int AASTORE = 0x53;
    private static final int BASTORE = 0x54;
    private static final int CASTORE = 0x55;
    private static final int SASTORE = 0x56;
    private static final int POP = 0x57;
    private static final int SWAP = 0x5F;
    private static final int IADD = 0x60;
    private static final int LADD = 0x61;
    private static final int FADD = 0x62;
    private static final int DADD = 0x63;
    private static final int ISUB = 0x64;
    private static final int LSUB = 0x65;
    private static final int FSUB = 0x66;
    private static final int DSUB = 0x67;
    private static final int IMUL = 0x68;
    private static final int LMUL = 0x69;
    private static final int FMUL = 0x6A;
    private static final int DMUL = 0x6B;
    private static final int IDIV = 0x6C;
    private static final int LDIV = 0x6D;
    private static final int FDIV = 0x6E;
    private static final int DDIV = 0x6F;
    private static final int IREM = 0x70;
    private static final int LREM = 0x71;
    private static final int FREM = 0x72;
    private static final int DREM = 0x73;
    private static final int IAND = 0x7E;
    private static final int IOR = 0x80;
    private static final int IXOR = 0x82;
    private static final int I2L = 0x85;
    private static final int I2F = 0x86;
    private static final int I2D = 0x87;
    private static final int L2I = 0x88;
    private static final int L2F = 0x89;
    private static final int L2D = 0x8A;
    private static final int F2I = 0x8B;
    private static final int F2L = 0x8C;
    private static final int F2D = 0x8D;
    private static final int D2I = 0x8E;
    private static final int D2L = 0x8F;
    private static final int D2F = 0x90;
    private static final int I2B = 0x91;
    private static final int I2C = 0x92;
    private static final int I2S = 0x93;
    private static final int LCMP = 0x94;
    private static final int FCMPL = 0x95;
    private static final int FCMPG = 0x96;
    private static final int DCMPL = 0x97;
    private static final int DCMPG = 0x98;
    private static final int IFEQ = 0x99;
    private static final int IFNE = 0x9A;
    private static final int IF_ICMPEQ = 0x9F;
    private static final int IF_ICMPNE = 0xA0;
    private static final int IF_ICMPLT = 0xA1;
    private static final int IF_ICMPGE = 0xA2;
    private static final int IF_ICMPGT = 0xA3;
    private static final int IF_ICMPLE = 0xA4;
    private static final int GOTO = 0xA7;
    private static final int IRETURN = 0xAC;
    private static final int LRETURN = 0xAD;
    private static final int FRETURN = 0xAE;
    private static final int DRETURN = 0xAF;
    private static final int ARETURN = 0xB0;
    private static final int RETURN = 0xB1;
    private static final int GETSTATIC = 0xB2;
    private static final int INVOKEVIRTUAL = 0xB6;
    private static final int INVOKESTATIC = 0xB8;
    private static final int NEWARRAY = 0xBC;
    private static final int ANEWARRAY = 0xBD;
    private static final int ARRAYLENGTH = 0xBE;

    private static final int ALOAD = 0x19;
    private static final int ILOAD = 0x15;
    private static final int LLOAD = 0x16;
    private static final int FLOAD = 0x17;
    private static final int DLOAD = 0x18;
    private static final int ASTORE = 0x3A;
    private static final int ISTORE = 0x36;
    private static final int LSTORE = 0x37;
    private static final int FSTORE = 0x38;
    private static final int DSTORE = 0x39;
    private static final int DUP_X2 = 0x5B;

    private static final String SYSTEM_OUT_FIELD = "Ljava/io/PrintStream;";

    private final JvmTypeMapper mapper;
    private final JvmClassWriter pool;
    private final JvmCodeBuilder code;
    private final Map<String, JvmLocalAllocator.Local> locals;
    private final Map<String, MethodSignature> signatures;
    private final String className;
    private final boolean isMain;
    private final IrType returnType;

    private int labelCounter = 0;

    /** Function signature used for call descriptors. */
    record MethodSignature(List<IrType> parameterTypes, IrType returnType) {
    }

    JvmInstructionEmitter(JvmTypeMapper mapper, JvmClassWriter pool, JvmCodeBuilder code,
                          Map<String, JvmLocalAllocator.Local> locals, IrModule module,
                          String functionName, boolean isMain, IrType returnType) {
        this.mapper = mapper;
        this.pool = pool;
        this.code = code;
        this.locals = locals;
        this.className = module.name();
        this.isMain = isMain;
        this.returnType = returnType;
        this.signatures = new HashMap<>();
        for (var function : module.functions()) {
            signatures.put(function.name(), new MethodSignature(
                    function.parameters().stream().map(IrValue::type).toList(),
                    function.returnType()));
        }
    }

    void emit(IrInstruction instruction) {
        switch (instruction.op()) {
            case CONST -> emitConst(instruction);
            case ADD -> emitBinary(instruction, IADD, LADD, FADD, DADD);
            case SUB -> emitBinary(instruction, ISUB, LSUB, FSUB, DSUB);
            case MUL -> emitBinary(instruction, IMUL, LMUL, FMUL, DMUL);
            case DIV -> emitBinary(instruction, IDIV, LDIV, FDIV, DDIV);
            case REM -> emitBinary(instruction, IREM, LREM, FREM, DREM);
            case AND -> emitIntBinary(instruction, IAND);
            case OR -> emitIntBinary(instruction, IOR);
            case XOR -> emitIntBinary(instruction, IXOR);
            case CMP -> emitCompare(instruction);
            case CONVERT -> emitConvert(instruction);
            case LOAD -> emitLoad(instruction);
            case STORE -> emitStore(instruction);
            case ALLOC -> emitAlloc(instruction);
            case CALL -> emitCall(instruction);
            case RETURN -> emitReturn(instruction);
            case BRANCH -> code.branch(GOTO, instruction.target());
            case COND_BRANCH -> {
                loadValue(instruction.operands().get(0));
                code.branch(IFNE, instruction.target());
            }
            case BOUNDS_CHECK -> {
                // JVM arrays perform native bounds checks; no explicit code needed.
            }
            case EXTERNAL_CALL -> emitExternalCall(instruction);
            case PHI -> throw new CompilerException(
                    "PHI instructions must be lowered before JVM emission");
        }
    }

    // ------------------------------------------------------------ constants

    private void emitConst(IrInstruction instruction) {
        IrValue result = instruction.result();
        String raw = instruction.operands().isEmpty() ? "0" : instruction.operands().get(0).name();
        pushConstant(raw, result.type());
        store(result);
    }

    private void pushConstant(String raw, IrType type) {
        switch (type.kind()) {
            case BOOL -> {
                int value;
                if ("true".equals(raw)) {
                    value = 1;
                } else if ("false".equals(raw)) {
                    value = 0;
                } else {
                    value = Integer.parseInt(raw);
                }
                code.ldc(pool.integer(value), 1);
            }
            case BYTE, SHORT, CHAR, INT -> code.ldc(pool.integer(Integer.parseInt(raw)), 1);
            case LONG -> code.ldc2w(pool.longConstant(Long.parseLong(raw)));
            case FLOAT -> code.ldc(pool.floatConstant(Float.parseFloat(raw)), 1);
            case DOUBLE -> code.ldc2w(pool.doubleConstant(Double.parseDouble(raw)));
            case STRING -> {
                String value = unescapeString(stripQuotes(raw));
                code.ldc(pool.stringRef(value), 1);
            }
            default -> throw new CompilerException("cannot materialize constant of type " + type.kind());
        }
    }

    // ----------------------------------------------------------- arithmetic

    private void emitBinary(IrInstruction instruction, int intOp, int longOp, int floatOp, int doubleOp) {
        List<IrValue> operands = instruction.operands();
        loadValue(operands.get(0));
        loadValue(operands.get(1));
        IrType type = instruction.result().type();
        if (mapper.isIntFamily(type)) {
            code.simple(intOp);
        } else if (type.kind() == IrType.Kind.LONG) {
            code.simple(longOp);
        } else if (type.kind() == IrType.Kind.FLOAT) {
            code.simple(floatOp);
        } else if (type.kind() == IrType.Kind.DOUBLE) {
            code.simple(doubleOp);
        } else {
            throw new CompilerException("arithmetic on unsupported JVM type " + type.kind());
        }
        store(instruction.result());
    }

    private void emitIntBinary(IrInstruction instruction, int opcode) {
        List<IrValue> operands = instruction.operands();
        loadValue(operands.get(0));
        loadValue(operands.get(1));
        code.simple(opcode);
        store(instruction.result());
    }

    // ------------------------------------------------------------ compare

    private void emitCompare(IrInstruction instruction) {
        List<IrValue> operands = instruction.operands();
        IrValue left = operands.get(0);
        IrValue right = operands.get(1);
        String symbol = instruction.target() == null ? "==" : instruction.target();
        IrType operandType = left.type();

        if (mapper.isIntFamily(operandType)) {
            loadValue(left);
            loadValue(right);
            emitMaterializedBranch(compareOpcode(symbol));
        } else if (operandType.kind() == IrType.Kind.LONG) {
            loadValue(left);
            loadValue(right);
            code.simple(LCMP);
            code.ldc(pool.integer(0), 1);
            emitMaterializedBranch(compareOpcode(symbol));
        } else if (operandType.kind() == IrType.Kind.FLOAT) {
            loadValue(left);
            loadValue(right);
            code.simple(usesFcmpg(symbol) ? FCMPG : FCMPL);
            code.ldc(pool.integer(0), 1);
            emitMaterializedBranch(compareOpcode(symbol));
        } else if (operandType.kind() == IrType.Kind.DOUBLE) {
            loadValue(left);
            loadValue(right);
            code.simple(usesFcmpg(symbol) ? DCMPG : DCMPL);
            code.ldc(pool.integer(0), 1);
            emitMaterializedBranch(compareOpcode(symbol));
        } else {
            throw new CompilerException("comparison on unsupported JVM type " + operandType.kind());
        }
        store(instruction.result());
    }

    /** Emits {@code if_icmpXX Ltrue; ldc 0; goto Lend; Ltrue: ldc 1; Lend:}. */
    private void emitMaterializedBranch(int ifOpcode) {
        String trueLabel = freshLabel("cmp_t");
        String endLabel = freshLabel("cmp_e");
        code.branch(ifOpcode, trueLabel);
        code.ldc(pool.integer(0), 1);
        code.branch(GOTO, endLabel);
        code.label(trueLabel);
        code.ldc(pool.integer(1), 1);
        code.label(endLabel);
    }

    private int compareOpcode(String symbol) {
        return switch (symbol) {
            case ">" -> IF_ICMPGT;
            case "<" -> IF_ICMPLT;
            case ">=" -> IF_ICMPGE;
            case "<=" -> IF_ICMPLE;
            case "==" -> IF_ICMPEQ;
            case "!=" -> IF_ICMPNE;
            default -> throw new CompilerException("unsupported comparison symbol '" + symbol + "'");
        };
    }

    /** NaN semantics: for {@code <}/{@code <=} use the -NaN-comparing instruction so NaN compares false. */
    private boolean usesFcmpg(String symbol) {
        return "<".equals(symbol) || "<=".equals(symbol);
    }

    // --------------------------------------------------------- conversions

    private void emitConvert(IrInstruction instruction) {
        IrValue source = instruction.operands().get(0);
        IrType from = source.type();
        IrType to = instruction.result().type();
        // Direct decimal-literal-to-double assignments are already exact in the
        // shared lowering (the constant arrives typed as double); any remaining
        // FLOAT -> DOUBLE convert is a genuine float32 widening.
        loadValue(source);
        emitConversion(from, to);
        store(instruction.result());
    }

    private void emitConversion(IrType from, IrType to) {
        if (from.kind() == to.kind()) {
            return;
        }
        boolean fromInt = mapper.isIntFamily(from);
        boolean toInt = mapper.isIntFamily(to);
        if (fromInt && toInt) {
            if (to.kind() == IrType.Kind.BYTE) {
                code.simple(I2B);
            } else if (to.kind() == IrType.Kind.CHAR) {
                code.simple(I2C);
            } else if (to.kind() == IrType.Kind.SHORT) {
                code.simple(I2S);
            }
            return;
        }
        if (fromInt) {
            if (to.kind() == IrType.Kind.LONG) {
                code.simple(I2L);
            } else if (to.kind() == IrType.Kind.FLOAT) {
                code.simple(I2F);
            } else if (to.kind() == IrType.Kind.DOUBLE) {
                code.simple(I2D);
            }
            return;
        }
        if (from.kind() == IrType.Kind.LONG) {
            if (toInt) {
                code.simple(L2I);
            } else if (to.kind() == IrType.Kind.FLOAT) {
                code.simple(L2F);
            } else if (to.kind() == IrType.Kind.DOUBLE) {
                code.simple(L2D);
            }
            return;
        }
        if (from.kind() == IrType.Kind.FLOAT) {
            if (toInt) {
                code.simple(F2I);
            } else if (to.kind() == IrType.Kind.LONG) {
                code.simple(F2L);
            } else if (to.kind() == IrType.Kind.DOUBLE) {
                code.simple(F2D);
            }
            return;
        }
        if (from.kind() == IrType.Kind.DOUBLE) {
            if (toInt) {
                code.simple(D2I);
            } else if (to.kind() == IrType.Kind.LONG) {
                code.simple(D2L);
            } else if (to.kind() == IrType.Kind.FLOAT) {
                code.simple(D2F);
            }
            return;
        }
        throw new CompilerException("no JVM conversion from " + from.kind() + " to " + to.kind());
    }

    // -------------------------------------------------------------- memory

    private void emitLoad(IrInstruction instruction) {
        List<IrValue> operands = instruction.operands();
        IrValue result = instruction.result();
        if ("length".equals(instruction.target()) && operands.size() == 1) {
            loadValue(operands.get(0));
            code.simple(ARRAYLENGTH);
            store(result);
            return;
        }
        loadValue(operands.get(0));
        loadValue(operands.get(1));
        code.simple(arrayLoadOpcode(result.type()));
        store(result);
    }

    private void emitStore(IrInstruction instruction) {
        List<IrValue> operands = instruction.operands();
        loadValue(operands.get(0));
        loadValue(operands.get(1));
        loadValue(operands.get(2));
        code.simple(arrayStoreOpcode(operands.get(2).type()));
    }

    private void emitAlloc(IrInstruction instruction) {
        IrValue result = instruction.result();
        IrType arrayType = result.type();
        loadValue(instruction.operands().get(0));
        if (arrayType.elementType().kind() == IrType.Kind.STRING) {
            code.cpRef(ANEWARRAY, pool.classRef("java/lang/String"));
        } else {
            code.newarray(arrayTypeCode(arrayType.elementType()));
        }
        store(result);
    }

    private int arrayLoadOpcode(IrType elementType) {
        return switch (elementType.kind()) {
            case INT -> IALOAD;
            case BOOL, BYTE -> BALOAD;
            case SHORT -> SALOAD;
            case CHAR -> CALOAD;
            case LONG -> LALOAD;
            case FLOAT -> FALOAD;
            case DOUBLE -> DALOAD;
            case STRING -> AALOAD;
            default -> throw new CompilerException("no JVM array load for " + elementType.kind());
        };
    }

    private int arrayStoreOpcode(IrType elementType) {
        return switch (elementType.kind()) {
            case INT -> IASTORE;
            case BOOL, BYTE -> BASTORE;
            case SHORT -> SASTORE;
            case CHAR -> CASTORE;
            case LONG -> LASTORE;
            case FLOAT -> FASTORE;
            case DOUBLE -> DASTORE;
            case STRING -> AASTORE;
            default -> throw new CompilerException("no JVM array store for " + elementType.kind());
        };
    }

    private int arrayTypeCode(IrType elementType) {
        return switch (elementType.kind()) {
            case BOOL -> 4;
            case CHAR -> 5;
            case FLOAT -> 6;
            case DOUBLE -> 7;
            case BYTE -> 8;
            case SHORT -> 9;
            case INT -> 10;
            case LONG -> 11;
            default -> throw new CompilerException("no JVM newarray type for " + elementType.kind());
        };
    }

    // --------------------------------------------------------------- calls

    private void emitCall(IrInstruction instruction) {
        String name = instruction.target();
        List<IrValue> args = instruction.operands();

        String descriptor;
        int argSlots;
        int returnSlots;
        if ("main".equals(name)) {
            // JVM entry point signature: (String[])V — push a null array for recursive calls.
            descriptor = "([Ljava/lang/String;)V";
            argSlots = 1;
            returnSlots = 0;
            code.simple(ACONST_NULL);
        } else {
            MethodSignature signature = signatures.get(name);
            if (signature == null) {
                throw new CompilerException("unknown function in JVM backend: " + name);
            }
            descriptor = methodDescriptor(signature);
            argSlots = slotSum(signature.parameterTypes());
            returnSlots = mapper.slots(signature.returnType());
        }

        for (IrValue arg : args) {
            loadValue(arg);
        }
        code.invoke(INVOKESTATIC, pool.methodRef(className, name, descriptor), argSlots, returnSlots);

        IrValue result = instruction.result();
        if (result != null && mapper.slots(result.type()) > 0) {
            store(result);
        }
    }

    private void emitExternalCall(IrInstruction instruction) {
        String function = instruction.target();
        switch (function) {
            case "printf" -> emitPrintf(instruction.operands());
            case "lemon_retain", "lemon_release" -> {
                // JVM arrays are garbage-collected; ARC runtime calls are no-ops here.
            }
            default -> throw new CompilerException(
                    "unsupported JVM runtime call: " + function);
        }
    }

    // -------------------------------------------------------------- printf

    private void emitPrintf(List<IrValue> args) {
        if (args.isEmpty()) {
            throw new CompilerException("printf requires a format string");
        }
        String format = unescapeString(stripQuotes(args.get(0).name()));
        int valueIndex = 1;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c != '%') {
                literal.append(c);
                continue;
            }
            flushPrintfLiteral(literal);
            if (i + 1 >= format.length()) {
                throw new CompilerException("printf format string ends with '%'");
            }
            char placeholder = format.charAt(++i);
            if (placeholder != 'd' && placeholder != 'f') {
                throw new CompilerException("printf does not support placeholder %" + placeholder);
            }
            if (valueIndex >= args.size()) {
                throw new CompilerException("printf argument count insufficient");
            }
            emitPrintValue(args.get(valueIndex));
            valueIndex++;
        }
        flushPrintfLiteral(literal);
        if (valueIndex != args.size()) {
            throw new CompilerException("printf argument count mismatch: format requires "
                    + (valueIndex - 1) + ", but found " + (args.size() - 1));
        }
    }

    private void flushPrintfLiteral(StringBuilder literal) {
        if (literal.length() == 0) {
            return;
        }
        String text = literal.toString();
        literal.setLength(0);
        code.ldc(pool.stringRef(text), 1);
        emitPrintTail("(Ljava/lang/String;)V", 1);
    }

    private void emitPrintValue(IrValue value) {
        loadValue(value);
        switch (value.type().kind()) {
            case BOOL, BYTE, SHORT, CHAR, INT -> emitPrintTail("(I)V", 1);
            case FLOAT -> emitPrintTail("(F)V", 1);
            case LONG -> emitPrintTail("(J)V", 2);
            case DOUBLE -> emitPrintTail("(D)V", 2);
            default -> throw new CompilerException(
                    "printf cannot print JVM type " + value.type().kind());
        }
    }

    /** getstatic System.out + (swap | dup_x2; pop) + invokevirtual print(desc). */
    private void emitPrintTail(String descriptor, int valueSlots) {
        code.cpRef(GETSTATIC, pool.fieldRef("java/lang/System", "out", SYSTEM_OUT_FIELD));
        if (valueSlots == 2) {
            code.simple(DUP_X2);
            code.simple(POP);
        } else {
            code.simple(SWAP);
        }
        code.invoke(INVOKEVIRTUAL, pool.methodRef("java/io/PrintStream", "print", descriptor),
                valueSlots + 1, 0);
    }

    // -------------------------------------------------------------- return

    private void emitReturn(IrInstruction instruction) {
        if (instruction.operands().isEmpty()) {
            code.simple(RETURN);
            return;
        }
        loadValue(instruction.operands().get(0));
        if (isMain) {
            // LemonIR main returns int 0; the JVM entry point is void.
            code.simple(POP);
            code.simple(RETURN);
            return;
        }
        if (returnType.kind() == IrType.Kind.VOID) {
            code.simple(POP);
            code.simple(RETURN);
            return;
        }
        if (mapper.isIntFamily(returnType)) {
            code.simple(IRETURN);
        } else if (returnType.kind() == IrType.Kind.LONG) {
            code.simple(LRETURN);
        } else if (returnType.kind() == IrType.Kind.FLOAT) {
            code.simple(FRETURN);
        } else if (returnType.kind() == IrType.Kind.DOUBLE) {
            code.simple(DRETURN);
        } else {
            code.simple(ARETURN);
        }
    }

    // -------------------------------------------------------- load / store

    private void loadValue(IrValue value) {
        JvmLocalAllocator.Local local = locals.get(value.name());
        if (local != null) {
            code.load(loadOpcode(local.type()), local.slot());
            return;
        }
        pushConstant(value.name(), value.type());
    }

    private void store(IrValue value) {
        JvmLocalAllocator.Local local = locals.get(value.name());
        if (local == null) {
            return; // void results and constants have no local slot
        }
        code.store(storeOpcode(local.type()), local.slot());
    }

    private int loadOpcode(IrType type) {
        if (mapper.isIntFamily(type)) {
            return ILOAD;
        }
        if (type.kind() == IrType.Kind.LONG) {
            return LLOAD;
        }
        if (type.kind() == IrType.Kind.FLOAT) {
            return FLOAD;
        }
        if (type.kind() == IrType.Kind.DOUBLE) {
            return DLOAD;
        }
        return ALOAD; // string and arrays
    }

    private int storeOpcode(IrType type) {
        if (mapper.isIntFamily(type)) {
            return ISTORE;
        }
        if (type.kind() == IrType.Kind.LONG) {
            return LSTORE;
        }
        if (type.kind() == IrType.Kind.FLOAT) {
            return FSTORE;
        }
        if (type.kind() == IrType.Kind.DOUBLE) {
            return DSTORE;
        }
        return ASTORE;
    }

    private int slotSum(List<IrType> types) {
        int sum = 0;
        for (IrType type : types) {
            sum += mapper.slots(type);
        }
        return sum;
    }

    private String methodDescriptor(MethodSignature signature) {
        StringBuilder descriptor = new StringBuilder("(");
        for (IrType parameterType : signature.parameterTypes()) {
            descriptor.append(mapper.descriptor(parameterType));
        }
        descriptor.append(')').append(mapper.descriptor(signature.returnType()));
        return descriptor.toString();
    }

    private String freshLabel(String prefix) {
        return prefix + "_" + (labelCounter++);
    }

    // -------------------------------------------------------------- strings

    private static String stripQuotes(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value == null ? "" : value;
    }

    /** Decodes the C-style escapes produced by the LemonIR lowerer. */
    private static String unescapeString(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            switch (next) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                default -> {
                    out.append('\\');
                    out.append(next);
                }
            }
        }
        return out.toString();
    }
}