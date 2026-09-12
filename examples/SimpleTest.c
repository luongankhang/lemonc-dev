#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <math.h>
#include <limits.h>
#include "lemon_runtime.h"

typedef struct { unsigned char _opaque; } lemon_opaque_t;

int32_t main();

int32_t main() {
    lemon_array* farr = 0;
    int32_t i = 0;
    int32_t _t1 = 0;
    float _t2 = 0;
    int32_t _t3 = 0;
    float _t4 = 0;
    int32_t _t5 = 0;
    float _t6 = 0;
    int32_t _t7 = 0;
    int32_t _t8 = 0;
    bool _t9 = 0;
    bool _t10 = 0;
    float _t11 = 0;
    int32_t _t12 = 0;
    int32_t _t13 = 0;
    farr = lemon_array_new((size_t)(3), sizeof(float), NULL);
    i = 0;
    _t1 = 0;
    _t2 = 1.1f;
    lemon_bounds_check(farr, farr->length, (size_t)(_t1));
    *((float*)lemon_array_at(farr, (size_t)(_t1))) = _t2;
    _t3 = 1;
    _t4 = 2.2f;
    lemon_bounds_check(farr, farr->length, (size_t)(_t3));
    *((float*)lemon_array_at(farr, (size_t)(_t3))) = _t4;
    _t5 = 2;
    _t6 = 3.3f;
    lemon_bounds_check(farr, farr->length, (size_t)(_t5));
    *((float*)lemon_array_at(farr, (size_t)(_t5))) = _t6;
    _t7 = 0;
    i = ((int32_t)(_t7));
    goto while_cond_1;
while_cond_1:;
    _t8 = 3;
    _t9 = (i < _t8);
    _t10 = (_t9 == 0);
    if (_t10) goto while_exit_3;
    lemon_bounds_check(farr, farr->length, (size_t)(i));
    _t11 = *((float*)lemon_array_at(farr, (size_t)(i)));
    lemon_print_float(_t11);
    printf(" ");
    _t12 = 1;
    _t13 = i + _t12;
    i = ((int32_t)(_t13));
    goto while_cond_1;
while_exit_3:;
    lemon_release(farr);
    return 0;
}

