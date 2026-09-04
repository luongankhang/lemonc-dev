import org.junit.Test;
import site.ilemon.backend.c.CBackend;
import site.ilemon.ir.*;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.assertTrue;

public class CBackendTest {
    @Test
    public void lowersTypedLemonIrToPortableC() {
        IrType integer = IrType.scalar(IrType.Kind.INT);
        BasicBlock entry = new BasicBlock("entry")
                .add(new IrInstruction(IrInstruction.Op.CONST, new IrValue("value", integer), List.of(new IrValue("41", integer)), null))
                .add(new IrInstruction(IrInstruction.Op.ADD, new IrValue("answer", integer), List.of(new IrValue("value", integer), new IrValue("1", integer)), null))
                .add(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(new IrValue("answer", integer)), null));
        IrModule module = new IrModule("demo").addFunction(new IrFunction("main", integer, List.of()).addBlock(entry));
        String c = new CBackend().generate(module);
        assertTrue(c.contains("int32_t main()"));
        assertTrue(c.contains("answer = value + 1;"));
        assertTrue(c.contains("return answer;"));
        assertTrue(c.contains("#include \"lemon_runtime.h\""));
    }

    @Test
    public void keepsNativeRuntimeAsSeparateSourceArtifacts() {
        assertTrue(Files.isRegularFile(Path.of("runtime", "lemon_runtime.h")));
        assertTrue(Files.isRegularFile(Path.of("runtime", "lemon_runtime.c")));
    }

    @Test
    public void supportsGxxAsACCompilerCandidate() {
        // g++ is accepted by the toolchain abstraction; compile() still passes -x c.
        assertTrue(new site.ilemon.backend.c.NativeToolchain("g++").compiler().equals("g++"));
    }

    @Test
    public void discoversMinGwGccCompiler() {
        site.ilemon.backend.c.NativeToolchain toolchain = site.ilemon.backend.c.NativeToolchain.discover();
        assertTrue(toolchain.compiler().toLowerCase().contains("gcc")
                || toolchain.compiler().toLowerCase().contains("clang")
                || toolchain.compiler().toLowerCase().contains("cc"));
    }

    @Test
    public void lowersAllArithmeticAndComparisonOpcodes() {
        IrType intType = IrType.scalar(IrType.Kind.INT);
        IrType floatType = IrType.scalar(IrType.Kind.FLOAT);
        IrType boolType = IrType.scalar(IrType.Kind.BOOL);

        BasicBlock bb = new BasicBlock("entry")
                .add(new IrInstruction(IrInstruction.Op.CONST, new IrValue("a", intType), List.of(new IrValue("10", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.CONST, new IrValue("b", intType), List.of(new IrValue("3", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.SUB, new IrValue("sub_res", intType), List.of(new IrValue("a", intType), new IrValue("b", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.MUL, new IrValue("mul_res", intType), List.of(new IrValue("a", intType), new IrValue("b", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.DIV, new IrValue("div_res", intType), List.of(new IrValue("a", intType), new IrValue("b", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.REM, new IrValue("rem_res", intType), List.of(new IrValue("a", intType), new IrValue("b", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.AND, new IrValue("and_res", intType), List.of(new IrValue("a", intType), new IrValue("b", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.OR, new IrValue("or_res", intType), List.of(new IrValue("a", intType), new IrValue("b", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.XOR, new IrValue("xor_res", intType), List.of(new IrValue("a", intType), new IrValue("b", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.CMP, new IrValue("cmp_lt", boolType), List.of(new IrValue("a", intType), new IrValue("b", intType)), "<"))
                .add(new IrInstruction(IrInstruction.Op.CMP, new IrValue("cmp_lte", boolType), List.of(new IrValue("a", intType), new IrValue("b", intType)), "<="))
                .add(new IrInstruction(IrInstruction.Op.CMP, new IrValue("cmp_gt", boolType), List.of(new IrValue("a", intType), new IrValue("b", intType)), ">"))
                .add(new IrInstruction(IrInstruction.Op.CMP, new IrValue("cmp_gte", boolType), List.of(new IrValue("a", intType), new IrValue("b", intType)), ">="))
                .add(new IrInstruction(IrInstruction.Op.CMP, new IrValue("cmp_neq", boolType), List.of(new IrValue("a", intType), new IrValue("b", intType)), "!="))
                .add(new IrInstruction(IrInstruction.Op.REM, new IrValue("frem_res", floatType), List.of(new IrValue("10.5f", floatType), new IrValue("2.0f", floatType)), null))
                .add(new IrInstruction(IrInstruction.Op.CONVERT, new IrValue("conv_res", floatType), List.of(new IrValue("a", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(new IrValue("sub_res", intType)), null));

        IrModule module = new IrModule("arithmetic").addFunction(new IrFunction("test_arith", intType, List.of()).addBlock(bb));
        String c = new CBackend().generate(module);

        assertTrue(c.contains("sub_res = a - b;"));
        assertTrue(c.contains("mul_res = a * b;"));
        assertTrue(c.contains("div_res = a / b;"));
        assertTrue(c.contains("rem_res = a % b;"));
        assertTrue(c.contains("and_res = a & b;"));
        assertTrue(c.contains("or_res = a | b;"));
        assertTrue(c.contains("xor_res = a ^ b;"));
        assertTrue(c.contains("cmp_lt = (a < b);"));
        assertTrue(c.contains("cmp_lte = (a <= b);"));
        assertTrue(c.contains("cmp_gt = (a > b);"));
        assertTrue(c.contains("cmp_gte = (a >= b);"));
        assertTrue(c.contains("cmp_neq = (a != b);"));
        assertTrue(c.contains("frem_res = fmodf(10.5f, 2.0f);"));
        assertTrue(c.contains("conv_res = ((float)(a));"));
    }

    @Test
    public void lowersArrayAndMemoryOperations() {
        IrType intType = IrType.scalar(IrType.Kind.INT);
        IrType arrType = IrType.array(intType);

        BasicBlock bb = new BasicBlock("entry")
                .add(new IrInstruction(IrInstruction.Op.ALLOC, new IrValue("arr", arrType), List.of(new IrValue("5", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.BOUNDS_CHECK, null, List.of(new IrValue("arr", arrType), new IrValue("0", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.STORE, null, List.of(new IrValue("arr", arrType), new IrValue("0", intType), new IrValue("42", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.LOAD, new IrValue("elem", intType), List.of(new IrValue("arr", arrType), new IrValue("0", intType)), null))
                .add(new IrInstruction(IrInstruction.Op.LOAD, new IrValue("len", intType), List.of(new IrValue("arr", arrType)), "length"))
                .add(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(new IrValue("arr", arrType)), "lemon_retain"))
                .add(new IrInstruction(IrInstruction.Op.EXTERNAL_CALL, null, List.of(new IrValue("arr", arrType)), "lemon_release"))
                .add(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(new IrValue("elem", intType)), null));

        IrModule module = new IrModule("arrays").addFunction(new IrFunction("test_arr", intType, List.of()).addBlock(bb));
        String c = new CBackend().generate(module);

        assertTrue(c.contains("lemon_array* arr = 0;"));
        assertTrue(c.contains("arr = lemon_array_new((size_t)(5), sizeof(int32_t), NULL);"));
        assertTrue(c.contains("lemon_bounds_check(arr, arr->length, (size_t)(0));"));
        assertTrue(c.contains("*((int32_t*)lemon_array_at(arr, (size_t)(0))) = 42;"));
        assertTrue(c.contains("elem = *((int32_t*)lemon_array_at(arr, (size_t)(0)));"));
        assertTrue(c.contains("len = (int32_t)(arr->length);"));
        assertTrue(c.contains("lemon_retain(arr);"));
        assertTrue(c.contains("lemon_release(arr);"));
    }

    @Test
    public void lowersBranchAndCondBranch() {
        IrType intType = IrType.scalar(IrType.Kind.INT);
        IrType boolType = IrType.scalar(IrType.Kind.BOOL);

        BasicBlock entry = new BasicBlock("entry")
                .add(new IrInstruction(IrInstruction.Op.CONST, new IrValue("cond", boolType), List.of(new IrValue("true", boolType)), null))
                .add(new IrInstruction(IrInstruction.Op.COND_BRANCH, null, List.of(new IrValue("cond", boolType)), "then_block"));

        BasicBlock elseBlock = new BasicBlock("else_block")
                .add(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(new IrValue("0", intType)), null));

        BasicBlock thenBlock = new BasicBlock("then_block")
                .add(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), "else_block"));

        IrModule module = new IrModule("branches")
                .addFunction(new IrFunction("test_branch", intType, List.of())
                        .addBlock(entry).addBlock(thenBlock).addBlock(elseBlock));
        String c = new CBackend().generate(module);

        assertTrue(c.contains("if (cond) goto then_block;"));
        assertTrue(c.contains("then_block:"));
        assertTrue(c.contains("else_block:"));
    }
}
