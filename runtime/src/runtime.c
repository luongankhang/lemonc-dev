#include "lemon_runtime.h"
#include "lemon_memory.h"
#include "lemon_error.h"
#include "lemon_object.h"

void lemon_dealloc(void *ptr) { lemon_free(ptr); }
void lemon_bounds_check(const void *array, size_t length, size_t index) { if (array == NULL || index >= length) lemon_panic_bounds(array, length, index); }
