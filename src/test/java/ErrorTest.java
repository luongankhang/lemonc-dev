import org.junit.Before;
import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.codegen.ast.Label;
import site.ilemon.exception.ParseException;
import site.ilemon.exception.SemanticException;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Negative tests - verify that the compiler properly reports errors on invalid input.
 * Each test verifies a specific error scenario.
 */
public class ErrorTest {

    @Before
    public void setUp() {
        Label.resetCounter();
    }

    // ===== Semantic errors =====

    @Test(expected = SemanticException.class)
    public void testUndeclaredVariable() throws IOException {
        // Using an undeclared variable should report a semantic error
        compileSource("void main() { x = 1; }");
    }

    @Test(expected = SemanticException.class)
    public void testDuplicateMethod() throws IOException {
        // Duplicate method definition should report a semantic error
        compileSource("void main() {} void main() {}");
    }

    @Test(expected = SemanticException.class)
    public void testWrongArgCount() throws IOException {
        // Method argument count mismatch should report a semantic error
        compileSource("void main() { foo(1, 2); } int foo(int a) { return a; }");
    }

    @Test(expected = SemanticException.class)
    public void testTypeMismatchInAssign() throws IOException {
        // Assigning bool to int should report a semantic error
        compileSource("void main() { int x; x = true; }");
    }

    @Test(expected = SemanticException.class)
    public void testNonBoolCondition() throws IOException {
        // Non-bool if condition should report a semantic error
        compileSource("void main() { int x; x = 1; if(x) { } }");
    }

    @Test(expected = SemanticException.class)
    public void testWhileNonBoolCondition() throws IOException {
        // Non-bool while condition should report a semantic error
        compileSource("void main() { int x; x = 1; while(x) { x = 0; } }");
    }

    @Test(expected = SemanticException.class)
    public void testMainReturnTypeNotVoid() throws IOException {
        // Main method non-void return type should report a semantic error
        compileSource("int main() { return 0; }");
    }

    @Test(expected = SemanticException.class)
    public void testMainMethodIsRequired() throws IOException {
        compileSource("int foo() { return 1; }");
    }

    @Test(expected = SemanticException.class)
    public void testMainMethodCannotHaveParameters() throws IOException {
        compileSource("void main(int x) {}");
    }

    @Test(expected = SemanticException.class)
    public void testReturnTypeMismatch() throws IOException {
        // Return value type mismatch should report a semantic error
        compileSource("void main() { int x; x = foo(); } int foo() { return true; }");
    }

    @Test(expected = SemanticException.class)
    public void testUseBeforeAssign() throws IOException {
        // Local variable used before assignment should report a semantic error
        compileSource("void main() { int x; int y; y = x; }");
    }

    @Test(expected = SemanticException.class)
    public void testIfSingleBranchAssignDoesNotGuaranteeAssignment() throws IOException {
        compileSource("void main() { int x; int y; y = 1; if (y > 0) { x = 1; } printf(\"x=%d\\n\", x); }");
    }

    @Test
    public void testIfElseAssignGuaranteesAssignment() throws IOException {
        compileSource("void main() { int x; int y; y = 1; if (y > 0) { x = 1; } else { x = 2; } printf(\"x=%d\\n\", x); }");
    }

    @Test(expected = SemanticException.class)
    public void testWhileBodyAssignDoesNotGuaranteeAssignment() throws IOException {
        compileSource("void main() { int x; int y; y = 0; while (y < 1) { x = 1; y = y + 1; } printf(\"x=%d\\n\", x); }");
    }

    @Test(expected = SemanticException.class)
    public void testAndOperatorNonBool() throws IOException {
        // && operator operands must be bool
        compileSource("void main() { int x; int y; x = 1; y = 2; if(x && y) {} }");
    }

    @Test(expected = SemanticException.class)
    public void testOrOperatorNonBool() throws IOException {
        // || operator operands must be bool
        compileSource("void main() { int x; int y; x = 1; y = 2; if(x || y) {} }");
    }

    @Test(expected = SemanticException.class)
    public void testCompareTypeMismatch() throws IOException {
        // Numeric types can widen; non-numeric types in ordering comparisons should report semantic error
        compileSource("void main() { int x; bool y; x = 1; y = true; if(x > y) {} }");
    }

    @Test(expected = SemanticException.class)
    public void testModRequiresIntOperands() throws IOException {
        compileSource("void main() { float f; int x; f = 2.0; x = 5 % f; }");
    }

    @Test(expected = SemanticException.class)
    public void testLengthRequiresArray() throws IOException {
        compileSource("void main() { int x; int y; x = 1; y = x.length; }");
    }

    @Test(expected = ParseException.class)
    public void testOnlyLengthArrayPropertyIsSupported() throws IOException {
        compileSource("void main() { int arr[3]; int y; y = arr.size; }");
    }

    @Test(expected = SemanticException.class)
    public void testArrayIndexMustBeIntInAssignment() throws IOException {
        compileSource("void main() { int arr[3]; bool b; b = true; arr[b] = 1; }");
    }

    @Test(expected = SemanticException.class)
    public void testArrayIndexCheckDoesNotUseStaleCurrType() throws IOException {
        compileSource("void main() { int arr[3]; bool b; int x; b = true; x = 1; arr[b] = 1; }");
    }

    @Test(expected = SemanticException.class)
    public void testArrayIndexMustBeIntInAccess() throws IOException {
        compileSource("void main() { int arr[3]; float f; int x; f = 1.0; x = arr[f]; }");
    }

    @Test(expected = SemanticException.class)
    public void testArrayAssignmentElementTypeMustMatch() throws IOException {
        compileSource("void main() { int arr[3]; float f; f = 1.0; arr[0] = f; }");
    }

    @Test(expected = SemanticException.class)
    public void testArrayAssignmentValueCheckDoesNotUseStaleCurrType() throws IOException {
        compileSource("void main() { int arr[3]; float f; int x; f = 1.0; x = 1; arr[0] = f; }");
    }

    @Test(expected = SemanticException.class)
    public void testScalarCannotBeAssignedWithArraySyntax() throws IOException {
        compileSource("void main() { int x; x = 1; x[0] = 2; }");
    }

    // ===== Syntax errors =====

    @Test(expected = SemanticException.class)
    public void testArrayArgumentElementTypeMustMatch() throws IOException {
        compileSource("void main() { float arr[3]; f(arr); } void f(int arr[]) {}");
    }

    @Test(expected = SemanticException.class)
    public void testArrayArgumentCannotBeScalar() throws IOException {
        compileSource("void main() { int x; x = 1; f(x); } void f(int arr[]) {}");
    }

    @Test(expected = SemanticException.class)
    public void testScalarArgumentCannotBeArray() throws IOException {
        compileSource("void main() { int arr[3]; f(arr); } void f(int x) {}");
    }

    @Test(expected = ParseException.class)
    public void testArrayParameterCannotDeclareFixedSize() throws IOException {
        compileSource("void main() {} void f(int arr[3]) {}");
    }

    @Test(expected = SemanticException.class)
    public void testArrayWholeAssignmentIsRejected() throws IOException {
        compileSource("void main() { int a[3]; int b[3]; a = b; }");
    }

    @Test(expected = SemanticException.class)
    public void testArrayComparisonIsRejected() throws IOException {
        compileSource("void main() { int a[3]; int b[3]; if (a == b) {} }");
    }

    @Test(expected = SemanticException.class)
    public void testNonVoidMethodMustReturnOnAllPaths() throws IOException {
        compileSource("void main() {} int f(int x) { if (x > 0) { return 1; } }");
    }

    @Test
    public void testNonVoidIfElseBothReturnPasses() throws IOException {
        compileSource("void main() { int x; x = f(1); } int f(int x) { if (x > 0) { return 1; } else { return 2; } }");
    }

    @Test
    public void testBlockReturnSatisfiesNonVoidMethod() throws IOException {
        compileSource("void main() { int x; x = f(); } int f() { { return 1; } }");
    }

    @Test
    public void testIfElseReturnPathStillInitializesVariable() throws IOException {
        compileSource("void main() { int y; int z; y = 1; z = f(y); } int f(int y) { int x; if (y > 0) { return 1; } else { x = 2; } return x; }");
    }

    @Test
    public void testIfReturnThenAssignGuaranteesAssignment() throws IOException {
        compileSource("void main() { int y; int z; y = 1; z = f(y); } int f(int y) { int x; if (y > 0) { x = 2; } else { return 3; } return x; }");
    }

    @Test(expected = SemanticException.class)
    public void testWhileReturnDoesNotGuaranteeNonVoidMethod() throws IOException {
        compileSource("void main() {} int f() { while (true) { return 1; } }");
    }

    @Test(expected = SemanticException.class)
    public void testVoidCallCannotBeUsedAsExpression() throws IOException {
        compileSource("void main() { int x; x = foo(); } void foo() { printf(\"x\\n\"); }");
    }

    @Test(expected = SemanticException.class)
    public void testPrintfArgCountMismatch() throws IOException {
        compileSource("void main() { printf(\"x=%d\\n\"); }");
    }

    @Test(expected = SemanticException.class)
    public void testPrintfTypeMismatch() throws IOException {
        compileSource("void main() { float f; f = 1.0; printf(\"x=%d\\n\", f); }");
    }

    @Test(expected = SemanticException.class)
    public void testPrintfUnsupportedPlaceholder() throws IOException {
        compileSource("void main() { printf(\"x=%s\\n\", 1); }");
    }

    @Test(expected = SemanticException.class)
    public void testPrintfDanglingPercent() throws IOException {
        compileSource("void main() { printf(\"x=%\", 1); }");
    }

    @Test(expected = SemanticException.class)
    public void testMainCannotReturnFromMiddle() throws IOException {
        compileSource("void main() { return 1; printf(\"unreachable\\n\"); }");
    }

    @Test(expected = ParseException.class)
    public void testMissingSemicolon() throws IOException {
        // Missing semicolon should report syntax error
        compileSource("void main() { int x }");
    }

    @Test(expected = ParseException.class)
    public void testMissingClosingBrace() throws IOException {
        // Missing closing brace should report syntax error
        compileSource("void main() { int x; ");
    }

    @Test(expected = ParseException.class)
    public void testMissingClosingParen() throws IOException {
        // Missing closing parenthesis should report syntax error
        compileSource("void main( { }");
    }

    @Test(expected = ParseException.class)
    public void testClassNameMismatch() throws IOException {
        File dir = new File("test_tmp");
        dir.mkdirs();
        File f = new File(dir, "Mismatch.lemon");
        Files.write(f.toPath(), "class Other { void foo() {} }".getBytes("UTF-8"));
        try {
            Lexer lexer = new Lexer(f);
            Parser parser = new Parser(lexer);
            parser.parse();
        } finally {
            f.delete();
        }
    }

    // ===== Error message format verification =====

    @Test
    public void testSemanticErrorMessageFormat() throws IOException {
        try {
            compileSource("void main() { x = 1; }");
            fail("Expected SemanticException to be thrown");
        } catch (SemanticException e) {
            String msg = e.getMessage();
            assertTrue("error message should be in English: " + msg, msg.contains("undefined variable"));
            assertTrue("error should carry a diagnostic: " + msg, e.getDiagnostic() != null);
        }
    }

    @Test
    public void testParseErrorMessageFormat() throws IOException {
        try {
            compileSource("void main() { int x }");
            fail("Expected ParseException to be thrown");
        } catch (ParseException e) {
            String msg = e.getMessage();
            assertTrue("error message should be in English: " + msg, msg.contains("[parser]"));
            assertTrue("error message should contain a line: " + msg, msg.contains("line"));
        }
    }

    // ======================== Infrastructure ========================

    /**
     * Writes source string to a temporary file and executes the full compilation pipeline.
     * Used to verify that the compiler properly throws exceptions on invalid inputs.
     */
    private void compileSource(String source) throws IOException {
        // Extract class name from source as file name
        String className = "Test";
        int classIdx = source.indexOf("class ");
        if (classIdx >= 0) {
            String rest = source.substring(classIdx + 6).trim();
            int spaceIdx = rest.indexOf(' ');
            int braceIdx = rest.indexOf('{');
            int endIdx = Math.min(
                    spaceIdx >= 0 ? spaceIdx : Integer.MAX_VALUE,
                    braceIdx >= 0 ? braceIdx : Integer.MAX_VALUE
            );
            if (endIdx < Integer.MAX_VALUE) {
                className = rest.substring(0, endIdx).trim();
            }
        }

        File tempDir = new File("test_tmp");
        tempDir.mkdirs();
        File tempFile = new File(tempDir, className + ".lemon");
        try {
            Files.write(tempFile.toPath(), source.getBytes("UTF-8"));

            Lexer lexer = new Lexer(tempFile);
            Parser parser = new Parser(lexer);
            Ast.Program.T program = parser.parse();

            SemanticVisitor semantic = new SemanticVisitor();
            semantic.visit(program);
        } finally {
            tempFile.delete();
        }
    }
}
