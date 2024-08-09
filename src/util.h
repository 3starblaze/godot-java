#ifndef UTIL_H_
#define UTIL_H_

#define ARRAY_COUNT(var) (sizeof(var) / sizeof(*var))

typedef struct {
  const char *name;
  const char *signature;
} method_signature;

#endif // UTIL_H_
