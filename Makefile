# TODO don't hardcode this
JAVA_HOME ?= /usr/lib/jvm/java-1.11.0-openjdk-amd64

# TODO I recommend switching to bb.edn rather than make a more complex Makefile.
OS := $(shell uname -s | tr '[:upper:]' '[:lower:]')
INCLUDES = -I${JAVA_HOME}/include -I${JAVA_HOME}/include/${OS}
LIBS = -ljvm -L ${JAVA_HOME}/lib -L ${JAVA_HOME}/lib/server
LINUX_OPTS ?= -Wl,--no-as-needed


# entry.so maker might have more flags than needed but I did not want to risk breaking
# anything so it stays the way it is
all:
	gcc -g -Wall ${LINUX_OPTS} -fPIC $(INCLUDES) -c src/entry.c -o entry.o -rdynamic $(LIBS)
	gcc -g -fPIC ${LINUX_OPTS} $(INCLUDES) $(LIBS) -shared -o entry.so entry.o -rdynamic
	rm entry.o
	mv entry.so src/dummy_godot_project/
