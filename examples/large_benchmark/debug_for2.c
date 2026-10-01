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
    int32_t i = 0;
    int32_t _t1 = 0;
    int32_t _t2 = 0;
    bool _t3 = 0;
    bool _t4 = 0;
    int32_t score = 0;
    (void)score;
    int32_t _t5 = 0;
    int32_t _t6 = 0;
    (void)_t6;
    int32_t _t7 = 0;
    int32_t _t8 = 0;
    int32_t _t9 = 0;
    int32_t _t10 = 0;
    bool _t11 = 0;
    bool _t12 = 0;
    int32_t _t13 = 0;
    int32_t _t14 = 0;
    (void)_t14;
    int32_t _t15 = 0;
    int32_t _t16 = 0;
    i = 0;
    _t1 = 0;
    i = ((int32_t)(_t1));
    goto for_cond_1;
for_cond_1:;
    _t2 = 3;
    _t3 = (i < _t2);
    _t4 = (_t3 == 0);
    if (_t4) goto for_exit_4;
    score = 0;
    _t5 = 10;
    _t6 = i * _t5;
    goto for_update_3;
for_update_3:;
    _t7 = 1;
    _t8 = i + _t7;
    i = ((int32_t)(_t8));
    goto for_cond_1;
for_exit_4:;
    _t9 = 0;
    i = ((int32_t)(_t9));
    goto for_cond_5;
for_cond_5:;
    _t10 = 2;
    _t11 = (i < _t10);
    _t12 = (_t11 == 0);
    if (_t12) goto for_exit_8;
    score = 0;
    _t13 = 20;
    _t14 = i * _t13;
    goto for_update_7;
for_update_7:;
    _t15 = 1;
    _t16 = i + _t15;
    i = ((int32_t)(_t16));
    goto for_cond_5;
for_exit_8:;
    printf("done\n");
    return 0;
}

