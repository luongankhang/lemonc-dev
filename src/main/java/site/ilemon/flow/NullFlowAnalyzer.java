package site.ilemon.flow;

import site.ilemon.ast.Ast;

import java.util.*;

/**
 * Flow-sensitive null-safety analyzer for LemonC.
 *
 * <p>Tracks nullability of pointers and references across linear code, control flow
 * branches, loops, and early returns.
 *
 * <p>Identifies guaranteed non-null dereferences (*p) and struct pointer accesses (p->x),
 * allowing backends to elide redundant runtime null checks while ensuring that all unproven
 * accesses receive deterministic runtime traps instead of undefined behavior.
 */
public class NullFlowAnalyzer {

    /**
     * Immutable environment tracking variable nullability and reachability.
     */
    public static final class NullEnv {
        private final Map<String, Nullability> vars;
        private final boolean reachable;

        public static final NullEnv EMPTY = new NullEnv(Map.of(), true);
        public static final NullEnv UNREACHABLE = new NullEnv(Map.of(), false);

        public NullEnv(Map<String, Nullability> vars, boolean reachable) {
            this.vars = vars == null ? Map.of() : Map.copyOf(vars);
            this.reachable = reachable;
        }

        public boolean isReachable() {
            return reachable;
        }

        public Nullability get(String varName) {
            if (!reachable || varName == null) {
                return Nullability.UNKNOWN;
            }
            return vars.getOrDefault(varName, Nullability.UNKNOWN);
        }

        public NullEnv set(String varName, Nullability nullability) {
            if (!reachable || varName == null || nullability == null) {
                return this;
            }
            Map<String, Nullability> copy = new HashMap<>(vars);
            copy.put(varName, nullability);
            return new NullEnv(copy, true);
        }

        public NullEnv merge(NullEnv other) {
            if (!this.reachable) {
                return other;
            }
            if (!other.reachable) {
                return this;
            }
            Map<String, Nullability> merged = new HashMap<>();
            Set<String> allKeys = new HashSet<>(this.vars.keySet());
            allKeys.addAll(other.vars.keySet());
            for (String key : allKeys) {
                Nullability n1 = this.get(key);
                Nullability n2 = other.get(key);
                merged.put(key, n1.merge(n2));
            }
            return new NullEnv(merged, true);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof NullEnv nullEnv)) return false;
            return reachable == nullEnv.reachable && vars.equals(nullEnv.vars);
        }

        @Override
        public int hashCode() {
            return Objects.hash(vars, reachable);
        }

        @Override
        public String toString() {
            return reachable ? vars.toString() : "<unreachable>";
        }
    }

    /**
     * Analyzes a single method and returns all proven-safe memory operations.
     */
    public static NullFlowResult analyze(Ast.Method.MethodSingle method) {
        NullFlowAnalyzer analyzer = new NullFlowAnalyzer();
        NullEnv env = NullEnv.EMPTY;

        // Initialize parameters: pointers default to UNKNOWN
        if (method.getFormals() != null) {
            for (Ast.Declare.T formal : method.getFormals()) {
                if (formal instanceof Ast.Declare.DeclareSingle d) {
                    env = env.set(d.getId(), Nullability.UNKNOWN);
                }
            }
        }

        if (method.getStms() != null) {
            for (Ast.Stmt.T stmt : method.getStms()) {
                if (!env.isReachable()) {
                    break;
                }
                env = analyzer.analyzeStmt(stmt, env);
            }
        }

        return analyzer.result;
    }

    private final NullFlowResult result = new NullFlowResult();

    private NullEnv analyzeStmt(Ast.Stmt.T stmt, NullEnv env) {
        if (stmt == null || !env.isReachable()) {
            return env;
        }

        if (stmt instanceof Ast.Stmt.VarDecl varDecl) {
            if (varDecl.getDeclaration() instanceof Ast.Declare.DeclareSingle d) {
                if (d.getInitExp() != null) {
                    env = analyzeExpr(d.getInitExp(), env);
                    Nullability n = evalExprNullability(d.getInitExp(), env);
                    env = env.set(d.getId(), n);
                } else {
                    env = env.set(d.getId(), Nullability.UNKNOWN);
                }
            }
            return env;
        }

        if (stmt instanceof Ast.Stmt.Assign assign) {
            env = analyzeExpr(assign.getExpr(), env);
            Nullability n = evalExprNullability(assign.getExpr(), env);
            env = env.set(assign.getId() != null ? assign.getId().getId() : null, n);
            return env;
        }

        if (stmt instanceof Ast.Stmt.DerefAssign derefAssign) {
            Ast.Expr.Deref target = derefAssign.getTarget();
            if (target.getOperand() instanceof Ast.Expr.Id id) {
                if (env.get(id.getId()) == Nullability.NON_NULL) {
                    result.markSafeDerefAssign(derefAssign);
                } else {
                    // Following execution of this statement (which traps on null),
                    // the pointer is guaranteed non-null in subsequent code.
                    env = env.set(id.getId(), Nullability.NON_NULL);
                }
            } else {
                env = analyzeExpr(target, env);
            }
            env = analyzeExpr(derefAssign.getExpr(), env);
            return env;
        }

        if (stmt instanceof Ast.Stmt.FieldAssign fieldAssign) {
            Ast.Expr.Field target = fieldAssign.getTarget();
            if (target.isPointerBase() && target.getReceiver() instanceof Ast.Expr.Id id) {
                if (env.get(id.getId()) == Nullability.NON_NULL) {
                    result.markSafeFieldAssign(fieldAssign);
                } else {
                    env = env.set(id.getId(), Nullability.NON_NULL);
                }
            } else {
                env = analyzeExpr(target.getReceiver(), env);
            }
            env = analyzeExpr(fieldAssign.getExpr(), env);
            return env;
        }

        if (stmt instanceof Ast.Stmt.ArrayAssign arrayAssign) {
            if (arrayAssign.getFieldTarget() != null) {
                env = analyzeExpr(arrayAssign.getFieldTarget(), env);
            }
            env = analyzeExpr(arrayAssign.getIndex(), env);
            env = analyzeExpr(arrayAssign.getExpr(), env);
            return env;
        }

        if (stmt instanceof Ast.Stmt.Block block) {
            if (block.getStmts() != null) {
                for (Ast.Stmt.T s : block.getStmts()) {
                    if (!env.isReachable()) break;
                    env = analyzeStmt(s, env);
                }
            }
            return env;
        }

        if (stmt instanceof Ast.Stmt.If ifStmt) {
            env = analyzeExpr(ifStmt.getCondition(), env);
            NullEnv thenEntry = applyConditionFacts(ifStmt.getCondition(), true, env);
            NullEnv elseEntry = applyConditionFacts(ifStmt.getCondition(), false, env);

            NullEnv thenExit = analyzeStmt(ifStmt.getThenStmt(), thenEntry);
            NullEnv elseExit = ifStmt.getElseStmt() != null
                    ? analyzeStmt(ifStmt.getElseStmt(), elseEntry)
                    : elseEntry;

            boolean thenTerminates = statementTerminates(ifStmt.getThenStmt());
            boolean elseTerminates = ifStmt.getElseStmt() != null && statementTerminates(ifStmt.getElseStmt());

            if (thenTerminates && elseTerminates) {
                return NullEnv.UNREACHABLE;
            }
            if (thenTerminates) {
                return elseExit;
            }
            if (elseTerminates) {
                return thenExit;
            }
            return thenExit.merge(elseExit);
        }

        if (stmt instanceof Ast.Stmt.While whileStmt) {
            NullEnv currentLoopEnv = env;
            // Iterate to find a stable fixpoint
            for (int i = 0; i < 3; i++) {
                NullEnv condEnv = analyzeExpr(whileStmt.getCondition(), currentLoopEnv);
                NullEnv bodyEntry = applyConditionFacts(whileStmt.getCondition(), true, condEnv);
                NullEnv bodyExit = analyzeStmt(whileStmt.getBody(), bodyEntry);
                NullEnv nextLoopEnv = currentLoopEnv.merge(bodyExit);
                if (nextLoopEnv.equals(currentLoopEnv)) {
                    break;
                }
                currentLoopEnv = nextLoopEnv;
            }
            NullEnv condEnv = analyzeExpr(whileStmt.getCondition(), currentLoopEnv);
            return applyConditionFacts(whileStmt.getCondition(), false, condEnv);
        }

        if (stmt instanceof Ast.Stmt.For forStmt) {
            if (forStmt.getInit() != null) {
                env = analyzeStmt(forStmt.getInit(), env);
            }
            NullEnv currentLoopEnv = env;
            for (int i = 0; i < 3; i++) {
                NullEnv condEnv = forStmt.getCondition() != null
                        ? applyConditionFacts(forStmt.getCondition(), true, analyzeExpr(forStmt.getCondition(), currentLoopEnv))
                        : currentLoopEnv;
                NullEnv bodyExit = analyzeStmt(forStmt.getBody(), condEnv);
                if (forStmt.getUpdate() != null) {
                    bodyExit = analyzeStmt(forStmt.getUpdate(), bodyExit);
                }
                NullEnv nextLoopEnv = currentLoopEnv.merge(bodyExit);
                if (nextLoopEnv.equals(currentLoopEnv)) {
                    break;
                }
                currentLoopEnv = nextLoopEnv;
            }
            if (forStmt.getCondition() != null) {
                return applyConditionFacts(forStmt.getCondition(), false, currentLoopEnv);
            }
            return currentLoopEnv;
        }

        if (stmt instanceof Ast.Stmt.Return retStmt) {
            if (retStmt.getExpr() != null) {
                analyzeExpr(retStmt.getExpr(), env);
            }
            return NullEnv.UNREACHABLE;
        }

        if (stmt instanceof Ast.Stmt.Break || stmt instanceof Ast.Stmt.Continue) {
            return NullEnv.UNREACHABLE;
        }

        if (stmt instanceof Ast.Stmt.Switch switchStmt) {
            NullEnv subjectEnv = switchStmt.getSubject() != null
                    ? analyzeExpr(switchStmt.getSubject(), env)
                    : env;
            if (switchStmt.getClauses() == null || switchStmt.getClauses().isEmpty()) {
                return subjectEnv;
            }
            // Case exits merge (a case without break falls through or reaches
            // the switch exit normally); a switch without default must also
            // merge the subject-exit path.
            NullEnv merged = subjectEnv;
            boolean seenDefault = false;
            for (Ast.Stmt.CaseClause clause : switchStmt.getClauses()) {
                if (clause.isDefault()) {
                    seenDefault = true;
                }
                NullEnv clauseExit = subjectEnv;
                if (clause.getBody() != null) {
                    for (Ast.Stmt.T s : clause.getBody()) {
                        clauseExit = analyzeStmt(s, clauseExit);
                    }
                }
                merged = merged.merge(clauseExit);
            }
            if (!seenDefault) {
                return merged;
            }
            return merged;
        }

        if (stmt instanceof Ast.Stmt.Call call) {
            if (call.getInputParams() != null) {
                for (Ast.Expr.T param : call.getInputParams()) {
                    env = analyzeExpr(param, env);
                }
            }
            return env;
        }

        if (stmt instanceof Ast.Stmt.Printf printf) {
            if (printf.getExprs() != null) {
                for (Ast.Expr.T param : printf.getExprs()) {
                    env = analyzeExpr(param, env);
                }
            }
            return env;
        }

        if (stmt instanceof Ast.Stmt.ExprStmt exprStmt) {
            if (exprStmt.getExpr() != null) {
                env = analyzeExpr(exprStmt.getExpr(), env);
            }
            return env;
        }

        return env;
    }

    private NullEnv analyzeExpr(Ast.Expr.T expr, NullEnv env) {
        if (expr == null || !env.isReachable()) {
            return env;
        }

        if (expr instanceof Ast.Expr.Deref deref) {
            if (deref.getOperand() instanceof Ast.Expr.Id id) {
                if (env.get(id.getId()) == Nullability.NON_NULL) {
                    result.markSafeDeref(deref);
                } else {
                    env = env.set(id.getId(), Nullability.NON_NULL);
                }
            } else {
                env = analyzeExpr(deref.getOperand(), env);
            }
            return env;
        }

        if (expr instanceof Ast.Expr.Field field) {
            if (field.isPointerBase() && field.getReceiver() instanceof Ast.Expr.Id id) {
                if (env.get(id.getId()) == Nullability.NON_NULL) {
                    result.markSafeField(field);
                } else {
                    env = env.set(id.getId(), Nullability.NON_NULL);
                }
            } else {
                env = analyzeExpr(field.getReceiver(), env);
            }
            return env;
        }

        if (expr instanceof Ast.Expr.ArrayAccess arrayAccess) {
            if (arrayAccess.getFieldTarget() != null) {
                env = analyzeExpr(arrayAccess.getFieldTarget(), env);
            }
            env = analyzeExpr(arrayAccess.getIndex(), env);
            return env;
        }

        if (expr instanceof Ast.Expr.And and) {
            // Short-circuit: left is analyzed, then facts of left=true refine right
            env = analyzeExpr(and.getLeft(), env);
            NullEnv rightEnv = applyConditionFacts(and.getLeft(), true, env);
            rightEnv = analyzeExpr(and.getRight(), rightEnv);
            return env.merge(rightEnv);
        }

        if (expr instanceof Ast.Expr.Or or) {
            env = analyzeExpr(or.getLeft(), env);
            NullEnv rightEnv = applyConditionFacts(or.getLeft(), false, env);
            rightEnv = analyzeExpr(or.getRight(), rightEnv);
            return env.merge(rightEnv);
        }

        if (expr instanceof Ast.Expr.Not not) {
            return analyzeExpr(not.getExpr(), env);
        }

        if (expr instanceof Ast.Expr.Ternary ternary) {
            env = analyzeExpr(ternary.getCondition(), env);
            NullEnv trueEnv = applyConditionFacts(ternary.getCondition(), true, env);
            NullEnv falseEnv = applyConditionFacts(ternary.getCondition(), false, env);
            NullEnv trueExit = analyzeExpr(ternary.getTrueExpr(), trueEnv);
            NullEnv falseExit = analyzeExpr(ternary.getFalseExpr(), falseEnv);
            return trueExit.merge(falseExit);
        }

        if (expr instanceof Ast.Expr.Call call) {
            if (call.getInputParams() != null) {
                for (Ast.Expr.T p : call.getInputParams()) {
                    env = analyzeExpr(p, env);
                }
            }
            return env;
        }

        if (expr instanceof Ast.Expr.InitializerList initList) {
            if (initList.getElements() != null) {
                for (Ast.Expr.T elem : initList.getElements()) {
                    env = analyzeExpr(elem, env);
                }
            }
            return env;
        }

        if (expr instanceof Ast.Expr.Add bin) {
            return analyzeExpr(bin.getRight(), analyzeExpr(bin.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.Sub bin) {
            return analyzeExpr(bin.getRight(), analyzeExpr(bin.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.Mul bin) {
            return analyzeExpr(bin.getRight(), analyzeExpr(bin.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.Div bin) {
            return analyzeExpr(bin.getRight(), analyzeExpr(bin.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.Mod bin) {
            return analyzeExpr(bin.getRight(), analyzeExpr(bin.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.EQ cmp) {
            return analyzeExpr(cmp.getRight(), analyzeExpr(cmp.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.NEQ cmp) {
            return analyzeExpr(cmp.getRight(), analyzeExpr(cmp.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.LT cmp) {
            return analyzeExpr(cmp.getRight(), analyzeExpr(cmp.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.GT cmp) {
            return analyzeExpr(cmp.getRight(), analyzeExpr(cmp.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.LTE cmp) {
            return analyzeExpr(cmp.getRight(), analyzeExpr(cmp.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.GTE cmp) {
            return analyzeExpr(cmp.getRight(), analyzeExpr(cmp.getLeft(), env));
        }
        if (expr instanceof Ast.Expr.PreInc inc) {
            return analyzeExpr(inc.getExp(), env);
        }
        if (expr instanceof Ast.Expr.PostInc inc) {
            return analyzeExpr(inc.getExp(), env);
        }
        if (expr instanceof Ast.Expr.PreDec dec) {
            return analyzeExpr(dec.getExp(), env);
        }
        if (expr instanceof Ast.Expr.PostDec dec) {
            return analyzeExpr(dec.getExp(), env);
        }
        if (expr instanceof Ast.Expr.UnaryMinus u) {
            return analyzeExpr(u.getExp(), env);
        }
        if (expr instanceof Ast.Expr.UnaryPlus u) {
            return analyzeExpr(u.getExp(), env);
        }
        if (expr instanceof Ast.Expr.BitNot u) {
            return analyzeExpr(u.getExp(), env);
        }

        return env;
    }

    private Nullability evalExprNullability(Ast.Expr.T expr, NullEnv env) {
        if (expr instanceof Ast.Expr.Null) {
            return Nullability.NULL;
        }
        if (expr instanceof Ast.Expr.AddressOf) {
            return Nullability.NON_NULL;
        }
        if (expr instanceof Ast.Expr.Id id) {
            return env.get(id.getId());
        }
        return Nullability.UNKNOWN;
    }

    /**
     * Extracts and applies facts deduced from a boolean condition expression.
     */
    private NullEnv applyConditionFacts(Ast.Expr.T cond, boolean branch, NullEnv env) {
        if (cond == null || !env.isReachable()) {
            return env;
        }

        if (cond instanceof Ast.Expr.NEQ neq) {
            // p != null
            String var = nullVarComparison(neq.getLeft(), neq.getRight());
            if (var != null) {
                return env.set(var, branch ? Nullability.NON_NULL : Nullability.NULL);
            }
            // p != q where one is known null
            if (neq.getLeft() instanceof Ast.Expr.Id l && neq.getRight() instanceof Ast.Expr.Id r) {
                if (branch) {
                    if (env.get(l.getId()) == Nullability.NULL) {
                        env = env.set(r.getId(), Nullability.NON_NULL);
                    }
                    if (env.get(r.getId()) == Nullability.NULL) {
                        env = env.set(l.getId(), Nullability.NON_NULL);
                    }
                }
            }
            return env;
        }

        if (cond instanceof Ast.Expr.EQ eq) {
            // p == null
            String var = nullVarComparison(eq.getLeft(), eq.getRight());
            if (var != null) {
                return env.set(var, branch ? Nullability.NULL : Nullability.NON_NULL);
            }
            if (eq.getLeft() instanceof Ast.Expr.Id l && eq.getRight() instanceof Ast.Expr.Id r) {
                if (branch) {
                    if (env.get(l.getId()) == Nullability.NON_NULL) {
                        env = env.set(r.getId(), Nullability.NON_NULL);
                    }
                    if (env.get(r.getId()) == Nullability.NON_NULL) {
                        env = env.set(l.getId(), Nullability.NON_NULL);
                    }
                    if (env.get(l.getId()) == Nullability.NULL) {
                        env = env.set(r.getId(), Nullability.NULL);
                    }
                    if (env.get(r.getId()) == Nullability.NULL) {
                        env = env.set(l.getId(), Nullability.NULL);
                    }
                }
            }
            return env;
        }

        if (cond instanceof Ast.Expr.Not not) {
            return applyConditionFacts(not.getExpr(), !branch, env);
        }

        if (cond instanceof Ast.Expr.And and) {
            if (branch) {
                NullEnv e1 = applyConditionFacts(and.getLeft(), true, env);
                return applyConditionFacts(and.getRight(), true, e1);
            } else {
                NullEnv eFalseLeft = applyConditionFacts(and.getLeft(), false, env);
                NullEnv eTrueLeft = applyConditionFacts(and.getLeft(), true, env);
                NullEnv eFalseRight = applyConditionFacts(and.getRight(), false, eTrueLeft);
                return eFalseLeft.merge(eFalseRight);
            }
        }

        if (cond instanceof Ast.Expr.Or or) {
            if (!branch) {
                NullEnv e1 = applyConditionFacts(or.getLeft(), false, env);
                return applyConditionFacts(or.getRight(), false, e1);
            } else {
                NullEnv eTrueLeft = applyConditionFacts(or.getLeft(), true, env);
                NullEnv eFalseLeft = applyConditionFacts(or.getLeft(), false, env);
                NullEnv eTrueRight = applyConditionFacts(or.getRight(), true, eFalseLeft);
                return eTrueLeft.merge(eTrueRight);
            }
        }

        return env;
    }

    private static String nullVarComparison(Ast.Expr.T left, Ast.Expr.T right) {
        if (left instanceof Ast.Expr.Id id && right instanceof Ast.Expr.Null) {
            return id.getId();
        }
        if (left instanceof Ast.Expr.Null && right instanceof Ast.Expr.Id id) {
            return id.getId();
        }
        return null;
    }

    private static boolean statementTerminates(Ast.Stmt.T stmt) {
        if (stmt == null) {
            return false;
        }
        if (stmt instanceof Ast.Stmt.Return || stmt instanceof Ast.Stmt.Break || stmt instanceof Ast.Stmt.Continue) {
            return true;
        }
        if (stmt instanceof Ast.Stmt.Block block) {
            if (block.getStmts() == null || block.getStmts().isEmpty()) {
                return false;
            }
            for (Ast.Stmt.T s : block.getStmts()) {
                if (statementTerminates(s)) {
                    return true;
                }
            }
            return false;
        }
        if (stmt instanceof Ast.Stmt.If ifStmt) {
            if (ifStmt.getElseStmt() == null) {
                return false;
            }
            return statementTerminates(ifStmt.getThenStmt()) && statementTerminates(ifStmt.getElseStmt());
        }
        return false;
    }
}
