package site.ilemon.diagnostic;

import site.ilemon.util.SourceSpan;

import java.util.ArrayList;
import java.util.List;

/** Collects diagnostics and provides the common reporting API for compiler phases. */
public final class DiagnosticEngine {
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public Diagnostic report(Severity severity, String code, String message,
                             SourceSpan span, String primaryLabel) {
        var diagnostic = Diagnostic.of(severity, code, english(message), span, primaryLabel);
        return report(diagnostic);
    }

    public DiagnosticBuilder error(String code) {
        return new DiagnosticBuilder(this, Severity.ERROR, code);
    }

    public DiagnosticBuilder warning(String code) {
        return new DiagnosticBuilder(this, Severity.WARNING, code);
    }

    public DiagnosticBuilder note(String code) {
        return new DiagnosticBuilder(this, Severity.NOTE, code);
    }

    public Diagnostic report(Diagnostic diagnostic) {
        var normalized = diagnostic;
        String englishMessage = english(diagnostic.message());
        if (!englishMessage.equals(diagnostic.message())) {
            normalized = new Diagnostic(diagnostic.severity(), diagnostic.code(), englishMessage,
                    diagnostic.primarySpan(), diagnostic.primaryLabel(),
                    diagnostic.secondaryLabels(), diagnostic.notes(), diagnostic.suggestions(), diagnostic.typeContext());
        }
        diagnostics.add(normalized);
        return normalized;
    }

    public Diagnostic error(String code, String message, SourceSpan span, String label) {
        return report(Severity.ERROR, code, message, span, label);
    }

    public Diagnostic warning(String code, String message, SourceSpan span, String label) {
        return report(Severity.WARNING, code, message, span, label);
    }

    public Diagnostic note(String code, String message, SourceSpan span, String label) {
        return report(Severity.NOTE, code, message, span, label);
    }

    public List<Diagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Severity.ERROR);
    }

    /** Normalizes whitespace and trims diagnostic messages. */
    public static String english(String message) {
        if (message == null) return "";
        return message
                .replaceAll("\\s+", " ")
                .trim();
    }
}
