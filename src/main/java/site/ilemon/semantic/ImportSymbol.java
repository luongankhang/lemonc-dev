package site.ilemon.semantic;

import java.nio.file.Path;

/** A compile-time module binding; it has no runtime storage or ARC ownership. */
public final class ImportSymbol {
    private final String name;
    private final Path module;
    private final int scopeDepth;

    public ImportSymbol(String name, Path module, int scopeDepth) {
        this.name = name;
        this.module = module;
        this.scopeDepth = scopeDepth;
    }

    public String name() { return name; }
    public Path module() { return module; }
    public int scopeDepth() { return scopeDepth; }
    public boolean isMutable() { return false; }
    public boolean isRuntimeStorage() { return false; }
}