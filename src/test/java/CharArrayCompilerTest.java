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

public class CharArrayCompilerTest {
    @Test
    public void supportsCharArrayDeclarationAccessStoreLengthParameterAndReturn() throws Exception {
        Analysis analysis = analyze("CharArrays", "class CharArrays {\n"
                + "    char[] identity(char values[]) { values[0] = '\\n'; return values; }\n"
                + "    void main() { char data[2]; int size; data[0] = 'A'; data[1] = '\\t'; size = data.length; identity(data); printf(\"%d\", data[0]); }\n}\n");
        assertTrue(analysis.semantic.passOrNot());
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(analysis.program);
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);
        String jasmin = Files.readString(generator.getOutputFile().toPath());
        assertTrue(jasmin.contains(".method static identity([C)[C"));
        assertTrue(jasmin.contains("newarray char"));
        assertTrue(jasmin.contains("caload"));
        assertTrue(jasmin.contains("castore"));
        assertTrue(jasmin.contains("arraylength"));
    }

    @Test
    public void rejectsDifferentArrayTypesAndInvalidCharElements() throws Exception {
        Analysis argument = analyze("CharArrayArgument", "class CharArrayArgument { void use(char values[]) {} void main() { short values[2]; use(values); } }\n");
        Diagnostic argumentDiagnostic = first(argument.semantic.getDiagnostics());
        assertEquals("E3003", argumentDiagnostic.code());
        assertTrue(argumentDiagnostic.message().contains("expected char[]"));
        assertTrue(argumentDiagnostic.message().contains("found short[]"));

        Analysis element = analyze("CharArrayElement", "class CharArrayElement { void main() { char values[1]; values[0] = 65; } }\n");
        Diagnostic elementDiagnostic = first(element.semantic.getDiagnostics());
        assertEquals("E3001", elementDiagnostic.code());
        assertTrue(elementDiagnostic.message().contains("expected char"));
    }

    @Test
    public void executesCharArrayOnJvm() throws Exception {
        Analysis analysis = analyze("CharArrayRuntime", "class CharArrayRuntime { void main() { char values[1]; values[0] = 'A'; printf(\"%d\", values[0]); } }\n");
        assertTrue(analysis.semantic.passOrNot());
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(analysis.program);
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);
        PrintStream oldOut = System.out, oldErr = System.err;
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        try { System.setOut(quiet); System.setErr(quiet); jasmin.Main.main(new String[]{"-d", generator.getOutputDir().getPath(), generator.getOutputFile().getPath()}); }
        finally { System.setOut(oldOut); System.setErr(oldErr); quiet.close(); }
        Process process = new ProcessBuilder(javaExecutable(), "-cp", generator.getOutputDir().getPath(), "CharArrayRuntime").redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertEquals("65", readAll(process.getInputStream()));
    }

    private Analysis analyze(String className, String source) throws Exception {
        File directory = Files.createTempDirectory("lemonc-char-array").toFile();
        File file = new File(directory, className + ".lemon");
        Files.writeString(file.toPath(), source, StandardCharsets.UTF_8);
        try { Parser parser = new Parser(new Lexer(file)); Ast.Program.T program = parser.parse(); SemanticVisitor semantic = SemanticVisitor.collecting(); semantic.visit(program); return new Analysis(program, semantic); }
        finally { Files.deleteIfExists(file.toPath()); Files.deleteIfExists(directory.toPath()); }
    }
    private Diagnostic first(List<Diagnostic> diagnostics) { assertFalse(diagnostics.isEmpty()); return diagnostics.get(0); }
    private String readAll(InputStream stream) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[256]; int n; while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n); return out.toString(StandardCharsets.UTF_8); }
    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return new File(new File(System.getProperty("java.home"), "bin"), executable).getPath();
    }
    private record Analysis(Ast.Program.T program, SemanticVisitor semantic) {}
}
