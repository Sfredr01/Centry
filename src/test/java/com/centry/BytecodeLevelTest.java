package com.centry;

import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

// The runtime image is a Java 21 JRE; newer bytecode fails there with UnsupportedClassVersionError.
class BytecodeLevelTest {

    private static final int JAVA_21_CLASS_MAJOR_VERSION = 65;

    @Test
    void productionClassesTargetJava21() throws Exception {
        try (InputStream in = CentryApplication.class.getResourceAsStream("CentryApplication.class");
             DataInputStream data = new DataInputStream(in)) {
            data.readInt();            // magic
            data.readUnsignedShort();  // minor version
            assertThat(data.readUnsignedShort()).isEqualTo(JAVA_21_CLASS_MAJOR_VERSION);
        }
    }
}
