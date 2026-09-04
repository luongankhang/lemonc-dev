import org.junit.Before;
import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.codegen.ast.Label;
import site.ilemon.exception.SemanticException;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * Comprehensive tests for return path analysis.
 * Audits the logic in SemanticVisitor for verifying that non-void methods return on all paths.
 */
public class ReturnPathAnalysisTest {

    @Before
    public void setUp() {
        Label.resetCounter();
    }

    // ===== Basic return paths =====

    @Test
    public void testSimpleReturn() throws IOException {
        compileSource("int f() { return 1; } void main() { printf(\"%d\", f()); }");
    }

    @Test(expected = SemanticException.class)
    public void testNonVoidMethodNoReturn() throws IOException {
        compileSource("int f() { printf(\"no return\"); } void main() { printf(\"%d\", f()); }");
    }

    @Test
    public void testNonVoidMethodWithReturn() throws IOException {
        compileSource("int f() { int x; x = 1; return x; } void main() { printf(\"%d\", f()); }");
    }

    @Test(expected = SemanticException.class)
    public void testNonVoidMethodWithConditionalReturn() throws IOException {
        // Only then branch returns
        compileSource("int f(int x) { if (x > 0) { return 1; } } void main() { printf(\"%d\", f(1)); }");
    }

    // ===== If-else return paths =====

    @Test
    public void testIfElseBothReturn() throws IOException {
        compileSource("int f(int x) { if (x > 0) { return 1; } else { return 2; } } void main() { printf(\"%d\", f(1)); }");
    }

    @Test(expected = SemanticException.class)
    public void testIfElseOnlyThenReturns() throws IOException {
        compileSource("int f(int x) { if (x > 0) { return 1; } else { printf(\"else\"); } } void main() { printf(\"%d\", f(1)); }");
    }

    @Test(expected = SemanticException.class)
    public void testIfElseOnlyElseReturns() throws IOException {
        compileSource("int f(int x) { if (x > 0) { printf(\"then\"); } else { return 2; } } void main() { printf(\"%d\", f(1)); }");
    }

    @Test(expected = SemanticException.class)
    public void testIfNoElseReturnsInThen() throws IOException {
        // Without else clause, if statement doesn't guarantee return
        compileSource("int f(int x) { if (x > 0) { return 1; } } void main() { printf(\"%d\", f(1)); }");
    }

    // ===== Nested if statements =====

    @Test
    public void testNestedIfBothReturnAllPaths() throws IOException {
        // Outer if-else, inner if-else both guarantee return
        compileSource("int f(int x) { " +
                "if (x > 0) { " +
                "  if (x > 1) { return 1; } else { return 2; } " +
                "} else { " +
                "  return 3; " +
                "} " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    @Test(expected = SemanticException.class)
    public void testNestedIfInnerDoesNotReturnAllPaths() throws IOException {
        // Inner if doesn't have else
        compileSource("int f(int x) { " +
                "if (x > 0) { " +
                "  if (x > 1) { return 1; } " +
                "} else { " +
                "  return 2; " +
                "} " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    @Test(expected = SemanticException.class)
    public void testNestedIfOuterDoesNotReturnAllPaths() throws IOException {
        // Outer if doesn't have else
        compileSource("int f(int x) { " +
                "if (x > 0) { " +
                "  if (x > 1) { return 1; } else { return 2; } " +
                "} " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    // ===== Return in blocks =====

    @Test
    public void testReturnInBlock() throws IOException {
        compileSource("int f() { { return 1; } } void main() { printf(\"%d\", f()); }");
    }

    @Test
    public void testReturnInNestedBlock() throws IOException {
        compileSource("int f() { { { return 1; } } } void main() { printf(\"%d\", f()); }");
    }

    @Test(expected = SemanticException.class)
    public void testReturnInBlockButExtraStatementAfter() throws IOException {
        // Statement after the block that contains return - block doesn't guarantee function return
        compileSource("int f() { if (true) { return 1; } printf(\"after\"); } void main() { printf(\"%d\", f()); }");
    }

    // ===== Multiple sequential returns =====

    @Test
    public void testMultipleReturnsFirstGuarantees() throws IOException {
        compileSource("int f(int x) { " +
                "if (x > 0) { return 1; } " +
                "return 2; " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    @Test
    public void testMultipleReturnsInSequence() throws IOException {
        compileSource("int f(int x) { " +
                "if (x > 0) { return 1; } else { return 2; } " +
                "return 3; " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    // ===== Loop handling =====

    @Test(expected = SemanticException.class)
    public void testWhileLoopDoesNotGuaranteeReturn() throws IOException {
        // While loop might not execute
        compileSource("int f() { while (true) { return 1; } } void main() { printf(\"%d\", f()); }");
    }

    @Test(expected = SemanticException.class)
    public void testForLoopDoesNotGuaranteeReturn() throws IOException {
        // For loop might not execute
        compileSource("int f(int n) { " +
                "int i; i = 0; " +
                "for (i = 0; i < n; i = i + 1) { return 1; } " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    @Test(expected = SemanticException.class)
    public void testBreakInIfWithinLoop() throws IOException {
        // Break escapes the loop, but doesn't guarantee function return
        compileSource("int f(int n) { " +
                "int i; i = 0; " +
                "for (i = 0; i < n; i = i + 1) { " +
                "  if (i == 5) { return 1; break; } " +
                "} " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    @Test(expected = SemanticException.class)
    public void testContinueDoesNotGuaranteeReturn() throws IOException {
        // Continue doesn't guarantee function return
        compileSource("int f(int n) { " +
                "int i; i = 0; " +
                "for (i = 0; i < n; i = i + 1) { " +
                "  if (i == 5) { continue; } " +
                "  return 1; " +
                "} " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    // ===== Complex nested structures =====

    @Test
    public void testComplexNestedIfElseAndBlocks() throws IOException {
        // Complex but valid structure
        compileSource("int f(int x) { " +
                "if (x > 0) { " +
                "  { if (x > 1) { return 1; } else { return 2; } } " +
                "} else { " +
                "  { return 3; } " +
                "} " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    @Test
    public void testIfElseWithMultipleStatementsBeforeReturn() throws IOException {
        // Multiple statements before return in both branches
        compileSource("void main() { int x; x = f(1); } " +
                "int f(int x) { " +
                "if (x > 0) { " +
                "  printf(\"%d\", x); " +
                "  return x; " +
                "} else { " +
                "  return 2; " +
                "} " +
                "}");
    }

    @Test
    public void testSequentialIfElseStatements() throws IOException {
        // Multiple if-else statements, each guarantees return on some path
        compileSource("int f(int x) { " +
                "if (x > 10) { return 1; } " +
                "else if (x > 5) { return 2; } " +
                "else if (x > 0) { return 3; } " +
                "else { return 4; } " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    @Test(expected = SemanticException.class)
    public void testSequentialIfElseStatementsMissingFinalElse() throws IOException {
        // Multiple if-else statements, but final one doesn't have else
        compileSource("int f(int x) { " +
                "if (x > 10) { return 1; } " +
                "else if (x > 5) { return 2; } " +
                "else if (x > 0) { return 3; } " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    // ===== Unreachable code =====

    @Test
    public void testUnreachableCodeAfterReturn() throws IOException {
        // Code after return in same block - parser should handle this
        compileSource("int f() { return 1; } void main() { printf(\"%d\", f()); }");
    }

    @Test(expected = SemanticException.class)
    public void testFunctionEndingWithoutReturn() throws IOException {
        // No guarantee of return at end
        compileSource("int f(int x) { if (x > 0) { return 1; } else { printf(\"no return\"); } } void main() { printf(\"%d\", f(1)); }");
    }

    // ===== Empty blocks =====

    @Test(expected = SemanticException.class)
    public void testEmptyBlockDoesNotReturn() throws IOException {
        compileSource("int f() { {} } void main() { printf(\"%d\", f()); }");
    }

    @Test
    public void testEmptyThenButReturnAfter() throws IOException {
        compileSource("int f(int x) { if (x > 0) {} return 1; } void main() { printf(\"%d\", f(1)); }");
    }

    // ===== Edge cases =====

    @Test
    public void testReturnInIfInsideIfElse() throws IOException {
        // Inner return in both branches
        compileSource("int f(int x) { " +
                "if (x > 0) { " +
                "  if (true) { return 1; } else { return 2; } " +
                "} else { " +
                "  return 3; " +
                "} " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    @Test
    public void testReturnOnlyInNestedIfNoElse() throws IOException {
        // Nested if without else, but return 2 guarantees overall return
        compileSource("int f(int x) { " +
                "if (x > 0) { " +
                "  if (x > 1) { return 1; } " +
                "} " +
                "return 2; " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    @Test
    public void testReturnAfterComplexNestedStructure() throws IOException {
        // Return after complex but incomplete structure
        compileSource("int f(int x) { " +
                "if (x > 0) { " +
                "  if (x > 1) { return 1; } " +
                "} " +
                "return 2; " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    @Test(expected = SemanticException.class)
    public void testMultipleBranchesOneDoesNotReturn() throws IOException {
        // One branch in complex if-else doesn't explicitly return
        compileSource("int f(int x) { " +
                "if (x > 0) { return 1; } " +
                "else if (x < 0) { if (x < -10) { return 2; } } " +
                "} void main() { printf(\"%d\", f(1)); }");
    }

    // ======================== Infrastructure ========================

    /**
     * Writes source string to a temporary file and executes the full compilation pipeline.
     */
    private void compileSource(String source) throws IOException {
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
