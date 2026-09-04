#ifndef LEMON_RUNTIME_H
#define LEMON_RUNTIME_H

#include <stddef.h>
#include "include/lemon_runtime_config.h"
#include "include/lemon_memory.h"
#include "include/lemon_object.h"
#include "include/lemon_string.h"
#include "include/lemon_array.h"
#include "include/lemon_error.h"

void lemon_dealloc(void *ptr);
void lemon_bounds_check(const void *array, size_t length, size_t index);

#endif
