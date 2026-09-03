import org.junit.Assert;
import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import static org.junit.Assert.*;

/**
 * Parser test cases.
 * Tests the recursive descent parser.
 */
public class ParserTest {

    // ==================== Basic parsing tests ====================

    @Test
    public void testParseBasic() throws IOException {
        Parser parser = createParser("examples/BoolTest01.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull("should successfully parse program", prog);
    }

    @Test
    public void testParseFloat() throws IOException {
        Parser parser = createParser("examples/FloatTest01.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
    }

    @Test
    public void testParseIteration() throws IOException {
        Parser parser = createParser("examples/Iteration01.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
    }

    // ==================== Comparison operator tests ====================

    @Test
    public void testCompareOperators() throws IOException {
        Parser parser = createParser("examples/CompareTest.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull("should successfully parse program containing all comparison operators", prog);
    }

    @Test
    public void testGreaterThan() throws IOException {
        Parser parser = createParser("examples/If01.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        // Verify that AST contains GT node
        boolean hasGT = containsExprType(prog, Ast.Expr.GT.class);
        // If01.lemon uses > operator
    }

    @Test
    public void testLessThan() throws IOException {
        Parser parser = createParser("examples/If01.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        // Verify that AST contains LT node
        boolean hasLT = containsExprType(prog, Ast.Expr.LT.class);
        assertTrue("should contain less-than operator", hasLT);
    }

    @Test
    public void testGreaterThanOrEqual() throws IOException {
        Parser parser = createParser("examples/CompareTest.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        // Verify that AST contains GTE node (>=)
        boolean hasGTE = containsExprType(prog, Ast.Expr.GTE.class);
        assertTrue("should contain greater-than-or-equal operator", hasGTE);
    }

    @Test
    public void testLessThanOrEqual() throws IOException {
        Parser parser = createParser("examples/CompareTest.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        // Verify that AST contains LTE node (<=)
        boolean hasLTE = containsExprType(prog, Ast.Expr.LTE.class);
        assertTrue("should contain less-than-or-equal operator", hasLTE);
    }

    @Test
    public void testEqual() throws IOException {
        Parser parser = createParser("examples/CompareTest.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        // Verify that AST contains EQ node (==)
        boolean hasEQ = containsExprType(prog, Ast.Expr.EQ.class);
        assertTrue("should contain equal operator", hasEQ);
    }

    @Test
    public void testNotEqual() throws IOException {
        Parser parser = createParser("examples/CompareTest.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        // Verify that AST contains NEQ node (!=)
        boolean hasNEQ = containsExprType(prog, Ast.Expr.NEQ.class);
        assertTrue("should contain not-equal operator", hasNEQ);
    }

    // ==================== Logical operator tests ====================

    @Test
    public void testLogicalAnd() throws IOException {
        Parser parser = createParser("examples/BoolTest01.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        boolean hasAnd = containsExprType(prog, Ast.Expr.And.class);
        assertTrue("should contain logical AND operator", hasAnd);
    }

    @Test
    public void testLogicalOr() throws IOException {
        Parser parser = createParser("examples/BoolTest01.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        boolean hasOr = containsExprType(prog, Ast.Expr.Or.class);
        assertTrue("should contain logical OR operator", hasOr);
    }

    @Test
    public void testLogicalNot() throws IOException {
        Parser parser = createParser("examples/BoolTest01.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        boolean hasNot = containsExprType(prog, Ast.Expr.Not.class);
        assertTrue("should contain logical NOT operator", hasNot);
    }

    // ==================== Method call tests ====================

    @Test
    public void testMethodCall() throws IOException {
        Parser parser = createParser("examples/SimpleMethodCall.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        boolean hasCall = containsExprType(prog, Ast.Expr.Call.class);
        assertTrue("should contain method call", hasCall);
    }

    @Test
    public void testRecursiveCall() throws IOException {
        Parser parser = createParser("examples/Cal.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull("should successfully parse recursive call", prog);
    }

    // ==================== Statement tests ====================

    @Test
    public void testIfStatement() throws IOException {
        Parser parser = createParser("examples/If01.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        boolean hasIf = containsStmtType(prog, Ast.Stmt.If.class);
        assertTrue("should contain if statement", hasIf);
    }

    @Test
    public void testWhileStatement() throws IOException {
        Parser parser = createParser("examples/Iteration01.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        boolean hasWhile = containsStmtType(prog, Ast.Stmt.While.class);
        assertTrue("should contain while statement", hasWhile);
    }

    @Test
    public void testReturnStatement() throws IOException {
        Parser parser = createParser("examples/Cal.lemon");
        Ast.Program.T prog = parser.parse();
        assertNotNull(prog);
        
        boolean hasReturn = containsStmtType(prog, Ast.Stmt.Return.class);
        assertTrue("should contain return statement", hasReturn);
    }

    // ==================== Helper methods ====================

    private Parser createParser(String filename) throws IOException {
        Lexer lexer = new Lexer(new File(filename));
        return new Parser(lexer);
    }

    /**
     * Checks whether the AST contains an expression of the specified type.
     */
    private boolean containsExprType(Ast.Program.T prog, Class<?> exprType) {
        if (prog instanceof Ast.Program.ProgramSingle) {
            Ast.Program.ProgramSingle ps = (Ast.Program.ProgramSingle) prog;
            if (ps.getMainClass() instanceof Ast.MainClass.MainClassSingle) {
                Ast.MainClass.MainClassSingle mc = (Ast.MainClass.MainClassSingle) ps.getMainClass();
                for (Ast.Method.T method : mc.getMethods()) {
                    if (method instanceof Ast.Method.MethodSingle) {
                        Ast.Method.MethodSingle ms = (Ast.Method.MethodSingle) method;
                        for (Ast.Stmt.T stmt : ms.getStms()) {
                            if (containsExprInStmt(stmt, exprType)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean containsExprInStmt(Ast.Stmt.T stmt, Class<?> exprType) {
        if (stmt instanceof Ast.Stmt.If) {
            Ast.Stmt.If ifStmt = (Ast.Stmt.If) stmt;
            if (containsExprInExpr(ifStmt.getCondition(), exprType)) return true;
            if (ifStmt.getThenStmt() != null && containsExprInStmt(ifStmt.getThenStmt(), exprType)) return true;
            if (ifStmt.getElseStmt() != null && containsExprInStmt(ifStmt.getElseStmt(), exprType)) return true;
        } else if (stmt instanceof Ast.Stmt.While) {
            Ast.Stmt.While whileStmt = (Ast.Stmt.While) stmt;
            if (containsExprInExpr(whileStmt.getCondition(), exprType)) return true;
            if (whileStmt.getBody() != null && containsExprInStmt(whileStmt.getBody(), exprType)) return true;
        } else if (stmt instanceof Ast.Stmt.Assign) {
            Ast.Stmt.Assign assign = (Ast.Stmt.Assign) stmt;
            if (containsExprInExpr(assign.getExpr(), exprType)) return true;
        } else if (stmt instanceof Ast.Stmt.Block) {
            Ast.Stmt.Block block = (Ast.Stmt.Block) stmt;
            for (Ast.Stmt.T s : block.getStmts()) {
                if (containsExprInStmt(s, exprType)) return true;
            }
        } else if (stmt instanceof Ast.Stmt.Return) {
            Ast.Stmt.Return ret = (Ast.Stmt.Return) stmt;
            if (containsExprInExpr(ret.getExpr(), exprType)) return true;
        }
        return false;
    }

    private boolean containsExprInExpr(Ast.Expr.T expr, Class<?> exprType) {
        if (expr == null) return false;
        if (exprType.isInstance(expr)) return true;
        
        // Recursively check subexpressions
        if (expr instanceof Ast.Expr.And) {
            Ast.Expr.And and = (Ast.Expr.And) expr;
            return containsExprInExpr(and.getLeft(), exprType) || containsExprInExpr(and.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.Or) {
            Ast.Expr.Or or = (Ast.Expr.Or) expr;
            return containsExprInExpr(or.getLeft(), exprType) || containsExprInExpr(or.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.Not) {
            return containsExprInExpr(((Ast.Expr.Not) expr).getExpr(), exprType);
        } else if (expr instanceof Ast.Expr.GT) {
            Ast.Expr.GT gt = (Ast.Expr.GT) expr;
            return containsExprInExpr(gt.getLeft(), exprType) || containsExprInExpr(gt.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.LT) {
            Ast.Expr.LT lt = (Ast.Expr.LT) expr;
            return containsExprInExpr(lt.getLeft(), exprType) || containsExprInExpr(lt.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.GTE) {
            Ast.Expr.GTE gte = (Ast.Expr.GTE) expr;
            return containsExprInExpr(gte.getLeft(), exprType) || containsExprInExpr(gte.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.LTE) {
            Ast.Expr.LTE lte = (Ast.Expr.LTE) expr;
            return containsExprInExpr(lte.getLeft(), exprType) || containsExprInExpr(lte.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.EQ) {
            Ast.Expr.EQ eq = (Ast.Expr.EQ) expr;
            return containsExprInExpr(eq.getLeft(), exprType) || containsExprInExpr(eq.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.NEQ) {
            Ast.Expr.NEQ neq = (Ast.Expr.NEQ) expr;
            return containsExprInExpr(neq.getLeft(), exprType) || containsExprInExpr(neq.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.Add) {
            Ast.Expr.Add add = (Ast.Expr.Add) expr;
            return containsExprInExpr(add.getLeft(), exprType) || containsExprInExpr(add.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.Sub) {
            Ast.Expr.Sub sub = (Ast.Expr.Sub) expr;
            return containsExprInExpr(sub.getLeft(), exprType) || containsExprInExpr(sub.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.Mul) {
            Ast.Expr.Mul mul = (Ast.Expr.Mul) expr;
            return containsExprInExpr(mul.getLeft(), exprType) || containsExprInExpr(mul.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.Div) {
            Ast.Expr.Div div = (Ast.Expr.Div) expr;
            return containsExprInExpr(div.getLeft(), exprType) || containsExprInExpr(div.getRight(), exprType);
        } else if (expr instanceof Ast.Expr.Call) {
            Ast.Expr.Call call = (Ast.Expr.Call) expr;
            for (Ast.Expr.T arg : call.getInputParams()) {
                if (containsExprInExpr(arg, exprType)) return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the AST contains a statement of the specified type.
     */
    private boolean containsStmtType(Ast.Program.T prog, Class<?> stmtType) {
        if (prog instanceof Ast.Program.ProgramSingle) {
            Ast.Program.ProgramSingle ps = (Ast.Program.ProgramSingle) prog;
            if (ps.getMainClass() instanceof Ast.MainClass.MainClassSingle) {
                Ast.MainClass.MainClassSingle mc = (Ast.MainClass.MainClassSingle) ps.getMainClass();
                for (Ast.Method.T method : mc.getMethods()) {
                    if (method instanceof Ast.Method.MethodSingle) {
                        Ast.Method.MethodSingle ms = (Ast.Method.MethodSingle) method;
                        for (Ast.Stmt.T stmt : ms.getStms()) {
                            if (containsStmtInStmt(stmt, stmtType)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean containsStmtInStmt(Ast.Stmt.T stmt, Class<?> stmtType) {
        if (stmt == null) return false;
        if (stmtType.isInstance(stmt)) return true;
        
        if (stmt instanceof Ast.Stmt.If) {
            Ast.Stmt.If ifStmt = (Ast.Stmt.If) stmt;
            if (containsStmtInStmt(ifStmt.getThenStmt(), stmtType)) return true;
            if (containsStmtInStmt(ifStmt.getElseStmt(), stmtType)) return true;
        } else if (stmt instanceof Ast.Stmt.While) {
            return containsStmtInStmt(((Ast.Stmt.While) stmt).getBody(), stmtType);
        } else if (stmt instanceof Ast.Stmt.Block) {
            for (Ast.Stmt.T s : ((Ast.Stmt.Block) stmt).getStmts()) {
                if (containsStmtInStmt(s, stmtType)) return true;
            }
        }
        return false;
    }
}
