# LemonC Feature Manual

This manual provides a comprehensive specification of all language features and compiler capabilities currently implemented in LemonC. Every runtime output documented here reflects end-to-end execution on both supported backends: compiling `.lemon` source files directly to JVM `.class` bytecode for the Java Virtual Machine, and to C99 native executables via the C backend.

---

## 1. Compiler Pipeline

LemonC is a **multi-backend compiler**: the frontend and analyses are shared, everything is lowered once into a backend-neutral **LemonIR**, and each backend lowers that IR to its own target:

```text
Lemon source (.lemon)
  -> Lexical Analysis (DFA Tokenizer with SourceSpan tracking)
  -> Syntax Analysis (LL(2) Recursive Descent Parser with Error Recovery)
  -> Semantic Analysis (Symbol Tables, Scopes, Type Checking, Return Checking)
  -> AST Optimization (Constant Folding, Algebraic Simplification, Dead Branch Removal)
  -> Ownership / ARC Analysis (shared, optional --arc verification)
  -> LemonIR Lowering (AstToIrLowerer -> backend-neutral control-flow IR)
  -> JVM Backend (direct JVM bytecode -> .class, no Jasmin / no .il stage)
     -> Standard JVM Execution
  (or -> C Backend: C99 source -> gcc/clang -> native executable)
```

The JVM backend writes class-file bytes directly (descriptors, constant pool, stack/local simulation, branch patching) and never shells out to an external assembler. The C backend lowers LemonIR into clean C99 code linked with Lemon's runtime library.

### Command Line Interface

Compile a source file to JVM bytecode (the generated class lands in `target/lemonc`):

```bash
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/HelloWorld.lemon
java -cp target/lemonc HelloWorld
```

Select the native C target explicitly:

```bash
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/HelloWorld.lemon --target c
./target/lemonc/HelloWorld
```

Inspection and debugging flags:

```bash
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/HelloWorld.lemon \
  --dump-tokens \
  --dump-ast \
  --dump-ir
```

Verify memory management with ARC analysis:

```bash
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/StringByteLongArrays.lemon --arc
```

---

## 2. Program Structure

Every Lemon program consists of functions and constants declared directly at top level (with legacy single top-level `class` declarations supported for backward compatibility). Execution begins at `void main()`; for class-free source the JVM backend uses the source file name as its generated class identity.

### Struct Declarations

Example: [examples/StructDemo.lemon](../examples/StructDemo.lemon)

```c
struct Point { int x; int y; };          // top-level declaration
struct Inner { int v; };
struct Outer { int id; struct Inner in; }; // nested struct by value

struct Point make(int x, int y) {
    struct Point p;                       // zero-initialized
    p.x = x;
    p.y = y;
    return p;                             // returned by value
}

int sum(struct Point p) { return p.x + p.y; }   // passed by value
void shift(struct Point* ptr, int dx) {         // struct pointer
    ptr->x = ptr->x + dx;                        // arrow field access
}

void main() {
    struct Point a;
    a = make(3, 4);                       // whole-struct copy (by value)
    struct Point* ptr;
    ptr = &a;                             // address of a struct local
    shift(ptr, 10);
    struct Point b;
    b = a;                                // independent copy
    b.x = 99;                             // does not alias a
    struct Outer o;
    o.id = 100;
    o.in.v = 5;                           // nested field chain
}
```

Both backends agree: C emits a `LemonC_Point` typedef with plain member access
(`p.x`, `ptr->x`) and C struct assignment; the JVM synthesizes one nested class
per struct (`Main$Point`) with a zero constructor and a recursive copy
constructor, and uses GETFIELD/PUTFIELD for member access.

### Top-Level Functions & Program Entry

Example: [examples/TopLevelFunctionsTest.lemon](../examples/TopLevelFunctionsTest.lemon)

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
int add(int x, int y) {
    return x + y;
}

void main() {
    int a = 15;
    int b = 27;
    printf("a=%d,b=%d,add=%d\n", a, b, add(a, b));
}
```

Output:

```text
a=15,b=27,add=42
```

---

## 3. Type System

LemonC features a static, strongly typed type system supporting 10 primitive/scalar types, pointer types, and 9 one-dimensional array types:

### 3.1. Primitive & Scalar Types

| Type | Keyword | Size / JVM Representation | JVM Descriptor | Description & Operations |
|---|---|---|---|---|
| `byte` | `byte` | 8-bit signed integer (`-128` to `127`) | `B` | Enforces compile-time static range validation on literals; promotes to `int`. |
| `short` | `short` | 16-bit signed integer (`-32768` to `32767`) | `S` | Enforces compile-time static range validation on literals; promotes to `int`. |
| `char` | `char` | 16-bit unsigned character (`0` to `65535`) | `C` | Character literals and escapes; promotes to `int`. |
| `int` | `int` | 32-bit signed two's complement integer | `I` | Standard integer arithmetic (`+`, `-`, `*`, `/`, `%`, unary `-`), comparisons, bitwise checks. |
| `long` | `long` | 64-bit signed two's complement integer | `J` | 64-bit arithmetic (`ladd`, `lsub`, `lmul`, `ldiv`, `lrem`, `lneg`), comparisons (`lcmp`), occupies 2 local variable slots. |
| `float` | `float` | 32-bit IEEE 754 single-precision float | `F` | Floating-point arithmetic, floating comparisons (`fcmpl`/`fcmpg`) with IEEE 754 NaN handling. |
| `double` | `double` | 64-bit IEEE 754 double-precision float | `D` | Double-precision arithmetic, comparisons (`dcmpl`/`dcmpg`), occupies 2 local variable slots. |
| `bool` | `bool` | 1-bit logical truth value (`true`/`false`) | `I` | Boolean logic (`!`, `&&`, `||`), short-circuit backpatching control-flow jumps; represented as `0` or `1` when materialized. |
| `string` | `string` / `String` | Reference to `java.lang.String` | `Ljava/lang/String;` | String literals for formatted output and string array elements. |
| `void` | `void` | No value | `V` | Return type for methods returning no value. |

### 3.2. Detailed Behavior of Scalar Types

#### `byte` (8-bit Signed Integer)
- **Range Enforcement**: Literals assigned to `byte` variables or byte array elements are statically verified at compile time. Literals outside `[-128, 127]` produce diagnostic `E3008 (TYPE_BYTE_RANGE)`:
  ```c
  byte b = 127;   // OK
  byte c = -128;  // OK
  byte d = 128;   // Compile error E3008: byte literal out of range
  ```
- **Arithmetic & Widening**: In expressions, `byte` automatically promotes to `int`.
- **I/O**: Printed via `printf("%d", b)`.

#### `short` and `char`
- `short` accepts decimal literals in `[-32768, 32767]`; out-of-range literals produce `E3009 (TYPE_SHORT_RANGE)`.
- `char` uses single-quoted literals, including `\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and `\"`, with code-unit range `0..65535`.
- Both types promote to `int` in numeric expressions and are printed with `%d`.

#### `long` (64-bit Signed Integer)
- Supports large integer literals up to `9223372036854775807` (`Long.MAX_VALUE`) and down to `-9223372036854775808` (`Long.MIN_VALUE`).
- Emits 64-bit arithmetic instructions (`ladd`, `lsub`, `lmul`, `ldiv`, `lrem`, `lneg`) and 64-bit comparisons (`lcmp`).
- Printed via `printf("%d", val)`.

#### `string` / `String`
- Both `string` and `String` keywords are accepted interchangeably.
- Represents string literals such as `"Hello World\n"`.
- Used in `printf` format strings and as elements of `string[]` arrays.

### 3.3. Pointer Types & `null`

LemonC supports C-like unmanaged raw pointers:

| Type | Syntax | Target Description | Backends |
|---|---|---|---|
| Pointer | `int*`, `float*`, `T*` | Address of scalar variable of type `T` | JVM & Native C |
| Pointer to Pointer | `int**`, `T**` | Address of pointer variable | JVM & Native C |
| Null Literal | `null` | Null pointer reference | JVM & Native C |

Pointers represent scalar addresses on the stack. They are unmanaged (never subject to ARC retain/release). Pointer safety rules are verified statically by the semantic analyzer (see Section 6).

---

## 4. Array Types & Operations

LemonC supports 1-dimensional, statically sized arrays for all scalar types.

### 4.1. Supported Array Types

| Array Type | Element Type | Declaration Syntax | JVM Type Descriptor | Load / Store Instructions |
|---|---|---|---|---|
| `int[]` | `int` | `int arr[size];` | `[I` | `iaload` / `iastore` |
| `byte[]` | `byte` | `byte arr[size];` | `[B` | `baload` / `bastore` |
| `short[]` | `short` | `short arr[size];` | `[S` | `saload` / `sastore` |
| `char[]` | `char` | `char arr[size];` | `[C` | `caload` / `castore` |
| `long[]` | `long` | `long arr[size];` | `[J` | `laload` / `lastore` |
| `float[]` | `float` | `float arr[size];` | `[F` | `faload` / `fastore` |
| `double[]` | `double` | `double arr[size];` | `[D` | `daload` / `dastore` |
| `bool[]` | `bool` | `bool arr[size];` | `[Z` | `baload` / `bastore` |
| `string[]` | `string` | `string arr[size];` | `[Ljava/lang/String;` | `aaload` / `aastore` |

### 4.2. Array Syntax & Rules

1. **Declaration**:
   Arrays are declared with a fixed positive integer literal size:
   ```c
   int numbers[10];
   string names[5];
   ```

2. **Element Indexing & Access**:
   Elements are accessed via zero-based integer index expressions:
   ```c
   int first = numbers[0];
   printf("%d\n", numbers[i + 1]);
   ```

3. **Element Assignment**:
   Values can be assigned to individual array indices:
   ```c
   numbers[0] = 42;
   names[0] = "Alice";
   ```

4. **Array Length (`.length`)**:
   The number of elements is retrieved using the `.length` property:
   ```c
   int count = names.length;
   ```

5. **Passing Arrays to Functions**:
   Arrays are passed by reference using `type id[]` or `type[] id`:
   ```c
   long sum(long values[]) {
       long total = 0;
       for (int i = 0; i < values.length; i = i + 1) {
           total = total + values[i];
       }
       return total;
   }
   ```

6. **Returning Arrays from Functions**:
   Functions can return array references:
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

## 5. Variable Declarations, Initializers, Scoping & Constants

### 5.1. Flexible Declaration Placement

Local variable and array declarations are **not** restricted to the start of a block; they can appear **anywhere a normal statement is permitted** within any block:

```c
void main() {
    int x = 10;
    printf("%d\n", x);

    int y = 20;
    x = x + y;
    printf("%d\n", x);
}
```

### 5.2. Declaration Initializers

Variables can be initialized directly at declaration time:

```c
int total = 0;
int* ptr = &total;
bool flag = true;
double rate = 3.14;
```

If a variable is declared without an initializer (e.g. `int x;`), it is tracked by definite assignment analysis and must be assigned before any read. Reading an unassigned variable triggers `E2001 (SEM_UNKNOWN_VARIABLE)`.

### 5.3. Lexical Block Scoping & Shadowing

Each `{ ... }` block introduces an independent lexical scope:

- Variables declared inside an inner block exist only for the lifetime of that block.
- Inner variables can safely shadow outer variables with the same name.
- Accessing an inner variable outside its enclosing block triggers `E2001 (SEM_UNKNOWN_VARIABLE)`.
- Declaring duplicate variables within the same scope level triggers `E2003 (SEM_DUPLICATE_DECLARATION)`.

```c
void main() {
    int x = 10;
    {
        int x = 20; // Shadows outer x
        int y = 30;
        printf("inner x=%d, y=%d\n", x, y);
    }
    // y is no longer in scope here
    printf("outer x=%d\n", x); // prints 10
}
```

### 5.4. Loop-Header Variable Declarations

C-style `for` loops support variable declarations directly inside the loop initialization clause:

```c
for (int i = 0; i < 5; i = i + 1) {
    int value = (i + 1) * 10;
    total = total + value;
}
// i is not visible outside the for loop
```

The loop variable is strictly scoped to the `for` loop body and initialization header.

### 5.5. Global Constants (`const`)

LemonC supports immutable top-level constants:

```c
const int MAX_USERS = 100;
const double PI = 3.14159;

void main() {
    printf("max=%d\n", MAX_USERS);
}
```

- Constant declarations must provide a compile-time constant initializer; omitting or providing a non-constant initializer triggers `E2007 (SEM_CONST_INITIALIZER)`.
- Attempting to reassign a constant triggers `E2006 (SEM_CONST_IMMUTABLE)`.

---

## 6. Pointers & Memory Addressing

LemonC provides direct memory addressing via C-compatible unmanaged pointers across both the JVM and Native C backends.

### 6.1. Address-of (`&`) and Dereference (`*`)

The address-of operator `&` retrieves the memory address of an lvalue variable:

```c
int value = 42;
int* ptr = &value;
```

The dereference operator `*` reads from or writes to the target memory location:

```c
int readValue = *ptr;   // Dereference read (42)
*ptr = 100;            // Dereference write: updates value to 100
printf("%d\n", value); // prints 100
```

### 6.2. Multi-Level Indirection (`int**`)

LemonC supports multi-level pointers:

```c
int value = 7;
int* p = &value;
int** pp = &p;

int result = **pp; // Multi-level dereference read: 7
**pp = 40;         // Multi-level dereference write: value is now 40
printf("%d\n", *p); // prints 40
```

### 6.3. Pointer Parameters & Pass-by-Reference

Pointers can be passed into functions to mutate caller variables:

```c
void swap(int* a, int* b) {
    int temp = *a;
    *a = *b;
    *b = temp;
}

void main() {
    int x = 10;
    int y = 20;
    swap(&x, &y);
    printf("x=%d, y=%d\n", x, y); // x=20, y=10
}
```

### 6.4. The `null` Literal and Pointer Comparisons

Pointers can be initialized to `null` and compared using `==` and `!=`:

```c
int* p = null;
if (p == null) {
    printf("p is null\n");
}

int x = 10;
p = &x;
int* q = &x;
if (p == q) {
    printf("p and q point to the same location\n");
}
```

Comparing incompatible pointer types triggers compile error `E3012 (TYPE_POINTER_COMPARISON)`.

### 6.5. Pointer Safety & Compile-Time Boundaries

To prevent memory corruption and undefined behavior, LemonC strictly enforces static safety checks:

1. **Stack Escape Prevention (`E2008`)**:
   Taking the address of a local stack variable and returning it from a function triggers diagnostic `E2008 (SEM_POINTER_ESCAPE)`:
   ```c
   int* badFunction() {
       int local = 50;
       return &local; // Rejected: cannot return pointer to local variable
   }
   ```
   Returning a pointer that was passed in by the caller (or returning `null`) is permitted.

2. **Pointer Arithmetic Prohibition (`E3014`)**:
   Pointer arithmetic (`p + 1`, `p++`) is unsupported. Pointers are unmanaged references to individual variables. Attempting pointer arithmetic triggers `E3014 (TYPE_POINTER_ARITHMETIC)`.

3. **Pointer-to-Pointer Reassignment Constraint (`E3015`)**:
   Assigning a pointer through a dereference target (`*pp = p;`) is prohibited and triggers `E3015 (TYPE_POINTER_WRITE)`. Multi-level dereferences must target scalar values (`**pp = val;`).

---

## 7. Numeric Widening & Promotion

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
1. **Variable Assignment**: `double d = 42;` (widens `int` to `double`).
2. **Function Arguments**: `void takeDouble(double x)` accepts `int`, `long`, or `float`.
3. **Return Statements**: `double compute() { return 1; }`.
4. **Array Element Stores**: `double arr[2]; arr[0] = 5;`.
5. **Binary Arithmetic**: Binary expressions promote both operands to the wider common type:
   - `int + long` $\to$ `long`
   - `long + float` $\to$ `float`
   - `float + double` $\to$ `double`
   - `byte + byte` $\to$ `int`
6. **Comparisons**: Operands are widened to a common numeric type before comparison.

---

## 8. Arithmetic & Expressions

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
    int a = 10 % 3;
    int b = 2 + 10 % 4 * 3;
    int c = 20 / 6 + 20 % 6;
    printf("a=%d,b=%d,c=%d\n", a, b, c);
}
```

Output:

```text
a=1,b=8,c=5
```

---

## 9. Relational & Comparison Operations

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

## 10. Boolean Logic & Short-Circuit Control Flow

Boolean expressions short-circuit in the shared IR lowering step: `&&`/`||` are lowered into branchy control flow on LemonIR (edges become JVM labels/jumps in the JVM backend and conditional branches in C), so the right operand is only evaluated when it can change the result:

| Operator | Description | Short-Circuit Behavior |
|---|---|---|
| `!` | Logical NOT | Inverts the taken edges of the sub-expression. |
| `&&` | Logical AND | If left operand is false, right operand is never evaluated. |
| `\|\|` | Logical OR | If left operand is true, right operand is never evaluated. |

Boolean values are materialized as `0`/`1` only when stored to a variable or printed; in conditions they operate directly as control flow jumps.

---

## 11. Control Flow

### 11.1. `if / else` Branching

```c
if (condition) {
    // then block
} else {
    // optional else block
}
```

Conditions can be boolean variables, comparison expressions, logical expressions, or function calls returning `bool`.

### 11.2. `while` Loops

```c
while (condition) {
    // loop body
}
```

Evaluates `condition` before each iteration; exits immediately when false.

### 11.3. `for` Loops

Supports C-style 3-clause `for` loops, including loop-header variable declarations:

```c
for (int i = 0; i < 10; i = i + 1) {
    sum = sum + i;
}
```

### 11.4. `break` and `continue`

- `break`: Immediately exits the nearest enclosing `while` or `for` loop.
- `continue`: Skips the remainder of the current iteration, jumping to the update clause in `for` loops or condition test in `while` loops.
- Using `break` or `continue` outside of a loop triggers compile error `E2005 (SEM_INVALID_SCOPE)`.

Example: [examples/NestedLoops.lemon](../examples/NestedLoops.lemon)

```c
void main() {
    int i = 0;
    while (i < 3) {
        i = i + 1;
        if (i == 2) {
            printf("outer continue skip %d\n", i);
            continue;
        }
        int j = 0;
        while (j < 3) {
            j = j + 1;
            if (j == 2) {
                printf("  inner break on %d\n", j);
                break;
            }
            printf("  inner run i=%d, j=%d\n", i, j);
        }
    }
}
```

Output:

```text
  inner run i=1, j=1
  inner break on 2
outer continue skip 2
  inner run i=3, j=1
  inner break on 2
```

---

## 12. Methods & Functions

LemonC supports static functions with parameter passing, return values, and recursion:

```c
int factorial(int n) {
    if (n <= 1) {
        return 1;
    }
    return n * factorial(n - 1);
}
```

### 12.1. `void` Methods and `return;`

Void functions support early returns using the empty return statement:

```c
void logStatus(int code) {
    if (code == 0) {
        printf("success\n");
        return; // Early return in void method
    }
    printf("error=%d\n", code);
}
```

### 12.2. Return Validation Rules
- **Void Methods**:
  - May contain `return;` for early returns.
  - Can omit return statements entirely.
  - Returning an expression (`return 123;`) in a void method produces compile error `E3002 (TYPE_RETURN)`.
  - Using a void function call inside an expression produces `E2004 (SEM_INVALID_SYMBOL_USAGE)`.
- **Non-Void Methods**:
  - Must return an expression matching or widening to the declared return type (`E3002`).
  - Using an empty `return;` without an expression produces `E3002 (TYPE_RETURN)`.
  - Definite return checking verifies that all execution paths statically guarantee a return.

---

## 13. Standard I/O: `printf` and `printLine`

LemonC provides built-in I/O methods:

### Format Specifiers in `printf`
- `%d`: Prints integer values (`byte`, `short`, `char`, `int`, `long`) or boolean values (`1`/`0`).
- `%f`: Prints floating-point values (`float`, `double`).
- `\n`: Newline character.
- `\t`: Tab character.

Compile-time checks verify that the number and types of format specifiers match the passed arguments (`E3006 (TYPE_FORMAT)`).

Example: [examples/PrintfMixed.lemon](../examples/PrintfMixed.lemon)

```c
void main() {
    int i = 7;
    float f = 1.5;
    double d = 2.25;
    printf("i=%d, f=%f, d=%f\n", i, f, d);
}
```

Output:

```text
i=7, f=1.500000, d=2.250000
```

---

## 14. Comments

LemonC supports single-line and multi-line comments:

```c
// This is a single-line comment

/*
 * This is a multi-line comment.
 * It can span multiple lines.
 */
```

---

## 15. AST Optimization

Before generating IR, LemonC executes an AST-level optimization pass ([`AstOptimizer`](file:///d:/ps1dev/lemonc-dev/src/main/java/site/ilemon/optimizer/AstOptimizer.java)):

| Optimization Technique | Example Transformation |
|---|---|
| Arithmetic Constant Folding | `(2 + 3) * 4` $\to$ `20` |
| Boolean Constant Folding | `(1 < 2) && true` $\to$ `true` |
| Comparison Constant Folding | `10 >= 20` $\to$ `false` |
| Algebraic Simplification | `x * 1` $\to$ `x`, `x + 0` $\to$ `x`, `x - 0` $\to$ `x`, `x * 0` $\to$ `0` |
| Dead Branch Elimination | `if (true) { A } else { B }` $\to$ `A` |
| Dead Loop Elimination | `while (false) { ... }` $\to$ removed |

Example: [examples/OptimizationTest.lemon](../examples/OptimizationTest.lemon)

```c
void main() {
    int a = (2 + 3) * 4;
    int b = (a * 1) + 0;
    bool c = (1 < 2) && true;
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

Output:

```text
a=20,b=20
```

---

## 16. Automatic Reference Counting (ARC) & Ownership

LemonC implements an optional compile-time ARC verification and lowering pass invoked via `--arc`:

- **Heap Reference Types**: Arrays (`int[]`, `string[]`, etc.) are tracked as managed heap references.
- **Pointers are Unmanaged**: Raw scalar pointers (`int*`, `int**`) represent stack addresses and bypass ARC retain/release.
- **Static Ownership Analysis**: Tracks ownership transitions, borrow lifetimes, and ensures variables are freed at scope boundaries without leaks or double-frees.
- **Native C Runtime Lowering**: In the C backend, ARC lowers array creation and reference transfers to runtime functions:
  - `lemon_array_new(elem_size, length)`
  - `lemon_retain(ptr)`
  - `lemon_release(ptr)`

---

## 17. Integration Examples

### 17.1. Pointer Showcase (`examples/pointer_showcase/pointer_showcase.lemon`)

```c
void setValue(int* p, int value) {
    *p = value;
}

int* pickLarger(int* a, int* b) {
    if (*a >= *b) {
        return a;
    }
    return b;
}

void main() {
    int x = 10;
    int* p = &x;
    *p = 20;

    int** pp = &p;
    **pp = 30;

    setValue(&x, 40);
    printf("final_x=%d\n", x);

    int y = 50;
    int* larger = pickLarger(&x, &y);
    printf("larger=%d\n", *larger);
}
```

Output:

```text
final_x=40
larger=50
```

### 17.2. Mixed String, Byte, and Long Arrays (`examples/StringByteLongArrays.lemon`)

```c
int lengths(string names[], byte bytes[], long values[]) {
    return names.length + bytes.length + values.length;
}

void main() {
    string names[2];
    byte bytes[2];
    long values[2];
    names[0] = "Alice";
    names[1] = "Bob";
    bytes[0] = -128;
    bytes[1] = 127;
    values[0] = 10;
    values[1] = 20;
    int total = lengths(names, bytes, values);
    printf("array-lengths=%d\n", total);
}
```

Output:

```text
array-lengths=6
```

---

## 18. Compiler Diagnostics & Error Codes

LemonC includes a standardized diagnostic reporting engine ([`DiagnosticEngine`](file:///d:/ps1dev/lemonc-dev/src/main/java/site/ilemon/diagnostic/DiagnosticEngine.java)) inspired by modern industrial compilers (Rust, Clang):

| Code | Category | Identifier | Description |
|---|---|---|---|
| `E0001` | Lexical | `LEX_INVALID_INPUT` | Unrecognized characters or malformed tokens. |
| `E1001` | Syntax | `PARSE_EXPECTED_TOKEN` | Missing expected token (e.g. `;`, `}`, `)`). |
| `E1002` | Syntax | `PARSE_INVALID_CONSTRUCT` | Malformed grammatical construct or declaration. |
| `E1003` | Syntax | `PARSE_INVALID_EXPRESSION` | Malformed expression syntax. |
| `E2001` | Semantic | `SEM_UNKNOWN_VARIABLE` | Use of undeclared or unassigned variable. |
| `E2002` | Semantic | `SEM_UNKNOWN_FUNCTION` | Call to undefined method. |
| `E2003` | Semantic | `SEM_DUPLICATE_DECLARATION` | Redefinition of variable or function name in the same scope. |
| `E2004` | Semantic | `SEM_INVALID_SYMBOL_USAGE` | Invalid symbol usage (e.g. void method used in expression). |
| `E2005` | Semantic | `SEM_INVALID_SCOPE` | `break` or `continue` outside loop body. |
| `E2006` | Semantic | `SEM_CONST_IMMUTABLE` | Reassignment to an immutable `const` symbol. |
| `E2007` | Semantic | `SEM_CONST_INITIALIZER` | Missing or non-constant initializer in `const` declaration. |
| `E2008` | Semantic | `SEM_POINTER_ESCAPE` | Address of local stack variable escapes function scope. |
| `E2099` | Semantic | `SEM_GENERAL` | General semantic analysis failure. |
| `E3001` | Type | `TYPE_ASSIGNMENT` | Type mismatch in variable assignment. |
| `E3002` | Type | `TYPE_RETURN` | Return expression mismatch or invalid `return;` usage. |
| `E3003` | Type | `TYPE_ARGUMENT` | Argument type does not match parameter type. |
| `E3004` | Type | `TYPE_OPERATOR` | Operands incompatible with operator. |
| `E3005` | Type | `TYPE_CONDITION` | Conditional expression is not `bool`. |
| `E3006` | Type | `TYPE_FORMAT` | `printf` format placeholder count or type mismatch. |
| `E3007` | Type | `TYPE_INDEX` | Array index expression is not an integer. |
| `E3008` | Type | `TYPE_BYTE_RANGE` | Byte literal exceeds signed 8-bit range `[-128, 127]`. |
| `E3009` | Type | `TYPE_SHORT_RANGE` | Short literal exceeds signed 16-bit range `[-32768, 32767]`. |
| `E3010` | Type | `TYPE_POINTER_DEREF` | Cannot dereference non-pointer type. |
| `E3011` | Type | `TYPE_POINTER_ADDRESS_OF` | Address-of operator `&` applied to non-lvalue expression. |
| `E3012` | Type | `TYPE_POINTER_COMPARISON` | Incompatible pointer comparison types. |
| `E3013` | Type | `TYPE_POINTER_ASSIGNMENT` | Incompatible pointer assignment. |
| `E3014` | Type | `TYPE_POINTER_ARITHMETIC` | Pointer arithmetic is unsupported. |
| `E3015` | Type | `TYPE_POINTER_WRITE` | Invalid pointer write target (e.g. `*pp = p`). |
| `E4001` | Module | `MODULE_NOT_FOUND` | Imported module file could not be found. |
| `E5001` | General | `GENERIC_ERROR` | General compiler failure. |
| `E6001` | FFI | `FFI_ERROR` | Foreign function interface error. |
| `E7001` | Backend | `BACKEND_ERROR` | Backend code generation failure. |
| `E8001` | ARC | `ARC_DOUBLE_RELEASE` | Managed object released more than once. |
| `E8002` | ARC | `ARC_USE_AFTER_RELEASE` | Managed object accessed after being released. |
| `E8003` | ARC | `ARC_MISSING_RELEASE` | Managed object not released before leaving scope (leak). |
| `E8004` | ARC | `ARC_OWNERSHIP_VIOLATION` | Ownership transfer or borrow rule violated. |
| `E8005` | ARC | `ARC_INVALID_MOVE_COPY` | Invalid move or copy of owned reference. |
| `E8006` | ARC | `ARC_LIFETIME_VIOLATION` | Borrowed reference outlives its parent object. |
| `E9001` | Internal | `INTERNAL_COMPILER_ERROR` | Unhandled internal compiler crash. |

---

## 19. Test Suite & Verification Baseline

Every change to the compiler is validated against a comprehensive automated test suite:

```bash
mvn clean test
```

Current Test Baseline:
- **445 Automated Tests Passing** (0 failures, 0 errors, 0 skipped).
- **95+ Root & Integration Example Programs** compiled to `.class` files by the JVM backend, executed on a real JVM, and verified byte-for-byte against `examples/example-output-manifest.tsv`.
- **Dual-Backend Parity Tests** (`PointerMultiBackendTest`, `NativeEndToEndTest`): LemonIR -> JVM and LemonIR -> C produce 100% identical outputs.

---

## 20. Current Language Boundaries

The following limitations are deliberate architectural boundaries for LemonC:

| Boundary | Description |
|---|---|
| Program Model | Top-level functions with static/global semantics; classes (beyond legacy top-level wrapper), interfaces, or object instantiation are not supported. Structs are C-style value records without methods. |
| Pointers | Unmanaged scalar stack addresses. Pointer arithmetic (`p + 1`) is forbidden (`E3014`). Returning the address of a local stack variable is prevented at compile time (`E2008`). Reassigning through double dereferences (`*pp = p`) is forbidden (`E3015`). |
| Memory Management | Heap arrays are managed via ARC (`--arc`) or GC on JVM; raw pointers are unmanaged stack addresses. |
| Array Dimensions | Statically sized 1-dimensional arrays only; multi-dimensional arrays (`int[][]`) are not supported. |
| Whole Array Copies | Direct assignment of entire arrays (`a = b;`) is disallowed; element-by-element iteration is required. |
| Format Specifiers | `printf` supports `%d` (integers, bools) and `%f` (floats, doubles). String formatting (`%s`) is unsupported. |
