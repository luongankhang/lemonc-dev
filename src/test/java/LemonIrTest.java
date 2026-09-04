import org.junit.Test;
import site.ilemon.ir.BasicBlock;
import site.ilemon.ir.IrFunction;
import site.ilemon.ir.IrInstruction;
import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrType;
import site.ilemon.ir.IrValue;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class LemonIrTest {
    @Test
    public void buildsTypedCfgAndDefensivelyCopiesLists() {
        IrType intType = IrType.scalar(IrType.Kind.INT);
        BasicBlock entry = new BasicBlock("entry").add(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(), null));
        IrFunction function = new IrFunction("main", IrType.scalar(IrType.Kind.VOID), List.of(new IrValue("x", intType))).addBlock(entry);
        IrModule module = new IrModule("test").addFunction(function);
        site.ilemon.ir.IrVerifier.verify(module);
        assertEquals(1, module.functions().get(0).blocks().size());
    }

    @Test
    public void rejectsUnterminatedBlock() {
        IrFunction function = new IrFunction("broken", IrType.scalar(IrType.Kind.VOID), List.of())
                .addBlock(new BasicBlock("entry"));
        assertThrows(IllegalStateException.class, () -> site.ilemon.ir.IrVerifier.verify(function));
    }

    @Test
    public void rejectsInstructionAfterTerminator() {
        BasicBlock block = new BasicBlock("entry").add(new IrInstruction(IrInstruction.Op.RETURN, null, List.of(), null));
        assertThrows(IllegalStateException.class, () -> block.add(new IrInstruction(IrInstruction.Op.CONST, null, List.of(), null)));
    }

    @Test
    public void rejectsNullFunction() {
        assertThrows(IllegalStateException.class, () -> site.ilemon.ir.IrVerifier.verify((IrFunction) null));
    }

    @Test
    public void rejectsUndefinedBranchTarget() {
        BasicBlock entry = new BasicBlock("entry")
                .add(new IrInstruction(IrInstruction.Op.BRANCH, null, List.of(), "missing_block"));
        IrFunction function = new IrFunction("broken_branch", IrType.scalar(IrType.Kind.VOID), List.of())
                .addBlock(entry);
        assertThrows(IllegalStateException.class, () -> site.ilemon.ir.IrVerifier.verify(function));
    }

    @Test
    public void rejectsDuplicateBlockNames() {
        BasicBlock entry = new BasicBlock("entry");
        BasicBlock duplicate = new BasicBlock("entry");
        IrFunction function = new IrFunction("broken_dup", IrType.scalar(IrType.Kind.VOID), List.of())
                .addBlock(entry)
                .addBlock(duplicate);
        assertThrows(IllegalStateException.class, () -> site.ilemon.ir.IrVerifier.verify(function));
    }
}
