import org.junit.Test;
import site.ilemon.backend.c.CBackend;
import site.ilemon.ir.*;
import java.util.List;
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
    }
}
