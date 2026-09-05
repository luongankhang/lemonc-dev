# LemonC Feature Manual

This manual provides a comprehensive specification of all language features and compiler capabilities currently implemented in LemonC. Every runtime output documented here reflects end-to-end JVM execution against real test baselines (`examples/example-output-manifest.tsv`), produced by compiling `.lemon` source files into real JVM `.class` bytecode and executing them on a standard Java Virtual Machine.

---

## 1. Compiler Pipeline

LemonC is a **multi-backend compiler**: the frontend and analyses are shared, everything is lowered once into a backend-neutral **LemonIR**, and each backend lowers that IR to its own target:

```text
Lemon source (.lemon)
  -> Lexical Analysis (DFA Tokenizer with SourceSpan tracking)
  -> Syntax Analysis (LL(2) Recursive Descent Parser with Error Recovery)
  -> Semantic Analysis (Symbol Tables, Type Checking, Control-Flow & Return Checking)
  -> AST Optimization (Constant Folding, Algebraic Simplification, Dead Branch Removal)
  -> Ownership / ARC Analysis (shared, optional --arc verification)
  -> LemonIR Lowering (AstToIrLowerer -> backend-neutral control-flow IR)
  -> JVM Backend (direct JVM bytecode -> .class, no Jasmin / no .il stage)
  -> Standard JVM Execution
  (or -> C Backend: C99 source -> gcc/clang -> native executable)
```

The JVM backend writes class-file bytes itself (descriptors, constant pool, stack/local simulation, branch patching) and never shells out to an assembler.

### Command Line Interface

Compile a source file to JVM bytecode (the class lands in `target/lemonc`):

```bash
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/HelloWorld.lemon
java -cp target/lemonc HelloWorld
```

Select the native C target explicitly:

```bash
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/HelloWorld.lemon --target c
```

Inspection and debugging flags:

```bash
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/StringByteLongArrays.lemon \
  --dump-tokens \
  --dump-ast \
  --dump-ir
```

---

## 2. Program Structure

Every Lemon program consists of functions declared directly at top level (with legacy single top-level `class` declarations supported for backward compatibility). Execution begins at `void main()`; for class-free source the current JVM compatibility backend uses the source file name as its generated class identity.

Class-free example: [examples/TopLevelFunctionsTest.lemon](../examples/TopLevelFunctionsTest.lemon)

```c
int add(int left, int right) {
    return left + right;
}

void main() {
    printf("top-level=%d\n", add(20, 22));
}
```

Example: [examples/HelloWorld.lemon](../examples/HelloWorld.lemon)

```c
void main() {
    int a;
    int b;
    a = 15;
    b = 27;
    printf("a=%d,b=%d,add=%d\n", a, b, add(a, b));
}

int add(int x, int y) {
    return x + y;
}
```

JVM Output:

```text
a=15,b=27,add=42
```

---

## 3. Type System

LemonC features a static, strongly typed type system supporting 10 primitive/scalar types and 9 one-dimensional array types:

### 3.1. Primitive Types

| Type | Keyword | Size / JVM Representation | JVM Descriptor | Description & Operations |
|---|---|---|---|---|
| `byte` | `byte` | 8-bit signed integer (`-128` to `127`) | `B` | Stored in 32-bit JVM operand stack slots; enforces static range validation on integer literals; promotes to `int`. |
| `short` | `short` | 16-bit signed integer (`-32768` to `32767`) | `S` | Stored as a JVM integer category-1 value; enforces static range validation on literals; promotes to `int`. |
| `char` | `char` | 16-bit unsigned character code unit (`0` to `65535`) | `C` | Character literals and escapes are stored as JVM integer category-1 values; promotes to `int`. |
| `int` | `int` | 32-bit signed two's complement integer | `I` | Standard integer arithmetic (`+`, `-`, `*`, `/`, `%`, unary `-`), comparisons, bitwise checks. |
| `long` | `long` | 64-bit signed two's complement integer | `J` | 64-bit arithmetic (`ladd`, `lsub`, `lmul`, `ldiv`, `lrem`, `lneg`), comparisons (`lcmp`), occupies 2 local variable slots. |
| `float` | `float` | 32-bit IEEE 754 single-precision float | `F` | Floating-point arithmetic, floating comparisons (`fcmpl`/`fcmpg`) with IEEE 754 NaN handling. |
| `double` | `double` | 64-bit IEEE 754 double-precision float | `D` | Double-precision arithmetic, comparisons (`dcmpl`/`dcmpg`), occupies 2 local variable slots. |
| `bool` | `bool` | 1-bit logical truth value (`true`/`false`) | `I` | Boolean logic (`!`, `&&`, `||`), backpatching control-flow jumps; represented as `0` or `1` when materialized. |
| `string` | `string` / `String` | Reference to `java.lang.String` | `Ljava/lang/String;` | String literals for formatted output and string array elements. |
| `void` | `void` | No value | `V` | Used exclusively as the return type for methods that return no value. |

### 3.2. Detailed Behavior of New Primitive Types

#### `byte` (8-bit Signed Integer)
- **Range Enforcement**: Literals assigned to `byte` variables or byte array elements are checked at compile time. Literals outside `[-128, 127]` produce compiler diagnostic `E3008 (TYPE_BYTE_RANGE)`:
  ```c
  byte b;
  b = 127;   // OK
  b = -128;  // OK
  b = 128;   // Compile error E3008: byte literal out of range; expected -128..127, found 128
  ```
- **Arithmetic & Widening**: In expressions, `byte` values automatically promote to `int`, allowing full integration with standard arithmetic.
- **Methods & I/O**: Supports declaration as parameter (`byte foo(byte x)`), return type, and printing via `printf("%d", b)`.

#### `long` (64-bit Signed Integer)
- **64-bit Range**: Supports large integer literals up to `9223372036854775807` (`Long.MAX_VALUE`) and negative bounds down to `-9223372036854775808` (`Long.MIN_VALUE`).
- **Operators**: Supports all arithmetic (`+`, `-`, `*`, `/`, `%`, unary `-`) emitting JVM 64-bit instructions (`ladd`, `lsub`, `lmul`, `ldiv`, `lrem`, `lneg`).
- **Comparisons**: Emits `lcmp` followed by integer conditional branches (`ifle`, `ifge`, etc.).
- **Slot Allocation**: Requires 2 local variable slots in method frames (`.limit locals`) and 2 operand stack words.
- **Methods & I/O**: Functions can accept and return `long`. Printed via `printf("%d", val)`.

#### `short` and `char`
- `short` accepts decimal literals in `[-32768, 32767]`; out-of-range literals produce `E3009 (TYPE_SHORT_RANGE)`.
- `char` uses single-quoted C-like literals, including `\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and `\"`, with code-unit range `0..65535`.
- Both types are distinct in the type checker and promote to `int` in numeric expressions; they are printed with `%d`.

#### `string` / `String`
- Both `string` and `String` keywords are accepted interchangeably.
- Represents string constants such as `"Hello World\n"`.
- Primary usage is in `printf` format strings and as elements of `string[]` arrays.

---

## 4. Array Types & Operations

LemonC supports 1-dimensional, statically sized arrays for all major types.

### 4.1. Supported Array Types

| Array Type | Element Type | Declaration Syntax | JVM Type Descriptor | JVM Allocation | Load / Store Instructions |
|---|---|---|---|---|---|
| `int[]` | `int` | `int arr[size];` | `[I` | `newarray int` | `iaload` / `iastore` |
| `byte[]` | `byte` | `byte arr[size];` | `[B` | `newarray byte` | `baload` / `bastore` |
| `short[]` | `short` | `short arr[size];` | `[S` | `newarray short` | `saload` / `sastore` |
| `char[]` | `char` | `char arr[size];` | `[C` | `newarray char` | `caload` / `castore` |
| `long[]` | `long` | `long arr[size];` | `[J` | `newarray long` | `laload` / `lastore` |
| `float[]` | `float` | `float arr[size];` | `[F` | `newarray float` | `faload` / `fastore` |
| `double[]` | `double` | `double arr[size];` | `[D` | `newarray double` | `daload` / `dastore` |
| `bool[]` | `bool` | `bool arr[size];` | `[Z` | `newarray boolean` | `baload` / `bastore` |
| `string[]` | `string` | `string arr[size];` | `[Ljava/lang/String;` | `anewarray java/lang/String` | `aaload` / `aastore` |

### 4.2. Array Syntax & Rules

1. **Declaration**:
   Arrays are declared at the top of a method body with a fixed positive integer size:
   ```c
   int numbers[10];
   byte rawData[16];
   short measurements[4];
   char letters[3];
   long timestamps[4];
   float coords[3];
   double matrix[8];
   bool flags[2];
   string names[5];
   ```
   *Note*: Array size must be a positive integer literal greater than 0.

2. **Element Indexing & Access**:
   Elements are accessed via zero-based index expressions:
   ```c
   int first;
   first = numbers[0];
   printf("%d\n", rawData[i + 1]);
   ```

3. **Element Assignment**:
   Values can be assigned to individual array indices:
   ```c
   numbers[0] = 42;
   rawData[0] = -128;
   rawData[1] = 127;
   measurements[0] = -32768;
   letters[0] = 'A';
   letters[1] = '\n';
   timestamps[0] = 9223372036854775807;
   flags[0] = true;
   names[0] = "Alice";
   names[1] = "Bob";
   ```
   *Note*: Assigned values must match or widen into the element type. For example, assigning `128` to a `byte[]` element causes compile error `E3008`.

4. **Array Length (`.length`)**:
   The number of elements can be retrieved using the `.length` property:
   ```c
   int count;
   count = names.length;  // emits JVM 'arraylength'
   ```

5. **Passing Arrays to Methods**:
   Arrays can be passed by reference as method parameters using `type id[]` or `type[] id`:
   ```c
   long sum(long values[]) {
       int i;
       long total;
       total = 0;
       for (i = 0; i < values.length; i = i + 1) {
           total = total + values[i];
       }
       return total;
   }
   ```

6. **Returning Arrays from Methods**:
   Methods can return array references:
   ```c
   string[] createNames() {
       string names[2];
       names[0] = "Alice";
       names[1] = "Bob";
       return names;
   }
   ```

7. **Whole Array Assignment Constraint**:
   Whole arrays cannot be assigned directly (`arr1 = arr2;` is rejected with `E3001`). Arrays must be copied element-by-element.

---

## 5. Variable Declarations & Definite Assignment

Local variables and arrays are declared at the beginning of each method before statements:

```c
void main() {
    // Declarations first
    int a;
    byte b;
    long c;
    float f;
    double d;
    bool flag;
    int arr[5];

    // Statements follow
    a = 10;
    b = 100;
    c = 1000;
    f = 1.5;
    d = 2.25;
    flag = true;
    arr[0] = a;
}
```

### Static Checks Performed
- **Duplicate Declaration**: Declaring the same variable twice in a method triggers `E2003 (SEM_DUPLICATE_DECLARATION)`.
- **Undefined Variable**: Referencing a variable that has not been declared triggers `E2001 (SEM_UNKNOWN_VARIABLE)`.
- **Definite Assignment (Use Before Assignment)**: Variables must be assigned a value before being read. Reading an unassigned variable triggers compile error `E2001`.
- **Branch Merging**: If an assignment occurs only inside one branch of an `if-else` without the other, the variable remains unassigned after the conditional.

---

## 6. Numeric Widening & Promotion

LemonC supports safe, automatic numeric widening conversions following standard computer arithmetic rules:

```text
byte / short / char  ──►  int  ──►  long  ──►  float  ──►  double
```

### Conversion Matrix

| Source Type | Promotes To | JVM Instruction Emitted |
|---|---|---|
| `byte` | `int` | Implicit (shared operand representation) |
| `short` / `char` | `int` | Implicit (shared operand representation) |
| `byte` / `int` | `long` | `i2l` |
| `byte` / `int` | `float` | `i2f` |
| `byte` / `int` | `double` | `i2d` |
| `long` | `float` | `l2f` |
| `long` | `double` | `l2d` |
| `float` | `double` | `f2d` |

### Where Promotion Applies
1. **Variable Assignment**: e.g. `double d; d = 42;` (widens `int` to `double`).
2. **Method Arguments**: e.g. `void takeDouble(double x)` accepts `int`, `long`, or `float`.
3. **Return Statements**: e.g. `double compute() { return 1; }`.
4. **Array Element Stores**: e.g. `double arr[2]; arr[0] = 5;`.
5. **Binary Arithmetic**: Binary expressions promote both operands to the wider type:
   - `int + long` $\to$ `long`
   - `long + float` $\to$ `float`
   - `float + double` $\to$ `double`
   - `byte + byte` $\to$ `int`
6. **Comparisons**: Operands are widened to a common numeric type before comparison.

---

## 7. Arithmetic & Expressions

LemonC supports all standard arithmetic operations on integer and floating-point types:

| Operator | Name | Valid Types | JVM Instructions |
|---|---|---|---|
| `+` | Addition | `byte`, `int`, `long`, `float`, `double` | `iadd`, `ladd`, `fadd`, `dadd` |
| `-` | Subtraction | `byte`, `int`, `long`, `float`, `double` | `isub`, `lsub`, `fsub`, `dsub` |
| `*` | Multiplication | `byte`, `int`, `long`, `float`, `double` | `imul`, `lmul`, `fmul`, `dmul` |
| `/` | Division | `byte`, `int`, `long`, `float`, `double` | `idiv`, `ldiv`, `fdiv`, `ddiv` |
| `%` | Remainder (Mod) | `byte`, `int`, `long` | `irem`, `lrem` |
| `-` (unary) | Negation | `byte`, `int`, `long`, `float`, `double` | `0 - x` / `lneg`, `fneg`, `dneg` |

Example: [examples/ModTest.lemon](../examples/ModTest.lemon)

```c
void main() {
    int a;
    int b;
    int c;
    a = 10 % 3;
    b = 2 + 10 % 4 * 3;
    c = 20 / 6 + 20 % 6;
    printf("a=%d,b=%d,c=%d\n", a, b, c);
}
```

JVM Output:

```text
a=1,b=8,c=5
```

---

## 8. Relational & Comparison Operations

LemonC supports six relational comparison operators:

```text
>    <    >=    <=    ==    !=
```

### Correct NaN Handling for Floats
When comparing `float` or `double` numbers, comparisons involving `NaN` (Not-a-Number) strictly follow IEEE 754 semantics:
- Greater-than operators (`>`, `>=`) generate `fcmpl` / `dcmpl` (which bias towards `< 0` when NaN is encountered).
- Less-than operators (`<`, `<=`) generate `fcmpg` / `dcmpg` (which bias towards `> 0` when NaN is encountered).
- Equality (`==`) fails on NaN, while inequality (`!=`) evaluates to true.

Example: [examples/CompareTest.lemon](../examples/CompareTest.lemon)

```text
10 > 20 = 0
10 < 20 = 1
10 >= 20 = 0
20 >= 10 = 1
10 <= 20 = 1
20 <= 10 = 0
10 == 20 = 0
10 == 10 = 1
10 != 20 = 1
10 != 10 = 0
```

---

## 9. Boolean Logic & Short-Circuit Control Flow

Boolean expressions short-circuit in the shared lowering step: `&&`/`||` are lowered into branchy control flow on LemonIR (edges become JVM labels/jumps in the JVM backend), so the right operand is only evaluated when it can change the result:

| Operator | Description | Short-Circuit Behavior |
|---|---|---|
| `!` | Logical NOT | Inverts the taken edges of the sub-expression. |
| `&&` | Logical AND | If left operand is false, right operand is never evaluated. |
| `\|\|` | Logical OR | If left operand is true, right operand is never evaluated. |

Boolean values are materialized as `0`/`1` only when assigned to a variable; in conditions they stay as control flow.

Example: [examples/BoolTest02.lemon](../examples/BoolTest02.lemon)

```c
b1 = true;
b2 = testBoolCall(false);
b3 = !(b1) && b2 || !(b2);
```

JVM Output:

```text
b1=1,b2=1,b3=0
```

---

## 10. Control Flow

### 10.1. `if / else` Branching

```c
if (condition) {
    // then block
} else {
    // else block
}
```

The `else` block is optional. Conditions can be boolean variables, comparison expressions, logical expressions, or method calls returning `bool`.

### 10.2. `while` Loops

```c
while (condition) {
    // loop body
}
```

Evaluates `condition` before each iteration; exits when false.

### 10.3. `for` Loops

Supports C-style 3-clause `for` loops:

```c
for (i = 0; i < 10; i = i + 1) {
    sum = sum + i;
}
```

### 10.4. `break` and `continue`

- `break`: Immediately exits the nearest enclosing `while` or `for` loop.
- `continue`: Skips the remainder of the current iteration (jumping to the update clause in `for` loops or the condition test in `while` loops).
- Using `break` or `continue` outside of a loop triggers compile error `E2005 (SEM_INVALID_SCOPE)`.

Example: [examples/NestedLoops.lemon](../examples/NestedLoops.lemon)

```c
while (i < 3) {
    i = i + 1;
    if (i == 2) {
        printf("outer continue skip %d\n", i);
        continue;
    }
    j = 0;
    while (j < 3) {
        j = j + 1;
        if (j == 2) {
            printf("  inner break on %d\n", j);
            break;
        }
        printf("  inner run i=%d, j=%d\n", i, j);
    }
}
```

JVM Output:

```text
  inner run i=1, j=1
  inner break on 2
outer continue skip 2
  inner run i=3, j=1
  inner break on 2
```

---

## 11. Methods & Functions

LemonC supports static methods with parameter passing, return values, and recursion:

```c
int add(int x, int y) {
    return x + y;
}

void greet() {
    printf("hello\n");
}

int factorial(int n) {
    if (n <= 1) {
        return 1;
    }
    return n * factorial(n - 1);
}

void main() {
    greet();
    printf("fact=%d\n", factorial(5));
}
```

### Key Semantics
- **Return Type Checking**: Returned expressions must match or widen into the method's declared return type (`E3002`).
- **Definite Return Validation**: Non-void methods are analyzed to verify that every execution path returns a value.
- **Void Methods**: Void methods cannot return expressions and cannot be evaluated as values (`E2004`).
- **Return in `main`**: Returning a value from `void main()` is disallowed.

---

## 12. Standard I/O: `printf` and `printLine`

LemonC provides built-in I/O methods:

### Format Specifiers in `printf`
- `%d`: Prints integer values (`byte`, `short`, `char`, `int`, `long`) or boolean values (`1`/`0`).
- `%f`: Prints floating-point values (`float`, `double`).
- `\n`: Newline character.
- `\t`: Tab character.

Compile-time checks verify that the number and types of format specifiers match the passed arguments (`E3006`).

Example: [examples/PrintfMixed.lemon](../examples/PrintfMixed.lemon)

```c
printf("i=%d, f=%f, d=%f\n", i, f, d);
```

JVM Output:

```text
i=7, f=1.5, d=2.25
```

---

## 13. Comments

LemonC supports both single-line and multi-line comments:

```c
// This is a single-line comment

/*
 * This is a multi-line comment.
 * It can span multiple lines.
 */
```

---

## 14. AST Optimization

Before generating IR, LemonC executes an AST-level optimization pass ([`AstOptimizer`](file:///c:/Users/Hieu/Documents/c_compiler/lemonc/src/main/java/site/ilemon/optimizer/AstOptimizer.java)):

| Optimization Technique | Example Transformation |
|---|---|
| Arithmetic Constant Folding | `(2 + 3) * 4` $\to$ `20` |
| Boolean Constant Folding | `(1 < 2) && true` $\to$ `true` |
| Comparison Constant Folding | `10 >= 20` $\to$ `false` |
| Algebraic Simplification | `x * 1` $\to$ `x`, `x + 0` $\to$ `x`, `x - 0` $\to$ `x`, `x * 0` $\to$ `0` |
| Dead Branch Elimination | `if (true) { A } else { B }` $\to$ `A` |
| Dead Loop Elimination | `while (false) { ... }` $\to$ deleted |

Example: [examples/OptimizationTest.lemon](../examples/OptimizationTest.lemon)

```c
void main() {
    int a;
    int b;
    bool c;
    a = (2 + 3) * 4;
    b = (a * 1) + 0;
    c = (1 < 2) && true;
    if (c) {
        printf("a=%d,b=%d\n", a, b);
    } else {
        printf("bad\n");
    }
    while (false) {
        printf("dead\n");
    }
}
```

JVM Output:

```text
a=20,b=20
```

---

## 15. Complete Examples with New Types

### 15.1. Mixed String, Byte, and Long Arrays

Source: [examples/StringByteLongArrays.lemon](../examples/StringByteLongArrays.lemon)

```c
int lengths(string names[], byte bytes[], long values[]) {
    return names.length + bytes.length + values.length;
}

string[] makeNames() {
    string names[2];
    names[0] = "Alice";
    names[1] = "Bob";
    return names;
}

void main() {
    string names[2];
    byte bytes[2];
    long values[2];
    int total;
    names[0] = "Alice";
    names[1] = "Bob";
    bytes[0] = -128;
    bytes[1] = 127;
    values[0] = 10;
    values[1] = 20;
    total = lengths(names, bytes, values);
    makeNames();
    printf("array-lengths=%d\n", total);
}
```

JVM Output:

```text
array-lengths=6
```

### 15.2. 64-bit Long Array Computation & Runtime Verification

Source: [examples/LongArrayRuntime.lemon](../examples/LongArrayRuntime.lemon)

```c
long sum(long values[]) {
    int i;
    long total;
    total = 0;
    for (i = 0; i < values.length; i = i + 1) {
        total = total + values[i];
    }
    return total;
}

long replaceFirst(long values[], long replacement) {
    values[0] = replacement;
    return values[0];
}

void main() {
    long values[3];
    long total;
    values[0] = -9223372036854775808;
    values[1] = 2;
    values[2] = 3;
    printf("first=%d\n", values[0]);
    total = sum(values);
    printf("sum=%d\n", total);
    printf("replaced=%d\n", replaceFirst(values, 9223372036854775807));
}
```

JVM Output:

```text
first=-9223372036854775808
sum=-9223372036854775803
replaced=9223372036854775807
```

---

## 16. Compiler Diagnostics & Error Codes

LemonC includes a standardized diagnostic reporting engine ([`DiagnosticEngine`](file:///c:/Users/Hieu/Documents/c_compiler/lemonc/src/main/java/site/ilemon/diagnostic/DiagnosticEngine.java)) inspired by modern industrial compilers (Rust, Clang):

| Code | Category | Name | Description |
|---|---|---|---|
| `E0001` | Lexical | `LEX_INVALID_INPUT` | Unrecognized characters or malformed tokens. |
| `E1001` | Syntax | `PARSE_EXPECTED_TOKEN` | Missing expected token (e.g., `;`, `}`, `)`). |
| `E1002` | Syntax | `PARSE_INVALID_CONSTRUCT` | Malformed grammatical construct or declaration. |
| `E1003` | Syntax | `PARSE_INVALID_EXPRESSION` | Malformed expression syntax. |
| `E2001` | Semantic | `SEM_UNKNOWN_VARIABLE` | Use of undeclared or unassigned variable. |
| `E2002` | Semantic | `SEM_UNKNOWN_FUNCTION` | Call to undefined method. |
| `E2003` | Semantic | `SEM_DUPLICATE_DECLARATION` | Redefinition of variable or method name. |
| `E2004` | Semantic | `SEM_INVALID_SYMBOL_USAGE` | Invalid symbol usage (e.g., using a void method in an expression). |
| `E2005` | Semantic | `SEM_INVALID_SCOPE` | `break` or `continue` outside loop body. |
| `E3001` | Type | `TYPE_ASSIGNMENT` | Type mismatch in variable assignment. |
| `E3002` | Type | `TYPE_RETURN` | Return expression does not match declared return type. |
| `E3003` | Type | `TYPE_ARGUMENT` | Argument type does not match parameter type. |
| `E3004` | Type | `TYPE_OPERATOR` | Operands incompatible with operator. |
| `E3005` | Type | `TYPE_CONDITION` | Conditional expression is not `bool`. |
| `E3006` | Type | `TYPE_FORMAT` | `printf` format placeholder count or type mismatch. |
| `E3007` | Type | `TYPE_INDEX` | Array index expression is not an integer. |
| `E3008` | Type | `TYPE_BYTE_RANGE` | Byte literal exceeds signed 8-bit range `[-128, 127]`. |

---

## 17. Test Suite & Verification Baseline

Every change to the compiler is validated against a comprehensive automated test suite:

```bash
mvn test
```

Current Test Baseline:
- **359 Automated Tests Passing** (0 failures, 0 errors).
- **94 Root Example Programs** compiled to `.class` files by the JVM backend, executed on a real JVM, and verified byte-for-byte against `examples/example-output-manifest.tsv`.
- The C backend is verified separately (`NativeEndToEndTest`): LemonIR -> C -> native compiler -> native execution.

---

## 18. Current Language Boundaries

The following limitations are deliberate architectural boundaries for LemonC as a production-oriented compiler project:

| Boundary | Description |
|---|---|
| Program Model | Programs consist of top-level functions with static/global semantics; no object instantiation (`new Object()`), inheritance, or interfaces. Legacy single-class wrappers are supported for compatibility. |
| Variable Placement | All local variable and array declarations must appear at the top of the method body before statements. |
| Scope Granularity | Variables are scoped to the method level (`MethodVarTable`). Sub-blocks (`{ ... }`) do not introduce independent shadowing scopes. |
| Scalar String Variables | Scalar string variable assignments (`string s; s = "text";`) are not supported; strings are supported as literals, `printf` arguments, and `string[]` array elements. |
| Whole Array Copies | Direct assignment of entire arrays (`a = b;`) is disallowed; element-by-element iteration is required. |
