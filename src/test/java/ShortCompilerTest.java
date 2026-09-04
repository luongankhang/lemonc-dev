import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.codegen.ByteCodeGenerator;
import site.ilemon.codegen.TranslatorVisitor;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ShortCompilerTest {
    @Test
    public void supportsShortVariablesParametersReturnsPromotionAndJvmCodegen() throws Exception {
        Analysis analysis = analyze("ShortValid", "class ShortValid {\n"
                + "    short identity(short value) { return value; }\n"
                + "    void main() {\n"
                + "        short value; int widened; long large;\n"
                + "        value = 32767; widened = value + 1; large = value;\n"
                + "        value = identity(value);\n"
                + "        if (value < 32767) { widened = value; }\n"
                + "        printf(\"%d\", value);\n"
                + "    }\n}\n");
        assertTrue("short program should be valid: " + analysis.semantic.getDiagnostics(), analysis.semantic.passOrNot());
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(analysis.program);
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);
        String jasmin = Files.readString(generator.getOutputFile().toPath());
        assertTrue(jasmin.contains(".method static identity(S)S"));
        assertTrue(jasmin.contains("iadd"));
        assertTrue(jasmin.contains("ireturn"));
        assertTrue(jasmin.contains("istore"));
        assertTrue(jasmin.contains("iload"));
    }

    @Test
    public void acceptsShortBoundaryConstantsAndRejectsOutOfRange() throws Exception {
        assertTrue(analyze("ShortBounds", "class ShortBounds { void main() { short low; short high; low = -32768; high = 32767; } }\n").semantic.passOrNot());
        Diagnostic diagnostic = firstDiagnostic(analyze("ShortRange", "class ShortRange { void main() { short value; value = 32768; } }\n").semantic.getDiagnostics());
        assertEquals("E3009", diagnostic.code());
        assertTrue(diagnostic.message().contains("expected -32768..32767"));
    }

    @Test
    public void rejectsShortMismatchesAndNarrowingFromNonConstant() throws Exception {
        Analysis assignment = analyze("ShortAssignment", "class ShortAssignment { void main() { short value; value = 1.5; } }\n");
        assertEquals("E3001", firstDiagnostic(assignment.semantic.getDiagnostics()).code());
        Analysis narrowing = analyze("ShortNarrowing", "class ShortNarrowing { void main() { int value; short result; value = 1; result = value; } }\n");
        assertEquals("E3001", firstDiagnostic(narrowing.semantic.getDiagnostics()).code());
    }

    @Test
    public void reportsShortRangeAcrossReturnArgumentAndArrayStore() throws Exception {
        assertEquals("E3009", firstDiagnostic(analyze("ShortReturnRange",
                "class ShortReturnRange { short value() { return 32768; } void main() {} }\n").semantic.getDiagnostics()).code());
        assertEquals("E3009", firstDiagnostic(analyze("ShortArgumentRange",
                "class ShortArgumentRange { void use(short value) {} void main() { use(32768); } }\n").semantic.getDiagnostics()).code());
        assertEquals("E3009", firstDiagnostic(analyze("ShortElementRange",
                "class ShortElementRange { void main() { short values[1]; values[0] = 32768; } }\n").semantic.getDiagnostics()).code());
    }

    @Test
    public void supportsShortArraysWithJvmShortArrayInstructions() throws Exception {
        Analysis analysis = analyze("ShortArray", "class ShortArray { void main() { short values[2]; values[0] = 12; printf(\"%d\", values[0]); } }\n");
        assertTrue(analysis.semantic.passOrNot());
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(analysis.program);
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);
        String jasmin = Files.readString(generator.getOutputFile().toPath());
        assertTrue(jasmin.contains("newarray short"));
        assertTrue(jasmin.contains("saload"));
        assertTrue(jasmin.contains("sastore"));
    }

    @Test
    public void executesShortProgramOnJvm() throws Exception {
        Analysis analysis = analyze("ShortRuntime", "class ShortRuntime { void main() { short value; int result; value = 41; result = value + 1; printf(\"%d\", result); } }\n");
        assertTrue(analysis.semantic.passOrNot());
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(analysis.program);
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        try {
            System.setOut(quiet);
            System.setErr(quiet);
            jasmin.Main.main(new String[]{"-d", generator.getOutputDir().getPath(), generator.getOutputFile().getPath()});
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            quiet.close();
        }
        Process process = new ProcessBuilder(new File(new File(System.getProperty("java.home"), "bin"), "java.exe").getPath(),
                "-cp", generator.getOutputDir().getPath(), "ShortRuntime").redirectErrorStream(true).start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        String output = readAll(process.getInputStream());
        assertTrue("JVM execution timed out: " + output, completed);
        assertEquals("JVM failed: " + output, 0, process.exitValue());
        assertEquals("42", output);
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

    private String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[256];
        int read;
        while ((read = stream.read(data)) != -1) {
            buffer.write(data, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private record Analysis(Ast.Program.T program, SemanticVisitor semantic) {}
}
