#ifndef LEMON_ARRAY_H
#define LEMON_ARRAY_H

#include "lemon_object.h"

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
void *lemon_array_at(lemon_array *array, size_t index);
void lemon_array_destroy(lemon_object *object);

#endif
