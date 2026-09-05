import org.junit.Test;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;
import site.ilemon.ast.Ast;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.*;

/**
 * End-to-end integration tests for the compiler.
 * Each .lemon example file has an independent @Test method,
 * verifying the complete compilation pipeline:
 * Lexer -> Parser -> SemanticVisitor -> Optimizer -> LemonIR -> JvmBackend (direct bytecode, no Jasmin).
 */
public class CompilerTest {

    // ===== Basic arithmetic =====
    @Test public void testCal() throws Exception { compileAndVerify("Cal", "10的阶乘是3628800"); }
    @Test public void testCal01() throws Exception { compileAndVerify("Cal01", "k2=19,n=3615\n"); }
    // Example01/03/05 (formerly class name mismatch issue)
    @Test public void testExample02() throws Exception { compileAndVerify("Example02"); }

    // ===== Integer operations =====
    @Test public void testIntTest01() throws Exception {
        compileAndVerify("IntTest01",
                "num1=20,num2=2,num1+num2+19=41,num1-num2=18,num1*num2=40,num1/num2=10");
    }

    @Test public void testModTest() throws Exception {
        compileAndVerify("ModTest", "a=1,b=8,c=5\n");
    }

    // ===== Floating point operations =====
    @Test public void testFloatTest01() throws Exception {
        compileAndVerify("FloatTest01",
                "num1=19.0,num2=1.9,num1+num2+19.9=40.8,num1-num2=17.1,num1*num2=36.1,num1/num2=10.0");
    }
    @Test public void testFloatTest02() throws Exception {
        compileAndVerify("FloatTest02", "b=1.8\n");
    }

    // ===== Double operations =====
    @Test public void testDoubleTest01() throws Exception {
        compileAndVerify("DoubleTest01", "num1=19.0,num2=1.9\n");
    }
    @Test public void testDoubleTest02() throws Exception {
        compileAndVerify("DoubleTest02",
                "a=3.14159265358979\n"
                        + "b=2.71828182845904\n"
                        + "c=5.859874482048831\n");
    }

    @Test public void testDoubleCompareTest() throws Exception {
        compileAndVerify("DoubleCompareTest",
                "lt=1\n"
                        + "gte=1\n"
                        + "eq=0\n"
                        + "neq=1\n"
                        + "if=1\n");
    }

    // ===== Boolean expressions =====
    @Test public void testBoolTest01() throws Exception { compileAndVerify("BoolTest01"); }
    // BoolTest02 (formerly class name mismatch issue)
    @Test public void testBoolTest03() throws Exception { compileAndVerify("BoolTest03"); }
    @Test public void testBoolTest04() throws Exception {
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
    @Test public void testBoolTest05() throws Exception {
        compileAndVerify("BoolTest05",
                "b=1\n"
                        + "b=2\n"
                        + "b=4\n"
                        + "b=6\n"
                        + "b=9\n"
                        + "b=10\n");
    }
    @Test public void testBoolTest06() throws Exception { compileAndVerify("BoolTest06"); }
    @Test public void testBoolTest07() throws Exception { compileAndVerify("BoolTest07"); }
    @Test public void testBoolTest08() throws Exception { compileAndVerify("BoolTest08"); }
    @Test public void testBoolTest10() throws Exception { compileAndVerify("BoolTest10"); }
    @Test public void testBoolTest11() throws Exception { compileAndVerify("BoolTest11", "b=1\n"); }
    @Test public void testBoolTest12() throws Exception { compileAndVerify("BoolTest12"); }
    @Test public void testBoolTest13() throws Exception { compileAndVerify("BoolTest13"); }
    @Test public void testBoolTest14() throws Exception { compileAndVerify("BoolTest14"); }
    @Test public void testBoolTest15() throws Exception { compileAndVerify("BoolTest15"); }
    @Test public void testBoolTest16() throws Exception {
        compileAndVerify("BoolTest16",
                "a=1\n"
                        + "a=3\n"
                        + "a=6\n"
                        + "a=7\n");
    }

    // ===== If conditions =====
    @Test public void testIf01() throws Exception { compileAndVerify("If01"); }
    @Test public void testIf02() throws Exception { compileAndVerify("If02"); }
    @Test public void testIf03() throws Exception { compileAndVerify("If03"); }
    // If04 syntax issue (missing left brace in if body), known issue
    @Test public void testIf05() throws Exception { compileAndVerify("If05"); }
    @Test public void testIf06() throws Exception { compileAndVerify("If06"); }
    @Test public void testIf07() throws Exception { compileAndVerify("If07"); }
    @Test public void testIf08() throws Exception { compileAndVerify("If08"); }
    @Test public void testIf09() throws Exception { compileAndVerify("If09"); }
    @Test public void testIf10() throws Exception { compileAndVerify("If10"); }
    @Test public void testIf11() throws Exception { compileAndVerify("If11"); }
    @Test public void testIf12() throws Exception { compileAndVerify("If12", "a=100"); }
    @Test public void testIf13() throws Exception {
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
    @Test public void testIteration01() throws Exception { compileAndVerify("Iteration01"); }
    @Test public void testIteration02() throws Exception { compileAndVerify("Iteration02"); }
    @Test public void testIteration03() throws Exception { compileAndVerify("Iteration03"); }
    @Test public void testIteration04() throws Exception { compileAndVerify("Iteration04"); }
    @Test public void testIteration05() throws Exception { compileAndVerify("Iteration05"); }
    @Test public void testIteration06() throws Exception {
        compileAndVerify("Iteration06", "start = 20c = 10.0");
    }
    @Test public void testIterationDemo() throws Exception { compileAndVerify("IterationDemo"); }
    @Test public void testGauss() throws Exception {
        compileAndVerify("Gauss", "起始数字是：1,结束数字是：100\n1加到100的和是5050");
    }
    @Test public void testMulTable() throws Exception { compileAndVerify("MulTable"); }

    // ===== Method calls =====
    @Test public void testMethodCallTest01() throws Exception { compileAndVerify("MethodCallTest01"); }
    @Test public void testMethodCallTest02() throws Exception { compileAndVerify("MethodCallTest02"); }
    @Test public void testMethodCallTest03() throws Exception { compileAndVerify("MethodCallTest03"); }
    @Test public void testMethodCallTest04() throws Exception { compileAndVerify("MethodCallTest04"); }
    @Test public void testSimpleMethodCall() throws Exception {
        compileAndVerify("SimpleMethodCall", "x=186,y=162,z=348");
    }
    @Test public void testSimpleMethodCallTwo() throws Exception { compileAndVerify("SimpleMethodCallTwo"); }
    @Test public void testSimpleMethodCallThree() throws Exception {
        compileAndVerify("SimpleMethodCallThree", "x=186.0,y=162.0,z=188.0");
    }
    @Test public void testSimpleMethodCallFour() throws Exception { compileAndVerify("SimpleMethodCallFour"); }

    // ===== Comprehensive =====
    @Test public void testFib() throws Exception {
        compileAndVerify("Fib",
                "递归计算斐波那契数列，一年后总共有144对兔子\n"
                        + "循环计算斐波那契数列，一年后总共有144对兔子\n");
    }
    @Test public void testCalHeightOfChild() throws Exception { compileAndVerify("CalHeightOfChild"); }
    @Test public void testCompareTest() throws Exception { compileAndVerify("CompareTest"); }
    // Return.lemon (formerly class name mismatch issue)
    @Test public void testHelloWorld() throws Exception { compileAndVerify("Test"); }
    @Test public void testTestTwo() throws Exception { compileAndVerify("TestTwo"); }

    // ===== Arrays =====
    @Test public void testArrayTest01() throws Exception {
        compileAndVerify("ArrayTest01",
                "arr[0] = 1\n"
                        + "arr[1] = 2\n"
                        + "arr[2] = 3\n"
                        + "arr[3] = 4\n"
                        + "arr[4] = 5\n");
    }
    @Test public void testArrayTest02() throws Exception {
        compileAndVerify("ArrayTest02",
                "Float array: 0\n"
                        + "1.1 2.2 3.3 \n"
                        + "Double array: 0\n"
                        + "10.010000228881836 20.020000457763672 30.030000686645508 \n");
    }

    @Test public void testArrayLengthTest() throws Exception {
        compileAndVerify("ArrayLengthTest", "values=5,weights=3,total=8\n");
    }
    @Test public void testArrayParamTest() throws Exception {
        compileAndVerify("ArrayParamTest",
                "int=99,sum=103,len=3\n"
                        + "float=4.0,double=7.25\n"
                        + "flag=1\n");
    }

    @Test public void testArrayParamIlDescriptorsAndNoFormalAllocation() throws Exception {
        compileAndVerify("ArrayParamTest");
        File classFile = new File("target/lemonc/ArrayParamTest.class");
        assertTrue("class file should exist: " + classFile, classFile.exists());
        List<JvmTestSupport.MethodInfo> methods = JvmTestSupport.readMethods(classFile);
        assertEquals("([III)V", method(methods, "setInt").descriptor());
        assertEquals("([I)I", method(methods, "sumInt").descriptor());
        assertEquals("([F)V", method(methods, "bumpFloat").descriptor());
        assertEquals("([DID)V", method(methods, "setDouble").descriptor());
        assertEquals("([ZII)V", method(methods, "setFlag").descriptor());
        // Formals must alias the caller's arrays, never allocate new ones.
        for (String name : new String[]{"setInt", "sumInt", "bumpFloat", "setDouble", "setFlag"}) {
            assertFalse(method(methods, name).name() + " must not allocate a formal array",
                    JvmTestSupport.hasSequence(method(methods, name).code(), 0xBC));
        }
    }

    @Test public void testBubbleSort() throws Exception {
        compileAndVerify("BubbleSort",
                "排序前: 0\n"
                        + "64 34 25 12 22 11 \n"
                        + "排序后: 0\n"
                        + "11 12 22 25 34 64 \n");
    }

    @Test public void testRecursiveMergeSort() throws Exception {
        compileAndVerify("RecursiveMergeSort", "3 9 10 19 27 38 43 82 \n");
    }

    @Test
    public void testNestedLoopsBreakAndContinueOutput() throws Exception {
        compileAndVerify("NestedLoops",
                "  inner run i=1, j=1\n"
                        + "  inner break on 2\n"
                        + "outer continue skip 2\n"
                        + "  inner run i=3, j=1\n"
                        + "  inner break on 2\n");
    }

    @Test
    public void testVoidMethodOutput() throws Exception {
        compileAndVerify("VoidMethod", "before\nhello from void\nafter\n");
    }

    @Test
    public void testVoidEmptyMethodOutput() throws Exception {
        compileAndVerify("VoidEmptyMethod", "done\n");
    }

    @Test
    public void testPrintfLiteralOutput() throws Exception {
        compileAndVerify("PrintfLiteral", "hello literal\n");
    }

    @Test
    public void testPrintfMixedOutput() throws Exception {
        compileAndVerify("PrintfMixed", "i=7, f=1.5, d=2.25\n");
    }

    @Test
    public void testReliabilityCanaryOutput() throws Exception {
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
    public void testMultipleCompilationsAreIndependent() throws Exception {
        // Compile two different files sequentially to verify no shared compiler
        // state leaks between runs.
        compileAndVerify("Cal");
        compileAndVerify("Fib");
    }

    // ======================== Infrastructure ========================

    private void compileAndVerify(String name) throws Exception {
        compileAndVerify(name, null);
    }

    private void compileAndVerify(String name, String expectedOutput) throws Exception {
        File sourceFile = new File("examples/" + name + ".lemon");
        assertTrue("source file should exist: " + sourceFile.getPath(), sourceFile.exists());

        // Frontend analysis must accept the example.
        Lexer lexer = new Lexer(sourceFile);
        Parser parser = new Parser(lexer);
        Ast.Program.T program = parser.parse();
        assertNotNull("AST should not be null", program);
        SemanticVisitor semantic = SemanticVisitor.collecting();
        semantic.visit(program);
        assertTrue("semantic analysis should pass: " + name, semantic.passOrNot());

        // Full pipeline: LemonIR -> JvmBackend -> .class, then run.
        JvmTestSupport.CompiledClass compiled = JvmTestSupport.compile(sourceFile);
        File classFile = compiled.classFile();
        assertTrue(".class file should exist: " + classFile, classFile.exists());
        assertTrue(".class file should not be empty: " + name, classFile.length() > 0);

        if (expectedOutput != null) {
            String output = JvmTestSupport.run(compiled);
            assertEquals("JVM output did not match expected: " + name,
                    expectedOutput.replace("\r\n", "\n").replace("\r", "\n"), output);
        }
    }

    private static JvmTestSupport.MethodInfo method(List<JvmTestSupport.MethodInfo> methods, String name) {
        for (JvmTestSupport.MethodInfo method : methods) {
            if (method.name().equals(name)) {
                return method;
            }
        }
        fail("Missing method " + name + " in generated class");
        return null;
    }
}
