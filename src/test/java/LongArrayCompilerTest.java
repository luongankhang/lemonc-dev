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

public class LongArrayCompilerTest {
    @Test
    public void supportsLongArrayDeclarationAccessStoreLengthParameterAndReturn() throws Exception {
        String source = ""
                + "long[] identity(long values[]) {\n"
                + "    values[0] = 9223372036854775807;\n"
                + "    return values;\n"
                + "}\n"
                + "void main() {\n"
                + "    long values[2];\n"
                + "    int size;\n"
                + "    values[0] = -9223372036854775808;\n"
                + "    values[1] = 42;\n"
                + "    size = values.length;\n"
                + "    printf(\"%d,\", values[0]);\n"
                + "    identity(values);\n"
                + "    printf(\"%d\", values[0]);\n"
                + "}\n";
        Analysis analysis = analyze("LongArrays", source);
        assertTrue("long[] program should be valid: " + analysis.semantic.getDiagnostics(),
                analysis.semantic.passOrNot());
        assertTrue(analysis.semantic.getDiagnostics().isEmpty());

        byte[] classBytes = JvmTestSupport.compileToBytes("LongArrays", source);
        assertTrue(JvmTestSupport.hasMethod(classBytes, "identity", "([J)[J"));
        assertTrue(JvmTestSupport.hasNewArray(classBytes, "long"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "laload"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "i2l"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "lastore"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "arraylength"));

        assertEquals("-9223372036854775808,9223372036854775807",
                JvmTestSupport.compileAndRun("LongArrays", source));
    }

    @Test
    public void rejectsOtherArrayTypesAsLongArrayArguments() throws Exception {
        Analysis intArray = analyze("LongIntArrayArgument",
                "void use(long values[]) {} void main() { int values[2]; use(values); }\n");
        Diagnostic intDiagnostic = firstDiagnostic(intArray.semantic.getDiagnostics());
        assertEquals("E3003", intDiagnostic.code());
        assertTrue(intDiagnostic.message().contains("expected long[]"));
        assertTrue(intDiagnostic.message().contains("found int[]"));

        Analysis byteArray = analyze("LongByteArrayArgument",
                "void use(long values[]) {} void main() { byte values[2]; use(values); }\n");
        Diagnostic byteDiagnostic = firstDiagnostic(byteArray.semantic.getDiagnostics());
        assertEquals("E3003", byteDiagnostic.code());
        assertTrue(byteDiagnostic.message().contains("expected long[]"));
        assertTrue(byteDiagnostic.message().contains("found byte[]"));

        Analysis stringArray = analyze("LongStringArrayArgument",
                "void use(long values[]) {} void main() { string values[2]; use(values); }\n");
        Diagnostic stringDiagnostic = firstDiagnostic(stringArray.semantic.getDiagnostics());
        assertEquals("E3003", stringDiagnostic.code());
        assertTrue(stringDiagnostic.message().contains("expected long[]"));
        assertTrue(stringDiagnostic.message().contains("found string[]"));
    }

    @Test
    public void rejectsNonLongArrayReturnValue() throws Exception {
        Analysis analysis = analyze("LongArrayReturn", "long[] get() { return 1; } void main() { get(); }\n");
        Diagnostic diagnostic = firstDiagnostic(analysis.semantic.getDiagnostics());
        assertEquals("E3002", diagnostic.code());
        assertTrue(diagnostic.message().contains("expected long[]"));
        assertTrue(diagnostic.message().contains("found int"));
    }

    private Analysis analyze(String className, String source) throws Exception {
        File directory = Files.createTempDirectory("lemonc-long-array").toFile();
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
