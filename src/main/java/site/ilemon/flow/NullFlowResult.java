package site.ilemon.flow;

import site.ilemon.ast.Ast;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Carries the set of dereferences and struct-pointer field accesses that the
 * null flow analysis has proven to be safe (guaranteed non-null at runtime).
 *
 * <p>When an operation is in this set, backends can elide redundant null checks/traps.
 * When not in this set, backends emit explicit runtime checks to guarantee defined behavior.
 */
public class NullFlowResult {
    private final Set<Ast.Expr.Deref> safeDerefs = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Ast.Stmt.DerefAssign> safeDerefAssigns = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Ast.Expr.Field> safeFields = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Ast.Stmt.FieldAssign> safeFieldAssigns = Collections.newSetFromMap(new IdentityHashMap<>());

    public void markSafeDeref(Ast.Expr.Deref deref) {
        if (deref != null) {
            safeDerefs.add(deref);
        }
    }

    public void markSafeDerefAssign(Ast.Stmt.DerefAssign stmt) {
        if (stmt != null) {
            safeDerefAssigns.add(stmt);
        }
    }

    public void markSafeField(Ast.Expr.Field field) {
        if (field != null) {
            safeFields.add(field);
        }
    }

    public void markSafeFieldAssign(Ast.Stmt.FieldAssign stmt) {
        if (stmt != null) {
            safeFieldAssigns.add(stmt);
        }
    }

    public boolean isSafe(Ast.Expr.Deref deref) {
        return deref != null && safeDerefs.contains(deref);
    }

    public boolean isSafe(Ast.Stmt.DerefAssign stmt) {
        return stmt != null && safeDerefAssigns.contains(stmt);
    }

    public boolean isSafe(Ast.Expr.Field field) {
        return field != null && safeFields.contains(field);
    }

    public boolean isSafe(Ast.Stmt.FieldAssign stmt) {
        return stmt != null && safeFieldAssigns.contains(stmt);
    }

    public int totalSafeOperations() {
        return safeDerefs.size() + safeDerefAssigns.size() + safeFields.size() + safeFieldAssigns.size();
    }
}
