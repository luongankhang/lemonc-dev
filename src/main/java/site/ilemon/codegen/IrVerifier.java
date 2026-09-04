package site.ilemon.codegen;

import site.ilemon.codegen.ast.Ast;
import site.ilemon.exception.CompilerException;

/** Validates the backend IR at the frontend/backend boundary. */
public final class IrVerifier {
    private IrVerifier() {}

    public static void verify(Ast.Program.ProgramSingle program) {
        if (program == null || program.mainClass == null) fail("program or main class is null");
        if (program.mainClass.id == null || program.mainClass.id.isBlank()) fail("class name is empty");
        if (program.mainClass.methods == null) fail("method list is null");
        for (Ast.Method.MethodSingle method : program.mainClass.methods) verify(method);
    }

    private static void verify(Ast.Method.MethodSingle method) {
        if (method == null) fail("method is null");
        if (method.id == null || method.id.isBlank()) fail("method name is empty");
        if (method.retType == null) fail("method '" + method.id + "' has no return type");
        if (method.formals == null || method.locals == null || method.stms == null) {
            fail("method '" + method.id + "' has an incomplete body");
        }
        for (Ast.Declare.DeclareSingle declaration : method.formals) verify(declaration, method.id);
        for (Ast.Declare.DeclareSingle declaration : method.locals) verify(declaration, method.id);
        for (Ast.Stmt.T statement : method.stms) {
            if (statement == null) fail("method '" + method.id + "' contains a null statement");
        }
    }

    private static void verify(Ast.Declare.DeclareSingle declaration, String method) {
        if (declaration == null || declaration.type == null || declaration.id == null || declaration.id.isBlank()) {
            fail("method '" + method + "' contains an invalid declaration");
        }
    }

    private static void fail(String message) {
        throw new CompilerException("Invalid backend IR: " + message);
    }
}
