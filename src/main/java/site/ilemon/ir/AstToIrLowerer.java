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

    private record LoopContext(BasicBlock breakTarget, BasicBlock continueTarget) {}

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
        Set<String> managedLocals = new LinkedHashSet<>();

        if (method.getFormals() != null) {
            for (Ast.Declare.T formal : method.getFormals()) {
                if (formal instanceof Ast.Declare.DeclareSingle d) {
                    IrType t = toIrType(d.getType());
                    params.add(new IrValue(d.getId(), t));
                    variableTypes.put(d.getId(), t);
                    if (isManaged(t) || !getManagedPaths(t).isEmpty()) {
                        managedLocals.add(d.getId());
                    }
                }
            }
        }

        if (method.getLocals() != null) {
            for (Ast.Declare.T local : method.getLocals()) {
                if (local instanceof Ast.Declare.DeclareSingle d) {
                    IrType t = toIrType(d.getType());
                    variableTypes.put(d.getId(), t);
                    if (isManaged(t) || !getManagedPaths(t).isEmpty()) {
                        managedLocals.add(d.getId());
                    }
                }
            }
        }

        IrFunction irFunc = new IrFunction(method.getId(), returnType, params);
        List<BasicBlock> blocks = new ArrayList<>();
        BasicBlock entry = new BasicBlock("entry");
        blocks.add(entry);

        List<Ast.ConstDecl> methodConsts = method.getModuleConsts() != null ? method.getModuleConsts() : programConsts;
        MethodLoweringContext ctx = new MethodLoweringContext(
                irFunc, blocks, entry, variableTypes, managedLocals, isMain, returnType, methodConsts
        );

        // In entry block: allocate arrays and initialize locals that do not have VarDecl statements
        Set<Ast.Declare.T> varDeclNodes = Collections.newSetFromMap(new IdentityHashMap<>());
        collectVarDeclNodes(method.getStms(), varDeclNodes);
        if (method.getLocals() != null) {
            for (Ast.Declare.T local : method.getLocals()) {
                if (!varDeclNodes.contains(local) && local instanceof Ast.Declare.DeclareSingle d) {
                    IrType t = variableTypes.get(d.getId());
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

        // In entry block: retain incoming managed parameters
        for (IrValue p : params) {
            emitRetain(p, ctx);
        }

        // Lower method statements
        if (method.getStms() != null) {
            for (Ast.Stmt.T stmt : method.getStms()) {
                lowerStmt(stmt, ctx);
            }
        }

        // If the last block is not terminated, emit cleanup and default return
        if (!ctx.isTerminated(ctx.currentBlock)) {
            for (String managed : managedLocals) {
                emitRelease(managed, variableTypes.get(managed), ctx);
            }
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
                    if (isManaged(targetType) || !getManagedPaths(targetType).isEmpty()) {
                        ctx.managedLocals.add(targetId);
                    }
                }
                if (d.getInitExp() != null) {
                    IrValue rhsVal;
                    if (targetType.kind() == IrType.Kind.DOUBLE
                            && d.getInitExp() instanceof Ast.Expr.Number number
                            && number.getType() instanceof Ast.Type.Float) {
                        rhsVal = ctx.newTemp(IrType.scalar(IrType.Kind.DOUBLE));
                        ctx.emit(new IrInstruction(IrInstruction.Op.CONST, rhsVal,
                                List.of(new IrValue(String.valueOf(number.getValue()),
                                        IrType.scalar(IrType.Kind.DOUBLE))), null));
                    } else {
                        rhsVal = lowerExpr(d.getInitExp(), ctx);
                    }
                    if (isManaged(targetType)) {
                        if (isManaged(rhsVal.type())) {
                            ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(rhsVal), "lemon_retain"));
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
                    ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(rhsVal), "lemon_retain"));
                    ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(new IrValue(targetId, targetType)), "lemon_release"));
                }
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, new IrValue(targetId, targetType), List.of(rhsVal), null));
            } else {
                if (rhsVal.type().kind() != targetType.kind()) {
                    IrValue converted = ctx.newTemp(targetType);
                    ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(rhsVal), null));
                    rhsVal = converted;
                }
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, new IrValue(targetId, targetType), List.of(rhsVal), null));
            }
        } else if (stmt instanceof Ast.Stmt.FieldAssign fieldAssign) {
            IrValue value = lowerExpr(fieldAssign.getExpr(), ctx);
            lowerFieldStore(fieldAssign.getTarget(), value, ctx);
        } else if (stmt instanceof Ast.Stmt.DerefAssign derefAssign) {
            lowerDerefAssign(derefAssign, ctx);
        } else if (stmt instanceof Ast.Stmt.ArrayAssign arrayAssign) {
            IrValue arrVal;
            IrType arrType;
            if (arrayAssign.getFieldTarget() != null) {
                arrVal = lowerFieldLoad(arrayAssign.getFieldTarget(), ctx);
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
            if (val.type().kind() != elemType.kind()) {
                IrValue converted = ctx.newTemp(elemType);
                ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(val), null));
                val = converted;
            }

            ctx.emit(new IrInstruction(IrInstruction.Op.STORE, null, List.of(arrVal, idxVal, val), null));
        } else if (stmt instanceof Ast.Stmt.Block block) {
            if (block.getStmts() != null) {
                for (Ast.Stmt.T s : block.getStmts()) {
                    lowerStmt(s, ctx);
                }
            }
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
            lowerStmt(ifStmt.getThenStmt(), ctx);
            boolean thenTerm = ctx.isTerminated(ctx.currentBlock);
            if (!thenTerm) {
                ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), mergeBlock.name()));
            }

            // Else branch (if exists)
            boolean elseTerm = false;
            if (elseBlock != null) {
                ctx.startBlock(elseBlock);
                lowerStmt(ifStmt.getElseStmt(), ctx);
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
            ctx.loopStack.push(new LoopContext(exitBlock, condBlock));
            lowerStmt(whileStmt.getBody(), ctx);
            ctx.loopStack.pop();

            if (!ctx.isTerminated(ctx.currentBlock)) {
                ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), condBlock.name()));
            }

            ctx.startBlock(exitBlock);
        } else if (stmt instanceof Ast.Stmt.For forStmt) {
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
            ctx.loopStack.push(new LoopContext(exitBlock, updateBlock));
            lowerStmt(forStmt.getBody(), ctx);
            ctx.loopStack.pop();

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
        } else if (stmt instanceof Ast.Stmt.Break) {
            if (!ctx.loopStack.isEmpty()) {
                LoopContext loop = ctx.loopStack.peek();
                ctx.emit(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), loop.breakTarget.name()));
            }
        } else if (stmt instanceof Ast.Stmt.Continue) {
            if (!ctx.loopStack.isEmpty()) {
                LoopContext loop = ctx.loopStack.peek();
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
                for (String managed : ctx.managedLocals) {
                    if (!managed.equals(retVal.name())) {
                        emitRelease(managed, ctx.variableTypes.get(managed), ctx);
                    }
                }
                ctx.emit(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(retVal), null));
            } else {
                for (String managed : ctx.managedLocals) {
                    emitRelease(managed, ctx.variableTypes.get(managed), ctx);
                }
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
        } else if (stmt instanceof Ast.Stmt.PrintLine) {
            IrValue nlVal = new IrValue("\"\\n\"", IrType.scalar(IrType.Kind.STRING));
            ctx.emit(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(nlVal), "printf"));
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
        }
    }

    // ==================================================== struct lowering

    /**
     * Lowers a field-read chain to a single FIELD_LOAD op whose {@code target}
     * carries the full dotted path ({@code inner.first.x}). The single
     * operand is the root value: the struct variable itself, or the struct*
     * variable when the chain starts with {@code ->}. Keeping the whole path
     * in one op lets the C backend emit plain {@code root.a.b} lvalues while
     * the JVM backend navigates one reference chain without intermediate
     * copies — both backends stay field-address based.
     */
    private IrValue lowerFieldLoad(Ast.Expr.Field field, MethodLoweringContext ctx) {
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
        boolean throughPointer = field.isPointerBase() && isPointerKind(rootType);
        IrType resultType = fieldPathType(rootType, field.getPath(), throughPointer);
        IrValue loaded = ctx.newTemp(resultType);
        ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_LOAD, loaded,
                List.of(new IrValue(id.getId(), rootType)), String.join(".", field.getPath())));
        return loaded;
    }

    /** Result type of a field path, resolving each link through struct layouts. */
    private IrType fieldPathType(IrType rootType, List<String> path, boolean throughPointer) {
        IrType current = throughPointer ? rootType.elementType() : rootType;
        for (String fieldName : path) {
            current = structFieldType(current, fieldName);
        }
        return current;
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
        Ast.Expr.T receiverExpr = target.getReceiver();
        if (!(receiverExpr instanceof Ast.Expr.Id id)) {
            throw new CompilerException("field store requires a named struct root");
        }
        IrType rootType = ctx.variableTypes.get(id.getId());
        if (rootType == null) rootType = IrType.scalar(IrType.Kind.INT);
        boolean throughPointer = target.isPointerBase() && isPointerKind(rootType);
        IrType fieldType = fieldPathType(rootType, target.getPath(), throughPointer);
        IrValue stored = value;
        if (stored.type().kind() != fieldType.kind()
                || (stored.type().kind() == IrType.Kind.STRUCT && !stored.type().name().equals(fieldType.name()))) {
            IrValue converted = ctx.newTemp(fieldType);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(stored), null));
            stored = converted;
        }
        if (isManaged(fieldType)) {
            IrValue oldField = ctx.newTemp(fieldType);
            ctx.emit(new IrInstruction(IrInstruction.Op.FIELD_LOAD, oldField,
                    List.of(new IrValue(id.getId(), rootType)), String.join(".", target.getPath())));
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
                String.join(".", target.getPath())));
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
        if (value.type().kind() != elemType.kind()) {
            IrValue converted = ctx.newTemp(elemType);
            ctx.emit(new IrInstruction(IrInstruction.Op.CONVERT, converted, List.of(value), null));
            value = converted;
        }
        ctx.emit(new IrInstruction(IrInstruction.Op.STORE, null, List.of(address, value), null));
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
            ctx.emit(new IrInstruction(IrInstruction.Op.LOAD, res, List.of(pointer), null));
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
            return res;
        } else if (expr instanceof Ast.Expr.ArrayLength arrayLen) {
            String arrName = arrayLen.getArrayName();
            IrType arrType = ctx.variableTypes.get(arrName);
            IrValue arrVal = new IrValue(arrName, arrType);
            IrValue res = ctx.newTemp(IrType.scalar(IrType.Kind.INT));
            
            // ArrayLength on pointer types is not supported - pointers don't have length
            if (arrType != null && arrType.kind() == IrType.Kind.POINTER) {
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
        }
        throw new IllegalArgumentException("unsupported expr: " + expr.getClass().getSimpleName());
    }

    private IrValue lowerBinary(IrInstruction.Op op, Ast.Expr.T leftExpr, Ast.Expr.T rightExpr, MethodLoweringContext ctx) {
        IrValue left = lowerExpr(leftExpr, ctx);
        IrValue right = lowerExpr(rightExpr, ctx);
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
        }        IrValue res = ctx.newTemp(commonType);
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
        if (!exprContainsCall(leftExpr) && !exprContainsCall(rightExpr)) {
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

    /** True when evaluating the expression could run a function call. */
    private boolean exprContainsCall(Ast.Expr.T expr) {
        if (expr instanceof Ast.Expr.Call) {
            return true;
        }
        if (expr instanceof Ast.Expr.Add a) {
            return exprContainsCall(a.getLeft()) || exprContainsCall(a.getRight());
        }
        if (expr instanceof Ast.Expr.Sub s) {
            return exprContainsCall(s.getLeft()) || exprContainsCall(s.getRight());
        }
        if (expr instanceof Ast.Expr.Mul m) {
            return exprContainsCall(m.getLeft()) || exprContainsCall(m.getRight());
        }
        if (expr instanceof Ast.Expr.Div d) {
            return exprContainsCall(d.getLeft()) || exprContainsCall(d.getRight());
        }
        if (expr instanceof Ast.Expr.Mod mo) {
            return exprContainsCall(mo.getLeft()) || exprContainsCall(mo.getRight());
        }
        if (expr instanceof Ast.Expr.And an) {
            return exprContainsCall(an.getLeft()) || exprContainsCall(an.getRight());
        }
        if (expr instanceof Ast.Expr.Or or) {
            return exprContainsCall(or.getLeft()) || exprContainsCall(or.getRight());
        }
        if (expr instanceof Ast.Expr.Not n) {
            return exprContainsCall(n.getExpr());
        }
        if (expr instanceof Ast.Expr.GT gt) {
            return exprContainsCall(gt.getLeft()) || exprContainsCall(gt.getRight());
        }
        if (expr instanceof Ast.Expr.LT lt) {
            return exprContainsCall(lt.getLeft()) || exprContainsCall(lt.getRight());
        }
        if (expr instanceof Ast.Expr.GTE ge) {
            return exprContainsCall(ge.getLeft()) || exprContainsCall(ge.getRight());
        }
        if (expr instanceof Ast.Expr.LTE le) {
            return exprContainsCall(le.getLeft()) || exprContainsCall(le.getRight());
        }
        if (expr instanceof Ast.Expr.EQ eq) {
            return exprContainsCall(eq.getLeft()) || exprContainsCall(eq.getRight());
        }
        if (expr instanceof Ast.Expr.NEQ ne) {
            return exprContainsCall(ne.getLeft()) || exprContainsCall(ne.getRight());
        }
        if (expr instanceof Ast.Expr.ArrayAccess aa) {
            return exprContainsCall(aa.getIndex());
        }
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

        if (type instanceof Ast.Type.IntArray) return IrType.array(IrType.scalar(IrType.Kind.INT));
        if (type instanceof Ast.Type.ByteArray) return IrType.array(IrType.scalar(IrType.Kind.BYTE));
        if (type instanceof Ast.Type.ShortArray) return IrType.array(IrType.scalar(IrType.Kind.SHORT));
        if (type instanceof Ast.Type.CharArray) return IrType.array(IrType.scalar(IrType.Kind.CHAR));
        if (type instanceof Ast.Type.LongArray) return IrType.array(IrType.scalar(IrType.Kind.LONG));
        if (type instanceof Ast.Type.FloatArray) return IrType.array(IrType.scalar(IrType.Kind.FLOAT));
        if (type instanceof Ast.Type.DoubleArray) return IrType.array(IrType.scalar(IrType.Kind.DOUBLE));
        if (type instanceof Ast.Type.BoolArray) return IrType.array(IrType.scalar(IrType.Kind.BOOL));
        if (type instanceof Ast.Type.StringArray) return IrType.array(IrType.scalar(IrType.Kind.STRING));

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
        return 0;
    }

    public static boolean isManaged(IrType type) {
        return type != null && type.kind() == IrType.Kind.ARRAY;
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
        final Set<String> managedLocals;
        final boolean isMain;
        final IrType returnType;
        final Deque<LoopContext> loopStack = new ArrayDeque<>();

        final List<Ast.ConstDecl> consts;

        MethodLoweringContext(IrFunction function, List<BasicBlock> blocks, BasicBlock currentBlock,
                              Map<String, IrType> variableTypes, Set<String> managedLocals,
                              boolean isMain, IrType returnType, List<Ast.ConstDecl> consts) {
            this.function = function;
            this.blocks = blocks;
            this.currentBlock = currentBlock;
            this.variableTypes = variableTypes;
            this.managedLocals = managedLocals;
            this.isMain = isMain;
            this.returnType = returnType;
            this.consts = consts;
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

    private void collectVarDeclNodes(List<Ast.Stmt.T> stmts, Set<Ast.Declare.T> decls) {
        if (stmts == null) return;
        for (Ast.Stmt.T s : stmts) {
            collectVarDeclNodes(s, decls);
        }
    }

    private void collectVarDeclNodes(Ast.Stmt.T stmt, Set<Ast.Declare.T> decls) {
        if (stmt == null) return;
        if (stmt instanceof Ast.Stmt.VarDecl varDecl) {
            decls.add(varDecl.getDeclaration());
        } else if (stmt instanceof Ast.Stmt.Block block) {
            collectVarDeclNodes(block.getStmts(), decls);
        } else if (stmt instanceof Ast.Stmt.If ifStmt) {
            collectVarDeclNodes(ifStmt.getThenStmt(), decls);
            collectVarDeclNodes(ifStmt.getElseStmt(), decls);
        } else if (stmt instanceof Ast.Stmt.While whileStmt) {
            collectVarDeclNodes(whileStmt.getBody(), decls);
        } else if (stmt instanceof Ast.Stmt.For forStmt) {
            collectVarDeclNodes(forStmt.getInit(), decls);
            collectVarDeclNodes(forStmt.getBody(), decls);
        }
    }
}

