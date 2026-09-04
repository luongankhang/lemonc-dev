import org.junit.Test;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.diagnostic.DiagnosticRenderer;
import site.ilemon.diagnostic.DiagnosticLabel;
import site.ilemon.diagnostic.DiagnosticEngine;
import site.ilemon.util.SourceSpan;

import java.util.List;

import static org.junit.Assert.assertTrue;

public class DiagnosticRendererTest {
    @Test
    public void rendersStructuredDiagnosticWithSourceAndLabels() {
        var engine = new DiagnosticEngine();
        SourceSpan primary = SourceSpan.of("main.lemon", 14, 21, 12, 15, 12, 22);
        SourceSpan secondary = SourceSpan.singlePoint("main.lemon", 2, 3, 1);
        Diagnostic diagnostic = engine.error("E3001")
                .message("type mismatch")
                .primary(primary, "expression")
                .secondary(secondary, "declared here")
                .type("int", "string", "string literal", "assignment")
                .note("the target type is fixed by the declaration")
                .suggestion(primary, "parseInt(\"hello\")", "convert the value", 0.9)
                .report();

        String output = new DiagnosticRenderer((file, line) -> line == 12
                ? "let x: int = \"hello\";"
                : "void main() {").render(diagnostic);

        assertTrue(output.contains("error[E3001]: type mismatch"));
        assertTrue(output.contains("--> main.lemon:12:15"));
        assertTrue(output.contains("12 | let x: int = \"hello\";"));
        assertTrue(output.contains("^^^^^^^ expression; expected `int`, found `string`"));
        assertTrue(output.contains("declared here"));
        assertTrue(output.contains("= note: the target type is fixed by the declaration"));
        assertTrue(output.contains("= help: convert the value (replace with 'parseInt(\"hello\")')"));
    }

    @Test
    public void rendersMultipleDiagnosticsSeparately() {
        var engine = new DiagnosticEngine();
        SourceSpan first = SourceSpan.singlePoint("main.lemon", 0, 1, 1);
        SourceSpan second = SourceSpan.singlePoint("main.lemon", 8, 2, 1);
        Diagnostic one = engine.error("E2001").message("unknown variable").primary(first, "name").report();
        Diagnostic two = engine.error("E1001").message("missing token").primary(second, "here").report();

        String output = new DiagnosticRenderer((file, line) -> "source").render(List.of(one, two));
        assertTrue(output.indexOf("error[E2001]") < output.indexOf("error[E1001]"));
        assertTrue(output.contains(System.lineSeparator() + System.lineSeparator()));
    }
}
