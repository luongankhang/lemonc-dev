import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.codegen.ByteCodeGenerator;
import site.ilemon.codegen.TranslatorVisitor;
import site.ilemon.compiler.LemonC;
import site.ilemon.exception.LexException;
import site.ilemon.exception.ParseException;
import site.ilemon.lexer.Lexer;
import site.ilemon.lexer.Token;
import site.ilemon.lexer.TokenKind;
import site.ilemon.optimizer.AstOptimizer;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class DiagnosticTest {

    @Test
    public void lexerRecordsTokenColumns() throws Exception {
        File file = writeSource("Test", "class Test {\n    void main() {}\n}\n");
        Lexer lexer = new Lexer(file);
        lexer.lexicalAnalysis();

        Token classToken = lexer.tokens.get(0);
        assertEquals(TokenKind.Class, classToken.kind);
        assertEquals(1, classToken.lineNumber);
        assertEquals(1, classToken.columnNumber);

        Token voidToken = findToken(lexer.tokens, TokenKind.Void);
        assertEquals(2, voidToken.lineNumber);
        assertEquals(5, voidToken.columnNumber);
    }

    @Test
    public void parserErrorIncludesColumnAndSourcePointer() throws Exception {
        File file = writeSource("Test", "class Test {\n    void main() { int x }\n}\n");
        try {
            new Parser(new Lexer(file)).parse();
            fail("Expected ParseException");
        } catch (ParseException e) {
            String message = e.getMessage();
            assertTrue(message, message.contains("int x }"));
            assertTrue(message, message.contains("^"));
        }
    }

    @Test
    public void semanticCollectingModeReportsMultipleErrors() throws Exception {
        File file = writeSource("Test",
                "class Test {\n" +
                "    void main() {\n" +
                "        int x;\n" +
                "        y = 1;\n" +
                "        x = true;\n" +
                "    }\n" +
                "}\n");
        Parser parser = new Parser(new Lexer(file));
        Ast.Program.T program = parser.parse();

        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);

        assertTrue("collecting visitor should fail", !semantic.passOrNot());
        assertTrue("should collect at least two errors: " + semantic.getErrors(),
                semantic.getErrors().size() >= 2);
        assertTrue(contains(semantic.getErrors(), "y"));
        assertTrue(contains(semantic.getErrors(), "bool"));
    }

    @Test
    public void cliReportsMultipleSemanticErrors() throws Exception {
        File file = writeSource("CliDiagnostics",
                "class CliDiagnostics {\n" +
                "    void main() {\n" +
                "        int x;\n" +
                "        y = 1;\n" +
                "        x = true;\n" +
                "    }\n" +
                "}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath()},
                new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"));

        String errorOutput = err.toString("UTF-8");
        assertEquals(1, exitCode);
        assertTrue(errorOutput, errorOutput.contains("compile failed"));
        assertTrue(errorOutput, errorOutput.contains("y"));
        assertTrue(errorOutput, errorOutput.contains("bool"));
        assertTrue(errorOutput, errorOutput.contains("        y = 1;"));
        assertTrue(errorOutput, errorOutput.contains("        x = true;"));
        assertTrue(errorOutput, errorOutput.contains("^"));
    }

    @Test
    public void cliCollectingModeReportsMissingMainWithoutNpe() throws Exception {
        File file = writeSource("NoMain",
                "class NoMain {\n" +
                "    int f() { return 1; }\n" +
                "}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath()},
                new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"));

        String errorOutput = err.toString("UTF-8");
        assertEquals(1, exitCode);
        assertTrue(errorOutput, errorOutput.contains("compile failed"));
        assertTrue(errorOutput, errorOutput.toLowerCase().contains("main"));
        assertTrue(errorOutput, !errorOutput.contains("NullPointerException"));
    }

    @Test
    public void cliCollectingModeReportsUndefinedMethodWithoutNpe() throws Exception {
        File file = writeSource("UndefinedMethod",
                "class UndefinedMethod {\n" +
                "    void main() {\n" +
                "        int x;\n" +
                "        x = missing();\n" +
                "    }\n" +
                "}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath()},
                new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"));

        String errorOutput = err.toString("UTF-8");
        assertEquals(1, exitCode);
        assertTrue(errorOutput, errorOutput.contains("missing"));
        assertTrue(errorOutput, !errorOutput.contains("NullPointerException"));
    }

    @Test
    public void cliCollectingModeReportsUndefinedArrayWithoutNpe() throws Exception {
        File file = writeSource("UndefinedArray",
                "class UndefinedArray {\n" +
                "    void main() {\n" +
                "        int x;\n" +
                "        x = values.length;\n" +
                "    }\n" +
                "}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath()},
                new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"));

        String errorOutput = err.toString("UTF-8");
        assertEquals(1, exitCode);
        assertTrue(errorOutput, errorOutput.contains("values"));
        assertTrue(errorOutput, !errorOutput.contains("NullPointerException"));
    }

    @Test
    public void lexerReportsIllegalCharacterWithSourcePointer() throws Exception {
        File file = writeSource("Test",
                "class Test {\n" +
                "    void main() {\n" +
                "        int x;\n" +
                "        x = 1 @ 2;\n" +
                "    }\n" +
                "}\n");
        try {
            new Lexer(file).lexicalAnalysis();
            fail("Expected LexException");
        } catch (LexException e) {
            String message = e.getMessage();
            assertTrue(message, message.contains("lexical analysis"));
            assertTrue(message, message.contains("illegal character '@'"));
            assertTrue(message, message.contains("x = 1 @ 2;"));
            assertTrue(message, message.contains("^"));
        }
    }

    @Test
    public void lexerReportsUnclosedString() throws Exception {
        File file = writeSource("Test",
                "class Test {\n" +
                "    void main() {\n" +
                "        printf(\"hello);\n" +
                "    }\n" +
                "}\n");
        try {
            new Lexer(file).lexicalAnalysis();
            fail("Expected LexException");
        } catch (LexException e) {
            String message = e.getMessage();
            assertTrue(message, message.contains("unclosed string literal"));
            assertTrue(message, message.contains("printf(\"hello);"));
            assertTrue(message, message.contains("^"));
        }
    }

    @Test
    public void lexerReportsSingleAmpersandAndPipe() throws Exception {
        assertLexErrorContains(
                "class Test { void main() { bool a; bool b; a = true; b = false; if (a & b) {} } }",
                "did you mean '&&'");
        assertLexErrorContains(
                "class Test { void main() { bool a; bool b; a = true; b = false; if (a | b) {} } }",
                "did you mean '||'");
    }

    @Test
    public void cliReportsLexicalErrors() throws Exception {
        File file = writeSource("CliLexDiagnostics",
                "class CliLexDiagnostics {\n" +
                "    void main() {\n" +
                "        int x;\n" +
                "        x = 1 @ 2;\n" +
                "    }\n" +
                "}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath()},
                new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"));

        String errorOutput = err.toString("UTF-8");
        assertEquals(1, exitCode);
        assertTrue(errorOutput, errorOutput.contains("compile failed"));
        assertTrue(errorOutput, errorOutput.contains("illegal character '@'"));
        assertTrue(errorOutput, errorOutput.contains("^"));
    }

    @Test
    public void lexerSupportsUnderscoreIdentifiers() throws Exception {
        File file = writeSource("Test",
                "class Test {\n" +
                "    void main() {\n" +
                "        int sum_count;\n" +
                "        sum_count = 3;\n" +
                "        printf(\"x=%d\\n\", sum_count);\n" +
                "    }\n" +
                "}\n");
        Lexer lexer = new Lexer(file);
        lexer.lexicalAnalysis();

        boolean found = false;
        for (Token token : lexer.tokens) {
            if (token.kind == TokenKind.Id && "sum_count".equals(token.lexeme)) {
                found = true;
                break;
            }
        }
        assertTrue("underscore identifier should be a single Id token", found);
    }

    @Test
    public void cliCompilesUnderscoreIdentifiers() throws Exception {
        File file = writeSource("Under_score",
                "class Under_score {\n" +
                "    void main() {\n" +
                "        int sum_count;\n" +
                "        sum_count = 3;\n" +
                "        printf(\"x=%d\\n\", sum_count);\n" +
                "    }\n" +
                "}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath()},
                new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"));

        assertEquals(err.toString("UTF-8"), 0, exitCode);
    }

    @Test
    public void cliSuccessfulCompileIsQuietByDefault() throws Exception {
        File file = writeSource("QuietCli",
                "class QuietCli {\n" +
                "    void main() {\n" +
                "        printf(\"ok\\n\");\n" +
                "    }\n" +
                "}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath()},
                new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"));

        assertEquals(err.toString("UTF-8"), 0, exitCode);
        assertEquals("", out.toString("UTF-8"));
        assertEquals("", err.toString("UTF-8"));
    }

    @Test
    public void cliVerboseShowsGenerationOutput() throws Exception {
        File file = writeSource("VerboseCli",
                "class VerboseCli {\n" +
                "    void main() {\n" +
                "        printf(\"ok\\n\");\n" +
                "    }\n" +
                "}\n");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{file.getPath(), "--verbose"},
                new PrintStream(out, true, "UTF-8"),
                new PrintStream(err, true, "UTF-8"));

        String output = out.toString("UTF-8");
        assertEquals(err.toString("UTF-8"), 0, exitCode);
        assertTrue(output, output.contains("Generated:"));
        assertEquals("", err.toString("UTF-8"));
    }

    @Test
    public void testArray() throws Exception {
        String source = "class Test {\n" +
                "  void main() {\n" +
                "    int arr[5];\n" +
                "    arr[0] = 1;\n" +
                "    printf(\"arr[0]=%d\\n\", arr[0]);\n" +
                "  }\n" +
                "}\n" +
                "";

        compileAndRun(source, "arr[0]=1\n");
    }

    @Test
    public void testNestedArray() throws Exception {
        String source = "class Test {\n" +
                "  void main() {\n" +
                "    int arr1[2];\n" +
                "    int arr2[2];\n" +
                "    arr1[0] = 1;\n" +
                "    arr1[1] = 2;\n" +
                "    arr2[0] = 3;\n" +
                "    arr2[1] = 4;\n" +
                "    printf(\"arr1[0]=%d\\n\", arr1[0]);\n" +
                "    printf(\"arr2[1]=%d\\n\", arr2[1]);\n" +
                "  }\n" +
                "}\n" +
                "";

        compileAndRun(source, "arr1[0]=1\narr2[1]=4\n");
    }

    @Test
    public void lexerSkipsMultilineCommentsAndKeepsLineNumbers() throws Exception {
        File file = writeSource("Test",
                "class Test {\n" +
                "    /* comment line 1\n" +
                "       comment line 2 */\n" +
                "    void main() {}\n" +
                "}\n");
        Lexer lexer = new Lexer(file);
        lexer.lexicalAnalysis();

        Token voidToken = findToken(lexer.tokens, TokenKind.Void);
        assertEquals(4, voidToken.lineNumber);
        assertEquals(5, voidToken.columnNumber);
    }

    @Test
    public void lexerReportsUnclosedMultilineComment() throws Exception {
        File file = writeSource("Test",
                "class Test {\n" +
                "    /* comment starts\n" +
                "    void main() {}\n" +
                "}\n");
        try {
            new Lexer(file).lexicalAnalysis();
            fail("Expected LexException");
        } catch (LexException e) {
            String message = e.getMessage();
            assertTrue(message, message.contains("unclosed multiline comment"));
            assertTrue(message, message.contains("/* comment starts"));
            assertTrue(message, message.contains("^"));
        }
    }

    private static Token findToken(List<Token> tokens, TokenKind kind) {
        for (Token token : tokens) {
            if (token.kind == kind) {
                return token;
            }
        }
        throw new AssertionError("Token not found: " + kind);
    }

    private static boolean contains(List<String> values, String part) {
        for (String value : values) {
            if (value.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static void assertLexErrorContains(String source, String expected) throws Exception {
        File file = writeSource("Test", source);
        try {
            new Lexer(file).lexicalAnalysis();
            fail("Expected LexException");
        } catch (LexException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(expected));
        }
    }

    private static File writeSource(String className, String source) throws Exception {
        File dir = new File("test_tmp");
        dir.mkdirs();
        File file = new File(dir, className + ".lemon");
        Files.write(file.toPath(), source.getBytes(StandardCharsets.UTF_8));
        file.deleteOnExit();
        return file;
    }
    
    private void compileAndRun(String source, String expectedOutput) throws Exception {
        File sourceFile = writeSource("Test", source);
        
        // 1. Lexical analysis
        Lexer lexer = new Lexer(sourceFile);
        assertNotNull(lexer);

        // 2. Syntax analysis
        Parser parser = new Parser(lexer);
        Ast.Program.T program = parser.parse();
        assertNotNull(program);

        // 3. Semantic analysis
        SemanticVisitor semantic = new SemanticVisitor();
        semantic.visit(program);
        assertTrue(semantic.passOrNot());

        // 4. IR translation
        program = new AstOptimizer().optimize(program);
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(program);
        assertNotNull(translator.prog);
        assertNotNull(translator.prog.mainClass);

        // 5. Bytecode generation
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);

        // 6. Verify .il file is generated
        File ilFile = generator.getOutputFile();
        String ilFileName = ilFile.getPath();
        assertTrue(ilFile.exists());
        assertTrue(ilFile.length() > 0);

        // 7. Jasmin assembler -> .class
        assembleWithJasmin(generator.getOutputDir(), ilFileName);

        File classFile = generator.getClassFile(translator.prog.mainClass.id);
        assertTrue(classFile.exists());
        assertTrue(classFile.length() > 0);

        // 8. Run and verify output
        Process process = new ProcessBuilder(javaExecutable(),
                "-Dfile.encoding=UTF-8",
                "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
                "-cp", generator.getOutputDir().getPath(), "Test")
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("JVM execution timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Waiting for JVM execution was interrupted");
        }

        String output = normalizeNewlines(readAll(process.getInputStream()));
        assertEquals("JVM exit code should be 0, output was:\n" + output, 0, process.exitValue());
        assertEquals("JVM output did not match expected",
                normalizeNewlines(expectedOutput), output);
    }
    
    private void assembleWithJasmin(File outputDir, String ilFileName) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream quiet = new PrintStream(sink, true, "UTF-8");
        try {
            System.setOut(quiet);
            System.setErr(quiet);
            jasmin.Main.main(new String[]{"-d", outputDir.getPath(), ilFileName});
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            quiet.close();
        }
    }

    private String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return new File(new File(System.getProperty("java.home"), "bin"), executable).getPath();
    }

    private String normalizeNewlines(String s) {
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }
    
    private String readAll(InputStream inputStream) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[1024];
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toString("UTF-8");
    }
}
