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

public class ShortCompilerTest {
    @Test
    public void supportsShortVariablesParametersReturnsPromotionAndJvmCodegen() throws Exception {
        String source = ""
                + "short identity(short value) { return value; }\n"
                + "void main() {\n"
                + "    short value; int widened; long large;\n"
                + "    value = 32767; widened = value + 1; large = value;\n"
                + "    value = identity(value);\n"
                + "    if (value < 32767) { widened = value; }\n"
                + "    printf(\"%d\", value);\n"
                + "}\n";
        Analysis analysis = analyze("ShortValid", source);
        assertTrue("short program should be valid: " + analysis.semantic.getDiagnostics(), analysis.semantic.passOrNot());
        byte[] classBytes = JvmTestSupport.compileToBytes("ShortValid", source);
        assertTrue(JvmTestSupport.hasMethod(classBytes, "identity", "(S)S"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "iadd"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "ireturn"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "istore"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "iload"));
    }

    @Test
    public void acceptsShortBoundaryConstantsAndRejectsOutOfRange() throws Exception {
        assertTrue(analyze("ShortBounds", "void main() { short low; short high; low = -32768; high = 32767; }\n").semantic.passOrNot());
        Diagnostic diagnostic = firstDiagnostic(analyze("ShortRange", "void main() { short value; value = 32768; }\n").semantic.getDiagnostics());
        assertEquals("E3009", diagnostic.code());
        assertTrue(diagnostic.message().contains("expected -32768..32767"));
    }

    @Test
    public void rejectsShortMismatchesAndNarrowingFromNonConstant() throws Exception {
        Analysis assignment = analyze("ShortAssignment", "void main() { short value; value = 1.5; }\n");
        assertEquals("E3001", firstDiagnostic(assignment.semantic.getDiagnostics()).code());
        Analysis narrowing = analyze("ShortNarrowing", "void main() { int value; short result; value = 1; result = value; }\n");
        assertEquals("E3001", firstDiagnostic(narrowing.semantic.getDiagnostics()).code());
    }

    @Test
    public void reportsShortRangeAcrossReturnArgumentAndArrayStore() throws Exception {
        assertEquals("E3009", firstDiagnostic(analyze("ShortReturnRange",
                "short value() { return 32768; } void main() {}\n").semantic.getDiagnostics()).code());
        assertEquals("E3009", firstDiagnostic(analyze("ShortArgumentRange",
                "void use(short value) {} void main() { use(32768); }\n").semantic.getDiagnostics()).code());
        assertEquals("E3009", firstDiagnostic(analyze("ShortElementRange",
                "void main() { short values[1]; values[0] = 32768; }\n").semantic.getDiagnostics()).code());
    }

    @Test
    public void supportsShortArraysWithJvmShortArrayInstructions() throws Exception {
        String source = "void main() { short values[2]; values[0] = 12; printf(\"%d\", values[0]); }\n";
        Analysis analysis = analyze("ShortArray", source);
        assertTrue(analysis.semantic.passOrNot());
        byte[] classBytes = JvmTestSupport.compileToBytes("ShortArray", source);
        assertTrue(JvmTestSupport.hasNewArray(classBytes, "short"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "saload"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "sastore"));
    }

    @Test
    public void executesShortProgramOnJvm() throws Exception {
        String source = "void main() { short value; int result; value = 41; result = value + 1; printf(\"%d\", result); }\n";
        Analysis analysis = analyze("ShortRuntime", source);
        assertTrue(analysis.semantic.passOrNot());
        assertEquals("42", JvmTestSupport.compileAndRun("ShortRuntime", source));
    }

    private Analysis analyze(String className, String source) throws Exception {
        File directory = Files.createTempDirectory("lemonc-short").toFile();
        File file = new File(directory, className + ".lemon");
        Files.writeString(file.toPath(), source, StandardCharsets.UTF_8);
        try {
            Parser parser = new Parser(new Lexer(file));
            Ast.Program.T program = parser.parse();
            SemanticVisitor semantic = SemanticVisitor.collecting();
            semantic.visit(program);
            return new Analysis(program, semantic);
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }

    private Diagnostic firstDiagnostic(List<Diagnostic> diagnostics) {
        assertFalse("expected a diagnostic", diagnostics.isEmpty());
        return diagnostics.get(0);
    }

    private record Analysis(Ast.Program.T program, SemanticVisitor semantic) {
    }
}
