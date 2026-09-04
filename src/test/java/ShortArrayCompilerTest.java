import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.codegen.ByteCodeGenerator;
import site.ilemon.codegen.TranslatorVisitor;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class ShortArrayCompilerTest {
    @Test
    public void supportsShortArrayDeclarationAccessStoreLengthParameterAndReturn() throws Exception {
        Analysis analysis = analyze("ShortArrays", "class ShortArrays {\n"
                + "    short[] identity(short values[]) { values[0] = 32767; return values; }\n"
                + "    void main() { short data[2]; int size; data[0] = -32768; data[1] = 32767; size = data.length; identity(data); printf(\"%d\", data[0]); }\n}\n");
        assertTrue(analysis.semantic.passOrNot());
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(analysis.program);
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);
        String jasmin = Files.readString(generator.getOutputFile().toPath());
        assertTrue(jasmin.contains(".method static identity([S)[S"));
        assertTrue(jasmin.contains("newarray short"));
        assertTrue(jasmin.contains("saload"));
        assertTrue(jasmin.contains("sastore"));
        assertTrue(jasmin.contains("arraylength"));
    }

    @Test
    public void rejectsDifferentArrayTypesAndInvalidShortElements() throws Exception {
        Analysis argument = analyze("ShortArrayArgument", "class ShortArrayArgument { void use(short values[]) {} void main() { int values[2]; use(values); } }\n");
        Diagnostic argumentDiagnostic = first(argument.semantic.getDiagnostics());
        assertEquals("E3003", argumentDiagnostic.code());
        assertTrue(argumentDiagnostic.message().contains("expected short[]"));
        assertTrue(argumentDiagnostic.message().contains("found int[]"));

        Analysis range = analyze("ShortArrayRange", "class ShortArrayRange { void main() { short values[1]; values[0] = 32768; } }\n");
        Diagnostic rangeDiagnostic = first(range.semantic.getDiagnostics());
        assertEquals("E3009", rangeDiagnostic.code());
        assertTrue(rangeDiagnostic.message().contains("expected -32768..32767"));
    }

    @Test
    public void executesShortArrayOnJvm() throws Exception {
        Analysis analysis = analyze("ShortArrayRuntime", "class ShortArrayRuntime { void main() { short values[1]; values[0] = 42; printf(\"%d\", values[0]); } }\n");
        assertTrue(analysis.semantic.passOrNot());
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(analysis.program);
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);
        PrintStream oldOut = System.out, oldErr = System.err;
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        try { System.setOut(quiet); System.setErr(quiet); jasmin.Main.main(new String[]{"-d", generator.getOutputDir().getPath(), generator.getOutputFile().getPath()}); }
        finally { System.setOut(oldOut); System.setErr(oldErr); quiet.close(); }
        Process process = new ProcessBuilder(new File(new File(System.getProperty("java.home"), "bin"), "java.exe").getPath(), "-cp", generator.getOutputDir().getPath(), "ShortArrayRuntime").redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertEquals("42", readAll(process.getInputStream()));
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
        } finally { Files.deleteIfExists(file.toPath()); Files.deleteIfExists(directory.toPath()); }
    }
    private Diagnostic first(List<Diagnostic> diagnostics) { assertFalse(diagnostics.isEmpty()); return diagnostics.get(0); }
    private String readAll(InputStream stream) throws Exception { ByteArrayOutputStream buffer = new ByteArrayOutputStream(); byte[] data = new byte[256]; int n; while ((n = stream.read(data)) != -1) buffer.write(data, 0, n); return buffer.toString(StandardCharsets.UTF_8); }
    private record Analysis(Ast.Program.T program, SemanticVisitor semantic) {}
}
