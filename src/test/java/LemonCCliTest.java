import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import site.ilemon.compiler.LemonC;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Tests for the LemonC command-line interface.
 */
public class LemonCCliTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void testRunSuccess() throws Exception {
        // Setup
        Path tempDir = temporaryFolder.getRoot().toPath();
        Path sourceFile = tempDir.resolve("Test.lemon");
        // Using syntax similar to existing examples
        Files.write(sourceFile, "class Test { void main() { int x; x = 123; printf(\"%d\", x); } }".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(outContent);
        PrintStream errStream = new PrintStream(errContent);

        // Execute
        int exitCode = LemonC.run(new String[]{sourceFile.toString()}, outStream, errStream);

        // Assert
        assertEquals(0, exitCode);
        // Success might still produce some output (e.g. from printf in the program or verbose Jasmin steps if enabled internally)
        // The key is that errContent should be empty or contain no errors.
        assertTrue(errContent.toString().isEmpty() || !errContent.toString().contains("error"));
    }

    @Test
    public void testRunTopLevelMainWithoutClassWrapper() throws Exception {
        Path sourceFile = temporaryFolder.getRoot().toPath().resolve("TopLevel.lemon");
        Files.writeString(sourceFile, "void main() { int x; x = 123; printf(\"%d\", x); }", StandardCharsets.UTF_8);
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{sourceFile.toString()},
                new PrintStream(outContent), new PrintStream(errContent));

        assertEquals(0, exitCode);
        assertTrue("top-level compilation failed: " + errContent, errContent.toString().isEmpty());
    }

    @Test
    public void testRunTopLevelFunctionsAndArraySyntax() throws Exception {
        Path sourceFile = temporaryFolder.getRoot().toPath().resolve("TopLevelFunctions.lemon");
        Files.writeString(sourceFile,
                "int add(int left, int right) { return left + right; }\n"
                        + "void main() { int values[2]; values[0] = 20; values[1] = 22; printf(\"%d,%d\", add(values[0], values[1]), values.length); }\n",
                StandardCharsets.UTF_8);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int exitCode = LemonC.run(new String[]{sourceFile.toString()},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors));

        assertEquals("top-level function compilation failed: " + errors, 0, exitCode);
    }

    @Test
    public void testRunWithVerboseOption() throws Exception {
        // Setup
        Path tempDir = temporaryFolder.getRoot().toPath();
        Path sourceFile = tempDir.resolve("Test.lemon");
        Files.write(sourceFile, "class Test { void main() { int x; x = 123; printf(\"%d\", x); } }".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(outContent);
        PrintStream errStream = new PrintStream(errContent);

        // Execute
        int exitCode = LemonC.run(new String[]{sourceFile.toString(), "--verbose"}, outStream, errStream);

        // Assert
        assertEquals(0, exitCode);
        // Verbose mode might produce more output, just check for no errors.
        assertTrue(!errContent.toString().contains("error")); // No error on success
    }

    @Test
    public void testRunWithDumpOptions() throws Exception {
        // Setup
        Path tempDir = temporaryFolder.getRoot().toPath();
        Path sourceFile = tempDir.resolve("Test.lemon");
        Files.write(sourceFile, "class Test { void main() { int x; x = 123; printf(\"%d\", x); } }".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(outContent);
        PrintStream errStream = new PrintStream(errContent);

        // Execute with --dump-ast
        int exitCode = LemonC.run(new String[]{sourceFile.toString(), "--dump-ast"}, outStream, errStream);

        // Assert
        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("== AST ==")); // Check if AST dump header is present
        assertTrue(errContent.toString().isEmpty()); // No error on success

        // Reset streams
        outContent.reset();
        errContent.reset();

        // Execute with --dump-ir
        exitCode = LemonC.run(new String[]{sourceFile.toString(), "--dump-ir"}, outStream, errStream);

        // Assert
        assertEquals(0, exitCode);
        assertTrue(outContent.toString().contains("== IR ==")); // Check if IR dump header is present
        assertTrue(errContent.toString().isEmpty()); // No error on success
    }

    @Test
    public void testRunInvalidFileExtension() throws Exception {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(outContent);
        PrintStream errStream = new PrintStream(errContent);

        // Execute
        int exitCode = LemonC.run(new String[]{"Test.txt"}, outStream, errStream);

        // Assert
        assertEquals(1, exitCode);
        assertTrue(errContent.toString().contains("error: source file must end with .lemon"));
    }

    @Test
    public void testRunFileNotFound() throws Exception {
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(outContent);
        PrintStream errStream = new PrintStream(errContent);

        // Execute
        int exitCode = LemonC.run(new String[]{"NonExistent.lemon"}, outStream, errStream);

        // Assert
        assertEquals(1, exitCode);
        assertTrue(errContent.toString().contains("error: file does not exist"));
    }

    @Test
    public void testRunInvalidOption() throws Exception {
        // Setup
        Path tempDir = temporaryFolder.getRoot().toPath();
        Path sourceFile = tempDir.resolve("Test.lemon");
        Files.write(sourceFile, "class Test { void main() { int x; x = 123; printf(\"%d\", x); } }".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        ByteArrayOutputStream errContent = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(outContent);
        PrintStream errStream = new PrintStream(errContent);

        // Execute
        int exitCode = LemonC.run(new String[]{sourceFile.toString(), "--invalid-option"}, outStream, errStream);

        // Assert
        assertEquals(1, exitCode);
        assertTrue(errContent.toString().contains("error: unknown option - --invalid-option"));
    }
}
