public abstract class HelperNodeOverride {
    private long nativeInstancePointer;

    HelperNodeOverride(long nativeInstancePointer) {
        this.nativeInstancePointer = nativeInstancePointer;
    }

    // TODO: Give proper types to parameters that are Object

    public void _enter_tree() {};
    public void _exit_tree() {};
    public void _get_configuration_warnings() {};
    public void _input(Object event) {};
    public void _physics_process(double delta) {};
    public void _process(double delta) {};
    public void _shortcut_input(Object event) {};
    public void _unhandled_input(Object event) {};
    public void _unhandled_key_input(Object event) {};
    public void _ready() {};
}
