# LemonC Pointer Showcase (`examples/pointer_showcase`)

An integration example demonstrating practical usage of **pointers** and their interplay with existing LemonC language features across both the **JVM** and **C (Native)** backends.

---

## 1. Overview & Objectives

This project showcases a complete, deterministic LemonC program (`pointer_showcase.lemon`) that exercises:
- Scalar pointers (`int*`) and multi-level indirection (`int**`).
- The address-of operator (`&`) and dereference read/write operators (`*`, `**`).
- Passing pointers into functions and mutating caller storage (`setValue(int* p, int value)`).
- Returning pointers from functions.
- Void functions with early `return;` statements.
- Pointer aliasing, comparison (`==`, `!=`), and `null` checking.
- Flexible local variable declarations throughout blocks (including initializers and nested scopes).
- Control flow (`if/else`, `while`, `for`).
- Interaction between unmanaged scalar pointers and ARC-managed reference types (`int[]`, `string[]`).

Both backends produce **100% byte-for-byte identical output**.

---

## 2. Source Code Highlights

### Mandatory Cases
```lemon
// Case 1: Basic pointer declaration, address-of, dereference write
int x;
x = 10;

int* p;
p = &x;

*p = 20;

// Case 2: Double pointer (int**)
int** pp;
pp = &p;
**pp = 30;

// Case 3: Mutating caller storage via pointer parameter
void setValue(int* p, int value) {
    *p = value;
}
setValue(&x, 40);
```

### Advanced Interactions
- **Void functions with early `return;`**:
  ```lemon
  void checkThreshold(int* p, int limit) {
      if (*p > limit) {
          printf("threshold_exceeded=1\n");
          return;
      }
      printf("threshold_exceeded=0\n");
  }
  ```
- **Returning pointer passed from caller**:
  ```lemon
  int* pickLarger(int* a, int* b) {
      if (*a >= *b) {
          return a;
      }
      return b;
  }
  ```
- **Array + Pointer out-parameters**:
  ```lemon
  void computeStats(int arr[], int* sumOut, int* maxOut) {
      *sumOut = 0;
      *maxOut = arr[0];
      for (int i = 0; i < arr.length; i = i + 1) {
          *sumOut = *sumOut + arr[i];
          if (arr[i] > *maxOut) {
              *maxOut = arr[i];
          }
      }
  }
  ```

---

## 3. How to Build & Run

### A. JVM Backend
```bash
# 1. Compile to JVM bytecode (.class)
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/pointer_showcase/pointer_showcase.lemon --target jvm

# 2. Run with java
java -cp target/lemonc pointer_showcase
```

### B. C (Native) Backend
```bash
# 1. Compile to native executable via C backend
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/pointer_showcase/pointer_showcase.lemon --target c

# 2. Run the executable (Windows / Linux)
./examples/pointer_showcase/pointer_showcase.exe   # Windows
./examples/pointer_showcase/pointer_showcase       # Linux / macOS
```

---

## 4. Expected Output (Deterministic)

Both backends produce the exact same standard output:

```text
=== Pointer Showcase ===
case1_x=20
case2_x=30
case2_deref_p=30
case2_deref_pp=30
case3_x_after_setValue_addr=40
case3_x_after_setValue_ptr=50
case3_x_after_setValue_deref_pp=60
threshold_exceeded=0
threshold_exceeded=1
larger_val=75
y_after_mutation=80
alias_equals_p=1
x_after_alias_write=90
p_equals_alias_after_repoint=0
via_pp_pointing_to_y=80
null_check=1
null_not_equal_valid=1
isNull_helper_null=1
isNull_helper_valid=0
double_val=2.5
bool_via_ptr=1
long_val=9999999
byte_val=42
countdown=3
countdown=2
countdown=1
countdown_final=0
data_len=4
array_sum=147
array_max=67
inside_block=333
outside_block=333
tags_len=2
=== End Pointer Showcase ===
```

---

## 5. Feature Status & Verification Matrix

| Feature | Status | Notes |
| :--- | :---: | :--- |
| `int*` declaration & deref | **PASS** | Read & write operations mutate target storage. |
| `int**` double pointer | **PASS** | Multi-level chains dereference cleanly (`**pp`). |
| Address-of (`&x`, `&p`) | **PASS** | Valid for local scalar variables and local pointers. |
| Pointer as parameter | **PASS** | Enables functions to mutate caller variables (`setValue`). |
| Pointer as return value | **PASS** | Returning caller-supplied pointers is supported. |
| Void + `return;` | **PASS** | Early returns inside void functions work properly. |
| Pointer aliasing | **PASS** | `q = p` creates an alias observing subsequent writes. |
| Pointer repointing | **PASS** | Repointed pointers update their target; outer pointers observe. |
| Pointer comparisons (`==`, `!=`) | **PASS** | Evaluates identity of underlying storage cell. |
| `null` literal & checking | **PASS** | Assignable to any pointer level; `p == null` works as expected. |
| Primitive scalar pointers | **PASS** | Verified with `int*`, `double*`, `bool*`, `long*`, `byte*`. |
| Pointer + Array integration | **PASS** | Passing arrays and pointer out-params to summarize data. |
| Flexible local declarations | **PASS** | Declarations placed anywhere in blocks with initializers. |
| Scoping & nested blocks | **PASS** | Pointers into nested blocks mutate outer scope correctly. |
| Control flow (`if`, `while`, `for`)| **PASS** | Conditionals and loops using dereferenced pointers. |
| ARC Memory Management | **PASS** | Managed arrays & strings allocate and release safely. |

---

## 6. Language Limitations & Boundaries

The LemonC language design deliberately enforces safety constraints around pointers and memory management:

1. **Address-of Restricted to Local Variables**:
   - `&arr` and `&str` are rejected (`E3011`): arrays and strings are managed reference types on the heap, not addressable stack cells.
   - `&param` is rejected (`E3011`): function parameters are value copies without stable caller addressable storage.
   - `&CONST` is rejected (`E3011`): constants are immutable and do not have mutable addressable cells.
2. **Unsupported Pointer Types**:
   - Pointers to managed types (`string*`, `int[]*`) are not supported (`E3013`).
3. **No Indirect Pointer Re-assignment through Dereference**:
   - `*pp = p` is rejected (`E3015`): assignment through a dereference must store a value scalar (`**pp = 30`), while pointer variables must be assigned directly (`p = q`).
4. **Escape Analysis / No Returning Stack Addresses**:
   - Functions cannot return `&local` (`E2008` `SEM_POINTER_ESCAPE`): returning addresses of local stack storage is rejected to prevent dangling pointer bugs.
5. **No Pointer Arithmetic**:
   - Pointer arithmetic (`p + 1`, `p - 1`) is forbidden (`E3014`), ensuring type safety and memory boundaries.
6. **`printf` Placeholders**:
   - `printf` format strings support `%d` (integer-like types including byte, short, int, long, bool) and `%f` (float, double). Strings are printed as literal format strings or checked via length/elements.
