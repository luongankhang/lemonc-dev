import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LongCompilerTest {
    @Test
    public void supportsLongLiteralArithmeticComparisonAndJvmCodegen() throws Exception {
        String source = ""
                + "long identity(long value) { return value; }\n"
                + "void main() {\n"
                + "    long value;\n"
                + "    long result;\n"
                + "    value = 9223372036854775807;\n"
                + "    result = value - 1;\n"
                + "    result = result + 2;\n"
                + "    result = identity(result);\n"
                + "    if (result > 0) { printf(\"%d\", result); }\n"
                + "}\n";
        Analysis analysis = analyze("LongValid", source);
        assertTrue("long program should be valid: " + analysis.semantic.getDiagnostics(),
                analysis.semantic.passOrNot());
        assertTrue(analysis.semantic.getDiagnostics().isEmpty());

        byte[] classBytes = JvmTestSupport.compileToBytes("LongValid", source);
        assertTrue(JvmTestSupport.hasMethod(classBytes, "identity", "(J)J"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "ladd"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "lsub"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "lcmp"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "lload"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "lstore"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "lreturn"));
    }

    @Test
    public void allowsByteAndIntWideningToLong() throws Exception {
        Analysis analysis = analyze("LongWidening", ""
                + "void main() {\n"
                + "    byte small;\n"
                + "    int number;\n"
                + "    long value;\n"
                + "    small = 1;\n"
                + "    number = 2;\n"
                + "    value = small + number;\n"
                + "}\n");
        assertTrue(analysis.semantic.passOrNot());
        assertTrue(analysis.semantic.getDiagnostics().isEmpty());
    }

    @Test
    public void reportsLongTypeMismatchesAndOutOfRangeLiteral() throws Exception {
        Analysis assignment = analyze("LongAssignment", "void main() { long value; value = \"x\"; }\n");
        Diagnostic assignmentDiagnostic = firstDiagnostic(assignment.semantic.getDiagnostics());
        assertEquals("E3001", assignmentDiagnostic.code());
        assertTrue(assignmentDiagnostic.message().contains("expected long"));
        assertTrue(assignmentDiagnostic.message().contains("found string"));

        Analysis argument = analyze("LongArgument", "void use(long value) {} void main() { use(1.5); }\n");
        Diagnostic argumentDiagnostic = firstDiagnostic(argument.semantic.getDiagnostics());
        assertEquals("E3003", argumentDiagnostic.code());
        assertTrue(argumentDiagnostic.message().contains("expected long"));
        assertTrue(argumentDiagnostic.message().contains("found float"));

        Analysis returnValue = analyze("LongReturn", "long get() { return \"x\"; } void main() {}\n");
        Diagnostic returnDiagnostic = firstDiagnostic(returnValue.semantic.getDiagnostics());
        assertEquals("E3002", returnDiagnostic.code());
        assertTrue(returnDiagnostic.message().contains("expected long"));
        assertTrue(returnDiagnostic.message().contains("found string"));

        Analysis tooLarge = analyze("LongTooLarge", "void main() { long value; value = 9223372036854775808; }\n");
        assertFalse("an out-of-range long literal should not produce a valid AST", tooLarge.parsed);
    }

    private Analysis analyze(String className, String source) throws Exception {
        File directory = Files.createTempDirectory("lemonc-long").toFile();
        File file = new File(directory, className + ".lemon");
        Files.writeString(file.toPath(), source, StandardCharsets.UTF_8);
        try {
            try {
                Parser parser = new Parser(new Lexer(file));
                Ast.Program.T program = parser.parse();
                SemanticVisitor semantic = SemanticVisitor.collecting();
                semantic.visit(program);
                return new Analysis(program, semantic, true);
            } catch (RuntimeException parseFailure) {
                return new Analysis(null, null, false);
            }
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private Diagnostic firstDiagnostic(List<Diagnostic> diagnostics) {
        assertFalse("expected a diagnostic", diagnostics.isEmpty());
        return diagnostics.get(0);
    }

    private record Analysis(Ast.Program.T program, SemanticVisitor semantic, boolean parsed) {
    }
}
