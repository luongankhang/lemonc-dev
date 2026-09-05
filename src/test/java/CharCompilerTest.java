import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.lexer.Lexer;
import site.ilemon.lexer.TokenKind;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CharCompilerTest {
    @Test
    public void lexesCharacterLiteralsAndEscapes() throws Exception {
        File file = source("CharLex", "void main() { char a; char b; a = 'A'; b = '\\n'; }\n");
        try {
            Lexer lexer = new Lexer(file);
            lexer.lexicalAnalysis();
            List<site.ilemon.lexer.Token> chars = lexer.tokens.stream().filter(t -> t.kind == TokenKind.CharLiteral).toList();
            assertEquals(2, chars.size());
            assertEquals("A", chars.get(0).lexeme);
            assertEquals("\n", chars.get(1).lexeme);
        } finally {
            delete(file);
        }
    }

    @Test
    public void supportsCharDeclarationFunctionsComparisonAndPromotion() throws Exception {
        String source = "char identity(char value) { return value; } void main() { char value; int code; value = 'A'; value = identity(value); code = value + 1; if (value == 'A') { code = value; } printf(\"%d\", code); }\n";
        Analysis analysis = analyze("CharValid", source);
        assertTrue("char program should be valid: " + analysis.semantic.getDiagnostics(), analysis.semantic.passOrNot());
        byte[] classBytes = JvmTestSupport.compileToBytes("CharValid", source);
        assertTrue(JvmTestSupport.hasMethod(classBytes, "identity", "(C)C"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "ireturn"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "iadd"));
    }

    @Test
    public void rejectsInvalidCharAssignmentsAndMalformedLiterals() throws Exception {
        Analysis mismatch = analyze("CharMismatch", "void main() { char value; value = 1; }\n");
        Diagnostic diagnostic = first(mismatch.semantic.getDiagnostics());
        assertEquals("E3001", diagnostic.code());
        assertTrue(diagnostic.message().contains("expected char"));

        File file = source("CharBad", "void main() { char value; value = 'ab'; }\n");
        try {
            assertThrows(RuntimeException.class, () -> new Parser(new Lexer(file)).parse());
        } finally {
            delete(file);
        }
    }

    @Test
    public void executesCharProgramOnJvm() throws Exception {
        String source = "void main() { char value; int result; value = 'A'; result = value + 1; printf(\"%d\", result); }\n";
        Analysis analysis = analyze("CharRuntime", source);
        assertTrue(analysis.semantic.passOrNot());
        assertEquals("66", JvmTestSupport.compileAndRun("CharRuntime", source));
    }

    private Analysis analyze(String name, String text) throws Exception {
        File file = source(name, text);
        try {
            Parser parser = new Parser(new Lexer(file));
            Ast.Program.T program = parser.parse();
            SemanticVisitor semantic = SemanticVisitor.collecting();
            semantic.visit(program);
            return new Analysis(program, semantic);
        } finally {
            delete(file);
        }
    }

    private File source(String name, String text) throws Exception {
        File dir = Files.createTempDirectory("lemonc-char").toFile();
        File file = new File(dir, name + ".lemon");
        Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
        return file;
    }

    private void delete(File file) throws Exception {
        Files.deleteIfExists(file.toPath());
        Files.deleteIfExists(file.getParentFile().toPath());
    }

    private Diagnostic first(List<Diagnostic> diagnostics) {
        assertFalse(diagnostics.isEmpty());
        return diagnostics.get(0);
    }

    private record Analysis(Ast.Program.T program, SemanticVisitor semantic) {
    }
}
