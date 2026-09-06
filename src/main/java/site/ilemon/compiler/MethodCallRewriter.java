package site.ilemon.compiler;

import site.ilemon.ast.Ast;
import site.ilemon.visitor.ISemanticVisitor;

import java.util.HashMap;
import java.util.Map;

/**
 * Rewrites method calls in an AST by replacing original names with prefixed names.
 * Used when importing modules to rewrite internal calls like {@code mul()} to {@code math_mul()}.
 */
public final class MethodCallRewriter implements ISemanticVisitor {

    private final Map<String, String> nameMap;

    public MethodCallRewriter(Map<String, String> nameMap) {
        this.nameMap = nameMap;
    }

    public void rewrite(Ast.Method.MethodSingle method) {
        if (method.getStms() != null) {
            for (Ast.Stmt.T stmt : method.getStms()) {
                visit(stmt);
            }
        }
    }

    // Expression visitors
    @Override
    public void visit(Ast.Expr.Call obj) {
        String originalName = obj.getName();
        String rewrittenName = nameMap.get(originalName);
        if (rewrittenName != null) {
            obj.setName(rewrittenName);
        }
        // Visit arguments to handle nested calls
        if (obj.getInputParams() != null) {
            for (Ast.Expr.T arg : obj.getInputParams()) {
                visit(arg);
            }
        }
    }

    @Override
    public void visit(Ast.Expr.Add obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.Sub obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.Mul obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.Div obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.Mod obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.And obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.Or obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.GT obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.LT obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.GTE obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.LTE obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.EQ obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.NEQ obj) { visit(obj.getLeft()); visit(obj.getRight()); }
    @Override
    public void visit(Ast.Expr.Not obj) { visit(obj.getExpr()); }
    @Override
    public void visit(Ast.Expr.ArrayAccess obj) { visit(obj.getIndex()); }
    @Override
    public void visit(Ast.Expr.AddressOf obj) { visit(obj.getOperand()); }
    @Override
    public void visit(Ast.Expr.Deref obj) { visit(obj.getOperand()); }
    @Override
    public void visit(Ast.Expr.Id obj) { }
    @Override
    public void visit(Ast.Expr.Number obj) { }
    @Override
    public void visit(Ast.Expr.Str obj) { }
    @Override
    public void visit(Ast.Expr.True obj) { }
    @Override
    public void visit(Ast.Expr.False obj) { }
    @Override
    public void visit(Ast.Expr.ArrayLength obj) { }
    @Override
    public void visit(Ast.Expr.Null obj) { }
    @Override
    public void visit(Ast.Expr obj) { }
    @Override
    public void visit(Ast.Expr.T obj) {
        // Dynamic dispatch is required here: callers pass the static type
        // Expr.T, so without accept() the specific visit(Call) never runs.
        obj.accept(this);
    }

    // Statement visitors
    @Override
    public void visit(Ast.Stmt.Call obj) {
        String originalName = obj.getName();
        String rewrittenName = nameMap.get(originalName);
        if (rewrittenName != null) {
            obj.setName(rewrittenName);
        }
        if (obj.getInputParams() != null) {
            for (Ast.Expr.T arg : obj.getInputParams()) {
                visit(arg);
            }
        }
    }

    @Override
    public void visit(Ast.Stmt.Assign obj) {
        visit(obj.getExpr());
    }

    @Override
    public void visit(Ast.Stmt.ArrayAssign obj) {
        visit(obj.getIndex());
        visit(obj.getExpr());
    }

    @Override
    public void visit(Ast.Stmt.DerefAssign obj) {
        visit(obj.getTarget());
        visit(obj.getExpr());
    }

    @Override
    public void visit(Ast.Stmt.Block obj) {
        if (obj.getStmts() != null) {
            for (Ast.Stmt.T stmt : obj.getStmts()) {
                visit(stmt);
            }
        }
    }

    @Override
    public void visit(Ast.Stmt.If obj) {
        visit(obj.getCondition());
        visit(obj.getThenStmt());
        if (obj.getElseStmt() != null) {
            visit(obj.getElseStmt());
        }
    }

    @Override
    public void visit(Ast.Stmt.While obj) {
        visit(obj.getCondition());
        visit(obj.getBody());
    }

    @Override
    public void visit(Ast.Stmt.For obj) {
        if (obj.getInit() != null) visit(obj.getInit());
        if (obj.getCondition() != null) visit(obj.getCondition());
        if (obj.getUpdate() != null) visit(obj.getUpdate());
        visit(obj.getBody());
    }

    @Override
    public void visit(Ast.Stmt.Return obj) {
        if (obj.getExpr() != null) {
            visit(obj.getExpr());
        }
    }

    @Override
    public void visit(Ast.Stmt.Printf obj) {
        if (obj.getExprs() != null) {
            for (Ast.Expr.T expr : obj.getExprs()) {
                visit(expr);
            }
        }
    }

    @Override
    public void visit(Ast.Stmt.PrintLine obj) { }

    @Override
    public void visit(Ast.Stmt.Break obj) { }

    @Override
    public void visit(Ast.Stmt.Continue obj) { }

    @Override
    public void visit(Ast.Stmt.Import obj) { }

    @Override
    public void visit(Ast.Stmt.T obj) {
        // Dynamic dispatch is required here: callers pass the static type
        // Stmt.T, so without accept() the specific visitors never run.
        obj.accept(this);
    }

    // Type and other visitors (no-op for this rewriter)
    @Override
    public void visit(Ast.Type obj) { }
    @Override
    public void visit(Ast.Type.T obj) { }
    @Override
    public void visit(Ast.Type.Bool obj) { }
    @Override
    public void visit(Ast.Type.Byte obj) { }
    @Override
    public void visit(Ast.Type.Short obj) { }
    @Override
    public void visit(Ast.Type.Char obj) { }
    @Override
    public void visit(Ast.Type.Long obj) { }
    @Override
    public void visit(Ast.Type.Float obj) { }
    @Override
    public void visit(Ast.Type.Double obj) { }
    @Override
    public void visit(Ast.Type.Str obj) { }
    @Override
    public void visit(Ast.Type.Void obj) { }
    @Override
    public void visit(Ast.Type.Int obj) { }
    @Override
    public void visit(Ast.Type.IntArray obj) { }
    @Override
    public void visit(Ast.Type.ByteArray obj) { }
    @Override
    public void visit(Ast.Type.ShortArray obj) { }
    @Override
    public void visit(Ast.Type.CharArray obj) { }
    @Override
    public void visit(Ast.Type.LongArray obj) { }
    @Override
    public void visit(Ast.Type.FloatArray obj) { }
    @Override
    public void visit(Ast.Type.DoubleArray obj) { }
    @Override
    public void visit(Ast.Type.BoolArray obj) { }
    @Override
    public void visit(Ast.Type.StringArray obj) { }
    @Override
    public void visit(Ast.Type.Pointer obj) { }
    @Override
    public void visit(Ast.Type.Null obj) { }

    @Override
    public void visit(Ast.Program.T obj) { }
    @Override
    public void visit(Ast.Declare.T obj) { }
    @Override
    public void visit(Ast.MainClass.T obj) { }
    @Override
    public void visit(Ast.Method.MethodSingle obj) { }
}