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

public class ByteCompilerTest {
    @Test
    public void supportsByteVariablesParametersReturnsPromotionAndJvmCodegen() throws Exception {
        String source = ""
                + "byte identity(byte value) { return value; }\n"
                + "void main() {\n"
                + "    byte value;\n"
                + "    int widened;\n"
                + "    value = 127;\n"
                + "    widened = value + 1;\n"
                + "    value = identity(value);\n"
                + "    if (value < 127) { widened = value; }\n"
                + "    printf(\"%d\", value);\n"
                + "}\n";
        Analysis analysis = analyze("ByteValid", source);

        assertTrue("byte program should be valid: " + analysis.semantic.getDiagnostics(),
                analysis.semantic.passOrNot());
        assertTrue(analysis.semantic.getDiagnostics().isEmpty());

        byte[] classBytes = JvmTestSupport.compileToBytes("ByteValid", source);
        assertTrue(JvmTestSupport.hasMethod(classBytes, "identity", "(B)B"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "ireturn"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "istore"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "iload"));
    }

    @Test
    public void reportsByteLiteralOutsideSignedEightBitRange() throws Exception {
        Analysis analysis = analyze("ByteRange", "void main() { byte value; value = 128; }\n");
        Diagnostic diagnostic = firstDiagnostic(analysis.semantic.getDiagnostics());
        assertEquals("E3008", diagnostic.code());
        assertTrue(diagnostic.message().contains("expected -128..127"));
        assertTrue(diagnostic.message().contains("found 128"));
        assertEquals(1, diagnostic.primarySpan().startLine());
        assertTrue(diagnostic.primarySpan().startColumn() > 0);
    }

    @Test
    public void reportsByteTypeMismatchesWithExpectedAndActualTypes() throws Exception {
        Analysis assignment = analyze("ByteAssignment", "void main() { byte value; value = 1.5; }\n");
        Diagnostic assignmentDiagnostic = firstDiagnostic(assignment.semantic.getDiagnostics());
        assertEquals("E3001", assignmentDiagnostic.code());
        assertTrue(assignmentDiagnostic.message().contains("expected byte"));
        assertTrue(assignmentDiagnostic.message().contains("found float"));

        Analysis argument = analyze("ByteArgument", "void use(byte value) {} void main() { use(1.5); }\n");
        Diagnostic argumentDiagnostic = firstDiagnostic(argument.semantic.getDiagnostics());
        assertEquals("E3003", argumentDiagnostic.code());
        assertTrue(argumentDiagnostic.message().contains("expected byte"));
        assertTrue(argumentDiagnostic.message().contains("found float"));

        Analysis result = analyze("ByteReturn", "byte get() { return 1.5; } void main() {}\n");
        Diagnostic returnDiagnostic = firstDiagnostic(result.semantic.getDiagnostics());
        assertEquals("E3002", returnDiagnostic.code());
        assertTrue(returnDiagnostic.message().contains("expected byte"));
        assertTrue(returnDiagnostic.message().contains("found float"));
    }

    private Analysis analyze(String className, String source) throws Exception {
        File directory = Files.createTempDirectory("lemonc-byte").toFile();
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
        assertFalse("expected a semantic diagnostic", diagnostics.isEmpty());
        return diagnostics.get(0);
    }

    private record Analysis(Ast.Program.T program, SemanticVisitor semantic) {
    }
}
