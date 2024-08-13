# Godot Java

(WIP) Godot bindings for Java.

## Dev information

``` bash
make
mvn clean assembly:assembly -DdescriptorId=jar-with-dependencies
# NOTE: On my laptop there's a bug in which Godot complains about not being able to compile shaders
# and spamming the console with internal source files. Deleting .godot folder before launching godot
# fixes the issue. This is especially annoying because godot has to be force-killed when it's
# printing its stuff.
rm -r src/dummy_godot_project/.godot/
LD_LIBRARY_PATH=/usr/lib/jvm/java-1.11.0-openjdk-amd64/lib/server \
  CLASSPATH="$(readlink -f target/godot_java-1.0-SNAPSHOT-jar-with-dependencies.jar)" \
  ENTRY_CLASS=DefaultInitHandler \
  HELPER_NODE_CLASS=DefaultNodeOverride \
  godot src/dummy_godot_project/project.godot
```

- `compile_flags.txt` is there so that clangd lsp can find Java headers, it's not used for actual compilation 
