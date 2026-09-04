import org.junit.Test;
import site.ilemon.codegen.IrVerifier;
import site.ilemon.codegen.ast.Ast;
import site.ilemon.exception.CompilerException;

import java.util.Collections;

public class IrVerifierTest {
    @Test(expected = CompilerException.class)
    public void rejectsNullBackendProgram() {
        IrVerifier.verify(null);
    }

    @Test(expected = CompilerException.class)
    public void rejectsMethodWithMissingReturnType() {
        Ast.Method.MethodSingle method = new Ast.Method.MethodSingle(null, "broken", "Broken",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), 0, 0);
        IrVerifier.verify(new Ast.Program.ProgramSingle(new Ast.MainClass.MainClassSingle("Broken",
                Collections.singletonList(method))));
    }
}
