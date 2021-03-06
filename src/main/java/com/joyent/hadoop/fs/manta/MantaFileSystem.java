package com.joyent.hadoop.fs.manta;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.joyent.manta.client.MantaClient;
import com.joyent.manta.client.MantaObject;
import com.joyent.manta.client.MantaObjectInputStream;
import com.joyent.manta.client.MantaObjectOutputStream;
import com.joyent.manta.client.MantaObjectResponse;
import com.joyent.manta.client.MantaSeekableByteChannel;
import com.joyent.manta.client.jobs.MantaJobBuilder;
import com.joyent.manta.client.jobs.MantaJobPhase;
import com.joyent.manta.config.ChainedConfigContext;
import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.config.DefaultsConfigContext;
import com.joyent.manta.config.EnvVarConfigContext;
import com.joyent.manta.config.MapConfigContext;
import com.joyent.manta.exception.MantaClientHttpResponseException;
import com.joyent.manta.http.MantaHttpHeaders;
import com.joyent.manta.util.MantaUtils;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.input.BoundedInputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FSInputStream;
import org.apache.hadoop.fs.FileAlreadyExistsException;
import org.apache.hadoop.fs.FileChecksum;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.util.Progressable;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.joyent.manta.client.MantaClient.SEPARATOR;

/**
 * This class is a <a href="https://github.com/joyent/manta">Manta object store</a>
 * remote file system implementation for Hadoop. It allows you to stream files
 * directly from a Manta object store.
 */
@InterfaceAudience.Public
public class MantaFileSystem extends FileSystem implements AutoCloseable {
    /**
     * File size threshold in which to run checksums using Manta job rather
     * than downloading and then running.
     */
    public static final long DEFAULT_THRESHOLD_FOR_REMOTE_CHECKSUM_CALC =
            1_048_576L;

    /**
     * Default replication factor.
     */
    public static final short DEFAULT_DURABILITY_LEVEL = 2;

    /**
     * Alias for the home directory in Manta.
     */
    public static final String HOME_ALIAS = "~~";

    /**
     * The schema used to identify Manta as a filesystem.
     */
    public static final String SCHEME = "manta";

    /**
     * Logger instance.
     */
    private static final Logger LOG =
            LoggerFactory.getLogger(MantaFileSystem.class);

    /**
     * The root Manta URI used to identify the filesystem.
     */
    private static final URI ROOT_MANTA_URI = URI.create(SCHEME + ":///");

    /**
     * Path to the current working directory.
     */
    private volatile Path workingDir;

    /**
     * Manta SDK configuration object.
     */
    private ConfigContext config;

    /**
     * Manta client.
     */
    private MantaClient client;

    static {
        /* Log class load in order to provide debugging information to
         * users that are attempting to embed the library.
         */
        LOG.debug("Manta filesystem class loaded");
    }

    /**
     * Create a new instance.
     */
    public MantaFileSystem() {
        super();
    }

    /**
     * Method used for initializing the class directly with the Manta SDK
     * configuration. This is used for testing.
     *
     * @param name a uri whose authority section names the host, port, etc.
     *             for this FileSystem. [Not used for Manta implementation]
     * @param customConfig custom configuration
     * @throws IOException thrown when we can't create a Manta Client
     */
    @VisibleForTesting
    void initialize(final URI name, final ConfigContext customConfig) throws IOException {
        this.config = customConfig;
        this.client = new MantaClient(customConfig);
    }

    /**
     * Initialization method called after a new FileSystem instance is constructed.
     *
     * @param name a uri whose authority section names the host, port, etc.
     *             for this FileSystem. [Not used for Manta implementation]
     * @param conf Hadoop configuration object
     * @throws IOException thrown when we can't create a Manta Client
     */
    @Override
    public void initialize(final URI name, final Configuration conf) throws IOException {
        super.initialize(name, conf);

        ChainedConfigContext chained = new ChainedConfigContext(
                new EnvVarConfigContext(),
                new MapConfigContext(System.getProperties()),
                new HadoopConfigurationContext(conf),
                new DefaultsConfigContext()
        );

        dumpConfig(chained);

        this.config = chained;
        this.client = new MantaClient(this.config);

        this.workingDir = getInitialWorkingDirectory();
    }

    /**
     * Dumps the configuration that is used to load a {@link MantaClient} if
     * the Java system property manta.dumpConfig is set.
     *
     * @param context Configuration context object to dump
     */
    private static void dumpConfig(final ConfigContext context) {
        if (context == null) {
            System.out.println("========================================");
            System.out.println("Configuration Context was null");
            System.out.println("========================================");
            return;
        }

        String dumpConfigVal = System.getProperty("manta.dumpConfig");
        if (dumpConfigVal != null && MantaUtils.parseBooleanOrNull(dumpConfigVal)) {
            System.out.println("========================================");
            System.out.println(ConfigContext.toString(context));
            System.out.println("========================================");
        }
    }

    /**
     * Return the protocol scheme for the FileSystem.
     *
     * @return "manta"
     */
    public String getScheme() {
        return "manta";
    }

    @Override
    public URI getUri() {
        return ROOT_MANTA_URI;
    }

    @Override
    public FSDataInputStream open(final Path path, final int bufferSize) throws IOException {
        LOG.debug("Opening '{}' for reading.", path);

        final FileStatus fileStatus = getFileStatus(path);

        if (fileStatus.isDirectory()) {
            final String msg = String.format("Can't open %s because it is a directory", path);
            throw new FileNotFoundException(msg);
        }

        String mantaPath = mantaPath(path);

        MantaSeekableByteChannel channel = client.getSeekableByteChannel(mantaPath);
        FSInputStream fsInput = new MantaSeekableInputStream(channel);

        return new FSDataInputStream(fsInput);
    }

    /**
     * {@inheritDoc}
     *
     * <p>We don't actually create a new file when this is invoked because
     * Manta doesn't support {@link #append(Path)} and all file writes are
     * new or overwrites.</p>
     *
     * @param path path to use for create
     * @return true if there is no file at the path, false if file already exists
     * @throws IOException thrown if there is an error checking for the files existance
     */
    @Override
    public boolean createNewFile(final Path path) throws IOException {
        String mantaPath = mantaPath(path);

        return !client.existsAndIsAccessible(mantaPath);
    }

    @Override
    public FSDataOutputStream create(final Path path, final FsPermission fsPermission,
                                     final boolean overwrite,
                                     final int bufferSize,
                                     final short replication,
                                     final long blockSize,
                                     final Progressable progressable) throws IOException {
        String mantaPath = mantaPath(path);

        if (!overwrite && client.existsAndIsAccessible(mantaPath)) {
            String msg = String.format("File already exists at path: %s", path);
            throw new FileAlreadyExistsException(msg);
        }

        MantaHttpHeaders headers = new MantaHttpHeaders();

        if (replication > 0) {
            headers.setDurabilityLevel(replication);
        }

        LOG.debug("Creating new file with {} replicas at path: {}", replication, path);

        String dir = FilenameUtils.getFullPath(mantaPath);

        if (!client.existsAndIsAccessible(dir)) {
            LOG.debug("Directory path to file didn't exist. Creating path: {}", dir);
            client.putDirectory(dir, true);
        }

        MantaObjectOutputStream out = client.putAsOutputStream(mantaPath, headers);

        if (progressable != null) {
            ProgressingOutputStream pout = new ProgressingOutputStream(progressable, out);
            return new FSDataOutputStream(pout, statistics);
        } else {
            return new FSDataOutputStream(out, statistics);
        }
    }

    @Override
    public FSDataOutputStream append(final Path path, final int i,
                                     final Progressable progressable) throws IOException {
        throw new IOException("Not supported");
    }

    @Override
    public boolean rename(final Path original, final Path newName) throws IOException {
        // We alias the semantics for moving a object and expose it as rename
        return move(original, newName);
    }

    @Override
    public boolean delete(final Path path, final boolean recursive) throws IOException {
        String mantaPath = mantaPath(path);

        // We don't bother deleting something that doesn't exist

        final MantaObjectResponse head;

        try {
             head = client.head(mantaPath);
        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return false;
            }

            throw e;
        }

        if (recursive && head.isDirectory()) {
            LOG.debug("Recursively deleting path: {}", mantaPath);
            client.deleteRecursive(mantaPath);
        } else {
            LOG.debug("Deleting path: {}", mantaPath);
            client.delete(mantaPath);
        }

        return !client.existsAndIsAccessible(mantaPath);
    }

    /**
     * Copies a file from the local system to the Manta object store.
     *
     * @param delSrc    whether to delete the source
     * @param overwrite whether to overwrite an existing file
     * @param src       file source path
     * @param dst       file destination
     */
    @Override
    public void copyFromLocalFile(final boolean delSrc, final boolean overwrite,
                                  final Path src, final Path dst) throws IOException {
        String mantaPath = mantaPath(dst);

        LOG.debug("Copying local file [{}] to [{}]", src, dst);

        if (!overwrite) {
            try {
                MantaObject head = client.head(mantaPath);
                if (!head.isDirectory()) {
                    throw new IOException("Can't copy file because destination "
                            + "already exists: " + dst);
                }
            } catch (MantaClientHttpResponseException e) {
                // 404 means we are good to go and not overwriting,
                // so we throw any error that is not a 404
                if (e.getStatusCode() != HttpStatus.SC_NOT_FOUND) {
                    throw e;
                }

                // Make any missing parent paths
                Path parent = dst.getParent();
                LOG.debug("Creating parent directory: {}", parent);
                client.putDirectory(mantaPath(parent), true);
            }
        }

        LocalFileSystem localFs = getLocal(getConf());
        File localFile = localFs.pathToFile(src);

        /* We used the default copy implementation if it is a wildcard copy
         * because we don't support wildcard copy in the Manta SDK yet. */
        if (localFile.isDirectory()) {
            super.copyFromLocalFile(delSrc, overwrite, src, dst);
            return;
        }

        client.put(mantaPath, localFile);

        if (delSrc) {
            Files.delete(localFile.toPath());
        }
    }

    /**
     * The src file is under FS, and the dst is on the local disk. Copy it from FS
     * control to the local dst name. delSrc indicates if the src will be removed
     * or not. useRawLocalFileSystem indicates whether to use RawLocalFileSystem
     * as local file system or not. RawLocalFileSystem is non crc file system.So,
     * It will not create any crc files at local.
     *
     * @param delSrc                whether to delete the src
     * @param src                   path
     * @param dst                   path
     * @param useRawLocalFileSystem whether to use RawLocalFileSystem as local file system or not.
     * @throws IOException - if any IO error
     */
    @Override
    public void copyToLocalFile(final boolean delSrc, final Path src,
                                final Path dst, final boolean useRawLocalFileSystem) throws IOException {
        /* If we can't get a reference to a File object, then we are better off
         * relying on the default implementation of this method.
         */
        if (useRawLocalFileSystem) {
            super.copyToLocalFile(delSrc, src, dst, useRawLocalFileSystem);
            return;
        }

        Configuration conf = getConf();
        LocalFileSystem local = getLocal(conf);
        File localFile = local.pathToFile(dst);
        String mantaPath = mantaPath(src);

        try {
            MantaObject head = client.head(mantaPath);

            /* We don't support wildcard copy yet, so we rely on the default
             * implementation of this method. */
            if (head.isDirectory()) {
                super.copyToLocalFile(delSrc, src, dst, useRawLocalFileSystem);
                return;
            }

        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                throw new FileNotFoundException(mantaPath);
            }

            throw e;
        }

        try (MantaObjectInputStream in = client.getAsInputStream(mantaPath)) {
            Files.copy(in, localFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }

        if (delSrc) {
            client.delete(mantaPath);
        }
    }

    @Override
    public FileStatus[] listStatus(final Path path) throws IOException {
        LOG.debug("List status for path: {}", path);
        String mantaPath = mantaPath(path);

        /* We emulate a normal filesystem by showing the home directory under root in
         * in order to provide compatibility with consumers that expect this behavior. */
        if (mantaPath.equals(SEPARATOR)) {
            return new FileStatus[] {new MantaFileStatus(true, getHomeDirectory())};
        }

        if (!client.existsAndIsAccessible(mantaPath)) {
            throw new FileNotFoundException(mantaPath);
        }

        return client.listObjects(mantaPath)
                .map((Function<MantaObject, FileStatus>) MantaFileStatus::new)
                .toArray(FileStatus[]::new);
    }

    @Override
    protected RemoteIterator<LocatedFileStatus> listLocatedStatus(
            final Path path, final PathFilter filter) throws IOException {
        LOG.debug("List located status for path: {}", path);

        String mantaPath = mantaPath(path);

        if (!client.existsAndIsAccessible(mantaPath)) {
            throw new FileNotFoundException(mantaPath);
        }

        /* We emulate a normal filesystem by showing the home directory under root in
         * in order to provide compatibility with consumers that expect this behavior. */
        if (mantaPath.equals(SEPARATOR)) {
            LocatedFileStatus singleEntry = new LocatedFileStatus(new MantaFileStatus(true, path), null);
            return new SingleEntryRemoteIterator<>(singleEntry);
        }

        if (!client.existsAndIsAccessible(mantaPath)) {
            throw new FileNotFoundException(mantaPath);
        }

        Stream<MantaObject> stream = client.listObjects(mantaPath);
        return new MantaRemoteIterator(filter, stream, path, this, true);
    }

    @Override
    public RemoteIterator<LocatedFileStatus> listFiles(final Path path, final boolean recursive)
            throws IOException {
        LOG.debug("List files for path: {}", path);

        String mantaPath = mantaPath(path);

        if (!client.existsAndIsAccessible(mantaPath)) {
            throw new FileNotFoundException(mantaPath);
        }

        final Stream stream;

        if (recursive) {
            stream = client.find(mantaPath).filter(obj -> !obj.isDirectory());
        } else {
            stream = client.listObjects(mantaPath).filter(obj -> !obj.isDirectory());
        }

        return new MantaRemoteIterator(null, stream, new Path(mantaPath), this, true);
    }

    @Override
    public void setWorkingDirectory(final Path path) {
        this.workingDir = path;
    }

    @Override
    public Path getWorkingDirectory() {
        return this.workingDir;
    }

    @Override
    public Path getHomeDirectory() {
        return new Path(config.getMantaHomeDirectory());
    }

    @Override
    protected Path getInitialWorkingDirectory() {
        return new Path(config.getMantaHomeDirectory());
    }

    @Override
    public boolean mkdirs(final Path path, final FsPermission fsPermission) throws IOException {
        String mantaPath = mantaPath(path);

        client.putDirectory(mantaPath, true);
        return client.existsAndIsAccessible(mantaPath);
    }

    @Override
    public FileStatus getFileStatus(final Path path) throws IOException {
        String mantaPath = mantaPath(path);
        LOG.debug("Getting path status for: {}", mantaPath);

        if (mantaPath.equals(SEPARATOR)) {
            return MantaFileStatus.ROOT;
        }

        final MantaObjectResponse response;

        try {
            response = client.head(mantaPath);
        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                throw new FileNotFoundException(mantaPath);
            }

            throw e;
        }

        MantaFileStatus status = new MantaFileStatus(response, path);

        return status;
    }

    @Override
    public boolean exists(final Path path) throws IOException {
        return client.existsAndIsAccessible(mantaPath(path));
    }

    @Override
    public boolean isDirectory(final Path path) throws IOException {
        try {
            return client.head(mantaPath(path)).isDirectory();
        } catch (MantaClientHttpResponseException e) {
            /* We imitate the behavior of FileSystem.isDirectory, by changing a
             * FileNotFoundException into a false return value. */
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                return false;
            }

            throw e;
        }
    }

    @Override
    public boolean isFile(final Path path) throws IOException {
        return !isDirectory(path);
    }

    @Override
    public boolean truncate(final Path path, final long newLength) throws IOException {
        final String mantaPath = mantaPath(path);

        final String contentType;

        try {
            MantaObject head = client.head(mantaPath);
            contentType = head.getContentType();
        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                throw new FileNotFoundException(mantaPath);
            }

            throw e;
        }

        if (newLength == 0) {
            MantaHttpHeaders headers = new MantaHttpHeaders()
                    .setContentType(contentType);

            client.put(mantaPath, "", headers, null);
            return true;
        }

        throw new UnsupportedOperationException("Truncating to an arbitrary length higher "
                + "than zero is not supported at this time");
    }

    /**
     * Get the checksum of a file.
     *
     * @param file The file path
     * @return The file checksum.  The default return value is null,
     * which indicates that no checksum algorithm is implemented
     * in the corresponding FileSystem.
     */
    @Override
    public FileChecksum getFileChecksum(final Path file) throws IOException {
        final String mantaPath = mantaPath(file);

        try {
            final MantaObject head = client.head(mantaPath);

            if (head.isDirectory()) {
                throw new IOException("Can't get checksum of directory");
            }

            byte[] md5bytes = head.getMd5Bytes();
            return new MantaChecksum(md5bytes);
        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                throw new FileNotFoundException(mantaPath);
            }
            throw e;
        }
    }

    /**
     * Get the checksum of a file, from the beginning of the file till the
     * specific length. Warning this operation is slow because we either have to
     * download the entire file or run a remote job in order to calculate the checksum.
     *
     * @param file The file path
     * @param length The length of the file range for checksum calculation*
     * @return the checksum of an arbitrary amount of bytes from the start of a file
     * @throws IOException thrown when unable to compute the checksum
     */
    public FileChecksum getFileChecksum(final Path file, final long length) throws IOException {
        Preconditions.checkArgument(length >= 0,
                "File range length must be greater than or equal to zero");

        final String mantaPath = mantaPath(file);

        try {
            final MantaObject head = client.head(mantaPath);

            if (head.isDirectory()) {
                throw new IOException("Can't get checksum of directory");
            }

            if (head.getContentLength() > DEFAULT_THRESHOLD_FOR_REMOTE_CHECKSUM_CALC) {
                return getFileChecksumRemotely(mantaPath, length);
            } else {
                return getFileChecksumLocally(mantaPath, length);
            }
        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                throw new FileNotFoundException(mantaPath);
            }

            throw e;
        }
    }

    /**
     * Get the checksum of a file, from the beginning of the file till the
     * specific length. Warning this operation is slow because we have to
     * download the entire file in order to calculate the checksum.
     *
     * @param mantaPath The file path
     * @param length The length of the file range for checksum calculation*
     * @return the checksum of an arbitrary amount of bytes from the start of a file
     * @throws IOException thrown when unable to compute the checksum
     */
    FileChecksum getFileChecksumLocally(final String mantaPath, final long length) throws IOException {
        LOG.debug("Calculating checksum for file {} locally by downloading all content",
                mantaPath);

        try (InputStream in = client.getAsInputStream(mantaPath);
             BoundedInputStream bin = new BoundedInputStream(in, length)) {
            byte[] bytes = DigestUtils.md5(bin);
            return new MantaChecksum(bytes);
        }
    }

    /**
     * Get the checksum of a file, from the beginning of the file till the
     * specific length. Warning this operation is slow because we have to
     * execute a remote job in order to perform it.
     *
     * @param mantaPath The file path
     * @param length The length of the file range for checksum calculation*
     * @return the checksum of an arbitrary amount of bytes from the start of a file
     * @throws IOException thrown when unable to compute the checksum
     */
    FileChecksum getFileChecksumRemotely(final String mantaPath, final long length) throws IOException {
        LOG.debug("Calculating checksum for file {} remotely using Manta job",
                mantaPath);

        MantaJobBuilder builder = client.jobBuilder();
        String jobName = String.format("hadoop-range-checksum-%s",
                UUID.randomUUID());

        MantaJobBuilder.Run runningJob = builder.newJob(jobName)
                .addInput(mantaPath)
                .addPhase(new MantaJobPhase()
                .setType("reduce")
                .setExec(String.format("head -c %d | md5sum -b | cut -d' ' -f1", length)))
                .validateInputs()
                .run();

        MantaJobBuilder.Done finishedJob = runningJob.waitUntilDone()
                .validateJobsSucceeded();

        String hexString = null;

        try (Stream<String> outputs = finishedJob.outputs()) {
            Optional<String> first = outputs.findFirst();

            if (!first.isPresent()) {
                String msg = String.format("No md5 outputted from calculation job [%s]",
                        runningJob.getJob().getId());
                throw new IOException(msg);
            }

            hexString = first.get().trim();
            return new MantaChecksum(hexString);
        } catch (DecoderException e) {
            String msg = String.format("Unable to decode hex string as md5: %s",
                    hexString);
            throw new IOException(msg, e);
        }
    }


    /**
     * Return the total size of all files in the filesystem.
     */
    @Override
    public long getUsed() throws IOException {
        String usageFilePath = String.format("%s/reports/usage/storage/latest",
                config.getMantaHomeDirectory());

        String json = null;

        try {
             json = client.getAsString(usageFilePath);
        } catch (MantaClientHttpResponseException e) {
            if (e.getStatusCode() == HttpStatus.SC_NOT_FOUND) {
                String msg = "Usage report file not found. Typically it will take"
                        + "one day to generate for a new account";
                throw new FileNotFoundException(msg);
            }
        }

        Preconditions.checkNotNull(json, "Response from usage inquiry shouldn't be null");

        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> storage =
                (Map<String, Map<String, String>>)new Gson()
                .fromJson(json, Map.class).get("storage");

        return storage.values().stream()
                .mapToLong(value -> Long.parseLong(value.getOrDefault("bytes", "0")))
                .sum();
    }

    /**
     * Get the default replication.
     *
     * @deprecated use {@link #getDefaultReplication(Path)} instead
     */
    @Override
    public short getDefaultReplication() {
        return DEFAULT_DURABILITY_LEVEL;
    }

    /**
     * In Manta all paths have the same default replication factor. This will
     * always return the same value.
     *
     * @param path of the file
     * @return default replication for the path's filesystem
     */
    @Override
    public short getDefaultReplication(final Path path) {
        return DEFAULT_DURABILITY_LEVEL;
    }

    @Override
    public void close() throws IOException {
        try {
            super.close();
        } finally {
            client.closeQuietly();
        }
    }

    /**
     * Moves an object from one path to another.
     * @param original path to move from
     * @param newName path to move to
     * @return true if moved successfully
     * @throws IOException thrown when we can't move paths
     */
    public boolean move(final Path original, final Path newName) throws IOException {
        String source = mantaPath(original);
        String destination = mantaPath(newName);

        if (!client.existsAndIsAccessible(source)) {
            throw new FileNotFoundException(source);
        }

        LOG.debug("Moving [{}] to [{}]", original, newName);

        client.move(source, destination, true);

        return client.existsAndIsAccessible(destination);
    }

    @Override
    protected URI getCanonicalUri() {
        return super.getCanonicalUri();
    }

    @Override
    protected URI canonicalizeUri(final URI uri) {
        if (uri.getScheme() != null && !uri.getScheme().equalsIgnoreCase(SCHEME)) {
            return super.canonicalizeUri(uri);
        }

        final String path = substituteHomeDirectory(uri.getPath());

        try {
            return new URI(SCHEME, null, path, null);
        } catch (URISyntaxException e) {
            throw new AssertionError("Valid URI became unparseable: "
                    + uri);
        }
    }

    /**
     * Substitutes the Manta home directory alias with the actual home directory path.
     *
     * @param rawPath path to process
     * @return path with substitution applied
     */
    private String substituteHomeDirectory(final String rawPath) {
        Objects.requireNonNull(rawPath, "Manta path is null");

        final String withoutPrefix = StringUtils.removeStart(rawPath, SEPARATOR);

        if (withoutPrefix.startsWith(HOME_ALIAS)) {
            return config.getMantaHomeDirectory() + rawPath.substring(HOME_ALIAS.length() + 1);
        }

        return rawPath;
    }

    /**
     * Converts a Hadoop {@link Path} object to a path String that the Manta
     * client understands.
     *
     * @param path Hadoop path object to convert
     * @return String representation of path on Manta
     */
    private String mantaPath(final Path path) {
        final String mantaPath;

        if (path.toUri() == null) {
            mantaPath = path.toString();
        } else {
            URI uri = canonicalizeUri(path.toUri());
            mantaPath = uri.getPath();
        }

        final String normalized = FilenameUtils.normalize(mantaPath, true);

        if (normalized == null) {
            String msg = String.format("Hadoop path was converted to a null value. "
                    + "Original value: %s", path);
            throw new NullPointerException(msg);
        }

        if (normalized.isEmpty()) {
            String msg = String.format("Hadoop path was converted to an empty value. "
                    + "Original value: %s", path);
            throw new IllegalArgumentException(msg);
        }

        return normalized;
    }

    /**
     * Package private visibility method for getting the Manta SDK client
     * used for testing.
     *
     * @return reference to the backing Manta client
     */
    @VisibleForTesting
    MantaClient getMantaClient() {
        return this.client;
    }

    /**
     * Package private visibility method for getting the Manta SDK configuration
     * used for test.
     *
     * @return reference to the backing Manta SDK configuration
     */
    @VisibleForTesting
    ConfigContext getConfig() {
        return this.config;
    }
}
