import com.sun.jna.Function;
import com.sun.jna.Pointer;

interface InitHandler {
    // NOTE: Initially I wanted these constants to be inside an Enum class but that would make the
    // interop messy and I don't wan to deal with that.
    public static final int LEVEL_CORE = 0;
    public static final int LEVEL_SERVERS = 1;
    public static final int LEVEL_SCENE = 2;
    public static final int LEVEL_EDITOR = 3;

    public abstract int getMinInitlevel();
    public abstract void initialize(int currentInitLevel);
    public abstract void deinitialize(int currentInitLevel);
    public abstract boolean entryFunction(long pGetProcAddress, long pLibrary);
}

// NOTE: This is a Singleton because Java can't handle abstract static methods
public class DefaultInitHandler implements InitHandler {
    public static DefaultInitHandler INSTANCE;

    private static long pGetProcAddress = 0L;

    private static long pLibrary = 0L;

    private DefaultInitHandler() {}

    public synchronized static DefaultInitHandler getInstance() {
        if(INSTANCE == null) {
            INSTANCE = new DefaultInitHandler();
        }
        return INSTANCE;
    }

    public static long getPGetProcAddress() {
        return pGetProcAddress;
    }

    public static long getPLibrary() {
        return pLibrary;
    }

    public int getMinInitlevel() {
        return InitHandler.LEVEL_SCENE;
    }

    public void initialize(int currentInitLevel) {
        if (currentInitLevel == InitHandler.LEVEL_SCENE) {
            test_get_godot_version();
        }
    }

    public void deinitialize(int currentInitLevel) {
        System.out.println("stopping at");
        System.out.println(currentInitLevel);
    }

    public void test_get_godot_version() {
        if (pGetProcAddress == 0) return;

        Function p = Function.getFunction(new Pointer(pGetProcAddress));
        Function getGodotVersion
            = Function.getFunction(p.invokePointer(new Object[]{ "get_godot_version" }));

        GDExtensionGodotVersion data = new GDExtensionGodotVersion();

        getGodotVersion.invoke(Void.TYPE, new Object[]{ data });

        System.out.println("Successfully called get_godot_version, here's the result");
        System.out.println(data);
    }

    public boolean entryFunction(long pGetProcAddress, long pLibrary) {
        DefaultInitHandler.pGetProcAddress = pGetProcAddress;
        DefaultInitHandler.pLibrary = pLibrary;

        return true;
    }
}
