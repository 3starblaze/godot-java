public class DefaultNodeOverride extends HelperNodeOverride {
    DefaultNodeOverride(long nativeInstancePointer) {
        super(nativeInstancePointer);
    }

    @Override
    public void _enter_tree() {
        System.out.println("Hello tree!");
    }

    @Override
    public void _exit_tree() {
        System.out.println("Goodbye tree!");
    }

    @Override
    public void _ready() {
        System.out.println("I am ready!");
    }

    @Override
    public void _input(Object event) {
        System.out.println("Received an input!");
    }

    @Override
    public void _process(double delta) {
        System.out.printf("delta is %f\n", delta);
    }
}
