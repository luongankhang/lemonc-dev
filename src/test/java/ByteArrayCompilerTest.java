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

public class ByteArrayCompilerTest {
    @Test
    public void supportsByteArrayDeclarationAccessStoreLengthParameterAndReturn() throws Exception {
        String source = ""
                + "byte[] identity(byte values[]) {\n"
                + "    values[0] = 127;\n"
                + "    return values;\n"
                + "}\n"
                + "void main() {\n"
                + "    byte data[2];\n"
                + "    int size;\n"
                + "    data[0] = -128;\n"
                + "    data[1] = 127;\n"
                + "    size = data.length;\n"
                + "    identity(data);\n"
                + "    printf(\"%d\", data[0]);\n"
                + "}\n";
        Analysis analysis = analyze("ByteArrays", source);
        assertTrue("byte[] program should be valid: " + analysis.semantic.getDiagnostics(),
                analysis.semantic.passOrNot());
        assertTrue(analysis.semantic.getDiagnostics().isEmpty());

        byte[] classBytes = JvmTestSupport.compileToBytes("ByteArrays", source);
        assertTrue(JvmTestSupport.hasMethod(classBytes, "identity", "([B)[B"));
        assertTrue(JvmTestSupport.hasNewArray(classBytes, "byte"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "baload"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "bastore"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "arraylength"));
    }

    @Test
    public void rejectsNonByteArrayArgumentsAndReturns() throws Exception {
        Analysis argument = analyze("ByteArrayArgument",
                "void use(byte values[]) {} void main() { int values[2]; use(values); }\n");
        Diagnostic argumentDiagnostic = firstDiagnostic(argument.semantic.getDiagnostics());
        assertEquals("E3003", argumentDiagnostic.code());
        assertTrue(argumentDiagnostic.message().contains("expected byte[]"));
        assertTrue(argumentDiagnostic.message().contains("found int[]"));

        Analysis stringArgument = analyze("ByteStringArrayArgument",
                "void use(byte values[]) {} void main() { string values[2]; use(values); }\n");
        Diagnostic stringArgumentDiagnostic = firstDiagnostic(stringArgument.semantic.getDiagnostics());
        assertEquals("E3003", stringArgumentDiagnostic.code());
        assertTrue(stringArgumentDiagnostic.message().contains("expected byte[]"));
        assertTrue(stringArgumentDiagnostic.message().contains("found string[]"));

        Analysis returnValue = analyze("ByteArrayReturn",
                "byte[] get() { return 1; } void main() { get(); }\n");
        Diagnostic returnDiagnostic = firstDiagnostic(returnValue.semantic.getDiagnostics());
        assertEquals("E3002", returnDiagnostic.code());
        assertTrue(returnDiagnostic.message().contains("expected byte[]"));
        assertTrue(returnDiagnostic.message().contains("found int"));
    }

    @Test
    public void rejectsInvalidByteArrayElementValues() throws Exception {
        Analysis analysis = analyze("ByteArrayElementRange",
                "void main() { byte values[1]; values[0] = 128; }\n");
        Diagnostic diagnostic = firstDiagnostic(analysis.semantic.getDiagnostics());
        assertEquals("E3008", diagnostic.code());
        assertTrue(diagnostic.message().contains("expected -128..127"));
        assertEquals(1, diagnostic.primarySpan().startLine());
        assertTrue(diagnostic.primarySpan().startColumn() > 0);

        Analysis stringElement = analyze("ByteArrayStringElement",
                "void main() { byte values[1]; values[0] = \"x\"; }\n");
        Diagnostic stringElementDiagnostic = firstDiagnostic(stringElement.semantic.getDiagnostics());
        assertEquals("E3001", stringElementDiagnostic.code());
        assertTrue(stringElementDiagnostic.message().contains("expected byte"));
        assertTrue(stringElementDiagnostic.message().contains("found string"));
    }

    private Analysis analyze(String className, String source) throws Exception {
        File directory = Files.createTempDirectory("lemonc-byte-array").toFile();
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
