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

    private DefaultInitHandler() {}

    public synchronized static DefaultInitHandler getInstance() {
        if(INSTANCE == null) {
            INSTANCE = new DefaultInitHandler();
        }
        return INSTANCE;
    }

    public int getMinInitlevel() {
        return InitHandler.LEVEL_SCENE;
    }

    public void initialize(int currentInitLevel) {
        System.out.println("starting at");
        System.out.println(currentInitLevel);
    }

    public void deinitialize(int currentInitLevel) {
        System.out.println("stopping at");
        System.out.println(currentInitLevel);
    }

    public boolean entryFunction(long pGetProcAddress, long pLibrary) {
        System.out.println("got addresses");
        System.out.println(pGetProcAddress);
        System.out.println(pLibrary);
        return true;
    }
}