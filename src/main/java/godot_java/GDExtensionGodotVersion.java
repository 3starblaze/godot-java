import com.sun.jna.Structure;

@Structure.FieldOrder({"major", "minor", "patch", "string"})
public class GDExtensionGodotVersion extends Structure {
    public int major;
    public int minor;
    public int patch;
    public String string;
}
