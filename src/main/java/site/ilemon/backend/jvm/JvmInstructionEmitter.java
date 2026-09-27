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
 * 
 * <p>When arcDebug is enabled, emits diagnostic output for ARC operations
 * to aid debugging and verification.</p>
 */
final class JvmInstructionEmitter {

    // ...existing code...
    
    private final boolean arcDebug;

    // Opcodes used by this backend.
    private static final int ACONST_NULL = 0x01;
    private static final int ICONST_M1 = 0x02;
    private static final int ICONST_0 = 0x03;
    private static final int ICONST_1 = 0x04;
    private static final int ICONST_2 = 0x05;
    private static final int ICONST_3 = 0x06;
    private static final int ICONST_4 = 0x07;
    private static final int ICONST_5 = 0x08;
    private static final int DUP = 0x59;
    private static final int DUP_X1 = 0x5A;
    private static final int SWAP_OPCODE = 0x5F;
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
    private static final int IFLT = 0x9B;
    private static final int IFGE = 0x9C;
    private static final int IFGT = 0x9D;
    private static final int IFLE = 0x9E;
    private static final int IF_ACMPEQ = 0xA5;
    private static final int IF_ACMPNE = 0xA6;
    private static final int IFNULL = 0xC6;
    private static final int NEW = 0xBB;
    private static final int ATHROW = 0xBF;
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
    private static final int GETFIELD = 0xB4;
    private static final int PUTFIELD = 0xB5;
    private static final int INVOKEVIRTUAL = 0xB6;
    private static final int INVOKESPECIAL = 0xB7;
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
    private final IrModule module;
    private final String className;
    private final boolean isMain;
    private final IrType returnType;
    /** Cached live view of the module constant table (never mutated after lowering). */
    private final Map<String, IrModule.IrConstant> moduleConstants;
    /** Cached call-site descriptors, keyed by function name. */
    private final Map<String, String> callDescriptors;
    /** Cached {argSlots, returnSlots} per callee, parallel to callDescriptors. */
    private final Map<String, int[]> callShapes;

    // Names of global constants resolved lazily per module; cached because
    // CONST emission is the hottest path in codegen.
    private static final int CONST_CACHE_MISS = -1;

    /** Names of locals whose address is taken; they live in single-element cells. */
    private final java.util.Set<String> cells;

    private int labelCounter = 0;
    /** Set when a struct cell write borrows the scratch slot past max_locals. */
    private boolean usesStructScratch = false;
    /** &-taken struct locals whose cell array was already materialized. */
    private final java.util.Set<String> cellCreated = new java.util.HashSet<>();

    /** Function signature used for call descriptors. */
    record MethodSignature(List<IrType> parameterTypes, IrType returnType) {
    }

    JvmInstructionEmitter(JvmTypeMapper mapper, JvmClassWriter pool, JvmCodeBuilder code,
                          Map<String, JvmLocalAllocator.Local> locals, IrModule module,
                          String functionName, boolean isMain, IrType returnType,
                          java.util.Set<String> cells, boolean arcDebug) {
        this(mapper, pool, code, locals, module, functionName, isMain, returnType, cells, null, arcDebug);
    }

    JvmInstructionEmitter(JvmTypeMapper mapper, JvmClassWriter pool, JvmCodeBuilder code,
                          Map<String, JvmLocalAllocator.Local> locals, IrModule module,
                          String functionName, boolean isMain, IrType returnType,
                          java.util.Set<String> cells, Map<String, MethodSignature> signatures, boolean arcDebug) {
        this.mapper = mapper;
        this.pool = pool;
        this.code = code;
        this.locals = locals;
        this.module = module;
        this.className = module.name();
        this.isMain = isMain;
        this.returnType = returnType;
        this.cells = cells == null ? java.util.Set.of() : cells;
        this.arcDebug = arcDebug;
        this.moduleConstants = module.hasNoConstants() ? Map.of() : module.constantsView();
        this.callDescriptors = signatures == null || signatures.isEmpty()
                ? Map.of()
                : new HashMap<>(signatures.size() * 2);
        this.callShapes = signatures == null || signatures.isEmpty()
                ? Map.of()
                : new HashMap<>(signatures.size() * 2);
        if (signatures != null) {
            this.signatures = signatures;
        } else {
            this.signatures = new HashMap<>();
            for (var function : module.functions()) {
                this.signatures.put(function.name(), new MethodSignature(
                        function.parameters().stream().map(IrValue::type).toList(),
                        function.returnType()));
            }
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
            case ADDRESS_OF -> emitAddressOf(instruction);
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
            case FIELD_LOAD -> emitFieldLoad(instruction);
            case FIELD_STORE -> emitFieldStore(instruction);
            case STRUCT_COPY -> emitStructCopy(instruction);
            case STRUCT_ZERO -> emitStructZero(instruction);
        }
    }

    // ------------------------------------------------------------ constants

    private void emitConst(IrInstruction instruction) {
        IrValue result = instruction.result();
        String raw = instruction.operands().isEmpty() ? "0" : instruction.operands().get(0).name();
        IrModule.IrConstant constant = moduleConstants.get(raw);
        if (constant != null) {
            // Global constant read: inline the resolved literal value.
            raw = constant.value();
        }
        JvmLocalAllocator.Local local = locals.get(result.name());
        if (local != null && cells.contains(result.name())) {
            // Address-taken local: allocate its single-element cell, keep the
            // cell reference in the local slot, then store the initial value
            // into cell[0].
            createCellArray(result.type());            // [cell]
            code.simple(DUP);                         // [cell, cell]
            code.store(ASTORE, local.slot());         // [cell]
            pushIntConstant(0);                       // [cell, 0]
            pushConstant(raw, result.type());         // [cell, 0, value]
            code.simple(arrayStoreOpcode(result.type()));
            return;
        }
        pushConstant(raw, result.type());
        store(result);
    }

    private void pushConstant(String raw, IrType type) {
        switch (type.kind()) {
            case POINTER, REFERENCE -> {
                // The only materializable pointer constants are null / zero;
                // both are the null reference in the cell representation.
                code.simple(ACONST_NULL);
            }
            case BOOL -> {
                int value = "true".equals(raw) ? 1 : "false".equals(raw) ? 0 : Integer.parseInt(raw);
                pushIntConstant(value);
            }
            case BYTE, SHORT, CHAR, INT -> pushIntConstant(Integer.parseInt(raw));
            case LONG -> code.ldc2w(pool.longConstant(Long.parseLong(raw)));
            case FLOAT -> code.ldc(pool.floatConstant(Float.parseFloat(raw)), 1);
            case DOUBLE -> code.ldc2w(pool.doubleConstant(Double.parseDouble(raw)));
            case STRING -> {
                String value = unescapeString(stripQuotes(raw));
                code.ldc(pool.stringRef(value), 1);
            }
            case STRUCT -> {
                pushStructConstant(raw, type);
            }
            default -> throw new CompilerException("cannot materialize constant of type " + type.kind());
        }
    }

    private void pushStructConstant(String raw, IrType type) {
        String val = raw;
        IrModule.IrConstant constant = moduleConstants.get(raw);
        if (constant != null) {
            val = constant.value();
        }
        String structClass = structClassName(type);
        code.cpRef(NEW, pool.classRef(structClass));
        code.simple(DUP);
        code.invoke(INVOKESPECIAL, pool.methodRef(structClass, "<init>", "()V"), 1, 0);
        if (val != null && val.startsWith("{") && val.endsWith("}")) {
            String inside = val.substring(1, val.length() - 1).trim();
            if (!inside.isEmpty()) {
                String[] parts = inside.split(",", -1);
                IrModule.IrStruct structDef = module.struct(type.name());
                if (structDef != null) {
                    for (int i = 0; i < parts.length && i < structDef.fields().size(); i++) {
                        IrModule.IrStructField field = structDef.fields().get(i);
                        code.simple(DUP);
                        pushConstant(parts[i].trim(), field.type());
                        code.fieldAccess(PUTFIELD, pool.fieldRef(structClass, field.name(), fieldDescriptor(field.type())), mapper.slots(field.type()));
                    }
                }
            }
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

        if (operandType.kind() == IrType.Kind.POINTER || operandType.kind() == IrType.Kind.REFERENCE) {
            // Pointer equality/inequality compares the cell references.
            loadValue(left);
            loadValue(right);
            int opcode = switch (symbol) {
                case "==" -> IF_ACMPEQ;
                case "!=" -> IF_ACMPNE;
                default -> throw new CompilerException(
                        "unsupported pointer comparison symbol '" + symbol + "'");
            };
            emitMaterializedBranch(opcode);
        } else if (mapper.isIntFamily(operandType)) {
            loadValue(left);
            loadValue(right);
            emitMaterializedBranch(compareOpcode(symbol));
        } else if (operandType.kind() == IrType.Kind.LONG) {
            loadValue(left);
            loadValue(right);
            // lcmp/dcmp/fcmp leave a -1/0/+1 result on the stack; a 1-operand
            // if<cond> branches on it directly, with the same truth table as
            // the materialized `push 0; if_icmp<cond>` shape but 1 byte less
            // and one instruction fewer.
            code.simple(LCMP);
            emitMaterializedBranch(singleOperandBranchOpcode(symbol));
        } else if (operandType.kind() == IrType.Kind.FLOAT) {
            loadValue(left);
            loadValue(right);
            // The fcmp variant is chosen exactly as before (FCMPG for < and
            // <=) so NaN lands on the false edge for every comparison.
            code.simple(usesFcmpg(symbol) ? FCMPG : FCMPL);
            emitMaterializedBranch(singleOperandBranchOpcode(symbol));
        } else if (operandType.kind() == IrType.Kind.DOUBLE) {
            loadValue(left);
            loadValue(right);
            code.simple(usesFcmpg(symbol) ? DCMPG : DCMPL);
            emitMaterializedBranch(singleOperandBranchOpcode(symbol));
        } else {
            throw new CompilerException("comparison on unsupported JVM type " + operandType.kind());
        }
        store(instruction.result());
    }

    /** Emits {@code if_icmpXX Ltrue; iconst_0; goto Lend; Ltrue: iconst_1; Lend:}. */
    private void emitMaterializedBranch(int ifOpcode) {
        String trueLabel = freshLabel("cmp_t");
        String endLabel = freshLabel("cmp_e");
        code.branch(ifOpcode, trueLabel);
        pushIntConstant(0);
        code.branch(GOTO, endLabel);
        code.label(trueLabel);
        pushIntConstant(1);
        code.label(endLabel);
    }

    /**
     * 1-operand branch for a -1/0/+1 compare result: replaces the older
     * `push 0; if_icmp<cond>` pair with `if<cond>` on the raw result.
     */
    private int singleOperandBranchOpcode(String symbol) {
        return switch (symbol) {
            case ">" -> IFGT;
            case "<" -> IFLT;
            case ">=" -> IFGE;
            case "<=" -> IFLE;
            case "==" -> IFEQ;
            case "!=" -> IFNE;
            default -> throw new CompilerException("unsupported comparison symbol '" + symbol + "'");
        };
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

    /** Constant-pool index for a boolean literal, cached for 0/1. */
    private int booleanConstantPoolIndex(String raw) {
        if ("true".equals(raw)) {
            return pool.integer(1);
        }
        if ("false".equals(raw)) {
            return pool.integer(0);
        }
        return pool.integer(Integer.parseInt(raw));
    }

    /** Constant-pool index for an int-family literal (pool entries are shared). */
    private int intConstantPoolIndex(String raw) {
        return pool.integer(Integer.parseInt(raw));
    }

    /**
     * Pushes an int-family constant. Small values use the 1-byte iconst
     * opcodes (no constant-pool round trip); everything else falls back to
     * ldc. Semantically identical, just a tighter encoding.
     */
    private void pushIntConstant(int value) {
        switch (value) {
            case -1 -> code.simple(ICONST_M1);
            case 0 -> code.simple(ICONST_0);
            case 1 -> code.simple(ICONST_1);
            case 2 -> code.simple(ICONST_2);
            case 3 -> code.simple(ICONST_3);
            case 4 -> code.simple(ICONST_4);
            case 5 -> code.simple(ICONST_5);
            default -> code.ldc(intConstantPoolIndex(String.valueOf(value)), 1);
        }
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
        JvmLocalAllocator.Local target = locals.get(instruction.result().name());
        if (target != null && cells.contains(target.name())) {
            // Assignment to an address-taken local: [cell, 0, value].
            code.load(ALOAD, target.slot());
            pushIntConstant(0);
            loadValue(source);
            emitConversion(from, to);
            code.simple(arrayStoreOpcode(to));
            return;
        }
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
        if (operands.size() == 1) {
            // Pointer dereference: *(p). The index is statically 0, so emit the
            // dedicated iconst_0 (1 byte) instead of a 2-3 byte ldc. Null
            // dereference is a defined runtime error (diagnosed and thrown),
            // never a raw NPE.
            loadValue(operands.get(0));
            emitNullDerefGuard();
            code.simple(ICONST_0);
            code.simple(arrayLoadOpcode(result.type()));
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
        if (operands.size() == 2) {
            // Pointer store: *(p) = v, guarded like the dereference load.
            loadValue(operands.get(0));
            emitNullDerefGuard();
            code.simple(ICONST_0);
            loadValue(operands.get(1));
            code.simple(arrayStoreOpcode(operands.get(1).type()));
            return;
        }
        loadValue(operands.get(0));
        loadValue(operands.get(1));
        loadValue(operands.get(2));
        code.simple(arrayStoreOpcode(operands.get(2).type()));
    }

    /**
     * Assumes a pointer on the operand stack. Leaves the pointer in place when
     * non-null; on null, reports a defined runtime error and terminates the
     * method by throwing.
     */
    private void emitNullDerefGuard() {
        String fatal = freshLabel("null_deref");
        String ok = freshLabel("null_deref_ok");
        code.simple(DUP);
        code.branch(IFNULL, fatal);
        code.branch(GOTO, ok);
        code.label(fatal);
        code.simple(POP);
        emitNullDerefFatal();
        code.label(ok);
    }

    /** Prints a diagnostic to {@code System.err} and throws (never returns). */
    private void emitNullDerefFatal() {
        code.cpRef(GETSTATIC, pool.fieldRef("java/lang/System", "err", SYSTEM_OUT_FIELD));
        code.ldc(pool.stringRef("Lemon runtime error: null pointer dereference"), 1);
        code.invoke(INVOKEVIRTUAL, pool.methodRef("java/io/PrintStream", "println", "(Ljava/lang/String;)V"), 2, 0);
        code.cpRef(NEW, pool.classRef("java/lang/RuntimeException"));
        code.simple(DUP);
        code.ldc(pool.stringRef("null pointer dereference"), 1);
        code.invoke(INVOKESPECIAL, pool.methodRef("java/lang/RuntimeException", "<init>", "(Ljava/lang/String;)V"), 2, 0);
        code.simple(ATHROW);
    }

    /** Creates a single-element cell array for an address-taken local. */
    private void createCellArray(IrType elementType) {
        pushIntConstant(1);
        if (mapper.isIntFamily(elementType) || elementType.kind() == IrType.Kind.LONG
                || elementType.kind() == IrType.Kind.FLOAT || elementType.kind() == IrType.Kind.DOUBLE) {
            code.newarray(arrayTypeCode(elementType));
        } else {
            // Element is a reference (pointer/string): allocate an array of
            // references via ANEWARRAY with the element's array descriptor.
            code.cpRef(ANEWARRAY, pool.classRef(mapper.descriptor(elementType)));
        }
    }

    private void emitAddressOf(IrInstruction instruction) {
        IrValue operand = instruction.operands().get(0);
        JvmLocalAllocator.Local local = locals.get(operand.name());
        if (local == null || !cells.contains(operand.name())) {
            throw new CompilerException(
                    "address-of target is not an addressable local cell: " + operand.name());
        }
        // The pointer value is the cell reference held in the local slot.
        code.load(ALOAD, local.slot());
        store(instruction.result());
    }

    // ------------------------------------------------------------ structs

    /** Struct class name of an IR struct type, e.g. {@code Main$Point}. */
    private String structClassName(IrType type) {
        return className + "$" + type.name();
    }

    /** GETFIELD/PUTFIELD descriptor of a struct field. */
    private String fieldDescriptor(IrType type) {
        return mapper.descriptor(type);
    }

    /** Emits {@code result = root.path} where root is a struct or struct* value. */
    private void emitFieldLoad(IrInstruction instruction) {
        IrValue root = instruction.operands().get(0);
        String path = instruction.target();
        IrType rootType = root.type();
        if (rootType.kind() == IrType.Kind.POINTER) {
            // p->f: null-guard the cell, load the struct object from cell[0],
            // then navigate the field chain by reference.
            loadValue(root);
            emitNullDerefGuard();
            pushIntConstant(0);
            code.simple(AALOAD);
        } else {
            loadValue(root);
        }
        IrType current = rootType.kind() == IrType.Kind.POINTER ? rootType.elementType() : rootType;
        for (String fieldName : path.split("\\.", -1)) {
            IrType fieldType = fieldTypeOf(current, fieldName);
            code.fieldAccess(GETFIELD, pool.fieldRef(structClassName(current),
                    fieldName, fieldDescriptor(fieldType)), mapper.slots(fieldType));
            current = fieldType;
        }
        store(instruction.result());
    }

    /**
     * Emits {@code root.path = v}. Like C, the write lands in the storage the
     * root designates: struct roots are navigated by reference (GETFIELD) and
     * the final link is a PUTFIELD; pointer roots dereference the cell first
     * (null-guarded) and mutate the same pointee object aliases see.
     */
    private void emitFieldStore(IrInstruction instruction) {
        IrValue root = instruction.operands().get(0);
        IrValue value = instruction.operands().get(1);
        String path = instruction.target();
        IrType rootType = root.type();
        if (rootType.kind() == IrType.Kind.POINTER) {
            // Null-guard the cell, then continue on the pointee object.
            loadValue(root);
            emitNullDerefGuard();
            pushIntConstant(0);
            code.simple(AALOAD);
        } else {
            loadValue(root);
        }
        IrType current = rootType.kind() == IrType.Kind.POINTER ? rootType.elementType() : rootType;
        String[] links = path.split("\\.", -1);
        for (int i = 0; i < links.length; i++) {
            IrType fieldType = fieldTypeOf(current, links[i]);
            if (i == links.length - 1) {
                loadValue(value);
                code.fieldAccess(PUTFIELD, pool.fieldRef(structClassName(current),
                        links[i], fieldDescriptor(fieldType)), mapper.slots(fieldType));
            } else {
                code.fieldAccess(GETFIELD, pool.fieldRef(structClassName(current),
                        links[i], fieldDescriptor(fieldType)), mapper.slots(fieldType));
                current = fieldType;
            }
        }
    }

    /**
     * Emits {@code result = clone(src)}: a fresh struct object built by the
     * synthesized copy constructor ({@code <init>(LType;)V}), which copies
     * every field by value — the by-value semantics C struct assignment has.
     */
    private void emitStructCopy(IrInstruction instruction) {
        IrValue src = instruction.operands().get(0);
        IrType type = instruction.result().type();
        if (src.name().startsWith("_t") && src.type().kind() != IrType.Kind.POINTER) {
            loadValue(src);
            storeStructIntoLocal(instruction.result());
            return;
        }
        // The copy constructor consumes (fresh, src) from the stack. Stash the
        // source in the scratch slot first — a swap across an uninitialized
        // object is rejected by the verifier.
        int scratch = structScratchSlot();
        if (src.type().kind() == IrType.Kind.POINTER) {
            // Copying through a pointer reads the pointee (null-guarded).
            loadValue(src);
            emitNullDerefGuard();
            pushIntConstant(0);
            code.simple(AALOAD);
        } else {
            loadValue(src);
        }
        code.store(ASTORE, scratch);          // []
        code.cpRef(NEW, pool.classRef(structClassName(type)));
        code.simple(DUP);                     // [obj, obj]
        code.load(ALOAD, scratch);            // [obj, obj, src]
        code.invoke(INVOKESPECIAL, pool.methodRef(structClassName(type), "<init>",
                "(" + fieldDescriptor(type) + ")V"), 2, 0);
        // [obj]: for &-taken targets write through the cell so aliases see it;
        // otherwise plain local store.
        storeStructIntoLocal(instruction.result());
    }

    /**
     * Stores a struct object (already on the stack) into its result local. An
     * address-taken struct (&obj) lives in a single-element cell referenced by
     * the local slot; if the cell does not exist yet (struct locals have no
     * CONST init), it is allocated here and stored into the slot.
     */
    private void storeStructIntoLocal(IrValue result) {
        if (cells.contains(result.name())) {
            JvmLocalAllocator.Local local = locals.get(result.name());
            int scratch = structScratchSlot();
            code.store(ASTORE, scratch);       // [] (obj stashed)
            if (!cellCreated.contains(result.name())) {
                // First write to this &-taken struct: materialize the cell.
                pushIntConstant(1);            // [1]
                code.cpRef(ANEWARRAY, pool.classRef(structClassName(
                        IrType.structType(structNameOf(result)))));
                code.store(ASTORE, local.slot()); // slot := cell
                cellCreated.add(result.name());
            }
            code.load(ALOAD, local.slot());    // [cell]
            pushIntConstant(0);                // [cell, 0]
            code.load(ALOAD, scratch);         // [cell, 0, obj]
            code.simple(AASTORE);              // cell[0] = obj
            return;
        }
        store(result);
    }

    /** Struct type name of a result value (root of a struct chain). */
    private String structNameOf(IrValue value) {
        return value.type().name();
    }

    /** One past the method's allocated locals; free for backend scratch. */
    private int structScratchSlot() {
        usesStructScratch = true;
        int max = 0;
        for (JvmLocalAllocator.Local local : locals.values()) {
            max = Math.max(max, local.slot() + mapper.slots(local.type()));
        }
        return max;
    }

    /** True when emission used the backend scratch slot (max_locals must grow). */
    boolean usesStructScratch() {
        return usesStructScratch;
    }

    /** Emits {@code result = all-fields-zero} for a struct local. */
    private void emitStructZero(IrInstruction instruction) {
        IrType type = instruction.result().type();
        code.cpRef(NEW, pool.classRef(structClassName(type)));
        code.simple(DUP);
        code.invoke(INVOKESPECIAL, pool.methodRef(structClassName(type), "<init>", "()V"), 1, 0);
        storeStructIntoLocal(instruction.result());
    }

    /** Result type of a single field link inside a struct type. */
    private IrType fieldTypeOf(IrType structType, String fieldName) {
        IrModule.IrStruct struct = module.struct(structType.name());
        if (struct == null) {
            throw new CompilerException("unknown struct in JVM backend: " + structType.name());
        }
        for (IrModule.IrStructField field : struct.fields()) {
            if (field.name().equals(fieldName)) {
                return field.type();
            }
        }
        throw new CompilerException("struct " + structType.name() + " has no field " + fieldName);
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
            case STRING, POINTER, REFERENCE, ARRAY -> AALOAD;
            case STRUCT -> AALOAD; // struct cell loads (&obj storage)
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
            case STRING, POINTER, REFERENCE, ARRAY -> AASTORE;
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
            // Call shape (descriptor + slot counts) is immutable per callee;
            // cache it so recursive/hot call sites skip re-deriving it.
            int[] shape = callShapes.get(name);
            if (shape != null) {
                descriptor = callDescriptors.get(name);
                argSlots = shape[0];
                returnSlots = shape[1];
            } else {
                descriptor = methodDescriptor(signature);
                callDescriptors.put(name, descriptor);
                argSlots = slotSum(signature.parameterTypes());
                returnSlots = mapper.slots(signature.returnType());
                callShapes.put(name, new int[]{argSlots, returnSlots});
            }
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
                if (arcDebug && !instruction.operands().isEmpty()) {
                    IrValue operand = instruction.operands().get(0);
                    // Emit debug output to System.err showing the ARC operation
                    String opName = function.equals("lemon_retain") ? "RETAIN" : "RELEASE";
                    code.cpRef(GETSTATIC, pool.fieldRef("java/lang/System", "err", SYSTEM_OUT_FIELD));
                    code.ldc(pool.stringRef("[ARC] " + opName + " " + operand.name() + " at " + className), 1);
                    code.invoke(INVOKEVIRTUAL, pool.methodRef("java/io/PrintStream", "println", "(Ljava/lang/String;)V"), 2, 0);
                }
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
            if (placeholder != 'd' && placeholder != 'f' && placeholder != 's') {
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
            case STRING -> emitPrintTail("(Ljava/lang/String;)V", 1);
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
        if (returnType.kind() == IrType.Kind.STRUCT) {
            // Returning a struct by value returns the object reference; the
            // caller clones it before storing into its own locals.
            code.simple(ARETURN);
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
        if (moduleConstants.containsKey(value.name())) {
            pushConstant(value.name(), value.type());
            return;
        }
        JvmLocalAllocator.Local local = locals.get(value.name());
        if (local != null) {
            if (cells.contains(value.name())) {
                // Value read of an address-taken local: load cell[0].
                code.load(ALOAD, local.slot());
                pushIntConstant(0);
                code.simple(arrayLoadOpcode(local.type()));
                return;
            }
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
        if (cells.contains(value.name())) {
            throw new CompilerException(
                    "direct store to address-taken local must be a cell write: " + value.name());
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