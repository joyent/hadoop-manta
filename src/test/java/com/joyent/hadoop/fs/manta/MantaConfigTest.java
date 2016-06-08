package com.joyent.hadoop.fs.manta;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;

import static org.junit.Assert.assertTrue;

public class MantaConfigTest {
    @Test
    public void hadoopCanLoadFilesystemFromServiceLoader() throws IOException {
        final Configuration config = new Configuration();

        config.set("manta.user", "testuser");
        config.set("manta.key_id", "00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00");
        config.set("manta.key_path", FileUtils.getUserDirectory().toString()
                + File.separator + ".ssh/id_rsa");

        URI uri = URI.create("manta:///");
        FileSystem fs = FileSystem.get(uri, config);

        assertTrue(String.format("FileSystem is not an instance of %s. Actually: %s",
                MantaFileSystem.class, fs.getClass()), fs instanceof MantaFileSystem);
    }
}
