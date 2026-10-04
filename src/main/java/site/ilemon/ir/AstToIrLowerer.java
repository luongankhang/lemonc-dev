package site.ilemon.ir;

import site.ilemon.ast.Ast;
import site.ilemon.exception.CompilerException;

import java.util.*;

/**
 * Lowers Lemon frontend AST into target-independent LemonIR (IrModule).
 * Handles control flow, arithmetic, arrays, functions, and ARC ownership operations.
 */
public final class AstToIrLowerer {

    private int labelCounter = 0;
    private int tempCounter = 0;

    /** Program-level constants (including {@code alias_NAME} re-exports from imports). */
    private List<Ast.ConstDecl> programConsts = List.of();

    private enum ScopeKind {
        METHOD,
        BLOCK,
        LOOP_FOR,
        LOOP_BODY
    }

    private static final class LexicalScope {
        final ScopeKind kind;
        final List<String> managedVars = new ArrayList<>();
        final List<IrValue> tempRvalues = new ArrayList<>();

        LexicalScope(ScopeKind kind) {
            this.kind = kind;
        }
    }

    private record LoopContext(BasicBlock breakTarget, BasicBlock continueTarget, LexicalScope forScope, LexicalScope bodyScope) {}

    /** Open switch statements; break inside a switch targets the nearest loop or switch. */
    private final Deque<BasicBlock> switchStack = new ArrayDeque<>();

    private final Map<String, IrType> methodReturnTypes = new HashMap<>();
    private final Map<String, List<IrType>> methodParamTypes = new HashMap<>();
    private IrModule module;

    public IrModule lower(Ast.Program.T program) {
        if (!(program instanceof Ast.Program.ProgramSingle root)) {
            throw new IllegalArgumentException("unsupported program AST");
        }
        if (!(root.getMainClass() instanceof Ast.MainClass.MainClassSingle main)) {
            throw new IllegalArgumentException("unsupported main class AST");
        }

        IrModule module = new IrModule(main.getClassId() != null ? main.getClassId() : "Main");
        this.module = module;
        // Register struct layouts so both backends can resolve field names.
        if (main.getStructs() != null) {
            for (Ast.StructDecl structDecl : main.getStructs()) {
                module.addStruct(toIrStruct(structDecl));
            }
        }
        if (main.getModuleStructs() != null) {
            for (var list : main.getModuleStructs().values()) {
                for (Ast.StructDecl structDecl : list) {
                    module.addStruct(toIrStruct(structDecl));
                }
            }
        }
        // Register enum layouts so both backends can resolve enum types/members.
        if (main.getEnums() != null) {
            for (Ast.EnumDecl enumDecl : main.getEnums()) {
                module.addEnum(toIrEnum(enumDecl));
            }
        }
        if (main.getModuleEnums() != null) {
            for (var list : main.getModuleEnums().values()) {
                for (Ast.EnumDecl enumDecl : list) {
                    module.addEnum(toIrEnum(enumDecl));
                }
            }
        }
        programConsts = main.getConstants() == null ? List.of() : main.getConstants();
        for (Ast.ConstDecl constant : programConsts) {
            registerConstant(module, constant);
        }

        // Pre-scan all method signatures
        for (Ast.Method.T m : main.getMethods()) {
            if (m instanceof Ast.Method.MethodSingle method) {
                IrType retType = toIrType(method.getRetType());
                if ("main".equals(method.getId()) && retType.kind() == IrType.Kind.VOID) {
                    retType = IrType.scalar(IrType.Kind.INT);
                }
                methodReturnTypes.put(method.getId(), retType);

                List<IrType> pTypes = new ArrayList<>();
                if (method.getFormals() != null) {
                    for (Ast.Declare.T formal : method.getFormals()) {
                        if (formal instanceof Ast.Declare.DeclareSingle d) {
                            pTypes.add(toIrType(d.getType()));
                        }
                    }
                }
                methodParamTypes.put(method.getId(), pTypes);
            }
        }

        // Lower each method
        for (Ast.Method.T m : main.getMethods()) {
            if (m instanceof Ast.Method.MethodSingle method) {
                // Constants reachable from this method's declaring module must be
                // visible to the backends (C declarations / JVM value substitution).
                if (method.getModuleConsts() != null) {
                    for (Ast.ConstDecl constant : method.getModuleConsts()) {
                        registerConstant(module, constant);
                    }
                }
                module.addFunction(lowerMethod(method));
            }
        }

        IrVerifier.verify(module);
        return module;
    }

    private IrFunction lowerMethod(Ast.Method.MethodSingle method) {
        boolean isMain = "main".equals(method.getId());
        IrType returnType = methodReturnTypes.get(method.getId());

        List<IrValue> params = new ArrayList<>();
        Map<String, IrType> variableTypes = new HashMap<>();

        if (method.getFormals() != null) {
            for (Ast.Declare.T formal : method.getFormals()) {
                if (formal instanceof Ast.Declare.DeclareSingle d) {
                    IrType t = toIrType(d.getType());
                    params.add(new IrValue(d.getId(), t));
                    variableTypes.put(d.getId(), t);
                }
            }
        }

        if (method.getLocals() != null) {
            for (Ast.Declare.T local : method.getLocals()) {
                if (local instanceof Ast.Declare.DeclareSingle d) {
                    IrType t = toIrType(d.getType());
                    variableTypes.put(d.getId(), t);
                }
            }
        }

        IrFunction irFunc = new IrFunction(method.getId(), returnType, params);
        List<BasicBlock> blocks = new ArrayList<>();
        BasicBlock entry = new BasicBlock("entry");
        blocks.add(entry);

        List<Ast.ConstDecl> methodConsts = method.getModuleConsts() != null ? method.getModuleConsts() : programConsts;
        site.ilemon.flow.NullFlowResult nullFlow = site.ilemon.flow.NullFlowAnalyzer.analyze(method);
        MethodLoweringContext ctx = new MethodLoweringContext(
                irFunc, blocks, entry, variableTypes, isMain, returnType, methodConsts, nullFlow
        );
        LexicalScope methodScope = ctx.pushScope(ScopeKind.METHOD);

        // In entry block: retain incoming managed parameters unless they are purely borrowed
        for (IrValue p : params) {
            if (!isParameterBorrowed(p.name(), method)) {
                emitRetain(p, ctx);
                methodScope.managedVars.add(p.name());
            }
        }

        // In entry block: allocate arrays and initialize locals that do not have VarDecl statements
        Set<String> varDeclNames = new HashSet<>();
        collectVarDeclNames(method.getStms(), varDeclNames);
        if (method.getLocals() != null) {
            for (Ast.Declare.T local : method.getLocals()) {
                if (local instanceof Ast.Declare.DeclareSingle d && !varDeclNames.contains(d.getId())) {
                    IrType t = variableTypes.get(d.getId());
                    if (isManaged(t) || !getManagedPaths(t).isEmpty()) {
                        methodScope.managedVars.add(d.getId());
                    }
                    if (t != null && t.kind() == IrType.Kind.STRUCT) {
                        emitStructInit(d.getId(), t, ctx);
                    } else if (isManaged(t)) {
                        int size = getArraySize(d.getType());
                        IrValue lenVal = new IrValue(String.valueOf(size), IrType.scalar(IrType.Kind.INT));
                        ctx.emit(new IrInstruction(IrInstruction.Op.ALLOC, new IrValue(d.getId(), t), List.of(lenVal), null));
                    } else {
                        ctx.emit(new IrInstruction(IrInstruction.Op.CONST, new IrValue(d.getId(), t), List.of(new IrValue("0", t)), null));
                    }
                }
            }
        }

        // Lower method statements
        if (method.getStms() != null) {
            for (Ast.Stmt.T stmt : method.getStms()) {
                lowerStmt(stmt, ctx);
            }
        }

        // If the last block is not terminated, emit cleanup and default return
        if (!ctx.isTerminated(ctx.currentBlock)) {
            ctx.releaseAllScopesExcept(null);
            if (isMain) {
                IrValue zero = new IrValue("0", IrType.scalar(IrType.Kind.INT));
                ctx.emit(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(zero), null));
            } else if (returnType.kind() == IrType.Kind.VOID) {
                ctx.emit(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(), null));
            } else {
                IrValue zero = new IrValue("0", returnType);
                ctx.emit(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(zero), null));
            }
        }

        // Add all non-empty, terminated blocks to the function. Block shells
        // that were never started stay empty and are dropped here.
        for (BasicBlock block : blocks) {
            if (!block.instructionsView().isEmpty() && ctx.isTerminated(block)) {
                irFunc.addBlock(block);
            }
        }

        return irFunc;
    }

    private void emitStructInit(String targetId, IrType structType, MethodLoweringContext ctx) {
        ctx.emit(new IrInstruction(IrInstruction.Op.STRUCT_ZERO, new IrValue(targetId, structType), List.of(), null));
        IrModule.IrStruct structDef = module.struct(structType.name());
        if (structDef != null) {
            for (IrModule.IrStructField sf : structDef.fields()) {
                if (sf.type().kind() == IrType.Kind.ARRAY && sf.arraySize() > 0) {
                    IrValue lenVal = new IrValue(String.valueOf(sf.arraySize()), IrType.scalar(IrType.Kind.INT));
                    IrValue arrVal = ctx.newTemp(sf.type());
                    ctx.emit(new IrInstruction(IrInstruction.Op.ALLOC, arrVal, List.of(lenVal), null));
                    ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_STORE, null, List.of(new IrValue(targetId, structType), arrVal), sf.name()));
                } else if (sf.type().kind() == IrType.Kind.STRUCT) {
                    IrValue nestedVal = ctx.newTemp(sf.type());
                    emitStructInit(nestedVal.name(), sf.type(), ctx);
                    ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_STORE, null, List.of(new IrValue(targetId, structType), nestedVal), sf.name()));
                }
            }
        }
    }

    /** Converts a declared struct into a backend-neutral layout. */
    private IrModule.IrStruct toIrStruct(Ast.StructDecl structDecl) {
        List<IrModule.IrStructField> fields = new ArrayList<>();
        if (structDecl.getFields() != null) {
            for (Ast.Declare.T field : structDecl.getFields()) {
                if (field instanceof Ast.Declare.DeclareSingle single) {
                    fields.add(new IrModule.IrStructField(single.getId(), toIrType(single.getType()), getArraySize(single.getType())));
                }
            }
        }
        return new IrModule.IrStruct(structDecl.getName(), fields);
    }

    /** Converts a declared enum into a backend-neutral layout. */
    private IrModule.IrEnum toIrEnum(Ast.EnumDecl enumDecl) {
        List<IrModule.IrEnumMember> members = new ArrayList<>();
        if (enumDecl.getMembers() != null) {
            for (Ast.EnumMember member : enumDecl.getMembers()) {
                members.add(new IrModule.IrEnumMember(member.getName(), member.getValue()));
            }
        }
        return new IrModule.IrEnum(enumDecl.getName(), members);
    }

    private record IrEnumMemberInfo(String enumName, String memberName, int value) {}

    private IrEnumMemberInfo findEnumMember(String name) {
        for (IrModule.IrEnum e : module.enumsView().values()) {
            for (IrModule.IrEnumMember m : e.members()) {
                if (m.name().equals(name)) {
                    return new IrEnumMemberInfo(e.name(), m.name(), m.value());
                }
            }
        }
        return null;
    }

    /** Adds a resolved AST constant to the module's backend-neutral table. */
    private void registerConstant(IrModule module, Ast.ConstDecl constant) {
        if (constant == null || constant.getResolvedValue() == null) {
            return;
        }
        IrType type = toIrType(constant.getType());
        String value = constant.getResolvedValue();
        if (type.kind() == IrType.Kind.STRING) {
            // Store the quoted, C-escaped representation, like string literals.
            value = "\"" + escapeCString(value) + "\"";
        }
        module.addConstant(new IrModule.IrConstant(
                constant.getId(), type, value, constant.getVisibility() == Ast.Visibility.PUBLIC));
    }

    /** Resolves a name to a global constant: the method's module table first, then the program's. */
    private Ast.ConstDecl findConst(String name, MethodLoweringContext ctx) {
        if (ctx.consts != null) {
            for (Ast.ConstDecl constant : ctx.consts) {
                if (constant.getId().equals(name)) {
                    return constant;
                }
            }
        }
        for (Ast.ConstDecl constant : programConsts) {
            if (constant.getId().equals(name)) {
                return constant;
            }
        }
        return null;
    }

    private void lowerStmt(Ast.Stmt.T stmt, MethodLoweringContext ctx) {
        if (stmt == null || ctx.isTerminated(ctx.currentBlock)) {
            return;
        }

        if (stmt instanceof Ast.Stmt.VarDecl varDecl) {
            Ast.Declare.T declaration = varDecl.getDeclaration();
            if (declaration instanceof Ast.Declare.DeclareSingle d) {
                String targetId = d.getId();
                IrType targetType = ctx.variableTypes.get(targetId);
                if (targetType == null) {
                    targetType = toIrType(d.getType());
                    ctx.variableTypes.put(targetId, targetType);
                }
                if (isManaged(targetType) || !getManagedPaths(targetType).isEmpty()) {
                    ctx.currentScope().managedVars.add(targetId);
                }
                IrValue rhsVal = null;
                if (d.getInitExp() != null) {
                    if (d.getInitExp() instanceof Ast.Expr.InitializerList initList) {
                        lowerArrayInitializer(initList, targetId, targetType, ctx);
                    } else if (targetType.kind() == IrType.Kind.DOUBLE
                            && d.getInitExp() instanceof Ast.Expr.Number number
                            && number.getType() instanceof Ast.Type.Float) {
                        rhsVal = ctx.newTemp(IrType.scalar(IrType.Kind.DOUBLE));
                        ctx.emit(new IrInstruction(IrInstruction.Op.CONST, rhsVal,
                                List.of(new IrValue(String.valueOf(number.getValue()),
                                        IrType.scalar(IrType.Kind.DOUBLE))), null));
                    } else {
                        rhsVal = lowerExpr(d.getInitExp(), ctx);
                    }
                    // Array initializer handles its own ALLOC + STOREs; skip generic init below.
                    if (rhsVal == null) {
                        // fall through — nothing more to do for this declaration
                    } else if (isManaged(targetType)) {
                        if (isManaged(rhsVal.type())) {
                            if (!rhsVal.name().startsWith("_t")) {
                                ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(rhsVal), "lemon_retain"));
                            }
                        }
                        ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, new IrValue(targetId, targetType), List.of(rhsVal), null));
                    } else if (targetType.kind() == IrType.Kind.STRUCT) {
                        List<String> paths = getManagedPaths(targetType);
                        if (!paths.isEmpty() && !rhsVal.name().startsWith("_t")) {
                            emitRetain(rhsVal, ctx);
                        }
                        // By-value struct init: copy into the fresh local.
                        ctx.emit(new IrInstruction(IrInstruction.Op.STRUCT_COPY,
                                new IrValue(targetId, targetType), List.of(rhsVal), null));
                    } else {
                        if (rhsVal.type().kind() != targetType.kind()) {
                            IrValue converted = ctx.newTemp(targetType);
                            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(rhsVal), null));
                            rhsVal = converted;
                        }
                        ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, new IrValue(targetId, targetType), List.of(rhsVal), null));
                    }
                } else {
                    if (targetType.kind() == IrType.Kind.STRUCT) {
                        emitStructInit(targetId, targetType, ctx);
                    } else if (isManaged(targetType)) {
                        int size = getArraySize(d.getType());
                        IrValue lenVal = new IrValue(String.valueOf(size), IrType.scalar(IrType.Kind.INT));
                        ctx.emit(new IrInstruction(IrInstruction.Op.ALLOC, new IrValue(targetId, targetType), List.of(lenVal), null));
                    } else {
                        ctx.emit(new IrInstruction(IrInstruction.Op.CONST, new IrValue(targetId, targetType), List.of(new IrValue("0", targetType)), null));
                    }
                }
                if (rhsVal != null) {
                    final String rName = rhsVal.name();
                    ctx.currentScope().tempRvalues.removeIf(v -> v.name().equals(rName));
                }
                ctx.cleanupStatementTemporaries();
            }
            return;
        }

        if (stmt instanceof Ast.Stmt.Assign assign) {
            String targetId = assign.getId() != null ? assign.getId().getId() : "";
            IrType targetType = ctx.variableTypes.get(targetId);
            if (targetType == null) targetType = IrType.scalar(IrType.Kind.INT);

            IrValue rhsVal;
            if (targetType.kind() == IrType.Kind.DOUBLE
                    && assign.getExpr() instanceof Ast.Expr.Number number
                    && number.getType() instanceof Ast.Type.Float) {
                // Direct assignment of a decimal literal to a double keeps the
                // exact decimal value (legacy JVM behavior, C-style literal
                // typing): emit the constant as double, skipping the float32
                // round-trip the general widening would perform.
                rhsVal = ctx.newTemp(IrType.scalar(IrType.Kind.DOUBLE));
                ctx.emit(new IrInstruction(IrInstruction.Op.CONST, rhsVal,
                        List.of(new IrValue(String.valueOf(number.getValue()),
                                IrType.scalar(IrType.Kind.DOUBLE))), null));
            } else {
                rhsVal = lowerExpr(assign.getExpr(), ctx);
            }
            IrInstruction.Op binOp = compoundAssignOp(assign.getOp());
            if (binOp != null) {
                IrValue oldVal = ctx.newTemp(targetType);
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, oldVal, List.of(new IrValue(targetId, targetType)), null));
                rhsVal = applyBinaryOp(binOp, oldVal, rhsVal, ctx);
            }
            if (targetType.kind() == IrType.Kind.STRUCT) {
                List<String> paths = getManagedPaths(targetType);
                if (!paths.isEmpty()) {
                    if (!rhsVal.name().startsWith("_t")) {
                        emitRetain(rhsVal, ctx);
                    }
                    emitRelease(targetId, targetType, ctx);
                }
                // Struct assignment copies field-by-field (by-value semantics).
                ctx.emit(new IrInstruction(IrInstruction.Op.STRUCT_COPY,
                        new IrValue(targetId, targetType), List.of(rhsVal), null));
            } else if (isManaged(targetType)) {
                if (isManaged(rhsVal.type())) {
                    if (!rhsVal.name().startsWith("_t")) {
                        ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(rhsVal), "lemon_retain"));
                    }
                }
                ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(new IrValue(targetId, targetType)), "lemon_release"));
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, new IrValue(targetId, targetType), List.of(rhsVal), null));
            } else {
                if (rhsVal.type().kind() != targetType.kind()) {
                    IrValue converted = ctx.newTemp(targetType);
                    ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(rhsVal), null));
                    rhsVal = converted;
                }
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, new IrValue(targetId, targetType), List.of(rhsVal), null));
            }
            if (rhsVal != null) {
                final String rName = rhsVal.name();
                ctx.currentScope().tempRvalues.removeIf(v -> v.name().equals(rName));
            }
            ctx.cleanupStatementTemporaries();
        } else if (stmt instanceof Ast.Stmt.FieldAssign fieldAssign) {
            boolean isSafe = fieldAssign.getTarget().isPointerBase() && ctx.nullFlow != null && ctx.nullFlow.isSafe(fieldAssign);
            IrValue value = lowerExpr(fieldAssign.getExpr(), ctx);
            IrInstruction.Op binOp = compoundAssignOp(fieldAssign.getOp());
            if (binOp != null) {
                IrValue oldVal = lowerFieldLoad(fieldAssign.getTarget(), ctx);
                value = applyBinaryOp(binOp, oldVal, value, ctx);
            }
            lowerFieldStore(fieldAssign.getTarget(), value, isSafe, ctx);
            ctx.cleanupStatementTemporaries();
        } else if (stmt instanceof Ast.Stmt.DerefAssign derefAssign) {
            lowerDerefAssign(derefAssign, ctx);
            ctx.cleanupStatementTemporaries();
        } else if (stmt instanceof Ast.Stmt.ArrayAssign arrayAssign) {
            IrValue arrVal;
            IrType arrType;
            if (arrayAssign.getFieldTarget() != null) {
                // For arr[i].field = expr: lower the index and field load/store
                arrVal = lowerArrayFieldLoad(arrayAssign.getFieldTarget(), arrayAssign.getIndex(), ctx);
                arrType = arrVal.type();
            } else {
                String arrName = arrayAssign.getArrayName();
                arrType = ctx.variableTypes.get(arrName);
                arrVal = new IrValue(arrName, arrType);
            }

            IrValue idxVal = lowerExpr(arrayAssign.getIndex(), ctx);
            IrValue val = lowerExpr(arrayAssign.getExpr(), ctx);

            ctx.emit(new IrInstruction(IrInstruction.Op.BOUNDS_CHECK, null, List.of(arrVal, idxVal), null));

            IrType elemType = arrType != null && arrType.elementType() != null ? arrType.elementType() : IrType.scalar(IrType.Kind.INT);
            IrInstruction.Op binOp = compoundAssignOp(arrayAssign.getOp());
            if (binOp != null) {
                IrValue oldVal = ctx.newTemp(elemType);
                ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, oldVal, List.of(arrVal, idxVal), null));
                val = applyBinaryOp(binOp, oldVal, val, ctx);
            }
            if (val.type().kind() != elemType.kind()) {
                IrValue converted = ctx.newTemp(elemType);
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(val), null));
                val = converted;
            }

            ctx.emit(new IrInstruction(IrInstruction.Op.STORE, null, List.of(arrVal, idxVal, val), null));
            ctx.cleanupStatementTemporaries();
        } else if (stmt instanceof Ast.Stmt.Block block) {
            LexicalScope blockScope = ctx.pushScope(ScopeKind.BLOCK);
            if (block.getStmts() != null) {
                for (Ast.Stmt.T s : block.getStmts()) {
                    lowerStmt(s, ctx);
                }
            }
            ctx.releaseScope(blockScope);
            ctx.popScope();
        } else if (stmt instanceof Ast.Stmt.If ifStmt) {
            IrValue condVal = lowerExpr(ifStmt.getCondition(), ctx);

            BasicBlock thenBlock = ctx.createBlock("if_then");
            BasicBlock elseBlock = ifStmt.getElseStmt() != null ? ctx.createBlock("if_else") : null;
            BasicBlock mergeBlock = ctx.createBlock("if_merge");

            BasicBlock falseTarget = elseBlock != null ? elseBlock : mergeBlock;
            IrValue notCond = ctx.newTemp(IrType.scalar(IrType.Kind.BOOL));
            ctx.emit(new IrInstruction(IrInstruction.Op.CMP, notCond, List.of(condVal, new IrValue("0", condVal.type())), "=="));
            ctx.emit(new IrInstruction(IrInstruction.Op.COND_BRANCH, null, List.of(notCond), falseTarget.name()));

            // Then branch
            ctx.startBlock(thenBlock);
            boolean isBlockThen = ifStmt.getThenStmt() instanceof Ast.Stmt.Block;
            LexicalScope thenScope = isBlockThen ? null : ctx.pushScope(ScopeKind.BLOCK);
            lowerStmt(ifStmt.getThenStmt(), ctx);
            if (thenScope != null) {
                ctx.releaseScope(thenScope);
                ctx.popScope();
            }
            boolean thenTerm = ctx.isTerminated(ctx.currentBlock);
            if (!thenTerm) {
                ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), mergeBlock.name()));
            }

            // Else branch (if exists)
            boolean elseTerm = false;
            if (elseBlock != null) {
                ctx.startBlock(elseBlock);
                boolean isBlockElse = ifStmt.getElseStmt() instanceof Ast.Stmt.Block;
                LexicalScope elseScope = isBlockElse ? null : ctx.pushScope(ScopeKind.BLOCK);
                lowerStmt(ifStmt.getElseStmt(), ctx);
                if (elseScope != null) {
                    ctx.releaseScope(elseScope);
                    ctx.popScope();
                }
                elseTerm = ctx.isTerminated(ctx.currentBlock);
                if (!elseTerm) {
                    ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), mergeBlock.name()));
                }
            }

            if (!thenTerm || !elseTerm || elseBlock == null) {
                ctx.startBlock(mergeBlock);
            }
        } else if (stmt instanceof Ast.Stmt.While whileStmt) {
            BasicBlock condBlock = ctx.createBlock("while_cond");
            BasicBlock bodyBlock = ctx.createBlock("while_body");
            BasicBlock exitBlock = ctx.createBlock("while_exit");

            ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), condBlock.name()));

            ctx.startBlock(condBlock);
            IrValue condVal = lowerExpr(whileStmt.getCondition(), ctx);
            IrValue notCond = ctx.newTemp(IrType.scalar(IrType.Kind.BOOL));
            ctx.emit(new IrInstruction(IrInstruction.Op.CMP, notCond, List.of(condVal, new IrValue("0", condVal.type())), "=="));
            ctx.emit(new IrInstruction(IrInstruction.Op.COND_BRANCH, null, List.of(notCond), exitBlock.name()));

            ctx.startBlock(bodyBlock);
            LexicalScope bodyScope = ctx.pushScope(ScopeKind.LOOP_BODY);
            ctx.loopStack.push(new LoopContext(exitBlock, condBlock, null, bodyScope));
            lowerStmt(whileStmt.getBody(), ctx);
            ctx.loopStack.pop();
            ctx.releaseScope(bodyScope);
            ctx.popScope();

            if (!ctx.isTerminated(ctx.currentBlock)) {
                ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), condBlock.name()));
            }

            ctx.startBlock(exitBlock);
        } else if (stmt instanceof Ast.Stmt.For forStmt) {
            LexicalScope forScope = ctx.pushScope(ScopeKind.LOOP_FOR);
            if (forStmt.getInit() != null) {
                lowerStmt(forStmt.getInit(), ctx);
            }

            BasicBlock condBlock = ctx.createBlock("for_cond");
            BasicBlock bodyBlock = ctx.createBlock("for_body");
            BasicBlock updateBlock = ctx.createBlock("for_update");
            BasicBlock exitBlock = ctx.createBlock("for_exit");

            ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), condBlock.name()));

            ctx.startBlock(condBlock);
            if (forStmt.getCondition() != null) {
                IrValue condVal = lowerExpr(forStmt.getCondition(), ctx);
                IrValue notCond = ctx.newTemp(IrType.scalar(IrType.Kind.BOOL));
                ctx.emit(new IrInstruction(IrInstruction.Op.CMP, notCond, List.of(condVal, new IrValue("0", condVal.type())), "=="));
                ctx.emit(new IrInstruction(IrInstruction.Op.COND_BRANCH, null, List.of(notCond), exitBlock.name()));
            }

            ctx.startBlock(bodyBlock);
            LexicalScope bodyScope = ctx.pushScope(ScopeKind.LOOP_BODY);
            ctx.loopStack.push(new LoopContext(exitBlock, updateBlock, forScope, bodyScope));
            lowerStmt(forStmt.getBody(), ctx);
            ctx.loopStack.pop();
            ctx.releaseScope(bodyScope);
            ctx.popScope();

            if (!ctx.isTerminated(ctx.currentBlock)) {
                ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), updateBlock.name()));
            }

            ctx.startBlock(updateBlock);
            if (forStmt.getUpdate() != null) {
                lowerStmt(forStmt.getUpdate(), ctx);
            }
            if (!ctx.isTerminated(ctx.currentBlock)) {
                ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), condBlock.name()));
            }

            ctx.startBlock(exitBlock);
            ctx.releaseScope(forScope);
            ctx.popScope();
        } else if (stmt instanceof Ast.Stmt.Switch switchStmt) {
            lowerSwitch(switchStmt, ctx);
        } else if (stmt instanceof Ast.Stmt.Break) {
            // Innermost breakable construct wins: a break inside a switch
            // nested in a loop exits the switch, not the loop.
            BasicBlock breakTarget = null;
            LexicalScope releaseTargetScope = null;
            if (!switchStack.isEmpty()) {
                boolean loopInsideSwitch = !ctx.loopStack.isEmpty()
                        && ctx.loopStack.peek().breakTarget == switchStack.peek();
                if (!loopInsideSwitch) {
                    breakTarget = switchStack.peek();
                    releaseTargetScope = ctx.currentScope();
                }
            }
            if (breakTarget == null && !ctx.loopStack.isEmpty()) {
                LoopContext loop = ctx.loopStack.peek();
                breakTarget = loop.breakTarget;
                releaseTargetScope = loop.bodyScope;
            }
            if (breakTarget == null && !switchStack.isEmpty()) {
                // Loop ended before the switch in the stack: the innermost
                // switch is the target (loop popped, switch still open).
                breakTarget = switchStack.peek();
                releaseTargetScope = ctx.currentScope();
            }
            if (breakTarget != null) {
                if (releaseTargetScope != null) {
                    ctx.releaseScopesUpTo(releaseTargetScope);
                }
                ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), breakTarget.name()));
            }
        } else if (stmt instanceof Ast.Stmt.Continue) {
            if (!ctx.loopStack.isEmpty()) {
                LoopContext loop = ctx.loopStack.peek();
                ctx.releaseScopesUpTo(loop.bodyScope);
                ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), loop.continueTarget.name()));
            }
        } else if (stmt instanceof Ast.Stmt.Return retStmt) {
            if (retStmt.getExpr() != null) {
                IrValue retVal = lowerExpr(retStmt.getExpr(), ctx);
                if (retVal.type().kind() != ctx.returnType.kind()) {
                    IrValue converted = ctx.newTemp(ctx.returnType);
                    ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(retVal), null));
                    retVal = converted;
                }
                if (isManaged(retVal.type()) || !getManagedPaths(retVal.type()).isEmpty()) {
                    if (retStmt.getExpr() instanceof Ast.Expr.Field || retStmt.getExpr() instanceof Ast.Expr.ArrayAccess) {
                        emitRetain(retVal, ctx);
                    }
                }
                ctx.releaseAllScopesExcept(retVal.name());
                ctx.emit(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(retVal), null));
            } else {
                ctx.releaseAllScopesExcept(null);
                if (ctx.isMain) {
                    IrValue zero = new IrValue("0", IrType.scalar(IrType.Kind.INT));
                    ctx.emit(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(zero), null));
                } else {
                    ctx.emit(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(), null));
                }
            }
        } else if (stmt instanceof Ast.Stmt.Printf printf) {
            String format = printf.getFormat() != null ? printf.getFormat() : "";
            IrValue fmtVal = new IrValue("\"" + escapeCString(format) + "\"", IrType.scalar(IrType.Kind.STRING));
            List<IrValue> callArgs = new ArrayList<>();
            callArgs.add(fmtVal);
            if (printf.getExprs() != null) {
                for (Ast.Expr.T expr : printf.getExprs()) {
                    callArgs.add(lowerExpr(expr, ctx));
                }
            }
            ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, callArgs, "printf"));
            ctx.cleanupStatementTemporaries();
        } else if (stmt instanceof Ast.Stmt.PrintLine) {
            IrValue nlVal = new IrValue("\"\\n\"", IrType.scalar(IrType.Kind.STRING));
            ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(nlVal), "printf"));
            ctx.cleanupStatementTemporaries();
        } else if (stmt instanceof Ast.Stmt.Call call) {
            List<IrValue> callArgs = new ArrayList<>();
            List<IrType> expectedParams = methodParamTypes.get(call.getName());
            if (call.getInputParams() != null) {
                for (int i = 0; i < call.getInputParams().size(); i++) {
                    IrValue argVal = lowerExpr(call.getInputParams().get(i), ctx);
                    if (expectedParams != null && i < expectedParams.size() && argVal.type().kind() != expectedParams.get(i).kind()) {
                        IrValue converted = ctx.newTemp(expectedParams.get(i));
                        ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(argVal), null));
                        argVal = converted;
                    }
                    callArgs.add(argVal);
                }
            }
            ctx.emit(new IrInstruction(IrInstruction.Op.CALL, null, callArgs, call.getName()));
            ctx.cleanupStatementTemporaries();
        } else if (stmt instanceof Ast.Stmt.ExprStmt exprStmt) {
            if (exprStmt.getExpr() != null) {
                lowerExpr(exprStmt.getExpr(), ctx);
            }
            ctx.cleanupStatementTemporaries();
        }
    }

    /**
     * Lowers {@code switch (subject) { case V: body ... default: body }} to a
     * comparison-dispatch CFG using only shared opcodes (CMP/COND_BRANCH/
     * BRANCH).
     *
     * <p>Layout order is fixed: every dispatch block first, then the default
     * body, then the remaining case bodies, then the exit block. Each dispatch
     * block compares the subject against one case constant and branches
     * ({@code COND_BRANCH}) to that case's body on match; on mismatch the
     * physical fall-through continues into the next dispatch block (or the
     * default body after the last dispatch). Case bodies end with an explicit
     * {@code BRANCH}: to the next case body on fallthrough, to
     * {@code switch_exit} on {@code break}, or to {@code switch_exit} when the
     * clause is last.</p>
     */
    private void lowerSwitch(Ast.Stmt.Switch switchStmt, MethodLoweringContext ctx) {
        LexicalScope switchScope = ctx.pushScope(ScopeKind.BLOCK);
        IrValue subjectVal = lowerExpr(switchStmt.getSubject(), ctx);

        BasicBlock exitBlock = ctx.createBlock("switch_exit");
        ArrayList<Ast.Stmt.CaseClause> clauses = switchStmt.getClauses() == null
                ? new ArrayList<>() : switchStmt.getClauses();
        if (clauses.isEmpty()) {
            ctx.startBlock(exitBlock);
            ctx.releaseScope(switchScope);
            ctx.popScope();
            return;
        }

        // Pre-create all blocks so dispatch/body/fallthrough can reference them.
        List<BasicBlock> bodyBlocks = new ArrayList<>();
        for (int i = 0; i < clauses.size(); i++) {
            bodyBlocks.add(ctx.createBlock(clauses.get(i).isDefault() ? "switch_default" : "switch_case"));
        }
        List<BasicBlock> dispatchBlocks = new ArrayList<>();
        for (int i = 0; i < clauses.size(); i++) {
            dispatchBlocks.add(clauses.get(i).isDefault() ? null : ctx.createBlock("switch_dispatch"));
        }

        // Emit dispatch blocks in layout order. The fall-through between them
        // is physical (the next block in the function's block list); only the
        // match edge is a COND_BRANCH.
        // Capture the subject block first: the dispatch loop reassigns
        // currentBlock, and the splice below must move the subject block.
        BasicBlock subjectBlock = ctx.currentBlock;
        for (int i = 0; i < clauses.size(); i++) {
            BasicBlock dispatchBlock = dispatchBlocks.get(i);
            if (dispatchBlock == null) {
                continue;
            }
            ctx.startBlock(dispatchBlock);
            IrValue caseVal = lowerExpr(clauses.get(i).getLabel(), ctx);
            // Comparison through the shared binary path: enum/byte/short
            // operands are promoted/converted exactly like an == expression.
            IrValue eq = lowerEqForSwitch(subjectVal, caseVal, ctx);
            ctx.emit(new IrInstruction(IrInstruction.Op.COND_BRANCH, null, List.of(eq), bodyBlocks.get(i).name()));
        }

        // Entry into the chain: the current block already holds the lowered
        // subject code; splice it before the dispatch blocks so the layout is
        // [entry+subject, dispatches..., default, bodies..., exit].
        BasicBlock entryTarget = dispatchBlocks.get(0) != null
                ? dispatchBlocks.get(0) : bodyBlocks.get(0);
        List<BasicBlock> blocks = ctx.blocks;
        // subjectBlock aliases subjectBlock; kept local for splice clarity.
        if (dispatchBlocks.get(0) != null) {
            // The fall-through from the subject block must reach the first
            // dispatch block; splice the subject block before it in the list.
            blocks.remove(subjectBlock);
            int insertAt = 0;
            for (int i = 0; i < blocks.size(); i++) {
                if (blocks.get(i) == dispatchBlocks.get(0)) {
                    insertAt = i;
                    break;
                }
            }
            blocks.add(insertAt, subjectBlock);
            if (!ctx.isTerminated(subjectBlock)) {
                // startBlock would add the branch to the (now-relocated)
                // subject block; emit directly to keep it at the block end.
                subjectBlock.add(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), entryTarget.name()));
            }
        } else if (!ctx.isTerminated(subjectBlock)) {
            // No dispatch: the default body follows physically; nothing to splice.
            ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), entryTarget.name()));
        }

        switchStack.push(exitBlock);
        // Emit case bodies in layout order: default first, then the rest.
        for (int i = 0; i < clauses.size(); i++) {
            if (dispatchBlocks.get(i) == null) {
                emitCaseBody(clauses.get(i), bodyBlocks.get(i), exitBlock, (i + 1 < clauses.size()) ? bodyBlocks.get(i + 1) : null, ctx);
            }
        }
        for (int i = 0; i < clauses.size(); i++) {
            if (dispatchBlocks.get(i) != null) {
                emitCaseBody(clauses.get(i), bodyBlocks.get(i), exitBlock, (i + 1 < clauses.size()) ? bodyBlocks.get(i + 1) : null, ctx);
            }
        }
        switchStack.pop();

        ctx.startBlock(exitBlock);
        ctx.releaseScope(switchScope);
        ctx.popScope();
    }

    /**
     * Lowers one case/default body: statements, then an explicit BRANCH —
     * to the next case body on fallthrough, or to the switch exit when the
     * clause is last or the body already terminated (break/return).
     */
    private void emitCaseBody(Ast.Stmt.CaseClause clause, BasicBlock bodyBlock, BasicBlock exitBlock,
                              BasicBlock nextBody, MethodLoweringContext ctx) {
        ctx.startBlock(bodyBlock);
        LexicalScope caseScope = ctx.pushScope(ScopeKind.BLOCK);
        if (clause.getBody() != null) {
            for (Ast.Stmt.T s : clause.getBody()) {
                lowerStmt(s, ctx);
            }
        }
        ctx.releaseScope(caseScope);
        ctx.popScope();
        if (!ctx.isTerminated(ctx.currentBlock)) {
            BasicBlock next = nextBody != null ? nextBody : exitBlock;
            ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), next.name()));
        }
    }

    /**
     * Equality comparison for switch dispatch, reusing the shared binary path
     * ({@code applyBinaryOp}) so enum/byte/short operands are promoted and
     * converted exactly like a hand-written {@code ==} expression. This keeps
     * the C backend free of signedness mismatches (e.g. {@code int32_t} vs
     * {@code LemonC_Op}) and the JVM backend identical.
     */
    private IrValue lowerEqForSwitch(IrValue left, IrValue right, MethodLoweringContext ctx) {
        IrType leftType = left.type();
        IrType rightType = right.type();
        boolean leftEnum = leftType.kind() == IrType.Kind.ENUM;
        boolean rightEnum = rightType.kind() == IrType.Kind.ENUM;
        if (leftEnum != rightEnum) {
            // Mixed enum/int: convert the enum side to INT, matching the
            // conversion an explicit == expression would emit.
            if (leftEnum) {
                IrValue converted = ctx.newTemp(IrType.scalar(IrType.Kind.INT));
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(left), null));
                left = converted;
            } else {
                IrValue converted = ctx.newTemp(IrType.scalar(IrType.Kind.INT));
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(right), null));
                right = converted;
            }
        }
        return applyBinaryOp(IrInstruction.Op.CMP, left, right, ctx);
    }

    // ==================================================== struct lowering

    /**
     * Lowers a field-read chain to a single FIELD_LOAD op whose {@code target}
     * carries the full dotted path ({@code inner.first.x}). The single
     * operand is the root value: the struct variable itself, or the struct*
     * variable when the chain starts with {@code ->}. Keeping the whole path
     * in one op lets the C backend emit plain {@code root.a.b} lvalues while
     * the JVM backend navigates one reference chain without intermediate
     * copies ÃƒÆ’Ã†â€™Ãƒâ€šÃ‚Â¢ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â€šÂ¬Ã…Â¡Ãƒâ€šÃ‚Â¬ÃƒÆ’Ã‚Â¢ÃƒÂ¢Ã¢â‚¬Å¡Ã‚Â¬Ãƒâ€šÃ‚Â both backends stay field-address based.
     */
    private IrValue lowerFieldLoad(Ast.Expr.Field field, MethodLoweringContext ctx) {
        Ast.Expr.T receiverExpr = field.getReceiver();
        if (receiverExpr instanceof Ast.Expr.Id id) {
            IrModule.IrEnum irEnum = module.irEnum(id.getId());
            if (irEnum != null && field.getPath().size() == 1) {
                String memberName = field.getPath().get(0);
                IrModule.IrEnumMember member = irEnum.member(memberName);
                if (member != null) {
                    IrType eType = IrType.enumType(irEnum.name());
                    IrValue res = ctx.newTemp(eType);
                    ctx.emit(new IrInstruction(IrInstruction.Op.CONST, res,
                            List.of(new IrValue(String.valueOf(member.value()), eType)), null));
                    return res;
                }
            }
        }
        if (!(receiverExpr instanceof Ast.Expr.Id id)) {
            throw new CompilerException("field access requires a named struct receiver");
        }
        IrType rootType = ctx.variableTypes.get(id.getId());
        if (rootType == null) {
            Ast.ConstDecl constant = findConst(id.getId(), ctx);
            if (constant != null) {
                rootType = toIrType(constant.getType());
            }
        }
        if (rootType == null) rootType = IrType.scalar(IrType.Kind.INT);
        // When the variable is an array (arr[i].field), load the element from
        // the array first, then access the field on the element.
        IrType elementRootType = getArrayElementType(id.getId(), ctx);
        if (elementRootType != null) {
            IrValue idxVal = new IrValue("0", IrType.scalar(IrType.Kind.INT));
            IrValue elemVal = ctx.newTemp(elementRootType);
            ctx.emit(new IrInstruction(IrInstruction.Op.BOUNDS_CHECK, null,
                    List.of(new IrValue(id.getId(), rootType), idxVal), null));
            ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, elemVal,
                    List.of(new IrValue(id.getId(), rootType), idxVal), null));
            IrType resultType = fieldPathType(elementRootType, field.getPath(), false);
            return lowerFieldLoadAtPath(elemVal, elementRootType, field.getPath(), ctx);
        }
        boolean throughPointer = field.isPointerBase() && isPointerKind(rootType);
        IrType resultType = fieldPathType(rootType, field.getPath(), throughPointer);
        IrValue loaded = ctx.newTemp(resultType);
        boolean isSafe = throughPointer && ctx.nullFlow != null && ctx.nullFlow.isSafe(field);
        ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_LOAD, loaded,
                List.of(new IrValue(id.getId(), rootType)), String.join(".", field.getPath()), isSafe));
        return loaded;
    }

    /**
     * Lowers a field access chain on an already-loaded value (e.g. arr[i].x where
     * the array element has already been loaded into {@code base}).
     * If {@code baseType} is a POINTER, the pointer is dereferenced once before
     * navigating the field path — this handles cases like c->items[0].field where
     * the loaded element is itself a struct* cell reference.
     */
    private IrValue lowerFieldLoadAtPath(IrValue base, IrType baseType, java.util.List<String> path,
                                         MethodLoweringContext ctx) {
        IrType currentType = baseType;
        IrValue current = base;
        for (String fieldName : path) {
            if (currentType.kind() == IrType.Kind.POINTER) {
                // Dereference the pointer to get the struct value.
                IrType pointee = currentType.elementType();
                IrValue deref = ctx.newTemp(pointee);
                ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, deref, List.of(current), null));
                current = deref;
                currentType = pointee;
            }
            IrType fieldType = structFieldType(currentType, fieldName);
            IrValue loaded = ctx.newTemp(fieldType);
            ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_LOAD, loaded, List.of(current), fieldName));
            current = loaded;
            currentType = fieldType;
        }
        return current;
    }

    /** Result type of a field path, resolving each link through struct layouts. */
    private IrType fieldPathType(IrType rootType, List<String> path, boolean throughPointer) {
        IrType current = throughPointer ? rootType.elementType() : rootType;
        for (String fieldName : path) {
            current = structFieldType(current, fieldName);
        }
        return current;
    }

    /**
     * Lowers arr[i].field = expr: loads the array element at index i into a temp,
     * then returns that temp as the storage base for the subsequent FIELD_STORE.
     * The caller is responsible for emitting BOUNDS_CHECK with the actual index.
     * For struct-to-array assignments (e.g. myPath.points[i] = expr), the receiver
     * is a struct variable and the path leads to an array field; in that case we
     * navigate the struct fields via FIELD_LOAD ops first, then load the array element.
     */
    private IrValue lowerArrayFieldLoad(Ast.Expr.Field field, Ast.Expr.T indexExpr, MethodLoweringContext ctx) {
        Ast.Expr.T receiverExpr = field.getReceiver();
        if (!(receiverExpr instanceof Ast.Expr.Id id)) {
            throw new CompilerException("field access requires a named struct receiver");
        }
        IrType rootType = ctx.variableTypes.get(id.getId());
        if (rootType == null) {
            Ast.ConstDecl constant = findConst(id.getId(), ctx);
            if (constant != null) {
                rootType = toIrType(constant.getType());
            }
        }
        if (rootType == null) rootType = IrType.scalar(IrType.Kind.INT);
        IrType elementRootType = getArrayElementType(id.getId(), ctx);
        if (elementRootType != null) {
            // Direct array access: arr[i] or arr[i].field
            IrValue idxVal = lowerExpr(indexExpr, ctx);
            IrValue elemVal = ctx.newTemp(elementRootType);
            ctx.emit(new IrInstruction(IrInstruction.Op.BOUNDS_CHECK, null,
                    List.of(new IrValue(id.getId(), rootType), idxVal), null));
            ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, elemVal,
                    List.of(new IrValue(id.getId(), rootType), idxVal), null));
            if (field.getPath() != null && !field.getPath().isEmpty()) {
                // arr[i].field — navigate fields on the loaded element.
                IrType resultType = fieldPathType(elementRootType, field.getPath(), false);
                return lowerFieldLoadAtPath(elemVal, elementRootType, field.getPath(), ctx);
            }
            return elemVal;
        }
        // Struct-to-array access: myPath.points[i] — navigate struct fields to reach
        // the array field, then load the element at the given index.
        if (field.getPath() == null || field.getPath().isEmpty()) {
            throw new CompilerException("array field access requires a path to array field: " + id.getId());
        }
        // Navigate struct fields to get the array base.
        IrValue arrayBase = lowerFieldLoadAtPath(new IrValue(id.getId(), rootType),
                rootType, field.getPath(), ctx);
        // Determine the array type and element type from the struct definition.
        IrType arrayType = null;
        IrType elemType = null;
        IrType curType = rootType;
        // If rootType is a pointer (c->items[i]), dereference once so we can
        // navigate the struct fields on the pointee type.
        if (curType.kind() == IrType.Kind.POINTER) {
            curType = curType.elementType();
        }
        for (int i = 0; i < field.getPath().size(); i++) {
            String name = field.getPath().get(i);
            IrType next = structFieldType(curType, name);
            if (i == field.getPath().size() - 1) {
                arrayType = next;
                elemType = next.elementType();
                break;
            }
            curType = next;
        }
        if (elemType == null) {
            throw new CompilerException("array field access requires array-typed variable: " + id.getId());
        }
        // For struct-to-array assignments, return the array base so the caller
        // (ArrayAssign handler) can perform BOUNDS_CHECK and STORE with the
        // correct array reference. The caller will also emit the element LOAD
        // if the expression reads from this array access.
        return arrayBase;
    }

    /** Field type of {@code fieldName} inside a struct-typed value. */
    private IrType structFieldType(IrType structType, String fieldName) {
        if (structType.kind() != IrType.Kind.STRUCT) {
            throw new CompilerException("field access on non-struct type " + structType.kind());
        }
        IrModule.IrStruct struct = module.struct(structType.name());
        if (struct == null) {
            throw new CompilerException("unknown struct in IR: " + structType.name());
        }
        for (IrModule.IrStructField field : struct.fields()) {
            if (field.name().equals(fieldName)) {
                return field.type();
            }
        }
        throw new CompilerException("struct " + structType.name() + " has no field " + fieldName);
    }

    /**
     * Lowers {@code root.path.field = v} to a single FIELD_STORE op. The first
     * operand is the root (struct variable, or the struct* variable when the
     * chain starts with {@code ->}), the second is the converted value, and
     * {@code target} carries the full dotted field path. Both backends write
     * the field directly in the storage the root designates.
     */
    private void lowerFieldStore(Ast.Expr.Field target, IrValue value, MethodLoweringContext ctx) {
        lowerFieldStore(target, value, false, ctx);
    }

    private void lowerFieldStore(Ast.Expr.Field target, IrValue value, boolean isSafe, MethodLoweringContext ctx) {
        Ast.Expr.T receiverExpr = target.getReceiver();
        if (!(receiverExpr instanceof Ast.Expr.Id id)) {
            throw new CompilerException("field store requires a named struct root");
        }
        IrType rootType = ctx.variableTypes.get(id.getId());
        if (rootType == null) rootType = IrType.scalar(IrType.Kind.INT);
        // When the variable is an array (arr[i].field), we need an array LOAD
        // as the storage root for FIELD_STORE, then navigate the field path.
        IrType elementRootType = getArrayElementType(id.getId(), ctx);
        if (elementRootType != null) {
            // Caller is responsible for BOUNDS_CHECK and LOAD; just return the element temp.
            IrValue elemVal = ctx.newTemp(elementRootType);
            IrType fieldType = fieldPathType(elementRootType, target.getPath(), false);
            IrValue stored = value;
            if (stored.type().kind() != fieldType.kind()) {
                IrValue converted = ctx.newTemp(fieldType);
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(stored), null));
                stored = converted;
            }
            ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_STORE, null,
                    List.of(elemVal, stored), String.join(".", target.getPath()), isSafe));
            return;
        }
        boolean throughPointer = target.isPointerBase() && isPointerKind(rootType);
        IrType fieldType = fieldPathType(rootType, target.getPath(), throughPointer);
        IrValue stored = value;
        // For struct-to-array assignments (b.data[0] = 11), the field path leads
        // to an array type (int[]). No type conversion is needed — the int value
        // is stored directly into the array field. Skip CONVERT for array types.
        if (fieldType.kind() != IrType.Kind.ARRAY
                && (stored.type().kind() != fieldType.kind()
                || (stored.type().kind() == IrType.Kind.STRUCT && !stored.type().name().equals(fieldType.name())))) {
            IrValue converted = ctx.newTemp(fieldType);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(stored), null));
            stored = converted;
        }
        if (isManaged(fieldType)) {
            IrValue oldField = ctx.newTemp(fieldType);
            ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_LOAD, oldField,
                    List.of(new IrValue(id.getId(), rootType)), String.join(".", target.getPath()), isSafe));
            emitRetain(stored, ctx);
            ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(oldField), "lemon_release"));
        } else if (fieldType.kind() == IrType.Kind.STRUCT && !getManagedPaths(fieldType).isEmpty()) {
            if (!stored.name().startsWith("_t")) {
                emitRetain(stored, ctx);
            }
            List<String> subPaths = getManagedPaths(fieldType);
            for (String sp : subPaths) {
                String fullPath = String.join(".", target.getPath()) + "." + sp;
                IrType spType = resolveFieldType(fieldType, sp);
                IrValue oldSub = ctx.newTemp(spType);
                ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_LOAD, oldSub,
                        List.of(new IrValue(id.getId(), rootType)), fullPath));
                ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(oldSub), "lemon_release"));
            }
        }
        ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_STORE, null,
                List.of(new IrValue(id.getId(), rootType), stored),
                String.join(".", target.getPath()), isSafe));
    }

    /**
     * Lowers {@code *p = v}, {@code **pp = v}, ... by walking the dereference
     * chain down to the storage cell that receives {@code v}. Every pointer
     * dereference carries an explicit runtime null check, so a null pointer
     * dereference is a defined runtime error on both backends.
     */
    private void lowerDerefAssign(Ast.Stmt.DerefAssign statement, MethodLoweringContext ctx) {
        Ast.Expr.Deref target = statement.getTarget();
        int depth = 0;
        Ast.Expr.T base = target;
        while (base instanceof Ast.Expr.Deref deref) {
            depth++;
            base = deref.getOperand();
        }

        // Resolve the address chain down to the level-1 storage (n-1 derefs).
        IrValue address = lowerExpr(base, ctx);
        for (int i = 1; i < depth; i++) {
            IrValue next = ctx.newTemp(address.type().elementType());
            ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, next, List.of(address), null));
            address = next;
        }

        IrType elemType = address.type().elementType();
        IrValue value = lowerExpr(statement.getExpr(), ctx);
        IrInstruction.Op binOp = compoundAssignOp(statement.getOp());
        boolean isSafe = ctx.nullFlow != null && ctx.nullFlow.isSafe(statement);
        if (binOp != null) {
            IrValue oldVal = ctx.newTemp(elemType);
            ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, oldVal, List.of(address), null, isSafe));
            value = applyBinaryOp(binOp, oldVal, value, ctx);
        }
        if (value.type().kind() != elemType.kind()) {
            IrValue converted = ctx.newTemp(elemType);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(value), null));
            value = converted;
        }
        ctx.emit(new IrInstruction(IrInstruction.Op.STORE, null, List.of(address, value), null, isSafe));
    }

    private IrValue lowerExpr(Ast.Expr.T expr, MethodLoweringContext ctx) {
        if (expr instanceof Ast.Expr.Number num) {
            IrType t = toIrType(num.getType());
            IrValue res = ctx.newTemp(t);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONST, res, List.of(new IrValue(String.valueOf(num.getValue()), t)), null));
            return res;
        } else if (expr instanceof Ast.Expr.True) {
            IrType t = IrType.scalar(IrType.Kind.BOOL);
            IrValue res = ctx.newTemp(t);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONST, res, List.of(new IrValue("true", t)), null));
            return res;
        } else if (expr instanceof Ast.Expr.False) {
            IrType t = IrType.scalar(IrType.Kind.BOOL);
            IrValue res = ctx.newTemp(t);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONST, res, List.of(new IrValue("false", t)), null));
            return res;
        } else if (expr instanceof Ast.Expr.Str str) {
            IrType t = IrType.scalar(IrType.Kind.STRING);
            IrValue res = ctx.newTemp(t);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONST, res, List.of(new IrValue("\"" + escapeCString(str.getValue()) + "\"", t)), null));
            return res;
        } else if (expr instanceof Ast.Expr.Id id) {
            IrType t = ctx.variableTypes.get(id.getId());
            if (t != null) {
                return new IrValue(id.getId(), t);
            }
            Ast.ConstDecl constant = findConst(id.getId(), ctx);
            if (constant != null && constant.getResolvedValue() != null) {
                // Read of a global constant: materialize its value. The CONST
                // operand carries the constant name; backends resolve it through
                // the module's constant table (C emits an identifier reference,
                // JVM inlines the value).
                IrType cType = toIrType(constant.getType());
                IrValue res = ctx.newTemp(cType);
                ctx.emit(new IrInstruction(IrInstruction.Op.CONST, res,
                        List.of(new IrValue(constant.getId(), cType)), null));
                return res;
            }
            IrEnumMemberInfo enumMember = findEnumMember(id.getId());
            if (enumMember != null) {
                IrType eType = IrType.enumType(enumMember.enumName);
                IrValue res = ctx.newTemp(eType);
                ctx.emit(new IrInstruction(IrInstruction.Op.CONST, res,
                        List.of(new IrValue(String.valueOf(enumMember.value), eType)), null));
                return res;
            }
            t = IrType.scalar(IrType.Kind.INT);
            return new IrValue(id.getId(), t);
        } else if (expr instanceof Ast.Expr.Add add) {
            return lowerBinary(IrInstruction.Op.ADD, add.getLeft(), add.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.Sub sub) {
            return lowerBinary(IrInstruction.Op.SUB, sub.getLeft(), sub.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.Mul mul) {
            return lowerBinary(IrInstruction.Op.MUL, mul.getLeft(), mul.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.Div div) {
            return lowerBinary(IrInstruction.Op.DIV, div.getLeft(), div.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.Mod mod) {
            return lowerBinary(IrInstruction.Op.REM, mod.getLeft(), mod.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.And and) {
            return lowerBooleanOperator(true, and.getLeft(), and.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.Or or) {
            return lowerBooleanOperator(false, or.getLeft(), or.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.Not not) {
            IrValue opVal = lowerExpr(not.getExpr(), ctx);
            IrValue res = ctx.newTemp(IrType.scalar(IrType.Kind.BOOL));
            IrValue zero = new IrValue("0", opVal.type());
            ctx.emit(new IrInstruction(IrInstruction.Op.CMP, res, List.of(opVal, zero), "=="));
            return res;
        } else if (expr instanceof Ast.Expr.GT gt) {
            return lowerCmp(">", gt.getLeft(), gt.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.LT lt) {
            return lowerCmp("<", lt.getLeft(), lt.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.GTE gte) {
            return lowerCmp(">=", gte.getLeft(), gte.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.LTE lte) {
            return lowerCmp("<=", lte.getLeft(), lte.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.EQ eq) {
            return lowerCmp("==", eq.getLeft(), eq.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.NEQ neq) {
            return lowerCmp("!=", neq.getLeft(), neq.getRight(), ctx);
        } else if (expr instanceof Ast.Expr.AddressOf addressOf) {
            if (!(addressOf.getOperand() instanceof Ast.Expr.Id id)) {
                throw new IllegalArgumentException("address-of operand is not a variable");
            }
            IrType targetType = ctx.variableTypes.get(id.getId());
            if (targetType == null) targetType = IrType.scalar(IrType.Kind.INT);
            IrValue res = ctx.newTemp(IrType.pointer(targetType, 0));
            ctx.emit(new IrInstruction(IrInstruction.Op.ADDRESS_OF, res, List.of(new IrValue(id.getId(), targetType)), null));
            return res;
        } else if (expr instanceof Ast.Expr.Deref deref) {
            IrValue pointer = lowerExpr(deref.getOperand(), ctx);
            IrType pointee = pointer.type().elementType() != null
                    ? pointer.type().elementType()
                    : IrType.scalar(IrType.Kind.INT);
            IrValue res = ctx.newTemp(pointee);
            boolean isSafe = ctx.nullFlow != null && ctx.nullFlow.isSafe(deref);
            ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, res, List.of(pointer), null, isSafe));
            return res;
        } else if (expr instanceof Ast.Expr.Null nullExpr) {
            IrType nullType = IrType.pointer(IrType.scalar(IrType.Kind.VOID), 0);
            IrValue res = ctx.newTemp(nullType);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONST, res, List.of(new IrValue("null", nullType)), null));
            return res;
        } else if (expr instanceof Ast.Expr.Field field) {
            return lowerFieldLoad(field, ctx);
        } else if (expr instanceof Ast.Expr.InitializerList initList) {
            IrType type = toIrType(initList.getType());
            IrValue temp = ctx.newTemp(type);
            ctx.emit(new IrInstruction(IrInstruction.Op.STRUCT_ZERO, temp, List.of(), null));
            IrModule.IrStruct structDef = module.struct(type.name());
            if (structDef != null) {
                for (int i = 0; i < initList.getElements().size() && i < structDef.fields().size(); i++) {
                    IrModule.IrStructField f = structDef.fields().get(i);
                    IrValue val = lowerExpr(initList.getElements().get(i), ctx);
                    ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_STORE, null, List.of(temp, val), f.name()));
                }
            }
            return temp;
        } else if (expr instanceof Ast.Expr.ArrayAccess access) {
            IrValue arrVal;
            IrType arrType;
            if (access.getFieldTarget() != null) {
                arrVal = lowerFieldLoad(access.getFieldTarget(), ctx);
                arrType = arrVal.type();
            } else {
                String arrName = access.getArrayName();
                arrType = ctx.variableTypes.get(arrName);
                arrVal = new IrValue(arrName, arrType);
            }
            IrValue idxVal = lowerExpr(access.getIndex(), ctx);
            
            // Distinguish between array types and pointer types
            if (arrType != null && arrType.kind() == IrType.Kind.POINTER) {
                // Pointer dereference: *(ptr + idx)
                IrType elemType = arrType.elementType() != null ? arrType.elementType() : IrType.scalar(IrType.Kind.INT);
                IrValue res = ctx.newTemp(elemType);
                // For pointer + index, we need to compute the address first
                // But in our current IR, we can use LOAD with 2 operands for pointer+offset
                ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, res, List.of(arrVal, idxVal), null));
                return res;
            }
            
            // Regular array access with bounds checking
            ctx.emit(new IrInstruction(IrInstruction.Op.BOUNDS_CHECK, null, List.of(arrVal, idxVal), null));
            IrType elemType = arrType != null && arrType.elementType() != null ? arrType.elementType() : IrType.scalar(IrType.Kind.INT);
            IrValue res = ctx.newTemp(elemType);
            ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, res, List.of(arrVal, idxVal), null));
            // Handle field path on array element: arr[i].field
            if (access.getFieldPath() != null && !access.getFieldPath().isEmpty()) {
                return lowerFieldLoadAtPath(res, elemType, access.getFieldPath(), ctx);
            }
            return res;
        } else if (expr instanceof Ast.Expr.ArrayLength arrayLen) {
            IrValue arrVal;
            IrType arrType;
            // Resolve the receiver expression to find the array value.
            // For simple Id receivers we can read from variableTypes directly.
            // For ArrayAccess/Field receivers we lower the sub-expression.
            if (arrayLen.getReceiver() instanceof Ast.Expr.Id idExpr) {
                String arrName = idExpr.getId();
                arrType = ctx.variableTypes.get(arrName);
                arrVal = new IrValue(arrName, arrType);
            } else if (arrayLen.getReceiver() instanceof Ast.Expr.ArrayAccess aa) {
                // For nested array access like matrix[0], lower the inner array access
                // to get the element type (which is itself an array for multi-dim).
                IrValue elemVal = lowerExpr(aa, ctx);
                arrType = elemVal.type();
                arrVal = elemVal;
            } else if (arrayLen.getReceiver() instanceof Ast.Expr.Field field) {
                // For struct-field-to-array access: myPath.points.length
                IrValue arrayBase = lowerFieldLoad(field, ctx);
                arrType = arrayBase.type();
                arrVal = arrayBase;
            } else {
                // Fallback: lower the receiver expression and use its type.
                IrValue recvVal = lowerExpr(arrayLen.getReceiver(), ctx);
                arrType = recvVal.type();
                arrVal = recvVal;
            }
            IrValue res = ctx.newTemp(IrType.scalar(IrType.Kind.INT));
            
            // ArrayLength on pointer types is not supported - pointers don't have length
            if (arrType != null && arrType.kind() == IrType.Kind.POINTER) {
                String arrName = arrayLen.getReceiver() instanceof Ast.Expr.Id idExpr ? idExpr.getId() : "expression";
                throw new CompilerException("cannot get length of pointer type: " + arrName);
            }
            
            ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, res, List.of(arrVal), "length"));
            return res;
        } else if (expr instanceof Ast.Expr.Call call) {
            List<IrValue> callArgs = new ArrayList<>();
            List<IrType> expectedParams = methodParamTypes.get(call.getName());
            if (call.getInputParams() != null) {
                for (int i = 0; i < call.getInputParams().size(); i++) {
                    IrValue argVal = lowerExpr(call.getInputParams().get(i), ctx);
                    if (expectedParams != null && i < expectedParams.size() && argVal.type().kind() != expectedParams.get(i).kind()) {
                        IrValue converted = ctx.newTemp(expectedParams.get(i));
                        ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(argVal), null));
                        argVal = converted;
                    }
                    callArgs.add(argVal);
                }
            }
            IrType retType = methodReturnTypes.get(call.getName());
            if (retType == null) retType = IrType.scalar(IrType.Kind.INT);
            IrValue res = ctx.newTemp(retType);
            ctx.emit(new IrInstruction(IrInstruction.Op.CALL, res, callArgs, call.getName()));
            return res;
        } else if (expr instanceof Ast.Expr.UnaryMinus unaryMinus) {
            IrValue val = lowerExpr(unaryMinus.getExp(), ctx);
            IrValue zero = ctx.newTemp(val.type());
            ctx.emit(new IrInstruction(IrInstruction.Op.CONST, zero, List.of(new IrValue("0", val.type())), null));
            return applyBinaryOp(IrInstruction.Op.SUB, zero, val, ctx);
        } else if (expr instanceof Ast.Expr.UnaryPlus unaryPlus) {
            return lowerExpr(unaryPlus.getExp(), ctx);
        } else if (expr instanceof Ast.Expr.BitNot bitNot) {
            IrValue val = lowerExpr(bitNot.getExp(), ctx);
            IrValue res = ctx.newTemp(val.type());
            ctx.emit(new IrInstruction(IrInstruction.Op.BIT_NOT, res, List.of(val), null));
            return res;
        } else if (expr instanceof Ast.Expr.Ternary ternary) {
            IrValue condVal = lowerExpr(ternary.getCondition(), ctx);
            BasicBlock trueBlock = ctx.createBlock("ternary_true");
            BasicBlock falseBlock = ctx.createBlock("ternary_false");
            BasicBlock endBlock = ctx.createBlock("ternary_end");
            
            IrValue notCond = ctx.newTemp(IrType.scalar(IrType.Kind.BOOL));
            ctx.emit(new IrInstruction(IrInstruction.Op.CMP, notCond, List.of(condVal, new IrValue("0", condVal.type())), "=="));
            ctx.emit(new IrInstruction(IrInstruction.Op.COND_BRANCH, null, List.of(notCond), falseBlock.name()));

            ctx.startBlock(trueBlock);
            IrValue trueVal = lowerExpr(ternary.getTrueExpr(), ctx);
            BasicBlock trueEndBlock = ctx.currentBlock;
            
            ctx.startBlock(falseBlock);
            IrValue falseVal = lowerExpr(ternary.getFalseExpr(), ctx);
            BasicBlock falseEndBlock = ctx.currentBlock;
            
            IrType resType = commonNumericType(trueVal.type(), falseVal.type());
            IrValue res = ctx.newTemp(resType);
            
            ctx.currentBlock = trueEndBlock;
            if (trueVal.type().kind() != resType.kind()) {
                IrValue conv = ctx.newTemp(resType);
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, conv, List.of(trueVal), null));
                trueVal = conv;
            }
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, res, List.of(trueVal), null));
            ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), endBlock.name()));
            
            ctx.currentBlock = falseEndBlock;
            if (falseVal.type().kind() != resType.kind()) {
                IrValue conv = ctx.newTemp(resType);
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, conv, List.of(falseVal), null));
                falseVal = conv;
            }
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, res, List.of(falseVal), null));
            ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), endBlock.name()));

            ctx.startBlock(endBlock);
            return res;
        } else if (expr instanceof Ast.Expr.PreInc preInc) {
            return lowerIncDec(preInc.getExp(), false, false, ctx);
        } else if (expr instanceof Ast.Expr.PostInc postInc) {
            return lowerIncDec(postInc.getExp(), true, false, ctx);
        } else if (expr instanceof Ast.Expr.PreDec preDec) {
            return lowerIncDec(preDec.getExp(), false, true, ctx);
        } else if (expr instanceof Ast.Expr.PostDec postDec) {
            return lowerIncDec(postDec.getExp(), true, true, ctx);
        }
        throw new IllegalArgumentException("unsupported expr: " + expr.getClass().getSimpleName());
    }

    private IrValue lowerBinary(IrInstruction.Op op, Ast.Expr.T leftExpr, Ast.Expr.T rightExpr, MethodLoweringContext ctx) {
        IrValue left = lowerExpr(leftExpr, ctx);
        IrValue right = lowerExpr(rightExpr, ctx);
        return applyBinaryOp(op, left, right, ctx);
    }

    private IrValue applyBinaryOp(IrInstruction.Op op, IrValue left, IrValue right, MethodLoweringContext ctx) {
        IrType commonType = commonNumericType(left.type(), right.type());

        if (left.type().kind() != commonType.kind()) {
            IrValue converted = ctx.newTemp(commonType);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(left), null));
            left = converted;
        }
        if (right.type().kind() != commonType.kind()) {
            IrValue converted = ctx.newTemp(commonType);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(right), null));
            right = converted;
        }        
        IrValue res = ctx.newTemp(commonType);
        ctx.emit(new IrInstruction(op, res, List.of(left, right), null));
        return res;
    }

    /**
     * Lowers {@code &&}/{@code ||}. When an operand may have side effects (a
     * call), evaluation short-circuits through blocks so the right operand runs
     * only when needed (the language's original semantics). Purely computed
     * operators stay as flat AND/OR instructions, keeping their output shape
     * unchanged for both backends.
     */
    private IrValue lowerBooleanOperator(boolean isAnd, Ast.Expr.T leftExpr, Ast.Expr.T rightExpr,
                                         MethodLoweringContext ctx) {
        if (!exprMustShortCircuit(leftExpr) && !exprMustShortCircuit(rightExpr)) {
            IrValue left = lowerExpr(leftExpr, ctx);
            IrValue right = lowerExpr(rightExpr, ctx);
            IrValue res = ctx.newTemp(IrType.scalar(IrType.Kind.BOOL));
            ctx.emit(new IrInstruction(isAnd ? IrInstruction.Op.AND : IrInstruction.Op.OR,
                    res, List.of(left, right), null));
            return res;
        }

        IrType boolType = IrType.scalar(IrType.Kind.BOOL);
        IrValue res = ctx.newTemp(boolType);

        // Evaluate the left operand, then branch away when it decides the result.
        IrValue left = lowerExpr(leftExpr, ctx);
        IrValue decision = ctx.newTemp(boolType);
        String decisionSymbol = isAnd ? "==" : "!=";
        ctx.emit(new IrInstruction(IrInstruction.Op.CMP, decision,
                List.of(left, new IrValue("0", left.type())), decisionSymbol));
        // AND: !left -> result is false; OR: left -> result is true.
        BasicBlock rhsBlock = ctx.createBlock(isAnd ? "and_rhs" : "or_rhs");
        BasicBlock skipBlock = ctx.createBlock(isAnd ? "and_skip" : "or_skip");
        BasicBlock endBlock = ctx.createBlock(isAnd ? "and_end" : "or_end");
        ctx.emit(new IrInstruction(IrInstruction.Op.COND_BRANCH, null, List.of(decision), skipBlock.name()));

        // Right operand runs when the left operand did not decide the result.
        ctx.startBlock(rhsBlock);
        IrValue right = lowerExpr(rightExpr, ctx);
        ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, res, List.of(right), null));
        ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), endBlock.name()));

        // Skip path materializes the decided constant.
        ctx.startBlock(skipBlock);
        ctx.emit(new IrInstruction(IrInstruction.Op.CONST, res,
                List.of(new IrValue(isAnd ? "false" : "true", boolType)), null));
        ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), endBlock.name()));

        ctx.startBlock(endBlock);
        return res;
    }

    /** True when evaluating the expression has side-effects, potential faults (null deref, div-by-zero, bounds), or calls. */
    private boolean exprMustShortCircuit(Ast.Expr.T expr) {
        if (expr == null) return false;
        if (expr instanceof Ast.Expr.Call
                || expr instanceof Ast.Expr.Deref
                || expr instanceof Ast.Expr.ArrayAccess
                || expr instanceof Ast.Expr.Div
                || expr instanceof Ast.Expr.Mod
                || expr instanceof Ast.Expr.PreInc
                || expr instanceof Ast.Expr.PostInc
                || expr instanceof Ast.Expr.PreDec
                || expr instanceof Ast.Expr.PostDec
                || expr instanceof Ast.Expr.Ternary) {
            return true;
        }
        if (expr instanceof Ast.Expr.Field f && f.isPointerBase()) {
            return true;
        }
        if (expr instanceof Ast.Expr.Add a) return exprMustShortCircuit(a.getLeft()) || exprMustShortCircuit(a.getRight());
        if (expr instanceof Ast.Expr.Sub s) return exprMustShortCircuit(s.getLeft()) || exprMustShortCircuit(s.getRight());
        if (expr instanceof Ast.Expr.Mul m) return exprMustShortCircuit(m.getLeft()) || exprMustShortCircuit(m.getRight());
        if (expr instanceof Ast.Expr.And an) return exprMustShortCircuit(an.getLeft()) || exprMustShortCircuit(an.getRight());
        if (expr instanceof Ast.Expr.Or or) return exprMustShortCircuit(or.getLeft()) || exprMustShortCircuit(or.getRight());
        if (expr instanceof Ast.Expr.Not n) return exprMustShortCircuit(n.getExpr());
        if (expr instanceof Ast.Expr.GT gt) return exprMustShortCircuit(gt.getLeft()) || exprMustShortCircuit(gt.getRight());
        if (expr instanceof Ast.Expr.LT lt) return exprMustShortCircuit(lt.getLeft()) || exprMustShortCircuit(lt.getRight());
        if (expr instanceof Ast.Expr.GTE ge) return exprMustShortCircuit(ge.getLeft()) || exprMustShortCircuit(ge.getRight());
        if (expr instanceof Ast.Expr.LTE le) return exprMustShortCircuit(le.getLeft()) || exprMustShortCircuit(le.getRight());
        if (expr instanceof Ast.Expr.EQ eq) return exprMustShortCircuit(eq.getLeft()) || exprMustShortCircuit(eq.getRight());
        if (expr instanceof Ast.Expr.NEQ ne) return exprMustShortCircuit(ne.getLeft()) || exprMustShortCircuit(ne.getRight());
        return false;
    }


    private boolean isPointerKind(IrType type) {
        return type != null && type.kind() == IrType.Kind.POINTER;
    }

    private IrValue lowerCmp(String symbol, Ast.Expr.T leftExpr, Ast.Expr.T rightExpr, MethodLoweringContext ctx) {
        IrValue left = lowerExpr(leftExpr, ctx);
        IrValue right = lowerExpr(rightExpr, ctx);
        // Pointer equality/inequality compares the pointer values directly;
        // null is a typed pointer constant, so no numeric conversion applies.
        if (isPointerKind(left.type()) || isPointerKind(right.type())) {
            IrValue res = ctx.newTemp(IrType.scalar(IrType.Kind.BOOL));
            ctx.emit(new IrInstruction(IrInstruction.Op.CMP, res, List.of(left, right), symbol));
            return res;
        }
        IrType commonType = commonNumericType(left.type(), right.type());

        if (left.type().kind() != commonType.kind()) {
            IrValue converted = ctx.newTemp(commonType);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(left), null));
            left = converted;
        }
        if (right.type().kind() != commonType.kind()) {
            IrValue converted = ctx.newTemp(commonType);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(right), null));
            right = converted;
        }

        IrValue res = ctx.newTemp(IrType.scalar(IrType.Kind.BOOL));
        ctx.emit(new IrInstruction(IrInstruction.Op.CMP, res, List.of(left, right), symbol));
        return res;
    }

    private IrType commonNumericType(IrType t1, IrType t2) {
        if (t1.kind() == IrType.Kind.DOUBLE || t2.kind() == IrType.Kind.DOUBLE) {
            return IrType.scalar(IrType.Kind.DOUBLE);
        }
        if (t1.kind() == IrType.Kind.FLOAT || t2.kind() == IrType.Kind.FLOAT) {
            return IrType.scalar(IrType.Kind.FLOAT);
        }
        if (t1.kind() == IrType.Kind.LONG || t2.kind() == IrType.Kind.LONG) {
            return IrType.scalar(IrType.Kind.LONG);
        }
        return IrType.scalar(IrType.Kind.INT);
    }

    public static IrType toIrType(Ast.Type.T type) {
        if (type == null || type instanceof Ast.Type.Void) return IrType.scalar(IrType.Kind.VOID);
        if (type instanceof Ast.Type.Bool) return IrType.scalar(IrType.Kind.BOOL);
        if (type instanceof Ast.Type.Byte) return IrType.scalar(IrType.Kind.BYTE);
        if (type instanceof Ast.Type.Short) return IrType.scalar(IrType.Kind.SHORT);
        if (type instanceof Ast.Type.Char) return IrType.scalar(IrType.Kind.CHAR);
        if (type instanceof Ast.Type.Int) return IrType.scalar(IrType.Kind.INT);
        if (type instanceof Ast.Type.Long) return IrType.scalar(IrType.Kind.LONG);
        if (type instanceof Ast.Type.Float) return IrType.scalar(IrType.Kind.FLOAT);
        if (type instanceof Ast.Type.Double) return IrType.scalar(IrType.Kind.DOUBLE);
        if (type instanceof Ast.Type.Str) return IrType.scalar(IrType.Kind.STRING);
        if (type instanceof Ast.Type.Pointer pointer) {
            return IrType.pointer(toIrType(pointer.getPointee()), 0);
        }
        if (type instanceof Ast.Type.Null) return IrType.pointer(IrType.scalar(IrType.Kind.VOID), 0);
        if (type instanceof Ast.Type.Struct structType) return IrType.structType(structType.getSimpleName());
        if (type instanceof Ast.Type.Enum enumType) return IrType.enumType(enumType.getSimpleName());

        if (type instanceof Ast.Type.IntArray) return IrType.array(IrType.scalar(IrType.Kind.INT));
        if (type instanceof Ast.Type.ByteArray) return IrType.array(IrType.scalar(IrType.Kind.BYTE));
        if (type instanceof Ast.Type.ShortArray) return IrType.array(IrType.scalar(IrType.Kind.SHORT));
        if (type instanceof Ast.Type.CharArray) return IrType.array(IrType.scalar(IrType.Kind.CHAR));
        if (type instanceof Ast.Type.LongArray) return IrType.array(IrType.scalar(IrType.Kind.LONG));
        if (type instanceof Ast.Type.FloatArray) return IrType.array(IrType.scalar(IrType.Kind.FLOAT));
        if (type instanceof Ast.Type.DoubleArray) return IrType.array(IrType.scalar(IrType.Kind.DOUBLE));
        if (type instanceof Ast.Type.BoolArray) return IrType.array(IrType.scalar(IrType.Kind.BOOL));
        if (type instanceof Ast.Type.StringArray) return IrType.array(IrType.scalar(IrType.Kind.STRING));
        if (type instanceof Ast.Type.StructArray structArray) {
            return IrType.array(IrType.structType(structArray.getStructName()));
        }

        return IrType.scalar(IrType.Kind.INT);
    }

    private static int getArraySize(Ast.Type.T type) {
        if (type instanceof Ast.Type.IntArray a) return a.getSize();
        if (type instanceof Ast.Type.ByteArray a) return a.getSize();
        if (type instanceof Ast.Type.ShortArray a) return a.getSize();
        if (type instanceof Ast.Type.CharArray a) return a.getSize();
        if (type instanceof Ast.Type.LongArray a) return a.getSize();
        if (type instanceof Ast.Type.FloatArray a) return a.getSize();
        if (type instanceof Ast.Type.DoubleArray a) return a.getSize();
        if (type instanceof Ast.Type.BoolArray a) return a.getSize();
        if (type instanceof Ast.Type.StringArray a) return a.getSize();
        if (type instanceof Ast.Type.StructArray sa) return sa.getSize();
        return 0;
    }

    public static boolean isManaged(IrType type) {
        return type != null && type.kind() == IrType.Kind.ARRAY;
    }

    /**
     * Returns the element type of an array-typed variable, or null if the type
     * is not an array. Used to resolve array-element field access like arr[i].x.
     */
    private IrType getArrayElementType(String varName, MethodLoweringContext ctx) {
        IrType t = ctx.variableTypes.get(varName);
        if (t != null && t.kind() == IrType.Kind.ARRAY) {
            return t.elementType();
        }
        return null;
    }

    private List<String> getManagedPaths(IrType type) {
        if (type == null) return List.of();
        if (isManaged(type)) return List.of("");
        if (type.kind() == IrType.Kind.STRUCT && module != null) {
            return getStructManagedPaths(type.name(), new HashSet<>());
        }
        return List.of();
    }

    private List<String> getStructManagedPaths(String structName, Set<String> visited) {
        if (!visited.add(structName)) return List.of();
        IrModule.IrStruct structDef = module.struct(structName);
        if (structDef == null) return List.of();
        List<String> paths = new ArrayList<>();
        for (IrModule.IrStructField field : structDef.fields()) {
            if (field.type().kind() == IrType.Kind.ARRAY) {
                paths.add(field.name());
            } else if (field.type().kind() == IrType.Kind.STRUCT) {
                List<String> subPaths = getStructManagedPaths(field.type().name(), visited);
                for (String sp : subPaths) {
                    paths.add(field.name() + "." + sp);
                }
            }
        }
        visited.remove(structName);
        return paths;
    }

    private IrType resolveFieldType(IrType rootType, String path) {
        if (path.isEmpty()) return rootType;
        String[] parts = path.split("\\.", -1);
        IrType current = rootType;
        for (String part : parts) {
            current = structFieldType(current, part);
        }
        return current;
    }

    /**
     * Lowers an array initializer list into ALLOC + a sequence of STORE instructions.
     * The allocated array is stored directly into the target local variable.
     */
    private void lowerArrayInitializer(Ast.Expr.InitializerList initList, String targetId, IrType targetType, MethodLoweringContext ctx) {
        int size = getArraySize(initList.getType());
        IrValue lenVal = new IrValue(String.valueOf(size), IrType.scalar(IrType.Kind.INT));
        IrValue arrVal = new IrValue(targetId, targetType);
        ctx.emit(new IrInstruction(IrInstruction.Op.ALLOC, arrVal, List.of(lenVal), null));
        IrType elemType = targetType.elementType();
        for (int i = 0; i < initList.getElements().size(); i++) {
            Ast.Expr.T elem = initList.getElements().get(i);
            IrValue elemVal = lowerExpr(elem, ctx);
            // Match the assignment-path behaviour: convert when element type differs.
            if (elemVal.type().kind() != elemType.kind()) {
                IrValue converted = ctx.newTemp(elemType);
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(elemVal), null));
                elemVal = converted;
            }
            IrValue idxVal = new IrValue(String.valueOf(i), IrType.scalar(IrType.Kind.INT));
            ctx.emit(new IrInstruction(IrInstruction.Op.STORE, null, List.of(arrVal, idxVal, elemVal), null));
        }
    }

    private void emitRetain(IrValue val, MethodLoweringContext ctx) {
        if (val == null || val.type() == null) return;
        if (isManaged(val.type())) {
            ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(val), "lemon_retain"));
        } else if (val.type().kind() == IrType.Kind.STRUCT) {
            List<String> paths = getManagedPaths(val.type());
            for (String path : paths) {
                IrType fType = resolveFieldType(val.type(), path);
                IrValue fieldVal = ctx.newTemp(fType);
                ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_LOAD, fieldVal, List.of(val), path));
                ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(fieldVal), "lemon_retain"));
            }
        }
    }

    private void emitRelease(String varName, IrType varType, MethodLoweringContext ctx) {
        if (varName == null || varType == null) return;
        if (isManaged(varType)) {
            ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(new IrValue(varName, varType)), "lemon_release"));
        } else if (varType.kind() == IrType.Kind.STRUCT) {
            List<String> paths = getManagedPaths(varType);
            for (String path : paths) {
                IrType fType = resolveFieldType(varType, path);
                IrValue fieldVal = ctx.newTemp(fType);
                ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_LOAD, fieldVal, List.of(new IrValue(varName, varType)), path));
                ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(fieldVal), "lemon_release"));
            }
        }
    }

    private static String escapeCString(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c == '"') {
                sb.append("\\\"");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private final class MethodLoweringContext {
        final IrFunction function;
        final List<BasicBlock> blocks;
        BasicBlock currentBlock;
        final Map<String, IrType> variableTypes;
        final boolean isMain;
        final IrType returnType;
        final Deque<LoopContext> loopStack = new ArrayDeque<>();
        final Deque<LexicalScope> scopeStack = new ArrayDeque<>();
        final List<Ast.ConstDecl> consts;
        final site.ilemon.flow.NullFlowResult nullFlow;

        MethodLoweringContext(IrFunction function, List<BasicBlock> blocks, BasicBlock currentBlock,
                              Map<String, IrType> variableTypes,
                              boolean isMain, IrType returnType, List<Ast.ConstDecl> consts,
                              site.ilemon.flow.NullFlowResult nullFlow) {
            this.function = function;
            this.blocks = blocks;
            this.currentBlock = currentBlock;
            this.variableTypes = variableTypes;
            this.isMain = isMain;
            this.returnType = returnType;
            this.consts = consts;
            this.nullFlow = nullFlow;
        }

        LexicalScope pushScope(ScopeKind kind) {
            LexicalScope scope = new LexicalScope(kind);
            scopeStack.push(scope);
            return scope;
        }

        LexicalScope popScope() {
            return scopeStack.pop();
        }

        LexicalScope currentScope() {
            return scopeStack.peek();
        }

        void registerTempRvalue(IrValue val) {
            if (val != null && val.name().startsWith("_t") && (isManaged(val.type()) || !getManagedPaths(val.type()).isEmpty())) {
                LexicalScope s = currentScope();
                if (s != null) {
                    s.tempRvalues.add(val);
                }
            }
        }

        void cleanupStatementTemporaries() {
            if (isTerminated(currentBlock)) return;
            LexicalScope s = currentScope();
            if (s != null && !s.tempRvalues.isEmpty()) {
                for (int i = s.tempRvalues.size() - 1; i >= 0; i--) {
                    IrValue temp = s.tempRvalues.get(i);
                    emitRelease(temp.name(), temp.type(), this);
                }
                s.tempRvalues.clear();
            }
        }

        void releaseScope(LexicalScope scope) {
            if (isTerminated(currentBlock)) return;
            for (int i = scope.managedVars.size() - 1; i >= 0; i--) {
                String varName = scope.managedVars.get(i);
                emitRelease(varName, variableTypes.get(varName), this);
            }
            for (int i = scope.tempRvalues.size() - 1; i >= 0; i--) {
                IrValue temp = scope.tempRvalues.get(i);
                emitRelease(temp.name(), temp.type(), this);
            }
            scope.tempRvalues.clear();
        }

        void releaseAllScopesExcept(String exceptVarName) {
            if (isTerminated(currentBlock)) return;
            for (LexicalScope scope : scopeStack) {
                for (int i = scope.managedVars.size() - 1; i >= 0; i--) {
                    String varName = scope.managedVars.get(i);
                    if (exceptVarName == null || !varName.equals(exceptVarName)) {
                        emitRelease(varName, variableTypes.get(varName), this);
                    }
                }
                for (int i = scope.tempRvalues.size() - 1; i >= 0; i--) {
                    IrValue temp = scope.tempRvalues.get(i);
                    if (exceptVarName == null || !temp.name().equals(exceptVarName)) {
                        emitRelease(temp.name(), temp.type(), this);
                    }
                }
            }
        }

        void releaseScopesUpTo(LexicalScope targetScope) {
            if (isTerminated(currentBlock)) return;
            for (LexicalScope scope : scopeStack) {
                for (int i = scope.managedVars.size() - 1; i >= 0; i--) {
                    String varName = scope.managedVars.get(i);
                    emitRelease(varName, variableTypes.get(varName), this);
                }
                for (int i = scope.tempRvalues.size() - 1; i >= 0; i--) {
                    IrValue temp = scope.tempRvalues.get(i);
                    emitRelease(temp.name(), temp.type(), this);
                }
                if (scope == targetScope) {
                    break;
                }
            }
        }

        BasicBlock createBlock(String prefix) {
            return new BasicBlock(prefix + "_" + (++labelCounter));
        }

        void startBlock(BasicBlock block) {
            if (!blocks.contains(block)) {
                blocks.add(block);
            }
            currentBlock = block;
        }

        IrValue newTemp(IrType type) {
            return new IrValue("_t" + (++tempCounter), type);
        }

        void emit(IrInstruction inst) {
            List<IrInstruction> ins = currentBlock.instructionsView();
            if (ins.isEmpty() || !ins.get(ins.size() - 1).isTerminator()) {
                currentBlock.add(inst);
            }
        }

        boolean isTerminated(BasicBlock b) {
            if (b == null) return false;
            List<IrInstruction> ins = b.instructionsView();
            if (ins.isEmpty()) return false;
            return ins.get(ins.size() - 1).isTerminator();
        }
    }

    private boolean isParameterBorrowed(String paramName, Ast.Method.MethodSingle method) {
        if (paramName == null || method == null || method.getStms() == null) {
            return true;
        }
        return !isParamMutatedOrEscaping(paramName, method.getStms(), method);
    }

    private boolean isParamMutatedOrEscaping(String paramName, List<Ast.Stmt.T> stmts, Ast.Method.MethodSingle method) {
        if (stmts == null) return false;
        for (Ast.Stmt.T stmt : stmts) {
            if (isParamMutatedOrEscaping(paramName, stmt, method)) {
                return true;
            }
        }
        return false;
    }

    private boolean isParamMutatedOrEscaping(String paramName, Ast.Stmt.T stmt, Ast.Method.MethodSingle method) {
        if (stmt == null) return false;
        if (stmt instanceof Ast.Stmt.Assign assign) {
            if (assign.getId() != null && paramName.equals(assign.getId().getId())) {
                return true;
            }
            return containsParamAddressOrReturn(paramName, assign.getExpr());
        }
        if (stmt instanceof Ast.Stmt.FieldAssign fieldAssign) {
            if (isFieldReceiverParam(paramName, fieldAssign.getTarget())) {
                return true;
            }
            return containsParamAddressOrReturn(paramName, fieldAssign.getExpr());
        }
        if (stmt instanceof Ast.Stmt.ArrayAssign arrayAssign) {
            if (paramName.equals(arrayAssign.getArrayName())) {
                return true;
            }
            if (arrayAssign.getFieldTarget() != null && isFieldReceiverParam(paramName, arrayAssign.getFieldTarget())) {
                return true;
            }
            // Also check the index expression (arr[i] where i is the param)
            if (containsParamAddressOrReturn(paramName, arrayAssign.getIndex())) {
                return true;
            }
            return containsParamAddressOrReturn(paramName, arrayAssign.getExpr());
        }
        if (stmt instanceof Ast.Stmt.DerefAssign derefAssign) {
            return containsParamAddressOrReturn(paramName, derefAssign.getExpr());
        }
        if (stmt instanceof Ast.Stmt.VarDecl varDecl) {
            if (varDecl.getDeclaration() instanceof Ast.Declare.DeclareSingle d) {
                return containsParamAddressOrReturn(paramName, d.getInitExp());
            }
            return false;
        }
        if (stmt instanceof Ast.Stmt.Block block) {
            return isParamMutatedOrEscaping(paramName, block.getStmts(), method);
        }
        if (stmt instanceof Ast.Stmt.If ifStmt) {
            if (isParamMutatedOrEscaping(paramName, ifStmt.getThenStmt(), method)) return true;
            if (isParamMutatedOrEscaping(paramName, ifStmt.getElseStmt(), method)) return true;
            return false;
        }
        if (stmt instanceof Ast.Stmt.While whileStmt) {
            return isParamMutatedOrEscaping(paramName, whileStmt.getBody(), method);
        }
        if (stmt instanceof Ast.Stmt.For forStmt) {
            if (isParamMutatedOrEscaping(paramName, forStmt.getInit(), method)) return true;
            if (isParamMutatedOrEscaping(paramName, forStmt.getBody(), method)) return true;
            if (isParamMutatedOrEscaping(paramName, forStmt.getUpdate(), method)) return true;
            return false;
        }
        if (stmt instanceof Ast.Stmt.Return retStmt) {
            IrType retType = methodReturnTypes.get(method.getId());
            if (retType != null && (isManaged(retType) || !getManagedPaths(retType).isEmpty())) {
                if (retStmt.getExpr() != null && mentionsParam(paramName, retStmt.getExpr())) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private boolean isFieldReceiverParam(String paramName, Ast.Expr.Field field) {
        if (field == null) return false;
        Ast.Expr.T receiver = field.getReceiver();
        while (receiver instanceof Ast.Expr.Field f) {
            receiver = f.getReceiver();
        }
        if (receiver instanceof Ast.Expr.Id id) {
            return paramName.equals(id.getId());
        }
        return false;
    }

    private boolean mentionsParam(String paramName, Ast.Expr.T expr) {
        if (expr == null) return false;
        if (expr instanceof Ast.Expr.Id id) {
            return paramName.equals(id.getId());
        }
        if (expr instanceof Ast.Expr.Field field) {
            return mentionsParam(paramName, field.getReceiver());
        }
        if (expr instanceof Ast.Expr.ArrayAccess aa) {
            if (paramName.equals(aa.getArrayName())) return true;
            return mentionsParam(paramName, aa.getFieldTarget()) || mentionsParam(paramName, aa.getIndex());
        }
        if (expr instanceof Ast.Expr.AddressOf addr) {
            return mentionsParam(paramName, addr.getOperand());
        }
        if (expr instanceof Ast.Expr.Deref deref) {
            return mentionsParam(paramName, deref.getOperand());
        }
        if (expr instanceof Ast.Expr.UnaryMinus um) {
            return mentionsParam(paramName, um.getExp());
        }
        if (expr instanceof Ast.Expr.UnaryPlus up) {
            return mentionsParam(paramName, up.getExp());
        }
        if (expr instanceof Ast.Expr.BitNot bn) {
            return mentionsParam(paramName, bn.getExp());
        }
        if (expr instanceof Ast.Expr.Not not) {
            return mentionsParam(paramName, not.getExpr());
        }
        if (expr instanceof Ast.Expr.PreInc inc) {
            return mentionsParam(paramName, inc.getExp());
        }
        if (expr instanceof Ast.Expr.PostInc inc) {
            return mentionsParam(paramName, inc.getExp());
        }
        if (expr instanceof Ast.Expr.PreDec dec) {
            return mentionsParam(paramName, dec.getExp());
        }
        if (expr instanceof Ast.Expr.PostDec dec) {
            return mentionsParam(paramName, dec.getExp());
        }
        if (expr instanceof Ast.Expr.InitializerList init) {
            if (init.getElements() != null) {
                for (Ast.Expr.T el : init.getElements()) {
                    if (mentionsParam(paramName, el)) return true;
                }
            }
            return false;
        }
        if (expr instanceof Ast.Expr.Call call) {
            if (call.getInputParams() != null) {
                for (Ast.Expr.T arg : call.getInputParams()) {
                    if (mentionsParam(paramName, arg)) return true;
                }
            }
            return false;
        }
        if (expr instanceof Ast.Expr.Ternary ternary) {
            return mentionsParam(paramName, ternary.getCondition())
                    || mentionsParam(paramName, ternary.getTrueExpr())
                    || mentionsParam(paramName, ternary.getFalseExpr());
        }
        if (isBinaryOp(expr)) {
            return mentionsParam(paramName, getBinaryLeft(expr)) || mentionsParam(paramName, getBinaryRight(expr));
        }
        return false;
    }

    private boolean isBinaryOp(Ast.Expr.T expr) {
        return expr instanceof Ast.Expr.Add || expr instanceof Ast.Expr.Sub || expr instanceof Ast.Expr.Mul
                || expr instanceof Ast.Expr.Div || expr instanceof Ast.Expr.Mod || expr instanceof Ast.Expr.And
                || expr instanceof Ast.Expr.Or || expr instanceof Ast.Expr.LT || expr instanceof Ast.Expr.LTE
                || expr instanceof Ast.Expr.GT || expr instanceof Ast.Expr.GTE || expr instanceof Ast.Expr.EQ
                || expr instanceof Ast.Expr.NEQ;
    }

    private Ast.Expr.T getBinaryLeft(Ast.Expr.T expr) {
        if (expr instanceof Ast.Expr.Add e) return e.getLeft();
        if (expr instanceof Ast.Expr.Sub e) return e.getLeft();
        if (expr instanceof Ast.Expr.Mul e) return e.getLeft();
        if (expr instanceof Ast.Expr.Div e) return e.getLeft();
        if (expr instanceof Ast.Expr.Mod e) return e.getLeft();
        if (expr instanceof Ast.Expr.And e) return e.getLeft();
        if (expr instanceof Ast.Expr.Or e) return e.getLeft();
        if (expr instanceof Ast.Expr.LT e) return e.getLeft();
        if (expr instanceof Ast.Expr.LTE e) return e.getLeft();
        if (expr instanceof Ast.Expr.GT e) return e.getLeft();
        if (expr instanceof Ast.Expr.GTE e) return e.getLeft();
        if (expr instanceof Ast.Expr.EQ e) return e.getLeft();
        if (expr instanceof Ast.Expr.NEQ e) return e.getLeft();
        return null;
    }

    private Ast.Expr.T getBinaryRight(Ast.Expr.T expr) {
        if (expr instanceof Ast.Expr.Add e) return e.getRight();
        if (expr instanceof Ast.Expr.Sub e) return e.getRight();
        if (expr instanceof Ast.Expr.Mul e) return e.getRight();
        if (expr instanceof Ast.Expr.Div e) return e.getRight();
        if (expr instanceof Ast.Expr.Mod e) return e.getRight();
        if (expr instanceof Ast.Expr.And e) return e.getRight();
        if (expr instanceof Ast.Expr.Or e) return e.getRight();
        if (expr instanceof Ast.Expr.LT e) return e.getRight();
        if (expr instanceof Ast.Expr.LTE e) return e.getRight();
        if (expr instanceof Ast.Expr.GT e) return e.getRight();
        if (expr instanceof Ast.Expr.GTE e) return e.getRight();
        if (expr instanceof Ast.Expr.EQ e) return e.getRight();
        if (expr instanceof Ast.Expr.NEQ e) return e.getRight();
        return null;
    }

    private boolean containsParamAddressOrReturn(String paramName, Ast.Expr.T expr) {
        if (expr == null) return false;
        if (expr instanceof Ast.Expr.AddressOf addr) {
            return mentionsParam(paramName, addr.getOperand());
        }
        if (isBinaryOp(expr)) {
            return containsParamAddressOrReturn(paramName, getBinaryLeft(expr)) || containsParamAddressOrReturn(paramName, getBinaryRight(expr));
        }
        if (expr instanceof Ast.Expr.Ternary ternary) {
            return containsParamAddressOrReturn(paramName, ternary.getTrueExpr()) || containsParamAddressOrReturn(paramName, ternary.getFalseExpr());
        }
        return false;
    }

    private void collectVarDeclNames(List<Ast.Stmt.T> stmts, Set<String> names) {
        if (stmts == null) return;
        for (Ast.Stmt.T s : stmts) {
            collectVarDeclNames(s, names);
        }
    }

    private void collectVarDeclNames(Ast.Stmt.T stmt, Set<String> names) {
        if (stmt == null) return;
        if (stmt instanceof Ast.Stmt.VarDecl varDecl) {
            if (varDecl.getDeclaration() instanceof Ast.Declare.DeclareSingle d) {
                names.add(d.getId());
            }
        } else if (stmt instanceof Ast.Stmt.Block block) {
            collectVarDeclNames(block.getStmts(), names);
        } else if (stmt instanceof Ast.Stmt.If ifStmt) {
            collectVarDeclNames(ifStmt.getThenStmt(), names);
            collectVarDeclNames(ifStmt.getElseStmt(), names);
        } else if (stmt instanceof Ast.Stmt.While whileStmt) {
            collectVarDeclNames(whileStmt.getBody(), names);
        } else if (stmt instanceof Ast.Stmt.For forStmt) {
            collectVarDeclNames(forStmt.getInit(), names);
            collectVarDeclNames(forStmt.getBody(), names);
        }
    }
    private IrInstruction.Op compoundAssignOp(site.ilemon.lexer.TokenKind op) {
        if (op == null) return null;
        return switch (op) {
            case AddAssign -> IrInstruction.Op.ADD;
            case SubAssign -> IrInstruction.Op.SUB;
            case MulAssign -> IrInstruction.Op.MUL;
            case DivAssign -> IrInstruction.Op.DIV;
            case ModAssign -> IrInstruction.Op.REM;
            default -> null;
        };
    }

    private IrValue lowerIncDec(Ast.Expr.T target, boolean isPost, boolean isDec, MethodLoweringContext ctx) {
        IrInstruction.Op binOp = isDec ? IrInstruction.Op.SUB : IrInstruction.Op.ADD;
        IrValue oldValLoc;
        IrType type;
        
        IrValue arrVal = null;
        IrValue idxVal = null;
        IrValue address = null;
        
        if (target instanceof Ast.Expr.Id id) {
            type = ctx.variableTypes.get(id.getId());
            if (type == null) type = IrType.scalar(IrType.Kind.INT);
            oldValLoc = new IrValue(id.getId(), type);
        } else if (target instanceof Ast.Expr.Field field) {
            oldValLoc = lowerFieldLoad(field, ctx);
            type = oldValLoc.type();
        } else if (target instanceof Ast.Expr.ArrayAccess arrayAccess) {
            IrType arrType;
            if (arrayAccess.getFieldTarget() != null) {
                arrVal = lowerFieldLoad(arrayAccess.getFieldTarget(), ctx);
                arrType = arrVal.type();
            } else {
                String arrName = arrayAccess.getArrayName();
                arrType = ctx.variableTypes.get(arrName);
                arrVal = new IrValue(arrName, arrType);
            }
            idxVal = lowerExpr(arrayAccess.getIndex(), ctx);
            type = arrType != null && arrType.elementType() != null ? arrType.elementType() : IrType.scalar(IrType.Kind.INT);
            oldValLoc = ctx.newTemp(type);
            ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, oldValLoc, List.of(arrVal, idxVal), null));
        } else if (target instanceof Ast.Expr.Deref deref) {
            int depth = 0;
            Ast.Expr.T base = deref;
            while (base instanceof Ast.Expr.Deref d) {
                depth++;
                base = d.getOperand();
            }
            address = lowerExpr(base, ctx);
            for (int i = 1; i < depth; i++) {
                IrValue next = ctx.newTemp(address.type().elementType());
                ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, next, List.of(address), null));
                address = next;
            }
            type = address.type().elementType();
            oldValLoc = ctx.newTemp(type);
            boolean isSafe = ctx.nullFlow != null && ctx.nullFlow.isSafe(deref);
            ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, oldValLoc, List.of(address), null, isSafe));
        } else {
            throw new IllegalArgumentException("invalid target for increment/decrement");
        }
        
        IrValue oldValCached = ctx.newTemp(type);
        ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, oldValCached, List.of(oldValLoc), null));

        IrValue one = ctx.newTemp(type);
        ctx.emit(new IrInstruction(IrInstruction.Op.CONST, one, List.of(new IrValue("1", type)), null));
        IrValue newVal = applyBinaryOp(binOp, oldValCached, one, ctx);
        
        if (target instanceof Ast.Expr.Id id) {
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, new IrValue(id.getId(), type), List.of(newVal), null));
        } else if (target instanceof Ast.Expr.Field field) {
            boolean isSafe = field.isPointerBase() && ctx.nullFlow != null && ctx.nullFlow.isSafe(field);
            lowerFieldStore(field, newVal, isSafe, ctx);
        } else if (target instanceof Ast.Expr.ArrayAccess) {
            ctx.emit(new IrInstruction(IrInstruction.Op.STORE, null, List.of(arrVal, idxVal, newVal), null));
        } else if (target instanceof Ast.Expr.Deref deref) {
            boolean isSafe = ctx.nullFlow != null && ctx.nullFlow.isSafe(deref);
            ctx.emit(new IrInstruction(IrInstruction.Op.STORE, null, List.of(address, newVal), null, isSafe));
        }

        return isPost ? oldValCached : newVal;
    }
}

