#include "lemon_array.h"
#include "lemon_memory.h"
#include "lemon_error.h"

static lemon_type_info array_type = { "array", sizeof(lemon_array), 0, lemon_array_destroy, 0 };
lemon_array *lemon_array_new(size_t length, size_t element_size, const lemon_type_info *element_type) { lemon_array *array = lemon_calloc(1, sizeof(*array)); lemon_object_init(&array->object, &array_type); array->length = length; array->capacity = length; array->element_size = element_size; array->element_type = element_type; array->data = lemon_calloc(length, element_size); return array; }
void *lemon_array_at(lemon_array *array, size_t index) { if (array == NULL || index >= array->length) lemon_panic_bounds(array, array == NULL ? 0 : array->length, index); return array->data + index * array->element_size; }
void lemon_array_destroy(lemon_object *object) { lemon_array *array = (lemon_array *)object; if (array->release_element != NULL) for (size_t i = 0; i < array->length; i++) array->release_element(array->data + i * array->element_size); lemon_free(array->data); lemon_free(array); }
