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
    int32_t j = 0;
    (void)j;
    int32_t rounds = 0;
    int32_t inner = 0;
    (void)inner;
    int32_t _t1 = 0;
    int32_t _t2 = 0;
    bool _t3 = 0;
    bool _t4 = 0;
    int32_t _t5 = 0;
    int32_t _t6 = 0;
    int32_t _t7 = 0;
    int32_t _t8 = 0;
    bool _t9 = 0;
    bool _t10 = 0;
    int32_t score = 0;
    (void)score;
    int32_t _t11 = 0;
    int32_t _t12 = 0;
    (void)_t12;
    int32_t _t13 = 0;
    int32_t _t14 = 0;
    i = 0;
    j = 0;
    rounds = 0;
    inner = 0;
    _t1 = 0;
    rounds = ((int32_t)(_t1));
    goto while_cond_1;
while_cond_1:;
    _t2 = 2;
    _t3 = (rounds < _t2);
    _t4 = (_t3 == 0);
    if (_t4) goto while_exit_3;
    _t5 = 1;
    _t6 = rounds + _t5;
    rounds = ((int32_t)(_t6));
    _t7 = 0;
    i = ((int32_t)(_t7));
    goto for_cond_4;
for_cond_4:;
    _t8 = 3;
    _t9 = (i < _t8);
    _t10 = (_t9 == 0);
    if (_t10) goto for_exit_7;
    score = 0;
    _t11 = 10;
    _t12 = i * _t11;
    goto for_update_6;
for_update_6:;
    _t13 = 1;
    _t14 = i + _t13;
    i = ((int32_t)(_t14));
    goto for_cond_4;
for_exit_7:;
    goto while_cond_1;
while_exit_3:;
    printf("done\n");
    return 0;
}

