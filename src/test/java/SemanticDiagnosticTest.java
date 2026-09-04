import org.junit.Test;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.exception.SemanticException;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class SemanticDiagnosticTest {
    @Test
    public void reportsUnknownVariableAndFunctionWithPreciseSpans() throws Exception {
        SemanticVisitor semantic = analyze("Names",
                "void main() { int y; x = 1; y = missing(); }\n", true);

        List<Diagnostic> diagnostics = semantic.getDiagnostics();
        assertEquals(2, diagnostics.size());
        assertEquals("E2001", diagnostics.get(0).code());
        assertEquals("E2002", diagnostics.get(1).code());
        assertEquals(1, diagnostics.get(0).primarySpan().getStartLine());
        assertEquals(1, diagnostics.get(1).primarySpan().getStartLine());
        assertNotNull(diagnostics.get(0).primaryLabel());
        assertNotNull(diagnostics.get(1).primaryLabel());
        DiagnosticTestSupport.assertSuggestion(diagnostics.get(0), "y");
    }

    @Test
    public void reportsDuplicateDeclarationAtSecondIdentifier() throws Exception {
        SemanticVisitor semantic = analyze("Duplicate", "void main() { int x; int x; }\n", false);

        Diagnostic diagnostic = semantic.getDiagnostics().get(0);
        assertEquals("E2003", diagnostic.code());
        assertEquals("duplicate declaration", diagnostic.primaryLabel());
        assertEquals(1, diagnostic.primarySpan().getStartLine());
    }

    @Test
    public void reportsVoidFunctionUsedAsValue() throws Exception {
        SemanticVisitor semantic = analyze("InvalidUse",
                "void foo() {} void main() { int x; x = foo(); }\n", false);

        Diagnostic diagnostic = semantic.getDiagnostics().get(0);
        assertEquals("E2004", diagnostic.code());
        assertEquals("void function used as value", diagnostic.primaryLabel());
        assertEquals(1, diagnostic.primarySpan().getStartLine());
    }

    private SemanticVisitor analyze(String className, String source, boolean collecting) throws Exception {
        File directory = Files.createTempDirectory("lemonc-semantic").toFile();
        File file = new File(directory, className + ".lemon");
        Files.writeString(file.toPath(), source, StandardCharsets.UTF_8);
        try {
            Parser parser = new Parser(new Lexer(file));
            var program = parser.parse();
            var semantic = collecting ? SemanticVisitor.collecting() : new SemanticVisitor();
            try {
                semantic.visit(program);
            } catch (SemanticException expected) {
                // The non-collecting mode still exposes its structured diagnostic.
            }
            return semantic;
        } finally {
            Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(directory.toPath());
        }
    }
}
