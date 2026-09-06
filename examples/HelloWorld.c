#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <math.h>
#include <limits.h>
#include "lemon_runtime.h"

typedef struct { unsigned char _opaque; } lemon_opaque_t;

int32_t main();
int32_t add(int32_t x, int32_t y);

int32_t main() {
    int32_t a = 0;
    int32_t b = 0;
    int32_t _t1 = 0;
    int32_t _t2 = 0;
    int32_t _t3 = 0;
    a = 0;
    b = 0;
    _t1 = 15;
    a = ((int32_t)(_t1));
    _t2 = 27;
    b = ((int32_t)(_t2));
    _t3 = add(a, b);
    printf("a=%d,b=%d,add=%d", a, b, _t3);
    return 0;
}

int32_t add(int32_t x, int32_t y) {
    int32_t _t4 = 0;
    _t4 = x + y;
    return _t4;
}

