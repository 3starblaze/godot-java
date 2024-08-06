# Godot Java

(WIP) Godot bindings for Java.

## Dev information

``` bash
make
javac src/godot_java/DefaultInitHandler.java
LD_LIBRARY_PATH=/usr/lib/jvm/java-1.11.0-openjdk-amd64/lib/server CLASSPATH=<FULL_PATH_TO_CLASS_FILES_DIR> ENTRY_CLASS="DefaultInitHandler" godot --export-debug Linux/X11 --path src/dummy_godot_project --headless
```

