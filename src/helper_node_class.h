#ifndef HELPER_NODE_CLASS_H_
#define HELPER_NODE_CLASS_H_

#include <string.h>
#include "../godot-headers/gdextension_interface.h"
#include "boilerplate.h"
#include <stdio.h>

typedef struct {
  GDExtensionObjectPtr gd_object;
} HelperNodeInstanceData;

typedef struct {
  const char* classname;
  const char* parent_classname;
} HelperNodeClassData;

char *copy_string(const char *s) {
  size_t bytes = strlen(s) + 1;
  char *res = malloc(bytes);
  memcpy(res, s, bytes);
  return res;
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

void register_helper_node_class(const char *parent_classname, const char *classname) {
  printf("deinit\n");
  HelperNodeClassData *class_userdata = malloc(sizeof(HelperNodeClassData));
  class_userdata->classname = copy_string(classname);
  class_userdata->parent_classname = copy_string(parent_classname);

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
    .get_virtual_call_data_func = NULL,
    .call_virtual_with_data_func = NULL,
    .get_rid_func = NULL,
    .class_userdata = class_userdata,
  };

  void *classname_string_name = c_to_gd_string_name(classname);
  void *parent_classname_string_name = c_to_gd_string_name(parent_classname);

  gd.api.classdb_register_extension_class2(gd.misc.p_library,
                                           classname_string_name,
                                           parent_classname_string_name,
                                           &class_info);

  gd.misc.destruct_string_name(classname_string_name);
  gd.misc.destruct_string_name(parent_classname_string_name);
}

#endif // HELPER_NODE_CLASS_H_
