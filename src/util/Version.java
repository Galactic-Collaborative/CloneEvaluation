package util;

import picocli.CommandLine;

public class Version implements CommandLine.IVersionProvider {
    public static final String version = "Dekker";

    @Override
    public String[] getVersion() {
        return new String[] {"1.1.0." + version};
    }
}
