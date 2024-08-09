# Godot Java

(WIP) Godot bindings for Java.

## Dev information

``` bash
make
javac src/godot_java/DefaultInitHandler.java
LD_LIBRARY_PATH=/usr/lib/jvm/java-1.11.0-openjdk-amd64/lib/server \
  CLASSPATH="$(readlink -f src/godot_java)" \
  ENTRY_CLASS=DefaultInitHandler \
  godot src/dummy_godot_project/project.godot
```

- `compile_flags.txt` is there so that clangd lsp can find Java headers, it's not used for actual compilation 
