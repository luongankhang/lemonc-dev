#ifndef LEMON_FLOATFMT_H
#define LEMON_FLOATFMT_H

/*
 * Print float/double values to stdout using the same decimal notation as the
 * JVM backend: the shortest round-trip digits (Java Float.toString /
 * Double.toString). Without this the C backend (%f, six decimals) and the JVM
 * backend (shortest decimal) would print different text for the same program.
 */

void lemon_print_float(float value);
void lemon_print_double(double value);

#endif
