#ifndef LEMON_STRING_H
#define LEMON_STRING_H

#include "lemon_object.h"

typedef struct lemon_string {
    lemon_object object;
    size_t length;
    size_t capacity;
    char *data;
} lemon_string;

lemon_string *lemon_string_new(const char *text);
lemon_string *lemon_string_concat(const lemon_string *left, const lemon_string *right);
int lemon_string_compare(const lemon_string *left, const lemon_string *right);
void lemon_string_destroy(lemon_object *object);

#endif
