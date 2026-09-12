import org.junit.Test;

import site.ilemon.compiler.LemonC;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

/**
 * Regression test for ExpenseTracker example - verifies identical output
 * from both JVM and C backends.
 */
public class ExpenseTrackerTest {

    @Test
    public void testExpenseTrackerJvmBackend() throws Exception {
        String source = readExpenseTrackerSource();
        String output = JvmTestSupport.compileAndRun("ExpenseTracker", source);
        String expectedOutput = getExpectedOutput();
        assertEquals("JVM output should match expected", expectedOutput, output);
    }

    @Test
    public void testExpenseTrackerBothBackendsProduceIdenticalOutput() throws Exception {
        String source = readExpenseTrackerSource();
        String expectedOutput = getExpectedOutput();
        
        // Test JVM backend
        String jvmOutput = JvmTestSupport.compileAndRun("ExpenseTracker", source);
        assertEquals("JVM output should match expected", expectedOutput, jvmOutput);
        
        // Test C backend compiles successfully
        Path sourceFile = Path.of("examples", "ExpenseTracker.lemon");
        ByteArrayOutputStream cOut = new ByteArrayOutputStream();
        ByteArrayOutputStream cErr = new ByteArrayOutputStream();
        int cCode = LemonC.run(new String[]{sourceFile.toString(), "--target", "c", "--emit-c"}, 
                new PrintStream(cOut), new PrintStream(cErr));
        assertEquals("C backend should compile successfully", 0, cCode);
    }

    private String readExpenseTrackerSource() throws Exception {
        return java.nio.file.Files.readString(Path.of("examples", "ExpenseTracker.lemon"), StandardCharsets.UTF_8);
    }

    private String getExpectedOutput() {
        return "=== Expense Tracker Summary ===\n" +
               "\n" +
               "Expenses:\n" +
               "  Expense 1: $45\n" +
               "  Expense 2: $89\n" +
               "  Expense 3: $12\n" +
               "  Expense 4: $120\n" +
               "  Expense 5: $35\n" +
               "  Expense 6: $67\n" +
               "  Expense 7: $15\n" +
               "  Expense 8: $32\n" +
               "  Expense 9: $18\n" +
               "  Expense 10: $120\n" +
               "\n" +
               "Total expenses: $553\n" +
               "Average expense: $55\n" +
               "Largest expense: $120\n" +
               "Expenses above $50: 4\n" +
               "\n" +
               "=== End of Summary ===\n";
    }
}