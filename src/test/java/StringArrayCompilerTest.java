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

public class StringArrayCompilerTest {
    @Test
    public void supportsStringArrayDeclarationsParametersReturnsAndIndexing() throws Exception {
        String source = ""
                + "string[] getNames() {\n"
                + "    string values[2];\n"
                + "    values[0] = \"Alice\";\n"
                + "    values[1] = \"Bob\";\n"
                + "    return values;\n"
                + "}\n"
                + "void useNames(string names[]) {\n"
                + "    string first[1];\n"
                + "    first[0] = names[0];\n"
                + "}\n"
                + "void main() {\n"
                + "    string names[2];\n"
                + "    names[0] = \"Alice\";\n"
                + "    names[1] = \"Bob\";\n"
                + "    useNames(names);\n"
                + "    getNames();\n"
                + "}\n";

        Analysis analysis = analyze("StringArrays", source);
        assertTrue("string[] should be semantically valid: " + analysis.semantic.getDiagnostics(),
                analysis.semantic.passOrNot());
        assertTrue(analysis.semantic.getDiagnostics().isEmpty());

        byte[] classBytes = JvmTestSupport.compileToBytes("StringArrays", source);
        assertTrue(JvmTestSupport.hasMethod(classBytes, "getNames", "()[Ljava/lang/String;"));
        assertTrue(JvmTestSupport.hasMethod(classBytes, "useNames", "([Ljava/lang/String;)V"));
        assertTrue(JvmTestSupport.hasANewArray(classBytes));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "aaload"));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "aastore"));
    }

    @Test
    public void supportsStringArrayLengthAndLegacyStringSpelling() throws Exception {
        String source = "void main() { String names[2]; int size; size = names.length; }\n";
        Analysis analysis = analyze("StringArrayLength", source);
        assertTrue("String spelling should remain supported", analysis.semantic.passOrNot());

        byte[] classBytes = JvmTestSupport.compileToBytes("StringArrayLength", source);
        assertTrue(JvmTestSupport.hasANewArray(classBytes));
        assertTrue(JvmTestSupport.hasMnemonic(classBytes, "arraylength"));
    }

    @Test
    public void reportsStringArrayAssignmentMismatch() throws Exception {
        Analysis analysis = analyze("StringArrayAssignment",
                "void main() { string names[2]; names = \"hello\"; }\n");
        Diagnostic diagnostic = firstDiagnostic(analysis.semantic.getDiagnostics());
        assertEquals("E3001", diagnostic.code());
        assertTrue(diagnostic.message().contains("expected string[]"));
        assertTrue(diagnostic.message().contains("found string"));
        assertEquals(1, diagnostic.primarySpan().startLine());
    }

    @Test
    public void reportsWrongStringArrayElementAndArrayArgumentTypes() throws Exception {
        Analysis element = analyze("StringArrayElement",
                "void main() { string names[2]; names[0] = 1; }\n");
        Diagnostic elementDiagnostic = firstDiagnostic(element.semantic.getDiagnostics());
        assertEquals("E3001", elementDiagnostic.code());
        assertTrue(elementDiagnostic.message().contains("expected string"));
        assertTrue(elementDiagnostic.message().contains("found int"));
        assertEquals(1, elementDiagnostic.primarySpan().startLine());
        assertTrue(elementDiagnostic.primarySpan().startColumn() > 0);

        Analysis argument = analyze("StringArrayArgument",
                "void use(string names[]) {} void main() { int values[2]; use(values); }\n");
        Diagnostic argumentDiagnostic = firstDiagnostic(argument.semantic.getDiagnostics());
        assertEquals("E3003", argumentDiagnostic.code());
        assertTrue(argumentDiagnostic.message().contains("expected string[]"));
        assertTrue(argumentDiagnostic.message().contains("found int[]"));
    }

    @Test
    public void reportsWrongStringArrayReturnType() throws Exception {
        Analysis analysis = analyze("StringArrayReturn",
                "string[] get() { return \"hello\"; } void main() { get(); }\n");
        Diagnostic diagnostic = firstDiagnostic(analysis.semantic.getDiagnostics());
        assertEquals("E3002", diagnostic.code());
        assertTrue(diagnostic.message().contains("expected string[]"));
        assertTrue(diagnostic.message().contains("found string"));
    }

    private Analysis analyze(String className, String source) throws Exception {
        File directory = Files.createTempDirectory("lemonc-string-array").toFile();
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
