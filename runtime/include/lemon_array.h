#ifndef LEMON_ARRAY_H
#define LEMON_ARRAY_H

#include "lemon_object.h"
#include "lemon_error.h"

typedef void (*lemon_element_retain)(void *element);
typedef void (*lemon_element_release)(void *element);

typedef struct lemon_array {
    lemon_object object;
    size_t length;
    size_t capacity;
    size_t element_size;
    const lemon_type_info *element_type;
    lemon_element_retain retain_element;
    lemon_element_release release_element;
    unsigned char *data;
} lemon_array;

lemon_array *lemon_array_new(size_t length, size_t element_size, const lemon_type_info *element_type);
void lemon_array_destroy(lemon_object *object);

LEMON_INLINE void *lemon_array_at(lemon_array *array, size_t index) {
    if (LEMON_UNLIKELY(array == NULL || index >= array->length)) {
        lemon_panic_bounds(array, array == NULL ? 0 : array->length, index);
    }
#if LEMON_RUNTIME_DEBUG
    if (LEMON_UNLIKELY(array->object.magic != LEMON_OBJECT_MAGIC_ALIVE)) {
        lemon_panic("use-after-free or invalid array in array indexing");
    }
#endif
    return array->data + index * array->element_size;
}

#endif
