import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.codegen.ByteCodeGenerator;
import site.ilemon.codegen.TranslatorVisitor;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.lexer.Lexer;
import site.ilemon.lexer.TokenKind;
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
        } finally { delete(file); }
    }

    @Test
    public void supportsCharDeclarationFunctionsComparisonAndPromotion() throws Exception {
        Analysis analysis = analyze("CharValid", "char identity(char value) { return value; } void main() { char value; int code; value = 'A'; value = identity(value); code = value + 1; if (value == 'A') { code = value; } printf(\"%d\", code); }\n");
        assertTrue("char program should be valid: " + analysis.semantic.getDiagnostics(), analysis.semantic.passOrNot());
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(analysis.program);
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);
        String jasmin = Files.readString(generator.getOutputFile().toPath());
        assertTrue(jasmin.contains(".method static identity(C)C"));
        assertTrue(jasmin.contains("ireturn"));
        assertTrue(jasmin.contains("iadd"));
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
        } finally { delete(file); }
    }

    @Test
    public void executesCharProgramOnJvm() throws Exception {
        Analysis analysis = analyze("CharRuntime", "void main() { char value; int result; value = 'A'; result = value + 1; printf(\"%d\", result); }\n");
        assertTrue(analysis.semantic.passOrNot());
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(analysis.program);
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);
        PrintStream oldOut = System.out, oldErr = System.err;
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        try { System.setOut(quiet); System.setErr(quiet); jasmin.Main.main(new String[]{"-d", generator.getOutputDir().getPath(), generator.getOutputFile().getPath()}); }
        finally { System.setOut(oldOut); System.setErr(oldErr); quiet.close(); }
        Process process = new ProcessBuilder(javaExecutable(), "-cp", generator.getOutputDir().getPath(), "CharRuntime").redirectErrorStream(true).start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertEquals("66", readAll(process.getInputStream()));
    }

    private Analysis analyze(String name, String text) throws Exception {
        File file = source(name, text);
        try {
            Parser parser = new Parser(new Lexer(file));
            Ast.Program.T program = parser.parse();
            SemanticVisitor semantic = SemanticVisitor.collecting();
            semantic.visit(program);
            return new Analysis(program, semantic);
        } finally { delete(file); }
    }
    private File source(String name, String text) throws Exception { File dir = Files.createTempDirectory("lemonc-char").toFile(); File file = new File(dir, name + ".lemon"); Files.writeString(file.toPath(), text, StandardCharsets.UTF_8); return file; }
    private void delete(File file) throws Exception { Files.deleteIfExists(file.toPath()); Files.deleteIfExists(file.getParentFile().toPath()); }
    private Diagnostic first(List<Diagnostic> diagnostics) { assertFalse(diagnostics.isEmpty()); return diagnostics.get(0); }
    private String readAll(InputStream stream) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[256]; int n; while ((n = stream.read(buffer)) != -1) out.write(buffer, 0, n); return out.toString(StandardCharsets.UTF_8); }
    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return new File(new File(System.getProperty("java.home"), "bin"), executable).getPath();
    }
    private record Analysis(Ast.Program.T program, SemanticVisitor semantic) {}
}
