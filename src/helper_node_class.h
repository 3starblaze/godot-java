#ifndef HELPER_NODE_CLASS_H_
#define HELPER_NODE_CLASS_H_

#include <string.h>
#include "../godot-headers/gdextension_interface.h"
#include "boilerplate.h"
#include "util.h"
#include <stdio.h>

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
  GDExtensionObjectPtr gd_object;
} HelperNodeInstanceData;

typedef struct {
  char* classname;
  char* parent_classname;
  void *classname_stringname;
  void *parent_classname_stringname;
  void *string_name_cache[ARRAY_COUNT(node_virtual_signatures)];
} HelperNodeClassData;

char *copy_string(const char *s) {
  size_t bytes = strlen(s) + 1;
  char *res = malloc(bytes);
  memcpy(res, s, bytes);
  return res;
}

typedef struct {
  NodeVirtualMethodType method_type;
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
      return res;
    }
  }

  return NULL;
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

  HelperNodeVirtualCallData *call_data = p_virtual_call_userdata;
  r_ret = NULL; // NOTE: All Node virtual methods return void

  switch (call_data->method_type) {
  case METHOD_ENTER_TREE:
    printf("METHOD_ENTER_TREE\n");
    // TODO
    return;
  case METHOD_EXIT_TREE:
    printf("METHOD_EXIT_TREE\n");
    // TODO
    return;
  case METHOD_GET_CONFIGURATION_WARNINGS:
    printf("METHOD_GET_CONFIGURATION_WARNINGS\n");
    // TODO
    return;
  case METHOD_INPUT:
    printf("METHOD_INPUT");
    // TODO
    return;
  case METHOD_PHYSICS_PROCESS:
    printf("METHOD_PHYSICS_PROCESS\n");
    // TODO
    return;
  case METHOD_PROCESS:
    printf("METHOD_PROCESS\n");
    // TODO
    return;
  case METHOD_SHORTCUT_INPUT:
    printf("METHOD_SHORTCUT_INPUT\n");
    // TODO
    return;
  case METHOD_UNHANDLED_INPUT:
    printf("METHOD_UNHANDLED_INPUT\n");
    // TODO
    return;
  case METHOD_UNHANDLED_KEY_INPUT:
    printf("METHOD_UNHANDLED_KEY_INPUT\n");
    // TODO
    return;
  case METHOD_READY:
    printf("METHOD_READY\n");
    // TODO
    return;
  }
}

GDExtensionObjectPtr helper_node_class_init(void *class_userdata) {
  printf("init\n");
  HelperNodeClassData *classdata = class_userdata;
  HelperNodeInstanceData *instance = malloc(sizeof(HelperNodeInstanceData));

  void *classname_string_name = c_to_gd_string_name(classdata->classname);
  void *parent_classname_string_name = c_to_gd_string_name(classdata->parent_classname);

  instance->gd_object = gd.api.classdb_construct_object(parent_classname_string_name);
  gd.api.object_set_instance(instance->gd_object, classname_string_name, instance);

  gd.misc.destruct_string_name(classname_string_name);
  gd.misc.destruct_string_name(parent_classname_string_name);

  return instance->gd_object;
}

void helper_node_class_deinit(void *class_userdata, void *p_instance) {
  printf("deinit\n");
  free(p_instance);
}

/**
 * Register helper Node class into classdb.
 *
 * @param parent_classname C string of the parent of the class.
 * @param classname C string of the helper class's name
 *
 * @return Pointer to classdata which should be passed to `unregister_helper_node_class` when you
 * need to release the class.
 */
HelperNodeClassData *
register_helper_node_class(
  const char *parent_classname,
  const char *classname
) {
  HelperNodeClassData *classdata = malloc(sizeof(HelperNodeClassData));

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
}

/**
 * Unregister helper node and release the resource.
 *
 * @param classdata Pointer to the constructed classdata which will be cleaned up.
 */
void unregister_helper_node_class(HelperNodeClassData *classdata) {
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
