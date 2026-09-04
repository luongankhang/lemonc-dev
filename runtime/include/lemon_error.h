#ifndef LEMON_ERROR_H
#define LEMON_ERROR_H

void lemon_panic(const char *message);
void lemon_panic_bounds(const void *array, size_t length, size_t index);

#endif
