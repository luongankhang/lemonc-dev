package site.ilemon.ir;

import java.util.Objects;

public record IrValue(String name, IrType type) {
    public IrValue {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("IR value name is empty");
        Objects.requireNonNull(type, "type");
    }
}
