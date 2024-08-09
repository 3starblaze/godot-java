#ifndef BOILERPLATE_H_
#define BOILERPLATE_H_

#include "../godot-headers/gdextension_interface.h"
#include <stdbool.h>
#include <stdlib.h>

#define IS_GODOT_64_BIT (true)
#define GD_STRING_NAME_SIZE (IS_GODOT_64_BIT ? 8 : 4)
#define GD_STRING_SIZE (IS_GODOT_64_BIT ? 8 : 4)
#define STORE_GD_EXTENSION(function_sym) gd.api.function_sym = (void *)p_get_proc_address(#function_sym);

struct {
  struct {
    GDExtensionInterfaceStringNameNewWithUtf8Chars string_name_new_with_utf8_chars;
    GDExtensionInterfaceStringNewWithUtf8Chars string_new_with_utf8_chars;
    GDExtensionInterfaceVariantGetPtrOperatorEvaluator variant_get_ptr_operator_evaluator;
    GDExtensionInterfaceVariantGetPtrDestructor variant_get_ptr_destructor;
    GDExtensionInterfaceClassdbConstructObject classdb_construct_object;
    GDExtensionInterfaceClassdbRegisterExtensionClass2 classdb_register_extension_class2;
    GDExtensionInterfaceClassdbUnregisterExtensionClass classdb_unregister_extension_class;
    GDExtensionInterfaceObjectSetInstance object_set_instance;
  } api;
  struct {
    GDExtensionClassLibraryPtr p_library;
    GDExtensionPtrDestructor destruct_string_name;
    GDExtensionPtrDestructor destruct_string;
  } misc;
  struct {
    GDExtensionPtrOperatorEvaluator raw_string_name_eq_op;
  } _internal;
} gd;

void *c_to_gd_string_name(const char *c_string) {
  void *res = malloc(GD_STRING_NAME_SIZE);
  gd.api.string_name_new_with_utf8_chars(res, c_string);
  return res;
}

void *c_to_gd_string(const char *c_string) {
  void *res = malloc(GD_STRING_SIZE);
  gd.api.string_new_with_utf8_chars(res, c_string);
  return res;
}

bool string_name_eq(const void *string_name_a, const void *string_name_b) {
  GDExtensionBool res;
  gd._internal.raw_string_name_eq_op(string_name_a, string_name_b, &res);
  return res;
}

void
init_boilerplate(
  GDExtensionInterfaceGetProcAddress p_get_proc_address,
  const GDExtensionClassLibraryPtr p_library
) {
  STORE_GD_EXTENSION(string_name_new_with_utf8_chars);
  STORE_GD_EXTENSION(string_new_with_utf8_chars);
  STORE_GD_EXTENSION(variant_get_ptr_operator_evaluator);
  STORE_GD_EXTENSION(variant_get_ptr_destructor);
  STORE_GD_EXTENSION(classdb_construct_object);
  STORE_GD_EXTENSION(classdb_register_extension_class2);
  STORE_GD_EXTENSION(object_set_instance);
  STORE_GD_EXTENSION(classdb_unregister_extension_class);

  gd.misc.p_library = p_library;

  gd._internal.raw_string_name_eq_op =
    gd.api.variant_get_ptr_operator_evaluator(GDEXTENSION_VARIANT_OP_EQUAL,
                                              GDEXTENSION_VARIANT_TYPE_STRING_NAME,
                                              GDEXTENSION_VARIANT_TYPE_STRING_NAME);
  gd.misc.destruct_string_name =
    gd.api.variant_get_ptr_destructor(GDEXTENSION_VARIANT_TYPE_STRING_NAME);
  gd.misc.destruct_string =
    gd.api.variant_get_ptr_destructor(GDEXTENSION_VARIANT_TYPE_STRING);
}

#endif // BOILERPLATE_H_
