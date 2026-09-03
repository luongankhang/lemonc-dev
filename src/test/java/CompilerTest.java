import org.junit.Before;
import org.junit.Test;
import site.ilemon.ast.Ast;
import site.ilemon.codegen.ByteCodeGenerator;
import site.ilemon.codegen.TranslatorVisitor;
import site.ilemon.codegen.ast.Label;
import site.ilemon.exception.CompilerException;
import site.ilemon.lexer.Lexer;
import site.ilemon.optimizer.AstOptimizer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * End-to-end integration tests for the compiler.
 * Each .lemon example file has an independent @Test method,
 * verifying the complete compilation pipeline: Lexer -> Parser -> SemanticVisitor -> TranslatorVisitor -> ByteCodeGenerator -> Jasmin.
 */
public class CompilerTest {

    @Before
    public void setUp() {
        Label.resetCounter();
    }

    // ===== Basic arithmetic =====
    @Test public void testCal() throws IOException { compileAndVerify("Cal", "10的阶乘是3628800"); }
    @Test public void testCal01() throws IOException { compileAndVerify("Cal01", "k2=19,n=3615\n"); }
    // Example01/03/05 class name does not match file name (TestMain/MulTable), known issue
    @Test public void testExample02() throws IOException { compileAndVerify("Example02"); }

    // ===== Integer operations =====
    @Test public void testIntTest01() throws IOException {
        compileAndVerify("IntTest01",
                "num1=20,num2=2,num1+num2+19=41,num1-num2=18,num1*num2=40,num1/num2=10");
    }

    @Test public void testModTest() throws IOException {
        compileAndVerify("ModTest", "a=1,b=8,c=5\n");
    }

    // ===== Floating point operations =====
    @Test public void testFloatTest01() throws IOException {
        compileAndVerify("FloatTest01",
                "num1=19.0,num2=1.9,num1+num2+19.9=40.8,num1-num2=17.1,num1*num2=36.1,num1/num2=10.0");
    }
    @Test public void testFloatTest02() throws IOException {
        compileAndVerify("FloatTest02", "b=1.8\n");
    }

    // ===== Double operations =====
    @Test public void testDoubleTest01() throws IOException {
        compileAndVerify("DoubleTest01", "num1=19.0,num2=1.9\n");
    }
    @Test public void testDoubleTest02() throws IOException {
        compileAndVerify("DoubleTest02",
                "a=3.14159265358979\n"
                        + "b=2.71828182845904\n"
                        + "c=5.859874482048831\n");
    }

    @Test public void testDoubleCompareTest() throws IOException {
        compileAndVerify("DoubleCompareTest",
                "lt=1\n"
                        + "gte=1\n"
                        + "eq=0\n"
                        + "neq=1\n"
                        + "if=1\n");
    }

    // ===== Boolean expressions =====
    @Test public void testBoolTest01() throws IOException { compileAndVerify("BoolTest01"); }
    // BoolTest02 class name written as BoolTest01, known file name mismatch issue
    @Test public void testBoolTest03() throws IOException { compileAndVerify("BoolTest03"); }
    @Test public void testBoolTest04() throws IOException {
        compileAndVerify("BoolTest04",
                "b=0\n"
                        + "b=3\n"
                        + "b=5\n"
                        + "b=6\n"
                        + "b=9\n"
                        + "b=10\n"
                        + "b=13\n"
                        + "b=15\n");
    }
    @Test public void testBoolTest05() throws IOException {
        compileAndVerify("BoolTest05",
                "b=1\n"
                        + "b=2\n"
                        + "b=4\n"
                        + "b=6\n"
                        + "b=9\n"
                        + "b=10\n");
    }
    @Test public void testBoolTest06() throws IOException { compileAndVerify("BoolTest06"); }
    @Test public void testBoolTest07() throws IOException { compileAndVerify("BoolTest07"); }
    @Test public void testBoolTest08() throws IOException { compileAndVerify("BoolTest08"); }
    @Test public void testBoolTest10() throws IOException { compileAndVerify("BoolTest10"); }
    @Test public void testBoolTest11() throws IOException { compileAndVerify("BoolTest11", "b=1\n"); }
    @Test public void testBoolTest12() throws IOException { compileAndVerify("BoolTest12"); }
    @Test public void testBoolTest13() throws IOException { compileAndVerify("BoolTest13"); }
    @Test public void testBoolTest14() throws IOException { compileAndVerify("BoolTest14"); }
    @Test public void testBoolTest15() throws IOException { compileAndVerify("BoolTest15"); }
    @Test public void testBoolTest16() throws IOException {
        compileAndVerify("BoolTest16",
                "a=1\n"
                        + "a=3\n"
                        + "a=6\n"
                        + "a=7\n");
    }

    // ===== If conditions =====
    @Test public void testIf01() throws IOException { compileAndVerify("If01"); }
    @Test public void testIf02() throws IOException { compileAndVerify("If02"); }
    @Test public void testIf03() throws IOException { compileAndVerify("If03"); }
    // If04 syntax issue (missing left brace in if body), known issue
    @Test public void testIf05() throws IOException { compileAndVerify("If05"); }
    @Test public void testIf06() throws IOException { compileAndVerify("If06"); }
    @Test public void testIf07() throws IOException { compileAndVerify("If07"); }
    @Test public void testIf08() throws IOException { compileAndVerify("If08"); }
    @Test public void testIf09() throws IOException { compileAndVerify("If09"); }
    @Test public void testIf10() throws IOException { compileAndVerify("If10"); }
    @Test public void testIf11() throws IOException { compileAndVerify("If11"); }
    @Test public void testIf12() throws IOException { compileAndVerify("If12", "a=100"); }
    @Test public void testIf13() throws IOException {
        compileAndVerify("If13",
                "a=19\n"
                        + "a=22\n"
                        + "a=24\n"
                        + "a=25\n"
                        + "a=26\n"
                        + "a=31\n"
                        + "a=33\n"
                        + "a=0\n"
                        + "a=35\n"
                        + "a=36\n");
    }

    // ===== Loops =====
    @Test public void testIteration01() throws IOException { compileAndVerify("Iteration01"); }
    @Test public void testIteration02() throws IOException { compileAndVerify("Iteration02"); }
    @Test public void testIteration03() throws IOException { compileAndVerify("Iteration03"); }
    @Test public void testIteration04() throws IOException { compileAndVerify("Iteration04"); }
    @Test public void testIteration05() throws IOException { compileAndVerify("Iteration05"); }
    @Test public void testIteration06() throws IOException {
        compileAndVerify("Iteration06", "start = 20c = 10.0");
    }
    @Test public void testIterationDemo() throws IOException { compileAndVerify("IterationDemo"); }
    @Test public void testGauss() throws IOException {
        compileAndVerify("Gauss", "起始数字是：1,结束数字是：100\n1加到100的和是5050");
    }
    @Test public void testMulTable() throws IOException { compileAndVerify("MulTable"); }

    // ===== Method calls =====
    @Test public void testMethodCallTest01() throws IOException { compileAndVerify("MethodCallTest01"); }
    @Test public void testMethodCallTest02() throws IOException { compileAndVerify("MethodCallTest02"); }
    @Test public void testMethodCallTest03() throws IOException { compileAndVerify("MethodCallTest03"); }
    @Test public void testMethodCallTest04() throws IOException { compileAndVerify("MethodCallTest04"); }
    @Test public void testSimpleMethodCall() throws IOException {
        compileAndVerify("SimpleMethodCall", "x=186,y=162,z=348");
    }
    @Test public void testSimpleMethodCallTwo() throws IOException { compileAndVerify("SimpleMethodCallTwo"); }
    @Test public void testSimpleMethodCallThree() throws IOException {
        compileAndVerify("SimpleMethodCallThree", "x=186.0,y=162.0,z=188.0");
    }
    @Test public void testSimpleMethodCallFour() throws IOException { compileAndVerify("SimpleMethodCallFour"); }

    // ===== Comprehensive =====
    @Test public void testFib() throws IOException {
        compileAndVerify("Fib",
                "递归计算斐波那契数列，一年后总共有144对兔子\n"
                        + "循环计算斐波那契数列，一年后总共有144对兔子\n");
    }
    @Test public void testCalHeightOfChild() throws IOException { compileAndVerify("CalHeightOfChild"); }
    @Test public void testCompareTest() throws IOException { compileAndVerify("CompareTest"); }
    // Return.lemon class name is TestMain which does not match file name, known issue
    @Test public void testHelloWorld() throws IOException { compileAndVerify("Test"); }
    @Test public void testTestTwo() throws IOException { compileAndVerify("TestTwo"); }

    // ===== Arrays =====
    @Test public void testArrayTest01() throws IOException {
        compileAndVerify("ArrayTest01",
                "arr[0] = 1\n"
                        + "arr[1] = 2\n"
                        + "arr[2] = 3\n"
                        + "arr[3] = 4\n"
                        + "arr[4] = 5\n");
    }
    @Test public void testArrayTest02() throws IOException {
        compileAndVerify("ArrayTest02",
                "Float array: 0\n"
                        + "1.1 2.2 3.3 \n"
                        + "Double array: 0\n"
                        + "10.010000228881836 20.020000457763672 30.030000686645508 \n");
    }

    @Test public void testArrayLengthTest() throws IOException {
        compileAndVerify("ArrayLengthTest", "values=5,weights=3,total=8\n");
    }
    @Test public void testArrayParamTest() throws IOException {
        compileAndVerify("ArrayParamTest",
                "int=99,sum=103,len=3\n"
                        + "float=4.0,double=7.25\n"
                        + "flag=1\n");
    }

    @Test public void testArrayParamIlDescriptorsAndNoFormalAllocation() throws IOException {
        compileAndVerify("ArrayParamTest");
        String il = new String(Files.readAllBytes(new File("target/lemonc/ArrayParamTest.il").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(il.contains(".method static setInt([III)V"));
        assertTrue(il.contains(".method static sumInt([I)I"));
        assertTrue(il.contains(".method static bumpFloat([F)V"));
        assertTrue(il.contains(".method static setDouble([DID)V"));
        assertTrue(il.contains(".method static setFlag([ZII)V"));
        assertFalse(methodBody(il, "setInt").contains("newarray"));
        assertFalse(methodBody(il, "sumInt").contains("newarray"));
        assertFalse(methodBody(il, "bumpFloat").contains("newarray"));
        assertFalse(methodBody(il, "setDouble").contains("newarray"));
        assertFalse(methodBody(il, "setFlag").contains("newarray"));
    }

    @Test public void testBubbleSort() throws IOException {
        compileAndVerify("BubbleSort",
                "排序前: 0\n"
                        + "64 34 25 12 22 11 \n"
                        + "排序后: 0\n"
                        + "11 12 22 25 34 64 \n");
    }

    @Test public void testRecursiveMergeSort() throws IOException {
        compileAndVerify("RecursiveMergeSort", "3 9 10 19 27 38 43 82 \n");
    }

    @Test
    public void testNestedLoopsBreakAndContinueOutput() throws IOException {
        compileAndVerify("NestedLoops",
                "  inner run i=1, j=1\n"
                        + "  inner break on 2\n"
                        + "outer continue skip 2\n"
                        + "  inner run i=3, j=1\n"
                        + "  inner break on 2\n");
    }

    // ===== Multiple compilations (verifying Label.resetCounter) =====
    @Test
    public void testVoidMethodOutput() throws IOException {
        compileAndVerify("VoidMethod", "before\nhello from void\nafter\n");
    }

    @Test
    public void testVoidEmptyMethodOutput() throws IOException {
        compileAndVerify("VoidEmptyMethod", "done\n");
    }

    @Test
    public void testPrintfLiteralOutput() throws IOException {
        compileAndVerify("PrintfLiteral", "hello literal\n");
    }

    @Test
    public void testPrintfMixedOutput() throws IOException {
        compileAndVerify("PrintfMixed", "i=7, f=1.5, d=2.25\n");
    }

    @Test
    public void testReliabilityCanaryOutput() throws IOException {
        compileAndVerify("ReliabilityCanary",
                "start\n"
                        + "sum=10\n"
                        + "f=1.5,d=2.25\n"
                        + "arrays=1.25,2.5,4.5,3.5\n"
                        + "bool=1\n"
                        + "fib=8\n"
                        + "discard-int\n"
                        + "discard-double\n"
                        + "void-call\n"
                        + "loop=1\n"
                        + "loop=3\n"
                        + "end\n");
    }

    @Test
    public void testMultipleCompilationsLabelReset() throws IOException {
        // Compile two different files sequentially to verify label counter does not collide
        compileAndVerify("Cal");
        Label.resetCounter();
        compileAndVerify("Fib");
    }

    // ======================== Infrastructure ========================

    /**
     * Compiles a .lemon file and verifies the output of each compilation phase.
     */
    private void compileAndVerify(String name) throws IOException {
        compileAndVerify(name, null);
    }

    /**
     * Compiles a .lemon file; when expectedOutput is provided, runs the generated JVM class and asserts stdout.
     */
    private void compileAndVerify(String name, String expectedOutput) throws IOException {
        File sourceFile = new File("examples/" + name + ".lemon");
        assertTrue("source file should exist: " + sourceFile.getPath(), sourceFile.exists());

        // 1. Lexical analysis
        Lexer lexer = new Lexer(sourceFile);
        assertNotNull("Lexer should not be null", lexer);

        // 2. Syntax analysis
        Parser parser = new Parser(lexer);
        Ast.Program.T program = parser.parse();
        assertNotNull("AST should not be null", program);

        // 3. Semantic analysis
        SemanticVisitor semantic = new SemanticVisitor();
        semantic.visit(program);
        assertTrue("semantic analysis should pass: " + name, semantic.passOrNot());

        // 4. IR translation
        program = new AstOptimizer().optimize(program);
        TranslatorVisitor translator = new TranslatorVisitor();
        translator.visit(program);
        assertNotNull("IR program should not be null", translator.prog);
        assertNotNull("IR main class should not be null", translator.prog.mainClass);

        // 5. Bytecode generation
        ByteCodeGenerator generator = new ByteCodeGenerator();
        generator.visit(translator.prog);

        // 6. Verify .il file is generated
        File ilFile = generator.getOutputFile();
        String ilFileName = ilFile.getPath();
        assertTrue(".il file should exist: " + ilFileName, ilFile.exists());
        assertTrue(".il file should not be empty: " + ilFileName, ilFile.length() > 0);

        // 7. Jasmin assembler -> .class
        assembleWithJasmin(generator.getOutputDir(), ilFileName);
        File classFile = generator.getClassFile(translator.prog.mainClass.id);
        assertTrue(".class file should exist: " + name, classFile.exists());
        assertTrue(".class file should not be empty: " + name, classFile.length() > 0);

        if (expectedOutput != null) {
            assertJvmOutput(translator.prog.mainClass.id, generator.getOutputDir(), expectedOutput);
        }
    }

    private void assertJvmOutput(String className, File classDir, String expectedOutput) throws IOException {
        Process process = new ProcessBuilder(javaExecutable(),
                "-Dfile.encoding=UTF-8",
                "-Dsun.stdout.encoding=UTF-8",
                "-Dsun.stderr.encoding=UTF-8",
                "-cp", classDir.getPath(), className)
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("JVM execution timed out: " + className);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("Waiting for JVM execution was interrupted: " + className);
        }

        String output = normalizeNewlines(readAll(process.getInputStream()));
        assertEquals("JVM exit code should be 0, output was:\n" + output, 0, process.exitValue());
        assertEquals("JVM output did not match expected: " + className,
                normalizeNewlines(expectedOutput), output);
    }

    private void assembleWithJasmin(File outputDir, String ilFileName) throws IOException {
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

    private String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int read;
        while ((read = stream.read(data)) != -1) {
            buffer.write(data, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private String normalizeNewlines(String value) {
        return value.replace("\r\n", "\n").replace("\r", "\n");
    }

    private String methodBody(String il, String methodName) {
        String marker = ".method static " + methodName + "(";
        int start = il.indexOf(marker);
        assertTrue("Missing method in IL: " + methodName, start >= 0);
        int end = il.indexOf(".end method", start);
        assertTrue("Missing method end in IL: " + methodName, end > start);
        return il.substring(start, end);
    }
}
