import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.codegen.ByteCodeGenerator;
import site.ilemon.codegen.TranslatorVisitor;
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
        Analysis analysis = analyze("LongValid", ""
                + "long identity(long value) { return value; }\n"
                + "void main() {\n"
                + "    long value;\n"
                + "    long result;\n"
                + "    value = 9223372036854775807;\n"
                + "    result = value - 1;\n"
                + "    result = result + 2;\n"
                + "    result = identity(result);\n"
                + "    if (result > 0) { printf(\"%d\", result); }\n"
                + "}\n");
        assertTrue("long program should be valid: " + analysis.semantic.getDiagnostics(),
                analysis.semantic.passOrNot());
        assertTrue(analysis.semantic.getDiagnostics().isEmpty());

        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(analysis.program);
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);
        String jasmin = Files.readString(generator.getOutputFile().toPath());
        assertTrue(jasmin.contains(".method static identity(J)J"));
        assertTrue(jasmin.contains("ladd"));
        assertTrue(jasmin.contains("lsub"));
        assertTrue(jasmin.contains("lcmp"));
        assertTrue(jasmin.contains("lload"));
        assertTrue(jasmin.contains("lstore"));
        assertTrue(jasmin.contains("lreturn"));
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
