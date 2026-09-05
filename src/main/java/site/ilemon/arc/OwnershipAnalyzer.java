package site.ilemon.arc;

import site.ilemon.ast.Ast;
import site.ilemon.util.SourceSpan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import site.ilemon.semantic.ScopeManager;

/**
 * Control-flow-aware ownership analyzer.
 * Builds an explicit basic-block CFG for each method and emits precise
 * retain/release/alloc operations along all execution paths.
 */
public final class OwnershipAnalyzer {

    private int labelCounter = 0;

    private record LoopScope(OwnershipBlock breakTarget, OwnershipBlock continueTarget, Set<String> scopeLocals) {}

    public OwnershipIr analyze(Ast.Program.T program) {
        if (!(program instanceof Ast.Program.ProgramSingle root)) {
            throw new IllegalArgumentException("unsupported program AST");
        }
        OwnershipIr ir = new OwnershipIr();
        if (!(root.getMainClass() instanceof Ast.MainClass.MainClassSingle main)) {
            throw new IllegalArgumentException("unsupported main class AST");
        }
        for (Ast.Method.T methodNode : main.getMethods()) {
            analyzeMethod((Ast.Method.MethodSingle) methodNode, main.getImports(), ir);
        }
        return ir;
    }

    private void analyzeMethod(Ast.Method.MethodSingle method, List<Ast.ImportDecl> imports, OwnershipIr ir) {
        boolean returnManaged = isManaged(method.getRetType());
        OwnershipFunction func = new OwnershipFunction(method.getId(), returnManaged);

        // Track parameters
        if (method.getFormals() != null) {
            for (Ast.Declare.T formal : method.getFormals()) {
                if (formal instanceof Ast.Declare.DeclareSingle d) {
                    boolean managed = isManaged(d.getType());
                    func.addParameter(d.getId(), managed);
                }
            }
        }

        // Track method-level locals
        Set<String> methodManagedLocals = new LinkedHashSet<>();
        if (method.getLocals() != null) {
            for (Ast.Declare.T declaration : method.getLocals()) {
                if (declaration instanceof Ast.Declare.DeclareSingle d && isManaged(d.getType())) {
                    methodManagedLocals.add(d.getId());
                    func.addManagedLocal(d.getId(), d.getType() != null ? d.getType().toString() : "@array");
                }
            }
        }

        OwnershipBlock entryBlock = new OwnershipBlock("entry");
        func.setEntryBlock(entryBlock);

        // Entry block: Allocations for method locals
        if (method.getLocals() != null) {
            for (Ast.Declare.T declaration : method.getLocals()) {
                if (declaration instanceof Ast.Declare.DeclareSingle d && isManaged(d.getType())) {
                    MemoryOp allocOp = new MemoryOp(MemoryOp.Kind.ALLOC, d.getId() + ":" + d.getType(),
                            d.getLineNum(), d.getSpan());
                    entryBlock.addOp(allocOp);
                    ir.add(allocOp);
                }
            }
        }

        // Entry block: Retain managed parameters if any
        for (String param : func.managedParameters()) {
            MemoryOp paramRetain = new MemoryOp(MemoryOp.Kind.RETAIN, param, method.getLineNum(), method.getSpan());
            entryBlock.addOp(paramRetain);
            ir.add(paramRetain);
        }

        MethodContext ctx = new MethodContext(func, ir, methodManagedLocals, imports);
        ctx.currentBlock = entryBlock;

        // Traverse statements
        if (method.getStms() != null) {
            for (Ast.Stmt.T stmt : method.getStms()) {
                emitStatement(stmt, ctx);
            }
        }

        // Method exit cleanup if the end of the method is reachable
        if (ctx.currentBlock != null && !ctx.currentBlock.isTerminated()) {
            emitExitCleanup(ctx, method.getId(), method.getLineNum(), method.getSpan());
        }

        ir.addFunction(func);
    }

    private void emitStatement(Ast.Stmt.T stmt, MethodContext ctx) {
        if (stmt == null || ctx.currentBlock == null || ctx.currentBlock.isTerminated()) {
            return;
        }

        int line = stmt.getLineNum();
        SourceSpan span = stmt.getSpan();

        if (stmt instanceof Ast.Stmt.Assign assign) {
            String targetId = assign.getId() != null ? assign.getId().getId() : "";
            boolean targetIsManaged = ctx.func.isManaged(targetId);

            String sourceId = extractIdentifier(assign.getExpr());
            boolean sourceIsManaged = sourceId != null && ctx.func.isManaged(sourceId);

            if (targetIsManaged && sourceIsManaged) {
                // x = y: Retain source first (self-assignment safe), release old target, store
                MemoryOp retainOp = new MemoryOp(MemoryOp.Kind.RETAIN, sourceId, line, span);
                MemoryOp releaseOp = new MemoryOp(MemoryOp.Kind.RELEASE, targetId, line, span);
                MemoryOp storeOp = new MemoryOp(MemoryOp.Kind.STORE, targetId + " = " + sourceId, line, span);
                ctx.recordOp(retainOp);
                ctx.recordOp(releaseOp);
                ctx.recordOp(storeOp);
            } else if (targetIsManaged) {
                // x = expr: Overwriting managed variable with non-variable expression
                MemoryOp retainOp = new MemoryOp(MemoryOp.Kind.RETAIN, targetId, line, span);
                ctx.recordOp(retainOp);
            }
        } else if (stmt instanceof Ast.Stmt.ArrayAssign arrayAssign) {
            String arrayName = arrayAssign.getArrayName();
            MemoryOp checkOp = new MemoryOp(MemoryOp.Kind.BOUNDS_CHECK, arrayName, line, span);
            ctx.recordOp(checkOp);

            String valId = extractIdentifier(arrayAssign.getExpr());
            if (valId != null && ctx.func.isManaged(valId)) {
                MemoryOp retainOp = new MemoryOp(MemoryOp.Kind.RETAIN, valId, line, span);
                MemoryOp storeOp = new MemoryOp(MemoryOp.Kind.STORE, arrayName + "[] = " + valId, line, span);
                ctx.recordOp(retainOp);
                ctx.recordOp(storeOp);
            }
        } else if (stmt instanceof Ast.Stmt.DerefAssign derefAssign) {
            // Track pointer store through dereference. Check if the stored value
            // is an address of a local (escape), which the semantic pass should
            // have rejected, but we record it for the simulator to verify.
            String sourceId = extractIdentifier(derefAssign.getExpr());
            boolean sourceIsLocalAddr = sourceId != null && isLocalAddress(derefAssign.getExpr(), ctx);
            if (sourceIsLocalAddr) {
                // Record a STORE of a local address - the simulator will flag this.
                MemoryOp storeOp = new MemoryOp(MemoryOp.Kind.STORE, "escape:" + sourceId, line, span);
                ctx.recordOp(storeOp);
            }
        } else if (stmt instanceof Ast.Stmt.Call call) {
            MemoryOp enterOp = new MemoryOp(MemoryOp.Kind.CALL_ENTER, call.getName(), line, span);
            MemoryOp exitOp = new MemoryOp(MemoryOp.Kind.CALL_EXIT, call.getName(), line, span);
            ctx.recordOp(enterOp);
            ctx.recordOp(exitOp);
        } else if (stmt instanceof Ast.Stmt.Import importStmt) {
            // Compile-time module bindings have no runtime ownership.
            Ast.ImportDecl declaration = importStmt.getDeclaration();
            ctx.scopeManager.declareImport(declaration.getName(), java.nio.file.Path.of(declaration.getPath()));
        } else if (stmt instanceof Ast.Stmt.Block block) {
            ctx.pushScope();
            if (block.getStmts() != null) {
                for (Ast.Stmt.T nested : block.getStmts()) {
                    emitStatement(nested, ctx);
                }
            }
            Set<String> blockLocals = ctx.popScope();
            for (String local : blockLocals) {
                if (!ctx.currentBlock.isTerminated()) {
                    MemoryOp releaseOp = new MemoryOp(MemoryOp.Kind.RELEASE, local, line, span);
                    ctx.recordOp(releaseOp);
                }
            }
        } else if (stmt instanceof Ast.Stmt.If ifStmt) {
            int id = labelCounter++;
            OwnershipBlock thenBlock = new OwnershipBlock("if.then_" + id);
            OwnershipBlock elseBlock = ifStmt.getElseStmt() != null ? new OwnershipBlock("if.else_" + id) : null;
            OwnershipBlock joinBlock = new OwnershipBlock("if.join_" + id);

            ctx.func.addBlock(thenBlock);
            if (elseBlock != null) {
                ctx.func.addBlock(elseBlock);
            }
            ctx.func.addBlock(joinBlock);

            ctx.currentBlock.addSuccessor(thenBlock);
            ctx.currentBlock.addSuccessor(elseBlock != null ? elseBlock : joinBlock);
            ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.BRANCH);

            // Emit then branch
            ctx.currentBlock = thenBlock;
            emitStatement(ifStmt.getThenStmt(), ctx);
            if (ctx.currentBlock != null && !ctx.currentBlock.isTerminated()) {
                ctx.currentBlock.addSuccessor(joinBlock);
                ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);
            }

            // Emit else branch
            if (elseBlock != null) {
                ctx.currentBlock = elseBlock;
                emitStatement(ifStmt.getElseStmt(), ctx);
                if (ctx.currentBlock != null && !ctx.currentBlock.isTerminated()) {
                    ctx.currentBlock.addSuccessor(joinBlock);
                    ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);
                }
            }

            ctx.currentBlock = joinBlock;
        } else if (stmt instanceof Ast.Stmt.While whileStmt) {
            int id = labelCounter++;
            OwnershipBlock condBlock = new OwnershipBlock("while.cond_" + id);
            OwnershipBlock bodyBlock = new OwnershipBlock("while.body_" + id);
            OwnershipBlock exitBlock = new OwnershipBlock("while.exit_" + id);

            ctx.func.addBlock(condBlock);
            ctx.func.addBlock(bodyBlock);
            ctx.func.addBlock(exitBlock);

            ctx.currentBlock.addSuccessor(condBlock);
            ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);

            condBlock.addSuccessor(bodyBlock);
            condBlock.addSuccessor(exitBlock);
            condBlock.setTerminatorType(OwnershipBlock.TerminatorType.BRANCH);

            ctx.pushLoop(exitBlock, condBlock);
            ctx.currentBlock = bodyBlock;
            emitStatement(whileStmt.getBody(), ctx);
            if (ctx.currentBlock != null && !ctx.currentBlock.isTerminated()) {
                ctx.currentBlock.addSuccessor(condBlock);
                ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);
            }
            ctx.popLoop();

            ctx.currentBlock = exitBlock;
        } else if (stmt instanceof Ast.Stmt.For forStmt) {
            int id = labelCounter++;
            emitStatement(forStmt.getInit(), ctx);

            OwnershipBlock condBlock = new OwnershipBlock("for.cond_" + id);
            OwnershipBlock bodyBlock = new OwnershipBlock("for.body_" + id);
            OwnershipBlock updateBlock = new OwnershipBlock("for.update_" + id);
            OwnershipBlock exitBlock = new OwnershipBlock("for.exit_" + id);

            ctx.func.addBlock(condBlock);
            ctx.func.addBlock(bodyBlock);
            ctx.func.addBlock(updateBlock);
            ctx.func.addBlock(exitBlock);

            ctx.currentBlock.addSuccessor(condBlock);
            ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);

            condBlock.addSuccessor(bodyBlock);
            condBlock.addSuccessor(exitBlock);
            condBlock.setTerminatorType(OwnershipBlock.TerminatorType.BRANCH);

            ctx.pushLoop(exitBlock, updateBlock);
            ctx.currentBlock = bodyBlock;
            emitStatement(forStmt.getBody(), ctx);
            if (ctx.currentBlock != null && !ctx.currentBlock.isTerminated()) {
                ctx.currentBlock.addSuccessor(updateBlock);
                ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);
            }
            ctx.popLoop();

            // update
            ctx.currentBlock = updateBlock;
            emitStatement(forStmt.getUpdate(), ctx);
            if (ctx.currentBlock != null && !ctx.currentBlock.isTerminated()) {
                ctx.currentBlock.addSuccessor(condBlock);
                ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);
            }

            ctx.currentBlock = exitBlock;
        } else if (stmt instanceof Ast.Stmt.Break) {
            LoopScope loop = ctx.currentLoop();
            if (loop != null) {
                // Release loop-local variables
                for (String local : loop.scopeLocals) {
                    MemoryOp releaseOp = new MemoryOp(MemoryOp.Kind.RELEASE, local, line, span);
                    ctx.recordOp(releaseOp);
                }
                ctx.currentBlock.addSuccessor(loop.breakTarget);
                ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);
            }
            OwnershipBlock unreachable = new OwnershipBlock("unreachable_" + labelCounter++);
            ctx.func.addBlock(unreachable);
            unreachable.setTerminatorType(OwnershipBlock.TerminatorType.UNREACHABLE);
            ctx.currentBlock = unreachable;
        } else if (stmt instanceof Ast.Stmt.Continue) {
            LoopScope loop = ctx.currentLoop();
            if (loop != null) {
                for (String local : loop.scopeLocals) {
                    MemoryOp releaseOp = new MemoryOp(MemoryOp.Kind.RELEASE, local, line, span);
                    ctx.recordOp(releaseOp);
                }
                ctx.currentBlock.addSuccessor(loop.continueTarget);
                ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);
            }
            OwnershipBlock unreachable = new OwnershipBlock("unreachable_" + labelCounter++);
            ctx.func.addBlock(unreachable);
            unreachable.setTerminatorType(OwnershipBlock.TerminatorType.UNREACHABLE);
            ctx.currentBlock = unreachable;
        } else if (stmt instanceof Ast.Stmt.Return ret) {
            String retVal = ret.getExpr() != null ? extractIdentifier(ret.getExpr()) : null;
            if (retVal != null && ctx.func.isManaged(retVal)) {
                MemoryOp transferOp = new MemoryOp(MemoryOp.Kind.TRANSFER, retVal, line, span);
                ctx.recordOp(transferOp);
            }
            MemoryOp returnOp = new MemoryOp(MemoryOp.Kind.RETURN, retVal != null ? retVal : "value", line, span);
            ctx.recordOp(returnOp);

            // Early return releases all local references before leaving
            for (String local : ctx.activeManagedLocals) {
                // If this is the returned variable, ownership was transferred; others are released
                if (!local.equals(retVal)) {
                    MemoryOp releaseOp = new MemoryOp(MemoryOp.Kind.RELEASE, local, line, span);
                    ctx.recordOp(releaseOp);
                }
            }

            ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.RETURN);
            OwnershipBlock unreachable = new OwnershipBlock("unreachable_" + labelCounter++);
            ctx.func.addBlock(unreachable);
            unreachable.setTerminatorType(OwnershipBlock.TerminatorType.UNREACHABLE);
            ctx.currentBlock = unreachable;
        }
    }

    private void emitExitCleanup(MethodContext ctx, String methodName, int line, SourceSpan span) {
        for (String local : ctx.activeManagedLocals) {
            MemoryOp releaseOp = new MemoryOp(MemoryOp.Kind.RELEASE, local, line, span);
            ctx.recordOp(releaseOp);
        }
        for (String param : ctx.func.managedParameters()) {
            MemoryOp releaseParam = new MemoryOp(MemoryOp.Kind.RELEASE, param, line, span);
            ctx.recordOp(releaseParam);
        }
        MemoryOp exitOp = new MemoryOp(MemoryOp.Kind.SCOPE_EXIT, methodName, line, span);
        ctx.recordOp(exitOp);
        ctx.currentBlock.setTerminatorType(OwnershipBlock.TerminatorType.RETURN);
    }

    private boolean isManaged(Ast.Type.T type) {
        return type != null && type.getKind() != null && type.getKind().name().endsWith("_ARRAY");
    }

    private String extractIdentifier(Ast.Expr.T expr) {
        if (expr instanceof Ast.Expr.Id id) {
            return id.getId();
        }
        return null;
    }

    /**
     * Check if an expression is (or contains) the address of a local variable.
     * Conservative: any address-of expression could be a local address.
     */
    private boolean isLocalAddress(Ast.Expr.T expr, MethodContext ctx) {
        if (expr instanceof Ast.Expr.AddressOf) {
            return true;
        }
        if (expr instanceof Ast.Expr.Deref deref) {
            return isLocalAddress(deref.getOperand(), ctx);
        }
        if (expr instanceof Ast.Expr.Call call) {
            if (call.getInputParams() != null) {
                for (Ast.Expr.T arg : call.getInputParams()) {
                    if (isLocalAddress(arg, ctx)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static final class MethodContext {
        private final OwnershipFunction func;
        private final OwnershipIr ir;
        private final Set<String> activeManagedLocals;
        private final Deque<Set<String>> scopeStack = new ArrayDeque<>();
        private final Deque<LoopScope> loopStack = new ArrayDeque<>();
        private final ScopeManager scopeManager = new ScopeManager();
        private OwnershipBlock currentBlock;

        private MethodContext(OwnershipFunction func, OwnershipIr ir, Set<String> activeManagedLocals,
                              List<Ast.ImportDecl> imports) {
            this.func = func;
            this.ir = ir;
            this.activeManagedLocals = new LinkedHashSet<>(activeManagedLocals);
            for (Ast.ImportDecl importDecl : imports) {
                scopeManager.declareImport(importDecl.getName(), java.nio.file.Path.of(importDecl.getPath()));
            }
            pushScope();
        }

        private void recordOp(MemoryOp op) {
            if (currentBlock != null) {
                currentBlock.addOp(op);
            }
            ir.add(op);
        }

        private void pushScope() {
            scopeStack.push(new HashSet<>());
            if (scopeStack.size() > 1) scopeManager.enterScope();
        }

        private Set<String> popScope() {
            if (scopeStack.isEmpty()) return Set.of();
            Set<String> result = scopeStack.pop();
            if (!scopeStack.isEmpty()) scopeManager.exitScope();
            return result;
        }

        private void pushLoop(OwnershipBlock breakTarget, OwnershipBlock continueTarget) {
            loopStack.push(new LoopScope(breakTarget, continueTarget, new HashSet<>()));
        }

        private void popLoop() {
            if (!loopStack.isEmpty()) {
                loopStack.pop();
            }
        }

        private LoopScope currentLoop() {
            return loopStack.peek();
        }
    }
}
