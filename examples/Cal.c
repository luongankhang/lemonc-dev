#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <math.h>
#include <limits.h>
#include "lemon_runtime.h"

typedef struct { unsigned char _opaque; } lemon_opaque_t;

int32_t main();
int32_t cal(int32_t n);

int32_t main() {
    int32_t k1 = 0;
    int32_t n = 0;
    int32_t _t1 = 0;
    int32_t _t2 = 0;
    k1 = 0;
    n = 0;
    _t1 = 10;
    n = ((int32_t)(_t1));
    _t2 = cal(n);
    k1 = ((int32_t)(_t2));
    printf("10的阶乘是");
    printf("%d", k1);
    return 0;
}

int32_t cal(int32_t n) {
    int32_t rs = 0;
    int32_t _t3 = 0;
    bool _t4 = 0;
    bool _t5 = 0;
    int32_t _t6 = 0;
    int32_t _t7 = 0;
    int32_t _t8 = 0;
    int32_t _t9 = 0;
    int32_t _t10 = 0;
    rs = 0;
    _t3 = 1;
    _t4 = (n < _t3);
    _t5 = (_t4 == 0);
    if (_t5) goto if_else_2;
    _t6 = 1;
    rs = ((int32_t)(_t6));
    goto if_merge_3;
if_else_2:;
    _t7 = 1;
    _t8 = n - _t7;
    _t9 = cal(_t8);
    _t10 = n * _t9;
    rs = ((int32_t)(_t10));
    goto if_merge_3;
if_merge_3:;
    return rs;
}

