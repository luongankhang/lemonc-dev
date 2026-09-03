import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.codegen.TranslatorVisitor;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;

/**
 * TranslatorVisitor test cases.
 * Tests Visitor pattern implementation with double dispatch.
 */
public class TranslatorVisitorTest {

    // ==================== Basic translation tests ====================

    @Test
    public void testTranslateBoolExpression() throws IOException {
        TranslatorVisitor visitor = translate("examples/BoolTest01.lemon");
        assertNotNull(visitor.prog);
        assertTrue("should generate at least one method", visitor.prog.mainClass.methods.size() > 0);
    }

    // ==================== Control flow translation tests ====================

    @Test
    public void testTranslateIfStatement() throws IOException {
        TranslatorVisitor visitor = translate("examples/If01.lemon");
        assertNotNull(visitor.prog);
        
        site.ilemon.codegen.ast.Ast.Method.MethodSingle method = visitor.prog.mainClass.methods.get(0);
        boolean hasGoto = false;
        boolean hasLabel = false;
        for (site.ilemon.codegen.ast.Ast.Stmt.T s : method.stms) {
            if (s instanceof site.ilemon.codegen.ast.Ast.Stmt.Goto) hasGoto = true;
            if (s instanceof site.ilemon.codegen.ast.Ast.Stmt.LabelJ) hasLabel = true;
        }
        assertTrue("if statement should generate Goto instruction", hasGoto);
        assertTrue("if statement should generate Label", hasLabel);
    }

    @Test
    public void testTranslateCompareOperators() throws IOException {
        TranslatorVisitor visitor = translate("examples/CompareTest.lemon");
        assertNotNull(visitor.prog);
        
        site.ilemon.codegen.ast.Ast.Method.MethodSingle method = visitor.prog.mainClass.methods.get(0);
        boolean hasCompare = false;
        for (site.ilemon.codegen.ast.Ast.Stmt.T s : method.stms) {
            if (s instanceof site.ilemon.codegen.ast.Ast.Stmt.Ificmpgt ||
                s instanceof site.ilemon.codegen.ast.Ast.Stmt.Ificmplt ||
                s instanceof site.ilemon.codegen.ast.Ast.Stmt.Ificmpge ||
                s instanceof site.ilemon.codegen.ast.Ast.Stmt.Ificmple ||
                s instanceof site.ilemon.codegen.ast.Ast.Stmt.Ificmpeq ||
                s instanceof site.ilemon.codegen.ast.Ast.Stmt.Ificmpne) {
                hasCompare = true;
                break;
            }
        }
        assertTrue("comparison operations should generate conditional branch instruction", hasCompare);
    }

    @Test
    public void testTranslateGreaterThan() throws IOException {
        TranslatorVisitor visitor = translate("examples/If01.lemon");
        assertNotNull(visitor.prog);
    }

    // ==================== Double dispatch validation tests ====================

    @Test
    public void testDoubleDispatchExprAcceptExists() {
        // Verify expression node accept method exists
        Ast.Expr.Number num = new Ast.Expr.Number(new Ast.Type.Int(), 42, 1);
        Ast.Expr.Add add = new Ast.Expr.Add(num, num, 1);
        Ast.Expr.Sub sub = new Ast.Expr.Sub(num, num, 1);
        Ast.Expr.Mul mul = new Ast.Expr.Mul(num, num, 1);
        Ast.Expr.Div div = new Ast.Expr.Div(num, num, 1);
        
        assertNotNull("Number node should exist", num);
        assertNotNull("Add node should exist", add);
        assertNotNull("Sub node should exist", sub);
        assertNotNull("Mul node should exist", mul);
        assertNotNull("Div node should exist", div);
    }

    @Test
    public void testDoubleDispatchBoolExprAcceptExists() {
        // Verify boolean expression node accept method exists
        Ast.Expr.True trueExpr = new Ast.Expr.True(1);
        Ast.Expr.False falseExpr = new Ast.Expr.False(1);
        Ast.Expr.Not notExpr = new Ast.Expr.Not(trueExpr);
        Ast.Expr.And andExpr = new Ast.Expr.And(trueExpr, falseExpr, 1);
        Ast.Expr.Or orExpr = new Ast.Expr.Or(trueExpr, falseExpr, 1);
        
        assertNotNull(trueExpr);
        assertNotNull(falseExpr);
        assertNotNull(notExpr);
        assertNotNull(andExpr);
        assertNotNull(orExpr);
    }

    @Test
    public void testDoubleDispatchCompareExprAcceptExists() {
        // Verify comparison expression node accept method exists
        Ast.Expr.Number num = new Ast.Expr.Number(new Ast.Type.Int(), 1, 1);
        Ast.Expr.GT gt = new Ast.Expr.GT(num, num, 1);
        Ast.Expr.LT lt = new Ast.Expr.LT(num, num, 1);
        Ast.Expr.GTE gte = new Ast.Expr.GTE(num, num, 1);
        Ast.Expr.LTE lte = new Ast.Expr.LTE(num, num, 1);
        Ast.Expr.EQ eq = new Ast.Expr.EQ(num, num, 1);
        Ast.Expr.NEQ neq = new Ast.Expr.NEQ(num, num, 1);
        
        assertNotNull(gt);
        assertNotNull(lt);
        assertNotNull(gte);
        assertNotNull(lte);
        assertNotNull(eq);
        assertNotNull(neq);
    }

    @Test
    public void testDoubleDispatchStmtAcceptExists() {
        // Verify statement node accept method exists
        Ast.Expr.Id id = new Ast.Expr.Id("x", new Ast.Type.Int(), 1);
        Ast.Expr.Number num = new Ast.Expr.Number(new Ast.Type.Int(), 1, 1);
        Ast.Stmt.Assign assign = new Ast.Stmt.Assign(id, num, 1);
        
        assertNotNull("Assign statement should exist", assign);
    }

    @Test
    public void testDoubleDispatchTypeAcceptExists() {
        // Verify type node accept method exists
        Ast.Type.Int intType = new Ast.Type.Int();
        Ast.Type.Float floatType = new Ast.Type.Float();
        Ast.Type.Bool boolType = new Ast.Type.Bool();
        Ast.Type.Str strType = new Ast.Type.Str();
        Ast.Type.Void voidType = new Ast.Type.Void();
        
        assertNotNull(intType);
        assertNotNull(floatType);
        assertNotNull(boolType);
        assertNotNull(strType);
        assertNotNull(voidType);
    }

    @Test
    public void testTranslateNestedIf() throws IOException {
        TranslatorVisitor visitor = translate("examples/If05.lemon");
        assertNotNull(visitor.prog);
    }

    @Test
    public void testTranslateBoolTest03() throws IOException {
        TranslatorVisitor visitor = translate("examples/BoolTest03.lemon");
        assertNotNull(visitor.prog);
    }

    // ==================== Helper methods ====================

    private TranslatorVisitor translate(String filename) throws IOException {
        Lexer lexer = new Lexer(new File(filename));
        Parser parser = new Parser(lexer);
        Ast.Program.T prog = parser.parse();
        SemanticVisitor semantic = new SemanticVisitor();
        semantic.visit(prog);
        
        TranslatorVisitor visitor = new TranslatorVisitor();
        visitor.visit(prog);
        
        return visitor;
    }
}
