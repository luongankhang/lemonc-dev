package site.ilemon.compiler;

import site.ilemon.ir.BasicBlock;
import site.ilemon.ir.IrFunction;
import site.ilemon.ir.IrInstruction;
import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrValue;

/**
 * Developer-facing pretty printer for the shared, backend-independent LemonIR
 * (the single intermediate representation consumed by the JVM and C backends).
 */
public final class IrPrinter {

    private IrPrinter() {
    }

    public static String print(IrModule module) {
        IrPrinter printer = new IrPrinter();
        printer.module(module);
        return printer.out.toString();
    }

    private final StringBuilder out = new StringBuilder();

    private void module(IrModule module) {
        line(0, "LemonIR Module " + module.name());
        for (IrFunction function : module.functions()) {
            function(function, 1);
        }
    }

    private void function(IrFunction function, int depth) {
        line(depth, "Function " + function.name() + " : " + function.returnType());
        if (!function.parameters().isEmpty()) {
            StringBuilder params = new StringBuilder("Params: ");
            for (IrValue parameter : function.parameters()) {
                params.append(parameter.type()).append(' ').append(parameter.name()).append(", ");
            }
            line(depth + 1, params.substring(0, params.length() - 2));
        }
        for (BasicBlock block : function.blocks()) {
            line(depth + 1, block.name() + ":");
            for (IrInstruction instruction : block.instructions()) {
                line(depth + 2, instruction.toString());
            }
        }
    }

    private void line(int depth, String text) {
        out.append("  ".repeat(depth)).append(text).append(System.lineSeparator());
    }
}