#ifndef QISHUI_LAME_ANDROID_CONFIG_H
#define QISHUI_LAME_ANDROID_CONFIG_H

#define LAME_CONFIG_H 1
#define STDC_HEADERS 1
#define HAVE_STDINT_H 1
#define HAVE_ERRNO_H 1

#ifndef HAVE_IEEE754_FLOAT32_T
typedef float ieee754_float32_t;
#endif

#ifndef HAVE_IEEE754_FLOAT64_T
typedef double ieee754_float64_t;
#endif

#ifndef HAVE_IEEE854_FLOAT80_T
typedef long double ieee854_float80_t;
#endif

#endif
