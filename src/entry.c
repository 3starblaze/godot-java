#include <stdio.h>
#include <jni.h>
#include <stdlib.h>
#include <stdbool.h>
#include "../godot-headers/gdextension_interface.h"

#define HALT_ON_ERR(env) if (!assert_no_errors(env)) return false;

void noop(void *userdata, GDExtensionInitializationLevel p_level) {
  return;
}

/**
 * If error has occurred, report it and return false, otherwise return true.
 */
bool assert_no_errors(JNIEnv *env) {
  if ((*env)->ExceptionOccurred(env)) {
    (*env)->ExceptionDescribe(env);
    return false;
  }
  return true;
}

/**
 * Return a freshly allocated formatted char*.
 */
char *new_sprintf(const char *format, ...) {
  va_list fargs0, fargs1;
  va_start(fargs0, format);
  va_copy(fargs1, fargs0);

  size_t buflen = vsnprintf(NULL, 0, format, fargs0) + 1;
  char *res = malloc(sizeof(char) * buflen);
  vsnprintf(res, buflen, format, fargs1);

  va_end(fargs0);
  va_end(fargs1);
  return res;
}

#define LOAD_ENV_OR_HALT(var_name, env_name)        \
  const char *var_name = getenv(env_name);          \
  if (!var_name) {                                  \
    fprintf(stderr, env_name " is not defined!\n"); \
    return 0;                                       \
  }

typedef struct {
  JNIEnv *env;
  jobject singleton_instance;
  jmethodID deinit;
  jmethodID init;
} entry_init_deinit_type;

void godot_initialize(void *userdata, GDExtensionInitializationLevel p_level) {
  entry_init_deinit_type *data = userdata;

  (*(data->env))->CallVoidMethod(data->env,
                              data->singleton_instance,
                              data->init,
                              p_level);
}

void godot_deinitialize(void *userdata, GDExtensionInitializationLevel p_level) {
  entry_init_deinit_type *data = userdata;

  (*(data->env))->CallVoidMethod(data->env,
                              data->singleton_instance,
                              data->deinit,
                              p_level);
}

// Make sure you open godot from console in order to see these printf's
// also you should be at Godot 4.1+ afaik
// NOTE: This function should return true on success and false on failure.
GDExtensionBool
godot_entry(
  GDExtensionInterfaceGetProcAddress p_get_proc_address,
  const GDExtensionClassLibraryPtr p_library,
  GDExtensionInitialization *r_initialization
) {
  JavaVM *jvm;
  JNIEnv *env;
  JavaVMInitArgs vm_args;
  JavaVMOption option;

  LOAD_ENV_OR_HALT(classpath, "CLASSPATH")
  LOAD_ENV_OR_HALT(entry_class_name, "ENTRY_CLASS")

  option.optionString = new_sprintf("%s%s", "-Djava.class.path=", classpath);
  vm_args.options = &option;
  vm_args.nOptions = 1;
  vm_args.version = JNI_VERSION_10;
  vm_args.ignoreUnrecognized = false;

  JNI_CreateJavaVM(&jvm, (void **)&env, &vm_args);
  HALT_ON_ERR(env);

  jclass entry_class = (*env)->FindClass(env, entry_class_name);
  if (!entry_class) {
    fprintf(stderr, "Could not find class %s!\n", entry_class_name);
    return false;
  }
  HALT_ON_ERR(env);

  char *singleton_getter_signature = new_sprintf("()L%s;", entry_class_name);

  jmethodID singletonMethodId =
    (*env)->GetStaticMethodID(env, entry_class, "getInstance", singleton_getter_signature);
  free(singleton_getter_signature);
  HALT_ON_ERR(env);

  entry_init_deinit_type *userdata = malloc(sizeof(entry_init_deinit_type));

  userdata->env = env;

  userdata->init
    = (*env)->GetMethodID(env, entry_class, "initialize", "(I)V");
  HALT_ON_ERR(env);

  userdata->deinit
    = (*env)->GetMethodID(env, entry_class, "deinitialize", "(I)V");
  HALT_ON_ERR(env);

  jmethodID entry_method_id
    = (*env)->GetMethodID(env, entry_class, "entryFunction", "(JJ)Z");
  HALT_ON_ERR(env);

  userdata->singleton_instance
    = (*env)->CallStaticObjectMethod(env, entry_class, singletonMethodId);
  HALT_ON_ERR(env);

  jmethodID init_level_method_id
    = (*env)->GetMethodID(env, entry_class, "getMinInitlevel", "()I");
  HALT_ON_ERR(env);

  r_initialization->minimum_initialization_level
    = (*env)->CallIntMethod(env,
                            userdata->singleton_instance,
                            init_level_method_id);
  HALT_ON_ERR(env);

  jboolean is_ok
    = (*env)->CallBooleanMethod(env,
                             userdata->singleton_instance,
                             entry_method_id,
                             p_get_proc_address,
                             p_library);
  HALT_ON_ERR(env);

  r_initialization->initialize = godot_initialize;
  r_initialization->deinitialize = godot_deinitialize;
  r_initialization->userdata = userdata;

  return is_ok;
}
