package site.ilemon.arc;

import site.ilemon.ast.Ast;
import java.util.HashSet;
import java.util.Set;

/** Emits backend-neutral ownership annotations without changing the JVM pipeline. */
public final class OwnershipAnalyzer {
    public OwnershipIr analyze(Ast.Program.T program) {
        if (!(program instanceof Ast.Program.ProgramSingle root)) throw new IllegalArgumentException("unsupported program AST");
        OwnershipIr ir = new OwnershipIr();
        if (!(root.getMainClass() instanceof Ast.MainClass.MainClassSingle main)) throw new IllegalArgumentException("unsupported main class AST");
        for (Ast.Method.T methodNode : main.getMethods()) analyzeMethod((Ast.Method.MethodSingle) methodNode, ir);
        return ir;
    }
    private void analyzeMethod(Ast.Method.MethodSingle method, OwnershipIr ir) {
        Set<String> managed = new HashSet<>();
        for (Ast.Declare.T declaration : method.getLocals()) {
            Ast.Declare.DeclareSingle d = (Ast.Declare.DeclareSingle) declaration;
            if (isManaged(d.getType())) { managed.add(d.getId()); ir.add(new MemoryOp(MemoryOp.Kind.ALLOC, d.getId() + ":" + d.getType(), d.getLineNum())); }
        }
        for (Ast.Stmt.T statement : method.getStms()) emit(statement, managed, ir);
        for (String name : managed) ir.add(new MemoryOp(MemoryOp.Kind.RELEASE, name, method.getLineNum()));
        ir.add(new MemoryOp(MemoryOp.Kind.SCOPE_EXIT, method.getId(), method.getLineNum()));
    }
    private void emit(Ast.Stmt.T statement, Set<String> managed, OwnershipIr ir) {
        if (statement instanceof Ast.Stmt.Assign a && managed.contains(a.getId().getId())) ir.add(new MemoryOp(MemoryOp.Kind.RETAIN, a.getId().getId(), a.getLineNum()));
        else if (statement instanceof Ast.Stmt.ArrayAssign a) ir.add(new MemoryOp(MemoryOp.Kind.BOUNDS_CHECK, a.getArrayName(), a.getLineNum()));
        else if (statement instanceof Ast.Stmt.Call c) { ir.add(new MemoryOp(MemoryOp.Kind.CALL_ENTER, c.getName(), c.getLineNum())); ir.add(new MemoryOp(MemoryOp.Kind.CALL_EXIT, c.getName(), c.getLineNum())); }
        else if (statement instanceof Ast.Stmt.Return) ir.add(new MemoryOp(MemoryOp.Kind.RETURN, "value", statement.getLineNum()));
        else if (statement instanceof Ast.Stmt.Block b) for (Ast.Stmt.T nested : b.getStmts()) emit(nested, managed, ir);
        else if (statement instanceof Ast.Stmt.If i) { emit(i.getThenStmt(), managed, ir); if (i.getElseStmt() != null) emit(i.getElseStmt(), managed, ir); }
        else if (statement instanceof Ast.Stmt.While w) emit(w.getBody(), managed, ir);
        else if (statement instanceof Ast.Stmt.For f) { emit(f.getInit(), managed, ir); emit(f.getBody(), managed, ir); emit(f.getUpdate(), managed, ir); }
    }
    private boolean isManaged(Ast.Type.T type) { return type != null && type.getKind().name().endsWith("_ARRAY"); }
}
