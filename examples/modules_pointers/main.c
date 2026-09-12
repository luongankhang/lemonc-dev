#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <math.h>
#include <limits.h>
#include "lemon_runtime.h"

typedef struct { unsigned char _opaque; } lemon_opaque_t;

int32_t main();
int32_t math_add(int32_t a, int32_t b);
int32_t math_mul(int32_t a, int32_t b);
void math_add_ptr(int32_t* result, int32_t a, int32_t b);
void math_mul_ptr(int32_t* result, int32_t a, int32_t b);
void math_swap(int32_t* a, int32_t* b);
void math_increment(int32_t* p);
void math_set_via_double_ptr(int32_t** pp, int32_t value);
int32_t math_get_value(int32_t* storage);
void math_modify_through_pp(int32_t** pp, int32_t new_val);
int32_t math_safe_deref(int32_t* p, int32_t default_val);
int32_t math_array_sum(int32_t* arr, int32_t len);
void math_fill_array(int32_t* arr, int32_t len, int32_t value);
void math_reverse_array(int32_t* arr, int32_t len);
int32_t math_compare_ptr(int32_t* a, int32_t* b);
void math_increment_via_chain(int32_t** pp);
int32_t math_is_null(int32_t* p);

int32_t main() {
    int32_t result = 0;
    int32_t x = 0;
    int32_t y = 0;
    int32_t storage = 0;
    int32_t* ptr = 0;
    int32_t** pptr = 0;
    int32_t _t1 = 0;
    int32_t _t2 = 0;
    int32_t _t3 = 0;
    int32_t _t4 = 0;
    void* _t5 = 0;
    void* _t6 = 0;
    int32_t _t7 = 0;
    int32_t _t8 = 0;
    int32_t _t9 = 0;
    int32_t _t10 = 0;
    int32_t _t11 = 0;
    int32_t _t12 = 0;
    int32_t* _t13 = 0;
    int32_t _t14 = 0;
    int32_t _t15 = 0;
    int32_t* _t16 = 0;
    int32_t _t17 = 0;
    int32_t _t18 = 0;
    int32_t _t19 = 0;
    int32_t _t20 = 0;
    int32_t* _t21 = 0;
    int32_t* _t22 = 0;
    int32_t _t23 = 0;
    int32_t* _t24 = 0;
    int32_t _t25 = 0;
    int32_t* _t26 = 0;
    int32_t** _t27 = 0;
    int32_t _t28 = 0;
    int32_t* _t29 = 0;
    int32_t _t30 = 0;
    int32_t _t31 = 0;
    int32_t _t32 = 0;
    int32_t* _t33 = 0;
    int32_t _t34 = 0;
    int32_t* _t35 = 0;
    int32_t _t36 = 0;
    int32_t* _t37 = 0;
    int32_t** _t38 = 0;
    int32_t _t39 = 0;
    void* _t40 = 0;
    int32_t _t41 = 0;
    int32_t _t42 = 0;
    int32_t _t43 = 0;
    int32_t* _t44 = 0;
    int32_t _t45 = 0;
    int32_t _t46 = 0;
    int32_t _t47 = 0;
    int32_t _t48 = 0;
    int32_t* _t49 = 0;
    int32_t* _t50 = 0;
    int32_t _t51 = 0;
    int32_t* _t52 = 0;
    int32_t* _t53 = 0;
    int32_t _t54 = 0;
    int32_t* _t55 = 0;
    int32_t* _t56 = 0;
    int32_t _t57 = 0;
    int32_t _t58 = 0;
    int32_t* _t59 = 0;
    int32_t** _t60 = 0;
    void* _t61 = 0;
    int32_t _t62 = 0;
    int32_t* _t63 = 0;
    int32_t _t64 = 0;
    result = 0;
    x = 0;
    y = 0;
    storage = 0;
    ptr = 0;
    pptr = 0;
    _t1 = 0;
    result = ((int32_t)(_t1));
    _t2 = 0;
    x = ((int32_t)(_t2));
    _t3 = 0;
    y = ((int32_t)(_t3));
    _t4 = 0;
    storage = ((int32_t)(_t4));
    _t5 = NULL;
    ptr = ((int32_t*)(_t5));
    _t6 = NULL;
    pptr = ((int32_t**)(_t6));
    printf("=== Basic Module Functions ===\n");
    _t7 = 10;
    _t8 = 5;
    _t9 = math_add(_t7, _t8);
    printf("add(10, 5)=");
    printf("%d", _t9);
    printf("\n");
    _t10 = 6;
    _t11 = 7;
    _t12 = math_mul(_t10, _t11);
    printf("mul(6, 7)=");
    printf("%d", _t12);
    printf("\n");
    printf("\n=== Pointer Pass-by-Reference ===\n");
    _t13 = &(result);
    _t14 = 15;
    _t15 = 25;
    math_add_ptr(_t13, _t14, _t15);
    printf("add_ptr(&result, 15, 25) -> result=");
    printf("%d", result);
    printf("\n");
    _t16 = &(result);
    _t17 = 8;
    _t18 = 9;
    math_mul_ptr(_t16, _t17, _t18);
    printf("mul_ptr(&result, 8, 9) -> result=");
    printf("%d", result);
    printf("\n");
    printf("\n=== Pointer Swap ===\n");
    _t19 = 100;
    x = ((int32_t)(_t19));
    _t20 = 200;
    y = ((int32_t)(_t20));
    printf("before swap: x=");
    printf("%d", x);
    printf(", y=");
    printf("%d", y);
    printf("\n");
    _t21 = &(x);
    _t22 = &(y);
    math_swap(_t21, _t22);
    printf("after swap: x=");
    printf("%d", x);
    printf(", y=");
    printf("%d", y);
    printf("\n");
    printf("\n=== Pointer Increment ===\n");
    _t23 = 42;
    x = ((int32_t)(_t23));
    printf("before increment: x=");
    printf("%d", x);
    printf("\n");
    _t24 = &(x);
    math_increment(_t24);
    printf("after increment: x=");
    printf("%d", x);
    printf("\n");
    printf("\n=== Double Pointer (int**) ===\n");
    _t25 = 10;
    x = ((int32_t)(_t25));
    _t26 = &(x);
    ptr = ((int32_t*)(_t26));
    _t27 = &(ptr);
    pptr = ((int32_t**)(_t27));
    _t28 = *(lemon_require_ptr(ptr), ptr);
    _t29 = *(lemon_require_ptr(pptr), pptr);
    _t30 = *(lemon_require_ptr(_t29), _t29);
    printf("before set_via_double_ptr: x=");
    printf("%d", x);
    printf(", *ptr=");
    printf("%d", _t28);
    printf(", **pptr=");
    printf("%d", _t30);
    printf("\n");
    _t31 = 999;
    math_set_via_double_ptr(pptr, _t31);
    _t32 = *(lemon_require_ptr(ptr), ptr);
    _t33 = *(lemon_require_ptr(pptr), pptr);
    _t34 = *(lemon_require_ptr(_t33), _t33);
    printf("after set_via_double_ptr(pptr, 999): x=");
    printf("%d", x);
    printf(", *ptr=");
    printf("%d", _t32);
    printf(", **pptr=");
    printf("%d", _t34);
    printf("\n");
    printf("\n=== Get Value via Double Pointer ===\n");
    _t35 = &(storage);
    math_get_value(_t35);
    printf("get_value -> storage=");
    printf("%d", storage);
    printf("\n");
    printf("\n=== Modify Through Double Pointer ===\n");
    _t36 = 50;
    x = ((int32_t)(_t36));
    _t37 = &(x);
    ptr = ((int32_t*)(_t37));
    _t38 = &(ptr);
    pptr = ((int32_t**)(_t38));
    printf("before modify_through_pp: x=");
    printf("%d", x);
    printf("\n");
    _t39 = 777;
    math_modify_through_pp(pptr, _t39);
    printf("after modify_through_pp(pptr, 777): x=");
    printf("%d", x);
    printf("\n");
    printf("\n=== Null-Safe Dereference ===\n");
    _t40 = NULL;
    ptr = ((int32_t*)(_t40));
    _t41 = -1;
    _t42 = math_safe_deref(ptr, _t41);
    result = ((int32_t)(_t42));
    printf("safe_deref(null, -1)=");
    printf("%d", result);
    printf("\n");
    _t43 = 123;
    x = ((int32_t)(_t43));
    _t44 = &(x);
    ptr = ((int32_t*)(_t44));
    _t45 = -1;
    _t46 = math_safe_deref(ptr, _t45);
    result = ((int32_t)(_t46));
    printf("safe_deref(&x, -1)=");
    printf("%d", result);
    printf("\n");
    printf("\n=== Pointer Comparison ===\n");
    _t47 = 10;
    x = ((int32_t)(_t47));
    _t48 = 20;
    y = ((int32_t)(_t48));
    _t49 = &(x);
    _t50 = &(y);
    _t51 = math_compare_ptr(_t49, _t50);
    result = ((int32_t)(_t51));
    printf("compare_ptr(&10, &20)=");
    printf("%d", result);
    printf("\n");
    _t52 = &(y);
    _t53 = &(x);
    _t54 = math_compare_ptr(_t52, _t53);
    result = ((int32_t)(_t54));
    printf("compare_ptr(&20, &10)=");
    printf("%d", result);
    printf("\n");
    _t55 = &(x);
    _t56 = &(x);
    _t57 = math_compare_ptr(_t55, _t56);
    result = ((int32_t)(_t57));
    printf("compare_ptr(&10, &10)=");
    printf("%d", result);
    printf("\n");
    printf("\n=== Pointer Chain Increment ===\n");
    _t58 = 55;
    x = ((int32_t)(_t58));
    _t59 = &(x);
    ptr = ((int32_t*)(_t59));
    _t60 = &(ptr);
    pptr = ((int32_t**)(_t60));
    printf("before increment_via_chain: x=");
    printf("%d", x);
    printf("\n");
    math_increment_via_chain(pptr);
    printf("after increment_via_chain: x=");
    printf("%d", x);
    printf("\n");
    printf("\n=== Null Pointer Test ===\n");
    _t61 = NULL;
    ptr = ((int32_t*)(_t61));
    _t62 = math_is_null(ptr);
    result = ((int32_t)(_t62));
    printf("is_null(null)=");
    printf("%d", result);
    printf("\n");
    _t63 = &(x);
    ptr = ((int32_t*)(_t63));
    _t64 = math_is_null(ptr);
    result = ((int32_t)(_t64));
    printf("is_null(&x)=");
    printf("%d", result);
    printf("\n");
    printf("\n=== All tests completed ===\n");
    return 0;
}

int32_t math_add(int32_t a, int32_t b) {
    int32_t _t65 = 0;
    _t65 = a + b;
    return _t65;
}

int32_t math_mul(int32_t a, int32_t b) {
    int32_t _t66 = 0;
    _t66 = a * b;
    return _t66;
}

void math_add_ptr(int32_t* result, int32_t a, int32_t b) {
    int32_t _t67 = 0;
    _t67 = a + b;
    *(lemon_require_ptr(result), result) = _t67;
    return;
}

void math_mul_ptr(int32_t* result, int32_t a, int32_t b) {
    int32_t _t68 = 0;
    _t68 = a * b;
    *(lemon_require_ptr(result), result) = _t68;
    return;
}

void math_swap(int32_t* a, int32_t* b) {
    int32_t temp = 0;
    int32_t _t69 = 0;
    int32_t _t70 = 0;
    temp = 0;
    _t69 = *(lemon_require_ptr(a), a);
    temp = ((int32_t)(_t69));
    _t70 = *(lemon_require_ptr(b), b);
    *(lemon_require_ptr(a), a) = _t70;
    *(lemon_require_ptr(b), b) = temp;
    return;
}

void math_increment(int32_t* p) {
    int32_t _t71 = 0;
    int32_t _t72 = 0;
    int32_t _t73 = 0;
    _t71 = *(lemon_require_ptr(p), p);
    _t72 = 1;
    _t73 = _t71 + _t72;
    *(lemon_require_ptr(p), p) = _t73;
    return;
}

void math_set_via_double_ptr(int32_t** pp, int32_t value) {
    int32_t* _t74 = 0;
    _t74 = *(lemon_require_ptr(pp), pp);
    *(lemon_require_ptr(_t74), _t74) = value;
    return;
}

int32_t math_get_value(int32_t* storage) {
    int32_t _t75 = 0;
    int32_t _t76 = 0;
    _t75 = 42;
    *(lemon_require_ptr(storage), storage) = _t75;
    _t76 = 1;
    return _t76;
}

void math_modify_through_pp(int32_t** pp, int32_t new_val) {
    int32_t* _t77 = 0;
    _t77 = *(lemon_require_ptr(pp), pp);
    *(lemon_require_ptr(_t77), _t77) = new_val;
    return;
}

int32_t math_safe_deref(int32_t* p, int32_t default_val) {
    void* _t78 = 0;
    bool _t79 = 0;
    bool _t80 = 0;
    int32_t _t81 = 0;
    _t78 = NULL;
    _t79 = (p == _t78);
    _t80 = (_t79 == 0);
    if (_t80) goto if_merge_2;
    return default_val;
if_merge_2:;
    _t81 = *(lemon_require_ptr(p), p);
    return _t81;
}

int32_t math_array_sum(int32_t* arr, int32_t len) {
    int32_t i = 0;
    int32_t sum = 0;
    int32_t _t82 = 0;
    int32_t _t83 = 0;
    bool _t84 = 0;
    bool _t85 = 0;
    int32_t _t86 = 0;
    int32_t _t87 = 0;
    int32_t _t88 = 0;
    int32_t _t89 = 0;
    i = 0;
    sum = 0;
    _t82 = 0;
    sum = ((int32_t)(_t82));
    _t83 = 0;
    i = ((int32_t)(_t83));
    goto while_cond_3;
while_cond_3:;
    _t84 = (i < len);
    _t85 = (_t84 == 0);
    if (_t85) goto while_exit_5;
    lemon_bounds_check(arr, arr->length, (size_t)(i));
    _t86 = *((int32_t*)lemon_array_at(arr, (size_t)(i)));
    _t87 = sum + _t86;
    sum = ((int32_t)(_t87));
    _t88 = 1;
    _t89 = i + _t88;
    i = ((int32_t)(_t89));
    goto while_cond_3;
while_exit_5:;
    return sum;
}

void math_fill_array(int32_t* arr, int32_t len, int32_t value) {
    int32_t i = 0;
    int32_t _t90 = 0;
    bool _t91 = 0;
    bool _t92 = 0;
    int32_t _t93 = 0;
    int32_t _t94 = 0;
    i = 0;
    _t90 = 0;
    i = ((int32_t)(_t90));
    goto while_cond_6;
while_cond_6:;
    _t91 = (i < len);
    _t92 = (_t91 == 0);
    if (_t92) goto while_exit_8;
    lemon_bounds_check(arr, arr->length, (size_t)(i));
    *((int32_t*)lemon_array_at(arr, (size_t)(i))) = value;
    _t93 = 1;
    _t94 = i + _t93;
    i = ((int32_t)(_t94));
    goto while_cond_6;
while_exit_8:;
    return;
}

void math_reverse_array(int32_t* arr, int32_t len) {
    int32_t i = 0;
    int32_t j = 0;
    int32_t temp = 0;
    int32_t _t95 = 0;
    int32_t _t96 = 0;
    int32_t _t97 = 0;
    bool _t98 = 0;
    bool _t99 = 0;
    int32_t _t100 = 0;
    int32_t _t101 = 0;
    int32_t _t102 = 0;
    int32_t _t103 = 0;
    int32_t _t104 = 0;
    int32_t _t105 = 0;
    i = 0;
    j = 0;
    temp = 0;
    _t95 = 0;
    i = ((int32_t)(_t95));
    _t96 = 1;
    _t97 = len - _t96;
    j = ((int32_t)(_t97));
    goto while_cond_9;
while_cond_9:;
    _t98 = (i < j);
    _t99 = (_t98 == 0);
    if (_t99) goto while_exit_11;
    lemon_bounds_check(arr, arr->length, (size_t)(i));
    _t100 = *((int32_t*)lemon_array_at(arr, (size_t)(i)));
    temp = ((int32_t)(_t100));
    lemon_bounds_check(arr, arr->length, (size_t)(j));
    _t101 = *((int32_t*)lemon_array_at(arr, (size_t)(j)));
    lemon_bounds_check(arr, arr->length, (size_t)(i));
    *((int32_t*)lemon_array_at(arr, (size_t)(i))) = _t101;
    lemon_bounds_check(arr, arr->length, (size_t)(j));
    *((int32_t*)lemon_array_at(arr, (size_t)(j))) = temp;
    _t102 = 1;
    _t103 = i + _t102;
    i = ((int32_t)(_t103));
    _t104 = 1;
    _t105 = j - _t104;
    j = ((int32_t)(_t105));
    goto while_cond_9;
while_exit_11:;
    return;
}

int32_t math_compare_ptr(int32_t* a, int32_t* b) {
    int32_t _t106 = 0;
    int32_t _t107 = 0;
    bool _t108 = 0;
    bool _t109 = 0;
    int32_t _t110 = 0;
    int32_t _t111 = 0;
    int32_t _t112 = 0;
    bool _t113 = 0;
    bool _t114 = 0;
    int32_t _t115 = 0;
    int32_t _t116 = 0;
    _t106 = *(lemon_require_ptr(a), a);
    _t107 = *(lemon_require_ptr(b), b);
    _t108 = (_t106 < _t107);
    _t109 = (_t108 == 0);
    if (_t109) goto if_merge_13;
    _t110 = -1;
    return _t110;
if_merge_13:;
    _t111 = *(lemon_require_ptr(a), a);
    _t112 = *(lemon_require_ptr(b), b);
    _t113 = (_t111 > _t112);
    _t114 = (_t113 == 0);
    if (_t114) goto if_merge_15;
    _t115 = 1;
    return _t115;
if_merge_15:;
    _t116 = 0;
    return _t116;
}

void math_increment_via_chain(int32_t** pp) {
    int32_t* _t117 = 0;
    int32_t* _t118 = 0;
    int32_t _t119 = 0;
    int32_t _t120 = 0;
    int32_t _t121 = 0;
    _t117 = *(lemon_require_ptr(pp), pp);
    _t118 = *(lemon_require_ptr(pp), pp);
    _t119 = *(lemon_require_ptr(_t118), _t118);
    _t120 = 1;
    _t121 = _t119 + _t120;
    *(lemon_require_ptr(_t117), _t117) = _t121;
    return;
}

int32_t math_is_null(int32_t* p) {
    void* _t122 = 0;
    bool _t123 = 0;
    bool _t124 = 0;
    int32_t _t125 = 0;
    int32_t _t126 = 0;
    _t122 = NULL;
    _t123 = (p == _t122);
    _t124 = (_t123 == 0);
    if (_t124) goto if_merge_17;
    _t125 = 1;
    return _t125;
if_merge_17:;
    _t126 = 0;
    return _t126;
}

