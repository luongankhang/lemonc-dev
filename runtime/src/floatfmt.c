/*
 * Java-compatible float/double printing.
 *
 * The JVM backend lowers printf("%f", v) to System.out.print(v), whose text is
 * Java's Float.toString/Double.toString: the shortest decimal that round-trips
 * to the same binary value, in positional notation for magnitudes in
 * [1e-3, 1e7) and scientific notation outside that range. This file gives the
 * C backend the same observable text so both backends print byte-identical
 * output for the same program.
 *
 * Strategy: for each precision p (0..17 significant-digit candidates) render
 * the value with printf's "%.*e", then parse the candidate back with the
 * correctly-rounded strtod/strtof. The first candidate that round-trips has
 * the shortest digit count; its digits are then rendered with Java's layout
 * rules (positional for -3 <= decExp <= 6, otherwise scientific with an "E"
 * exponent and no leading zeros / plus sign).
 */
#include "lemon_floatfmt.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Renders decimal digits with the decimal point located so that the value is
 * digits[0..n) with exp digits between the first digit and the point
 * (digits = mantissa digits of "%.*e", exp = the exponent). */
static void print_formatted(int negative, const char *digits, int exp) {
    int nd = (int)strlen(digits);
    while (nd > 1 && digits[nd - 1] == '0') {
        nd--; /* trailing zeros never carry information here */
    }
    if (negative) {
        putchar('-');
    }
    if (exp >= -3 && exp <= 6) {
        /* Positional notation (Java prints 0.001 but 1.0E-4, 9999999.0 but 1.0E7). */
        int intPoint = exp + 1; /* number of digits before the point */
        if (intPoint <= 0) {
            fputs("0.", stdout);
            for (int i = 0; i < -intPoint; i++) {
                putchar('0');
            }
            fwrite(digits, 1, (size_t)nd, stdout);
        } else if (intPoint >= nd) {
            fwrite(digits, 1, (size_t)nd, stdout);
            for (int i = nd; i < intPoint; i++) {
                putchar('0');
            }
            fputs(".0", stdout); /* Java keeps ".0" for integral values */
        } else {
            fwrite(digits, 1, (size_t)intPoint, stdout);
            putchar('.');
            fwrite(digits + intPoint, 1, (size_t)(nd - intPoint), stdout);
        }
    } else {
        /* Scientific notation: d.dddE<exp>, no '+' and no zero padding. */
        putchar(digits[0]);
        if (nd == 1) {
            fputs(".0", stdout); /* Java prints 1.0E7, not 1E7 */
        } else {
            putchar('.');
            fwrite(digits + 1, 1, (size_t)(nd - 1), stdout);
        }
        putchar('E');
        printf("%d", exp);
    }
}

/* Shared core: value is positive (sign handled by caller), parseBack parses a
 * candidate string back to the binary type, maxDigits bounds the search. */
static void print_shortest(int negative, double value,
                           double (*parseBack)(const char *), int maxDigits) {
    char candidate[48];
    int chosen = -1;

    for (int p = 0; p <= maxDigits; p++) {
        char buf[48];
        snprintf(buf, sizeof buf, "%.*e", p, value);
        if (parseBack(buf) == value) {
            chosen = p;
            memcpy(candidate, buf, sizeof candidate);
            break;
        }
    }
    if (chosen < 0) {
        /* Should not happen (17 digits always round-trip a double); fall back. */
        snprintf(candidate, sizeof candidate, "%.17e", value);
    }

    char *e = strchr(candidate, 'e');
    if (e == NULL) {
        e = strchr(candidate, 'E');
    }
    int exp = e != NULL ? atoi(e + 1) : 0;
    size_t mantLen = e != NULL ? (size_t)(e - candidate) : strlen(candidate);

    char digits[32];
    size_t n = 0;
    for (size_t i = 0; i < mantLen; i++) {
        if (candidate[i] != '.') {
            if (n < sizeof digits - 1) {
                digits[n++] = candidate[i];
            }
        }
    }
    digits[n] = '\0';
    print_formatted(negative, digits, exp);
}

static double parse_double(const char *text) {
    return strtod(text, NULL);
}

static double parse_float(const char *text) {
    return (double)strtof(text, NULL);
}

void lemon_print_double(double value) {
    if (isnan(value)) {
        fputs("NaN", stdout);
        return;
    }
    if (isinf(value)) {
        fputs(value < 0 ? "-Infinity" : "Infinity", stdout);
        return;
    }
    int negative = signbit(value);
    if (value == 0.0) {
        fputs(negative ? "-0.0" : "0.0", stdout);
        return;
    }
    print_shortest(negative, fabs(value), parse_double, 17);
}

void lemon_print_float(float value) {
    if (isnan(value)) {
        fputs("NaN", stdout);
        return;
    }
    if (isinf(value)) {
        fputs(value < 0 ? "-Infinity" : "Infinity", stdout);
        return;
    }
    int negative = signbit(value);
    if (value == 0.0f) {
        fputs(negative ? "-0.0" : "0.0", stdout);
        return;
    }
    /* printf's %e sees the promoted double, whose digits are the float's own
     * digits; round-trip must be checked against the float (strtof). */
    print_shortest(negative, (double)fabsf(value), parse_float, 9);
}
