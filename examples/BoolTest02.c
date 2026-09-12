#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <math.h>
#include <limits.h>
#include "lemon_runtime.h"

typedef struct { unsigned char _opaque; } lemon_opaque_t;

int32_t main();
bool testBoolCall(bool b);

int32_t main() {
    bool b1 = 0;
    bool b2 = 0;
    bool b3 = 0;
    int32_t i1 = 0;
    int32_t i2 = 0;
    int32_t i3 = 0;
    int32_t n1 = 0;
    (void)n1;
    float f1 = 0;
    (void)f1;
    bool _t1 = 0;
    bool _t2 = 0;
    bool _t3 = 0;
    bool _t4 = 0;
    bool _t5 = 0;
    bool _t6 = 0;
    bool _t7 = 0;
    int32_t _t8 = 0;
    (void)_t8;
    bool _t9 = 0;
    int32_t _t10 = 0;
    int32_t _t11 = 0;
    bool _t12 = 0;
    int32_t _t13 = 0;
    int32_t _t14 = 0;
    bool _t15 = 0;
    int32_t _t16 = 0;
    int32_t _t17 = 0;
    b1 = 0;
    b2 = 0;
    b3 = 0;
    i1 = 0;
    i2 = 0;
    i3 = 0;
    n1 = 0;
    f1 = 0.0f;
    _t1 = true;
    b1 = ((bool)(_t1));
    _t2 = false;
    _t3 = testBoolCall(_t2);
    b2 = ((bool)(_t3));
    _t4 = (b1 == 0);
    _t5 = _t4 && b2;
    _t6 = (b2 == 0);
    _t7 = _t5 || _t6;
    b3 = ((bool)(_t7));
    _t8 = 0;
    _t9 = (b1 == 0);
    if (_t9) goto if_else_2;
    _t10 = 1;
    i1 = ((int32_t)(_t10));
    goto if_merge_3;
if_else_2:;
    _t11 = 0;
    i1 = ((int32_t)(_t11));
    goto if_merge_3;
if_merge_3:;
    _t12 = (b2 == 0);
    if (_t12) goto if_else_5;
    _t13 = 1;
    i2 = ((int32_t)(_t13));
    goto if_merge_6;
if_else_5:;
    _t14 = 0;
    i2 = ((int32_t)(_t14));
    goto if_merge_6;
if_merge_6:;
    _t15 = (b3 == 0);
    if (_t15) goto if_else_8;
    _t16 = 1;
    i3 = ((int32_t)(_t16));
    goto if_merge_9;
if_else_8:;
    _t17 = 0;
    i3 = ((int32_t)(_t17));
    goto if_merge_9;
if_merge_9:;
    printf("b1=");
    printf("%d", i1);
    printf(",b2=");
    printf("%d", i2);
    printf(",b3=");
    printf("%d", i3);
    return 0;
}

bool testBoolCall(bool b) {
    bool rs = 0;
    bool c = 0;
    bool _t18 = 0;
    bool _t19 = 0;
    bool _t20 = 0;
    bool _t21 = 0;
    rs = 0;
    c = 0;
    _t18 = false;
    c = ((bool)(_t18));
    _t19 = (c == 0);
    if (_t19) goto if_else_11;
    _t20 = (b == 0);
    rs = ((bool)(_t20));
    goto if_merge_12;
if_else_11:;
    rs = ((bool)(b));
    goto if_merge_12;
if_merge_12:;
    _t21 = true;
    rs = ((bool)(_t21));
    return rs;
}

