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
    lemon_array* values = 0;
    lemon_array* weights = 0;
    int32_t total = 0;
    int32_t _t1 = 0;
    int32_t _t2 = 0;
    int32_t _t3 = 0;
    int32_t _t4 = 0;
    int32_t _t5 = 0;
    values = lemon_array_new((size_t)(5), sizeof(int32_t), NULL);
    weights = lemon_array_new((size_t)(3), sizeof(float), NULL);
    total = 0;
    _t1 = (int32_t)(values->length);
    _t2 = (int32_t)(weights->length);
    _t3 = _t1 + _t2;
    total = ((int32_t)(_t3));
    _t4 = (int32_t)(values->length);
    _t5 = (int32_t)(weights->length);
    printf("values=");
    printf("%d", _t4);
    printf(",weights=");
    printf("%d", _t5);
    printf(",total=");
    printf("%d", total);
    printf("\n");
    lemon_release(values);
    lemon_release(weights);
    return 0;
}

