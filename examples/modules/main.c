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

int32_t main() {
    int32_t _t1 = 0;
    int32_t _t2 = 0;
    int32_t _t3 = 0;
    _t1 = 10;
    _t2 = 20;
    _t3 = math_add(_t1, _t2);
    printf("%d", _t3);
    printf("\n");
    return 0;
}

int32_t math_add(int32_t a, int32_t b) {
    int32_t _t4 = 0;
    _t4 = a + b;
    return _t4;
}

