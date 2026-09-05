package site.ilemon.backend.jvm;

import site.ilemon.ir.BasicBlock;
import site.ilemon.ir.IrFunction;
import site.ilemon.ir.IrInstruction;
import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrType;
import site.ilemon.ir.IrValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Emits a single JVM method (descriptor, locals, code, stack limit) from a
 * LemonIR function, lowering its CFG to JVM labels and jumps.
 */
final class JvmMethodEmitter {

    private final JvmTypeMapper mapper = new JvmTypeMapper();

    JvmMethod emit(IrFunction function, IrModule module, JvmClassWriter pool, boolean arcDebug) {
        boolean isMain = "main".equals(function.name());

        JvmLocalAllocator allocator = new JvmLocalAllocator(mapper);
        Map<String, JvmLocalAllocator.Local> locals = allocator.allocate(function);

        // Locals whose address is taken (&x) are materialized as single-element
        // cells so dereferences and direct accesses alias the same storage.
        java.util.Set<String> cells = new java.util.HashSet<>();
        for (BasicBlock block : function.blocks()) {
            for (IrInstruction instruction : block.instructions()) {
                if (instruction.op() == IrInstruction.Op.ADDRESS_OF
                        && !instruction.operands().isEmpty()) {
                    cells.add(instruction.operands().get(0).name());
                }
            }
        }

        JvmCodeBuilder code = new JvmCodeBuilder();
        JvmInstructionEmitter instructions = new JvmInstructionEmitter(
                mapper, pool, code, locals, module, function.name(), isMain, function.returnType(), cells, arcDebug);

        for (BasicBlock block : function.blocks()) {
            code.label(block.name());
            for (var instruction : block.instructions()) {
                instructions.emit(instruction);
            }
        }

        byte[] bytecode = code.toBytecode();
        int maxStack = JvmStackTracker.computeMaxStack(code.insns(), pool);
        int maxLocals = allocator.slotCount(locals);
        if (isMain && maxLocals < 1) {
            maxLocals = 1; // the implicit String[] argument occupies slot 0
        }

        String descriptor = methodDescriptor(function);
        return new JvmMethod(JvmClassWriter.methodAccess(isMain), function.name(), descriptor,
                bytecode, maxStack, maxLocals);
    }

    private String methodDescriptor(IrFunction function) {
        if ("main".equals(function.name())) {
            return "([Ljava/lang/String;)V";
        }
        StringBuilder descriptor = new StringBuilder("(");
        for (IrValue parameter : function.parameters()) {
            descriptor.append(mapper.descriptor(parameter.type()));
        }
        descriptor.append(')').append(mapper.descriptor(function.returnType()));
        return descriptor.toString();
    }
}