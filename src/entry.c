#include <stdio.h>
#include <jni.h>
#include <stdlib.h>
#include <stdbool.h>
#include "../godot-headers/gdextension_interface.h"
#include "boilerplate.h"
#include "helper_node_class.h"
#include "util.h"

#define HALT_ON_ERR(env) if (!assert_no_errors(env)) return false;

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
  char *helper_node_class_name;
  HelperNodeClassData *helper_node_classdata;
} GlobalUserdata;

void godot_initialize(void *userdata, GDExtensionInitializationLevel p_level) {
  GlobalUserdata *data = userdata;

  (*(data->env))->CallVoidMethod(data->env,
                              data->singleton_instance,
                              data->init,
                              p_level);

  if (p_level == GDEXTENSION_INITIALIZATION_SCENE) {
    data->helper_node_classdata = register_helper_node_class(data->env,
                                                             "Node",
                                                             data->helper_node_class_name);
  }
}

void godot_deinitialize(void *userdata, GDExtensionInitializationLevel p_level) {
  GlobalUserdata *data = userdata;

  (*(data->env))->CallVoidMethod(data->env,
                              data->singleton_instance,
                              data->deinit,
                              p_level);

  if (p_level == GDEXTENSION_INITIALIZATION_SCENE) {
    unregister_helper_node_class(data->helper_node_classdata);
  }
}

typedef enum {
  METHOD_INITIALIZE,
  METHOD_DEINITIALIZE,
  METHOD_ENTRY_FUNCTION,
  METHOD_GET_MIN_INIT_LEVEL,
} entry_class_method;

method_signature entry_class_signatures[] = {
  [METHOD_INITIALIZE] =
  { "initialize",      "(I)V" },
  [METHOD_DEINITIALIZE] =
  { "deinitialize",    "(I)V" },
  [METHOD_ENTRY_FUNCTION] =
  { "entryFunction",   "(JJ)Z" },
  [METHOD_GET_MIN_INIT_LEVEL] =
  { "getMinInitlevel", "()I" },
};


// Make sure you open godot from console in order to see these printf's
// also you should be at Godot 4.1+ afaik
// NOTE: This function should return true on success and false on failure.
GDExtensionBool
godot_entry(
  GDExtensionInterfaceGetProcAddress p_get_proc_address,
  const GDExtensionClassLibraryPtr p_library,
  GDExtensionInitialization *r_initialization
) {
  LOAD_ENV_OR_HALT(classpath, "CLASSPATH")
  LOAD_ENV_OR_HALT(entry_class_name, "ENTRY_CLASS")
  LOAD_ENV_OR_HALT(helper_node_class_name, "HELPER_NODE_CLASS")

  init_boilerplate(p_get_proc_address, p_library);

  JavaVM *jvm;
  JNIEnv *env;
  JavaVMOption option = {
    .optionString = new_sprintf("-Djava.class.path=%s", classpath),
  };
  JavaVMInitArgs vm_args = {
    .options = &option,
    .nOptions = 1,
    .version = JNI_VERSION_10,
    .ignoreUnrecognized = false,
  };

  jint jvm_init_result = JNI_CreateJavaVM(&jvm, (void **)&env, &vm_args);
  free(option.optionString);
  if (jvm_init_result != 0) {
    fprintf(stderr, "Could not initialize JVM, error code %d!\n", jvm_init_result);
    return false;
  }

  jclass entry_class = (*env)->FindClass(env, entry_class_name);
  HALT_ON_ERR(env);

  char *singleton_getter_signature = new_sprintf("()L%s;", entry_class_name);
  jmethodID singletonMethodId =
    (*env)->GetStaticMethodID(env, entry_class, "getInstance", singleton_getter_signature);
  free(singleton_getter_signature);
  HALT_ON_ERR(env);

  jmethodID methods[ARRAY_COUNT(entry_class_signatures)];

  for (size_t i = 0; i < ARRAY_COUNT(entry_class_signatures); i++) {
    methods[i] = (*env)->GetMethodID(env,
                                     entry_class,
                                     entry_class_signatures[i].name,
                                     entry_class_signatures[i].signature);
    HALT_ON_ERR(env);
  }

  GlobalUserdata *userdata = malloc(sizeof(GlobalUserdata));

  userdata->env = env;
  userdata->init = methods[METHOD_INITIALIZE];
  userdata->deinit = methods[METHOD_DEINITIALIZE];
  userdata->singleton_instance
    = (*env)->CallStaticObjectMethod(env, entry_class, singletonMethodId);
  HALT_ON_ERR(env);
  userdata->helper_node_class_name = copy_string(helper_node_class_name);

  r_initialization->minimum_initialization_level
    = (*env)->CallIntMethod(env,
                            userdata->singleton_instance,
                            methods[METHOD_GET_MIN_INIT_LEVEL]);
  HALT_ON_ERR(env);

  jboolean is_ok
    = (*env)->CallBooleanMethod(env,
                             userdata->singleton_instance,
                             methods[METHOD_ENTRY_FUNCTION],
                             p_get_proc_address,
                             p_library);
  HALT_ON_ERR(env);

  r_initialization->initialize = godot_initialize;
  r_initialization->deinitialize = godot_deinitialize;
  r_initialization->userdata = userdata;

  return is_ok;
}
