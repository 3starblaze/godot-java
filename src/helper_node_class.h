#ifndef HELPER_NODE_CLASS_H_
#define HELPER_NODE_CLASS_H_

#include <string.h>
#include "../godot-headers/gdextension_interface.h"
#include "boilerplate.h"
#include "util.h"
#include <stdio.h>
#include <jni.h>

typedef enum {
  METHOD_ENTER_TREE,
  METHOD_EXIT_TREE,
  METHOD_GET_CONFIGURATION_WARNINGS,
  METHOD_INPUT,
  METHOD_PHYSICS_PROCESS,
  METHOD_PROCESS,
  METHOD_SHORTCUT_INPUT,
  METHOD_UNHANDLED_INPUT,
  METHOD_UNHANDLED_KEY_INPUT,
  METHOD_READY,
} NodeVirtualMethodType;

const char *helper_node_constructor_signature = "(J)V";

method_signature node_virtual_signatures[] = {
  [METHOD_ENTER_TREE] =
  { "_enter_tree",                  "()V" },
  [METHOD_EXIT_TREE] =
  { "_exit_tree",                   "()V" },
  [METHOD_GET_CONFIGURATION_WARNINGS] =
  { "_get_configuration_warnings",  "()V" },
  [METHOD_INPUT] =
  { "_input",                       "(Ljava/lang/Object;)V" },
  [METHOD_PHYSICS_PROCESS] =
  { "_physics_process",             "(D)V"},
  [METHOD_PROCESS] =
  { "_process",                     "(D)V"},
  [METHOD_SHORTCUT_INPUT] =
  { "_shortcut_input",              "(Ljava/lang/Object;)V"},
  [METHOD_UNHANDLED_INPUT] =
  { "_unhandled_input",             "(Ljava/lang/Object;)V"},
  [METHOD_UNHANDLED_KEY_INPUT] =
  { "_unhandled_key_input",         "(Ljava/lang/Object;)V"},
  [METHOD_READY] =
  { "_ready",                       "()V" },
};

typedef struct {
  JNIEnv *env;
  char* classname;
  char* parent_classname;
  void *classname_stringname;
  void *parent_classname_stringname;
  void *string_name_cache[ARRAY_COUNT(node_virtual_signatures)];
  jmethodID jmethod_virtuals_cache[ARRAY_COUNT(node_virtual_signatures)];
  jmethodID jmethod_constructor_cache;
  jclass dummy_object_class;
  jmethodID dummy_constructor_cache;
  jobject dummy_java_object;
  jclass java_class;
} HelperNodeClassData;

typedef struct {
  GDExtensionObjectPtr gd_object;
  jobject java_object;
} HelperNodeInstanceData;

char *copy_string(const char *s) {
  size_t bytes = strlen(s) + 1;
  char *res = malloc(bytes);
  memcpy(res, s, bytes);
  return res;
}

typedef struct {
  NodeVirtualMethodType method_type;
  // NOTE: Classdata is stored because classdata is not passed to
  // `GDExtensionClassCallVirtualWithData` function
  HelperNodeClassData *classdata;
} HelperNodeVirtualCallData;

void *
helper_node_class_get_virtual_call_data(
  void *p_class_userdata,
  GDExtensionConstStringNamePtr p_name
) {
  HelperNodeClassData *classdata = p_class_userdata;

  for (size_t i = 0; i < ARRAY_COUNT(node_virtual_signatures); i++) {
    if (string_name_eq(p_name, classdata->string_name_cache[i])) {
      HelperNodeVirtualCallData *res = malloc(sizeof(HelperNodeVirtualCallData));
      res->method_type = i;
      res->classdata = classdata;
      return res;
    }
  }

  return NULL;
}

void
handle_virtual_call(
  HelperNodeInstanceData *instance,
  HelperNodeVirtualCallData *virtualdata,
  const GDExtensionConstTypePtr *p_args
) {
  JNIEnv *env = virtualdata->classdata->env;
  const NodeVirtualMethodType method_type = virtualdata->method_type;

  switch (method_type) {
  case METHOD_ENTER_TREE:
  case METHOD_EXIT_TREE:
  case METHOD_GET_CONFIGURATION_WARNINGS:
  case METHOD_READY:
    (*env)->CallVoidMethod(env,
                           instance->java_object,
                           virtualdata->classdata->jmethod_virtuals_cache[method_type]);
    return;
  case METHOD_PHYSICS_PROCESS:
  case METHOD_PROCESS:
    (*env)->CallVoidMethod(env,
                           instance->java_object,
                           virtualdata->classdata->jmethod_virtuals_cache[method_type],
                           *(double *)(p_args[0]));
    return;
  case METHOD_INPUT:
  case METHOD_SHORTCUT_INPUT:
  case METHOD_UNHANDLED_INPUT:
  case METHOD_UNHANDLED_KEY_INPUT:
    // TODO Replace dummy object
    (*env)->CallVoidMethod(env,
                           instance->java_object,
                           virtualdata->classdata->jmethod_virtuals_cache[method_type],
                           virtualdata->classdata->dummy_java_object);
    return;
  }
}

void
helper_class_call_virtual_with_data(
   GDExtensionClassInstancePtr p_instance,
   GDExtensionConstStringNamePtr p_name,
   void *p_virtual_call_userdata,
   const GDExtensionConstTypePtr *p_args,
   GDExtensionTypePtr r_ret
) {
  if (p_virtual_call_userdata == NULL) return;

  r_ret = NULL; // NOTE: All Node virtual methods return void
  handle_virtual_call(p_instance, p_virtual_call_userdata, p_args);
}

GDExtensionObjectPtr helper_node_class_init(void *class_userdata) {
  printf("init\n");
  HelperNodeClassData *classdata = class_userdata;
  HelperNodeInstanceData *instance = malloc(sizeof(HelperNodeInstanceData));

  // NOTE: You may need to make a global reference so that this is not GC'd
  instance->java_object =
    (*classdata->env)->NewObject(classdata->env,
                                 classdata->java_class,
                                 classdata->jmethod_constructor_cache,
                                 instance);

  if ((*classdata->env)->ExceptionOccurred(classdata->env)) {
    (*classdata->env)->ExceptionDescribe(classdata->env);
    free(instance);
    return NULL; // lmao, I hope I don't segfault
  }

  instance->gd_object = gd.api.classdb_construct_object(classdata->parent_classname_stringname);
  gd.api.object_set_instance(instance->gd_object, classdata->classname_stringname, instance);

  return instance->gd_object;
}

void helper_node_class_deinit(void *class_userdata, void *p_instance) {
  printf("deinit\n");
  if (p_instance == NULL) return;

  free(p_instance);
}

/**
 * Register helper Node class into classdb and return class information.
 *
 * @param parent_classname C string of the parent of the class.
 * @param classname C string of the helper class's name
 *
 * @return Pointer to classdata which should be passed to `unregister_helper_node_class` when you
 * need to release the class. If the registering fails, NULL is returned. NULL is safe to pass to
 * `unregister_helper_node_class`.
 */
HelperNodeClassData *
register_helper_node_class(
  JNIEnv *env,
  const char *parent_classname,
  const char *classname
) {
  HelperNodeClassData *classdata = malloc(sizeof(HelperNodeClassData));

  classdata->java_class = (*env)->FindClass(env, classname);
  if ((*env)->ExceptionOccurred(env)) goto jvm_env_error;

  classdata->jmethod_constructor_cache = (*env)->GetMethodID(env,
                                                             classdata->java_class,
                                                             "<init>",
                                                             helper_node_constructor_signature);
  if ((*env)->ExceptionOccurred(env)) goto jvm_env_error;

  classdata->dummy_object_class = (*env)->FindClass(env, "java/lang/Object");
  if ((*env)->ExceptionOccurred(env)) goto jvm_env_error;

  classdata->dummy_constructor_cache = (*env)->GetMethodID(env,
                                                           classdata->dummy_object_class,
                                                           "<init>",
                                                           "()V");
  if ((*env)->ExceptionOccurred(env)) goto jvm_env_error;

  classdata->dummy_java_object = (*env)->NewObject(env,
                                                   classdata->dummy_object_class,
                                                   classdata->dummy_constructor_cache);
  if ((*env)->ExceptionOccurred(env)) goto jvm_env_error;

  for (size_t i = 0; i < ARRAY_COUNT(node_virtual_signatures); i++) {
    classdata->jmethod_virtuals_cache[i]
      = (*env)->GetMethodID(env,
                            classdata->java_class,
                            node_virtual_signatures[i].name,
                            node_virtual_signatures[i].signature);
    if ((*env)->ExceptionOccurred(env)) goto jvm_env_error;
  }

  classdata->env = env;
  classdata->classname = copy_string(classname);
  classdata->parent_classname = copy_string(parent_classname);
  classdata->classname_stringname = c_to_gd_string_name(classname);
  classdata->parent_classname_stringname = c_to_gd_string_name(parent_classname);

  for (size_t i = 0; i < ARRAY_COUNT(node_virtual_signatures); i++) {
    classdata->string_name_cache[i] = c_to_gd_string_name(node_virtual_signatures[i].name);
  }

  GDExtensionClassCreationInfo2 class_info = {
    .is_virtual = false,
    .is_abstract = false,
    .is_exposed = true,
    .set_func = NULL,
    .get_func = NULL,
    .get_property_list_func = NULL,
    .free_property_list_func = NULL,
    .property_can_revert_func = NULL,
    .property_get_revert_func = NULL,
    .validate_property_func = NULL,
    .notification_func = NULL,
    .to_string_func = NULL,
    .reference_func = NULL,
    .unreference_func = NULL,
    .create_instance_func = helper_node_class_init,
    .free_instance_func = helper_node_class_deinit,
    .recreate_instance_func = NULL,
    .get_virtual_func = NULL,
    .get_virtual_call_data_func = helper_node_class_get_virtual_call_data,
    .call_virtual_with_data_func = helper_class_call_virtual_with_data,
    .get_rid_func = NULL,
    .class_userdata = classdata,
  };

  gd.api.classdb_register_extension_class2(gd.misc.p_library,
                                           classdata->classname_stringname,
                                           classdata->parent_classname_stringname,
                                           &class_info);

  return classdata;

  jvm_env_error:
  (*env)->ExceptionDescribe(env);
  free(classdata);
  return NULL;
}

/**
 * Unregister helper node and release the resource.
 *
 * @param classdata Pointer to the constructed classdata which will be cleaned up.
 */
void unregister_helper_node_class(HelperNodeClassData *classdata) {
  if (classdata == NULL) return;
  gd.api.classdb_unregister_extension_class(gd.misc.p_library, classdata->classname_stringname);

  for (size_t i = 0; i < ARRAY_COUNT(classdata->string_name_cache); i++) {
    gd.misc.destruct_string_name(classdata->string_name_cache);
  }

  free(classdata->classname);
  free(classdata->parent_classname);
  gd.misc.destruct_string_name(classdata->parent_classname_stringname);
  gd.misc.destruct_string_name(classdata->classname_stringname);
  free(classdata);
}

#endif // HELPER_NODE_CLASS_H_
