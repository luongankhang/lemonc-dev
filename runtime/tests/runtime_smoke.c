#include "lemon_runtime.h"
#include <stdint.h>

int main(void) {
    lemon_string *left = lemon_string_new("lemon");
    lemon_string *right = lemon_string_new("c");
    lemon_string *joined = lemon_string_concat(left, right);
    lemon_string *expected = lemon_string_new("lemonc");
    if (lemon_string_compare(joined, expected) != 0) return 1;
    lemon_release(&expected->object);

    lemon_array *values = lemon_array_new(2, sizeof(int32_t), NULL);
    *(int32_t *)lemon_array_at(values, 0) = 41;
    *(int32_t *)lemon_array_at(values, 1) = 1;
    if (*(int32_t *)lemon_array_at(values, 0) + *(int32_t *)lemon_array_at(values, 1) != 42) return 2;

    lemon_retain(&joined->object);
    lemon_release(&joined->object);
    lemon_release(&joined->object);
    lemon_release(&left->object);
    lemon_release(&right->object);
    lemon_release(&values->object);
    return 0;
}
