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

public class ShortArrayCompilerTest {
    @Test
    public void supportsShortArrayDeclarationAccessStoreLengthParameterAndReturn() throws Exception {
        String source = ""
                + "short[] identity(short values[]) { values[0] = 32767; return values; }\n"
                + "void main() { short data[2]; int size; data[0] = -32768; data[1] = 32767; size = data.length; identity(data); printf(\"%d\", data[0]); }\n";
        Analysis analysis = analyze("ShortArrays", source);
        assertTrue(analysis.semantic.passOrNot());
        byte[] classBytes = JvmTestSupport.compileToBytes("ShortArrays", source);
        assertTrue(JvmTestSupport.hasMethod(classBytes, "identity", "([S)[S"));
        assertTrue(JvmTestSupport.hasNewArray(classBytes, "short"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "saload"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "sastore"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "arraylength"));
    }

    @Test
    public void rejectsDifferentArrayTypesAndInvalidShortElements() throws Exception {
        Analysis argument = analyze("ShortArrayArgument",
                "void use(short values[]) {} void main() { int values[2]; use(values); }\n");
        Diagnostic argumentDiagnostic = first(argument.semantic.getDiagnostics());
        assertEquals("E3003", argumentDiagnostic.code());
        assertTrue(argumentDiagnostic.message().contains("expected short[]"));
        assertTrue(argumentDiagnostic.message().contains("found int[]"));

        Analysis range = analyze("ShortArrayRange", "void main() { short values[1]; values[0] = 32768; }\n");
        Diagnostic rangeDiagnostic = first(range.semantic.getDiagnostics());
        assertEquals("E3009", rangeDiagnostic.code());
        assertTrue(rangeDiagnostic.message().contains("expected -32768..32767"));
    }

    @Test
    public void executesShortArrayOnJvm() throws Exception {
        String source = "void main() { short values[1]; values[0] = 42; printf(\"%d\", values[0]); }\n";
        Analysis analysis = analyze("ShortArrayRuntime", source);
        assertTrue(analysis.semantic.passOrNot());
        assertEquals("42", JvmTestSupport.compileAndRun("ShortArrayRuntime", source));
    }

    private Analysis analyze(String className, String source) throws Exception {
        File directory = Files.createTempDirectory("lemonc-short-array").toFile();
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

    private Diagnostic first(List<Diagnostic> diagnostics) {
        assertFalse(diagnostics.isEmpty());
        return diagnostics.get(0);
    }

    private record Analysis(Ast.Program.T program, SemanticVisitor semantic) {
    }
}
