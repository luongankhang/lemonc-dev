# LemonC Compiler Delivery

This delivery scope covers the complete multi-backend LemonC compiler pipeline targeting both the **JVM** (direct bytecode generation) and **C99 / Native** (`site.ilemon.backend.c`).

## Scope

The compiler covers:

- **Frontend Pipeline**: Lexical analysis (DFA tokenizer), recursive descent parsing, symbol tables with block-level lexical scoping, semantic type checking, definite assignment analysis, and standardized diagnostic reporting (`E0001`–`E9001`).
- **Data Types**:
  - Primitives: `byte`, `short`, `char`, `int`, `long`, `float`, `double`, `bool`, `string`, `void`.
  - Pointers: Scalar pointers (`int*`, `int**`, `T*`), address-of (`&`), dereference read/write (`*p`, `**pp`), pointer comparison, and `null`.
  - Global Constants: `const <type> <id> = <expr>;`.
  - Arrays: Statically sized 1D arrays for all major types (`type id[size];`), `.length` property, element indexing, passing to methods, and returning from methods.
- **Statements & Declarations**:
  - Declarations anywhere statements are permitted inside any block.
  - Variable initializers (`int total = 0;`).
  - Block scoping with lexical shadowing rules.
  - Loop header declarations (`for (int i = 0; ...)`).
- **Control Flow**:
  - `if / else` conditional branches.
  - `while` loops.
  - C-style 3-clause `for` loops.
  - `break` and `continue` with scope validation.
- **Functions & Methods**:
  - Top-level functions (and legacy single `class` wrappers).
  - Parameter passing (scalar, array references, pointers).
  - Return values and definite return path validation.
  - `void` functions with empty `return;` support.
  - Stack escape detection preventing returning addresses of local variables (`E2008`).
- **Memory Management**:
  - Automatic Reference Counting (ARC) analysis for reference types (`--arc`).
  - C runtime integration with reference counting (`lemon_retain`, `lemon_release`).
- **Backends**:
  - **JVM Backend**: Direct JVM `.class` bytecode generation (constant pool, stack/local tracking, branch patching; no Jasmin IL intermediary).
  - **C Backend**: C99 code generation lowered from LemonIR, compiled via GCC/Clang to native executables.
- **Standard I/O**:
  - `printf` with format validation for `%d` and `%f`.

Generated `.class` and C output files are written to `target/lemonc/`.

## Verification

Run the full validation suite:

```bash
mvn clean test
```

Expected result:

```text
Tests run: 445, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The automated test suite verifies:
- 445 automated unit and integration tests across frontend, optimizer, ARC analyzer, diagnostics, LemonIR, and both backends.
- 95+ root and integration example programs (`examples/`), verified byte-for-byte against `examples/example-output-manifest.tsv`.
- Dual-backend equivalence tests (`PointerMultiBackendTest`, `NativeEndToEndTest`).

## Architectural Boundaries

The following design constraints are deliberately enforced:

- `printf` supports `%d` for integer and boolean types and `%f` for floating-point types; `%s` format strings are not supported.
- Pointers are unmanaged scalar addresses; pointer arithmetic (`p + 1`) is forbidden (`E3014`).
- Taking the address of local variables and returning it outside the function scope is rejected (`E2008`).
- Double-dereference pointer assignment (`*pp = p`) is forbidden (`E3015`); multi-level indirection must be dereferenced to target scalars (`**pp = val`).
- Array assignment is element-wise; whole-array direct assignment (`a = b;`) is rejected (`E3001`).
- Structs, user-defined classes, and object instantiation are outside the current language scope.
