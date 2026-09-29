#include "lemon_array.h"
#include "lemon_memory.h"
#include "lemon_error.h"
#include <limits.h>
#include <string.h>

static lemon_type_info array_type = { "array", sizeof(lemon_array), 0, lemon_array_destroy, 0 };

lemon_array *lemon_array_new(size_t length, size_t element_size, const lemon_type_info *element_type) {
    if (LEMON_UNLIKELY(element_size == 0 || (length != 0 && element_size > (SIZE_MAX / 2) / length))) {
        lemon_panic_size_overflow();
    }
    lemon_array *array = lemon_calloc(1, sizeof(*array));
    lemon_object_init(&array->object, &array_type);
    array->length = length;
    array->capacity = length;
    array->element_size = element_size;
    array->element_type = element_type;
    array->data = length == 0 ? lemon_alloc(1) : lemon_calloc(length, element_size);
    return array;
}

void lemon_array_destroy(lemon_object *object) {
    if (LEMON_UNLIKELY(object == NULL)) return;
    lemon_array *array = (lemon_array *)object;
    if (array->release_element != NULL && array->data != NULL) {
        for (size_t i = 0; i < array->length; i++) {
            array->release_element(array->data + i * array->element_size);
        }
    }
#if LEMON_RUNTIME_DEBUG
    if (array->data != NULL && array->length > 0 && array->element_size > 0) {
        memset(array->data, 0xAA, array->length * array->element_size);
    }
#endif
    lemon_free(array->data);
    array->data = NULL;
    array->length = 0;
    array->capacity = 0;
    array->object.magic = (uint32_t)LEMON_OBJECT_MAGIC_FREED;
    array->object.refcount = (size_t)LEMON_OBJECT_REFCOUNT_FREED;
    lemon_free(array);
}

