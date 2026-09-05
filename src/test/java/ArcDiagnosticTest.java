import org.junit.Test;
import site.ilemon.arc.MemoryOp;
import site.ilemon.arc.OwnershipBlock;
import site.ilemon.arc.OwnershipFunction;
import site.ilemon.arc.OwnershipIr;
import site.ilemon.arc.RefcountSimulator;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.diagnostic.DiagnosticCodes;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ArcDiagnosticTest {

    @Test
    public void testDetectsPointerEscapeThroughDereference() {
        OwnershipFunction func = new OwnershipFunction("testPointerEscape", false);
        func.addManagedLocal("local_arr", "@int[]");
        func.addParameter("global_ptr", false); // Not managed, just a raw pointer

        OwnershipBlock entry = new OwnershipBlock("entry");
        entry.addOp(new MemoryOp(MemoryOp.Kind.ALLOC, "local_arr:@int[]", 1));
        // Simulate: *global_ptr = &local_arr (escape of local address through dereference)
        entry.addOp(new MemoryOp(MemoryOp.Kind.STORE, "escape:local_arr", 2));
        entry.setTerminatorType(OwnershipBlock.TerminatorType.RETURN);

        func.setEntryBlock(entry);
        OwnershipIr ir = new OwnershipIr();
        ir.addFunction(func);

        RefcountSimulator simulator = new RefcountSimulator();
        try {
            simulator.verify(ir);
            org.junit.Assert.fail("Expected verification failure on pointer escape");
        } catch (IllegalStateException e) {
            assertTrue(simulator.hasErrors());
            List<Diagnostic> diagnostics = simulator.getDiagnostics();
            assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals(DiagnosticCodes.ARC_LIFETIME_VIOLATION)));
        }
    }

    @Test
    public void testDetectsDoubleRelease() {
        OwnershipFunction func = new OwnershipFunction("testDoubleRelease", false);
        func.addManagedLocal("arr", "@int[]");

        OwnershipBlock entry = new OwnershipBlock("entry");
        entry.addOp(new MemoryOp(MemoryOp.Kind.ALLOC, "arr:@int[]", 1));
        entry.addOp(new MemoryOp(MemoryOp.Kind.RELEASE, "arr", 2));
        entry.addOp(new MemoryOp(MemoryOp.Kind.RELEASE, "arr", 3)); // Second release: DOUBLE RELEASE!
        entry.setTerminatorType(OwnershipBlock.TerminatorType.RETURN);

        func.setEntryBlock(entry);
        OwnershipIr ir = new OwnershipIr();
        ir.addFunction(func);

        RefcountSimulator simulator = new RefcountSimulator();
        try {
            simulator.verify(ir);
            org.junit.Assert.fail("Expected verification failure on double release");
        } catch (IllegalStateException e) {
            assertTrue(simulator.hasErrors());
            List<Diagnostic> diagnostics = simulator.getDiagnostics();
            assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals(DiagnosticCodes.ARC_DOUBLE_RELEASE)));
        }
    }

    @Test
    public void testDetectsUseAfterRelease() {
        OwnershipFunction func = new OwnershipFunction("testUseAfterRelease", false);
        func.addManagedLocal("arr", "@int[]");

        OwnershipBlock entry = new OwnershipBlock("entry");
        entry.addOp(new MemoryOp(MemoryOp.Kind.ALLOC, "arr:@int[]", 1));
        entry.addOp(new MemoryOp(MemoryOp.Kind.RELEASE, "arr", 2));
        entry.addOp(new MemoryOp(MemoryOp.Kind.BOUNDS_CHECK, "arr", 3)); // Access after release!
        entry.setTerminatorType(OwnershipBlock.TerminatorType.RETURN);

        func.setEntryBlock(entry);
        OwnershipIr ir = new OwnershipIr();
        ir.addFunction(func);

        RefcountSimulator simulator = new RefcountSimulator();
        try {
            simulator.verify(ir);
            org.junit.Assert.fail("Expected verification failure on use-after-release");
        } catch (IllegalStateException e) {
            assertTrue(simulator.hasErrors());
            List<Diagnostic> diagnostics = simulator.getDiagnostics();
            assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals(DiagnosticCodes.ARC_USE_AFTER_RELEASE)));
        }
    }

    @Test
    public void testDetectsMissingReleaseLeak() {
        OwnershipFunction func = new OwnershipFunction("testMissingRelease", false);
        func.addManagedLocal("arr", "@int[]");

        OwnershipBlock entry = new OwnershipBlock("entry");
        entry.addOp(new MemoryOp(MemoryOp.Kind.ALLOC, "arr:@int[]", 1));
        // Function exits without releasing "arr" -> LEAK!
        entry.setTerminatorType(OwnershipBlock.TerminatorType.RETURN);

        func.setEntryBlock(entry);
        OwnershipIr ir = new OwnershipIr();
        ir.addFunction(func);

        RefcountSimulator simulator = new RefcountSimulator();
        try {
            simulator.verify(ir);
            org.junit.Assert.fail("Expected verification failure on missing release / leak");
        } catch (IllegalStateException e) {
            assertTrue(simulator.hasErrors());
            List<Diagnostic> diagnostics = simulator.getDiagnostics();
            assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals(DiagnosticCodes.ARC_MISSING_RELEASE)));
        }
    }

    @Test
    public void testDetectsOwnershipImbalanceAcrossBranches() {
        OwnershipFunction func = new OwnershipFunction("testImbalance", false);
        func.addManagedLocal("arr", "@int[]");

        OwnershipBlock entry = new OwnershipBlock("entry");
        entry.addOp(new MemoryOp(MemoryOp.Kind.ALLOC, "arr:@int[]", 1));
        entry.setTerminatorType(OwnershipBlock.TerminatorType.BRANCH);

        OwnershipBlock thenBlock = new OwnershipBlock("if.then");
        thenBlock.addOp(new MemoryOp(MemoryOp.Kind.RELEASE, "arr", 2)); // then releases arr
        thenBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);

        OwnershipBlock elseBlock = new OwnershipBlock("if.else");
        // else does NOT release arr!
        elseBlock.setTerminatorType(OwnershipBlock.TerminatorType.JUMP);

        OwnershipBlock joinBlock = new OwnershipBlock("if.join");
        joinBlock.setTerminatorType(OwnershipBlock.TerminatorType.RETURN);

        entry.addSuccessor(thenBlock);
        entry.addSuccessor(elseBlock);
        thenBlock.addSuccessor(joinBlock);
        elseBlock.addSuccessor(joinBlock);

        func.setEntryBlock(entry);
        func.addBlock(thenBlock);
        func.addBlock(elseBlock);
        func.addBlock(joinBlock);

        OwnershipIr ir = new OwnershipIr();
        ir.addFunction(func);

        RefcountSimulator simulator = new RefcountSimulator();
        try {
            simulator.verify(ir);
            org.junit.Assert.fail("Expected verification failure on branch ownership imbalance");
        } catch (IllegalStateException e) {
            assertTrue(simulator.hasErrors());
            List<Diagnostic> diagnostics = simulator.getDiagnostics();
            assertTrue(diagnostics.stream().anyMatch(d -> d.code().equals(DiagnosticCodes.ARC_OWNERSHIP_VIOLATION)));
        }
    }
}
