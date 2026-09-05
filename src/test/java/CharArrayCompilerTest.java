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

public class CharArrayCompilerTest {
    @Test
    public void supportsCharArrayDeclarationAccessStoreLengthParameterAndReturn() throws Exception {
        String source = "char[] identity(char values[]) { values[0] = '\\n'; return values; }\n"
                + "void main() { char data[2]; int size; data[0] = 'A'; data[1] = '\\t'; size = data.length; identity(data); printf(\"%d\", data[0]); }\n";
        Analysis analysis = analyze("CharArrays", source);
        assertTrue(analysis.semantic.passOrNot());
        byte[] classBytes = JvmTestSupport.compileToBytes("CharArrays", source);
        assertTrue(JvmTestSupport.hasMethod(classBytes, "identity", "([C)[C"));
        assertTrue(JvmTestSupport.hasNewArray(classBytes, "char"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "caload"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "castore"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "arraylength"));
    }

    @Test
    public void rejectsDifferentArrayTypesAndInvalidCharElements() throws Exception {
        Analysis argument = analyze("CharArrayArgument", "void use(char values[]) {} void main() { short values[2]; use(values); }\n");
        Diagnostic argumentDiagnostic = first(argument.semantic.getDiagnostics());
        assertEquals("E3003", argumentDiagnostic.code());
        assertTrue(argumentDiagnostic.message().contains("expected char[]"));
        assertTrue(argumentDiagnostic.message().contains("found short[]"));

        Analysis element = analyze("CharArrayElement", "void main() { char values[1]; values[0] = 65; }\n");
        Diagnostic elementDiagnostic = first(element.semantic.getDiagnostics());
        assertEquals("E3001", elementDiagnostic.code());
        assertTrue(elementDiagnostic.message().contains("expected char"));
    }

    @Test
    public void executesCharArrayOnJvm() throws Exception {
        String source = "void main() { char values[1]; values[0] = 'A'; printf(\"%d\", values[0]); }\n";
        Analysis analysis = analyze("CharArrayRuntime", source);
        assertTrue(analysis.semantic.passOrNot());
        assertEquals("65", JvmTestSupport.compileAndRun("CharArrayRuntime", source));
    }

    private Analysis analyze(String className, String source) throws Exception {
        File directory = Files.createTempDirectory("lemonc-char-array").toFile();
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
