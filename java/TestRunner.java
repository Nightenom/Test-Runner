import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import static java.util.function.Predicate.not;

public class TestRunner
{
    private static final int DUMP_AROUND_SIZE = 30;
    private static final boolean DEBUG = false;

    // runtime
    private static final AtomicReference<Process> runningProcess = new AtomicReference<>();
    private static int mainTimeout = 10;

    public static void main(final String[] args) throws Exception
    {
        final Path testFolder;
        final String[] mainBase;

        {
            boolean errored = false;
            final String folderProperty = System.getProperty("tr.folder");
            final String mainProperty = System.getProperty("tr.main");
            final String fileExtensionProperty = System.getProperty("tr.file_exts");
            final String timeoutProperty = System.getProperty("tr.main_timeout");

            if (timeoutProperty != null)
            {
                try
                {
                    mainTimeout = Integer.parseInt(timeoutProperty);
                }
                catch (NumberFormatException e)
                {
                    System.err.println("unparsable timeout: " + e.getMessage());
                    errored = true;
                }
            }

            if (mainProperty == null)
            {
                System.err.println("missing -Dtr.main in format 'path_to_executable arguments'");
                errored = true;
                mainBase = null;
            }
            else
            {
                mainBase = mainProperty.split(" "); // arguments splitting? not so easy
            }

            if (fileExtensionProperty != null && FileExtension.changeExtensions(fileExtensionProperty.split(",")))
            {
                errored = true;
            }

            if (folderProperty == null)
            {
                System.err.println("missing -Dtr.folder pointing to folder with .in/out/err/... files");
                errored = true;
                testFolder = null; // irrelevant
            }
            else
            {
                testFolder = Path.of(folderProperty).toAbsolutePath().normalize();
                if (!Files.isDirectory(testFolder))
                {
                    System.err.println("non-directory path in -Dtr.folder: " + testFolder);
                    errored = true;
                }
            }

            if (errored)
            {
                throw new IllegalArgumentException("Failed to setup: see above for further informantion");
            }
        }

        final Map<String, TestInfo> testInfos = new HashMap<>();

        try (var it = Files.newDirectoryStream(testFolder))
        {
            for (final Path testPath : it)
            {
                final String fileNameWithExt = testPath.getFileName().toString();
                final int lastPeriod = fileNameWithExt.lastIndexOf('.');
                if (lastPeriod == -1)
                {
                    continue;
                }

                final var fileExtension = FileExtension.get(fileNameWithExt.substring(lastPeriod + 1));
                if (fileExtension != null)
                {
                    final String fileName = fileNameWithExt.substring(0, lastPeriod);
                    fileExtension.extensionProcessor.accept(testInfos.computeIfAbsent(fileName, TestInfo::ofName),
                        testPath.toAbsolutePath().normalize());
                }
            }
        }

        int correctTests = 0;
        long accumulatedTime = 0;

        try
        {
            new ProcessBuilder("echo").start().waitFor(); // warmup process builder
        }
        catch (final IOException e)
        {}

        Runtime.getRuntime()
            .addShutdownHook(new Thread(() -> Optional.ofNullable(runningProcess.getAndSet(null)).ifPresent(Process::destroy)));

        for (final TestInfo test : testInfos.values().stream().sorted(Comparator.comparing(t -> t.name)).toList())
        {
            System.out.println("===== TEST " + test.name + " =====");

            test.printDescription(System.out);

            if (test.prepare(testFolder))
            {
                System.out.println();
                continue;
            }

            // Generate input
            if (test.hasGenerate())
            {
                System.out.println("Generating input...");

                if (test.runProcess(test.prepareGenerateInput(testFolder).start()))
                {
                    System.out.println("Input generation timeout, skipping...");
                    System.out.println();
                    continue;
                }
            }

            // copy input files to rundir
            if (test.hasInputFiles())
            {
                final List<String> mainArgs = new ArrayList<>(Arrays.asList(mainBase));
                if (test.hasArguments())
                {
                    mainArgs.addAll(Files.readAllLines(test.args));
                }

                // do not copy inFiles into runDir if args contains inFiles target
                if (!mainArgs.stream().anyMatch(a -> a.contains("$$INPUT_FILES_")) || testFolder.equals(test.runDir))
                {
                    for (final Path in : test.inFiles)
                    {
                        if (Files.exists(in)) // path might be generated by input gen, thus it may stay in runDir
                        {
                            final Path fileName = in.getFileName();
                            System.out.println("\tCopying \"" + fileName + "\" to run directory");

                            Files.copy(in, test.runDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }

            // Generate output and error
            if (test.hasRefSolution())
            {
                System.out.println("Generating reference solution...");

                final Process processGen = test.prepareGenerateOutput(testFolder).start();
                if (!test.hasInput())
                {
                    processGen.getOutputStream().close();
                }
                if (test.runProcess(processGen))
                {
                    System.out.println("Reference solution generation timeout, skipping...");
                    System.out.println();
                    continue;
                }

                // move reference files from rundir to testdir
                if (test.hasOutputFiles())
                {
                    for (final Path out : test.outFiles)
                    {
                        final Path fileName = out.getFileName();
                        final Path reference = test.runDir.resolve(fileName).toAbsolutePath().normalize();

                        if (Files.exists(reference))
                        {
                            final Path target = testFolder.resolve(test.name + "." + fileName);
                            System.out.printf("\tMoving reference file \"%s\" from run directory to test folder as \"%s\"%n",
                                reference.getFileName(),
                                target.getFileName());

                            Files.move(reference, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }

                System.out.println();
            }

            // execute "main"
            boolean timeouted = false;
            final ProcessBuilder pb = test.prepareMain(testFolder, mainBase);
            final long start = System.nanoTime();
            final Process process = pb.start();
            if (!test.hasInput())
            {
                process.getOutputStream().close();
            }
            if (test.runProcess(process))
            {
                timeouted = true;
            }
            final long end = System.nanoTime();

            // blame human for being SgTrUePaItD

            boolean isCorrect = true;
            isCorrect &= checkExitCode(test, process.exitValue());
            isCorrect &= checkStream(test.output, process.getInputStream(), "out");
            isCorrect &= checkStream(test.error, process.getErrorStream(), "err");
            isCorrect &= checkOutputFiles(test, testFolder);
            correctTests += !timeouted && isCorrect ? 1 : 0;

            System.out.printf("%s\ttime: \t%.2fms%n%n%n",
                timeouted ? "TIMEOUT " : (isCorrect ? "OK      " : "ERROR   "),
                (end - start) / 1000000.0d);

            accumulatedTime += (end - start);
        }

        System.out.printf("CORRECT: %d/%d\n\t\ttime: \t%.2fms\n", correctTests, testInfos.size(), accumulatedTime / 1000000.0d);
        if (correctTests == testInfos.size())
        {
            System.out.printf(
                "%n=====>>>>>     YOU ARE     <<<<<=====%n=====>>>>>     AWESOME     <<<<<=====%n=====>>>>>    WELL DONE    <<<<<=====%n%n");
        }
    }

    private static boolean checkOutputFiles(final TestInfo test, final Path testFolder) throws IOException
    {
        if (!test.hasOutputFiles())
        {
            return true;
        }

        boolean isCorrect = true;

        for (final Path out : test.outFiles)
        {
            final Path fileName = out.getFileName();
            final Path user = test.runDir.resolve(fileName).toAbsolutePath().normalize();
            final Path reference = testFolder.resolve(test.name + "." + fileName);

            if (DEBUG)
            {
                System.err.println("DEBUG: comparing: " + user + " <> " + reference);
            }

            if (!Files.exists(user))
            {
                System.out.println("Missing user output file of name: \"" + fileName + "\" at: " + user.toString());
                System.out.println();

                isCorrect = false;
            }
            else if (!Files.exists(reference))
            {
                System.out.println("Missing reference output file of name: \"" + fileName + "\" at: " + reference.toString());
                System.out.println();

                isCorrect = false;
            }
            else
            {
                final long firstMismatchByte = Files.mismatch(user, reference);
                if (firstMismatchByte != -1)
                {
                    System.out.println("Your output file with name \"" + fileName +
                        "\" does not match reference, position of first wrong byte: " +
                        firstMismatchByte);
                    // TODO: do hexdump around mismatch position
                    System.out.println();

                    isCorrect = false;
                }
            }
        }

        return isCorrect;
    }

    private static boolean checkExitCode(final TestInfo test, final int processExitCode) throws IOException
    {
        if (test.hasExitCode())
        {
            final int exitCode = Integer.valueOf(Files.readAllLines(test.exitCode).get(0));

            if (exitCode != processExitCode)
            {
                System.out.printf("Result exit code: %d%nExpected exit code: %d%n%n", exitCode, processExitCode);
                return false;
            }
        }
        else if (processExitCode != 0)
        {
            System.out.println("Nonzero exit code: " + processExitCode);
            System.out.println();
            return false;
        }
        return true;
    }

    private static boolean checkStream(final Path solutionPath, final InputStream processStream, final String streamName)
        throws IOException
    {
        final byte[] processBuffer = processStream.readAllBytes();

        if (solutionPath != null)
        {
            final byte[] solutionBuffer = Files.readAllBytes(solutionPath);
            final int firstByteMismatch = Arrays.mismatch(processBuffer, solutionBuffer);
            if (firstByteMismatch != -1)
            {
                final String result = new String(processBuffer);
                System.out.printf("Result %s:%n%s%n",
                    streamName,
                    result.length() == 0 ? "<empty>" : escapeInvisibles(result.substring(0, Math.min(1000, result.length()))));
                System.out.printf("Expected %s:%n%s%n",
                    streamName,
                    escapeInvisibles(new String(solutionBuffer).substring(0, Math.min(1000, solutionBuffer.length))));

                if (result.length() == 0)
                {
                    System.out.println();
                    return false;
                }

                if (firstByteMismatch == 0 && result.length() == 1)
                {
                    System.out.printf("Mismatch in only character of result %s:%n| %s |%n%n",
                        streamName,
                        escapeInvisibles(result.substring(firstByteMismatch, firstByteMismatch + 1)));
                }
                else if (firstByteMismatch >= result.length())
                {
                    System.out.printf("Mismatch after end of result %s%n%n", streamName);
                }
                else
                {
                    System.out.printf("Mismatch at %d of result %s:%n%s %s <> %s <> %s %s%n%n",
                        firstByteMismatch,
                        streamName,
                        firstByteMismatch < 1 + DUMP_AROUND_SIZE ? "|" : "...",
                        escapeInvisibles(result.substring(Math.max(firstByteMismatch - DUMP_AROUND_SIZE, 0), firstByteMismatch)),
                        escapeInvisibles(result.substring(firstByteMismatch, firstByteMismatch + 1)),
                        escapeInvisibles(result.substring(firstByteMismatch + 1,
                            Math.min(firstByteMismatch + 1 + DUMP_AROUND_SIZE, result.length()))),
                        Math.min(firstByteMismatch + 1 + DUMP_AROUND_SIZE, result.length()) >= result.length() ? "|" : "...");
                }
                return false;
            }
        }
        else if (processBuffer.length > 0)
        {
            final String result = new String(processBuffer);
            System.out.printf("Result %s should be empty:%n%s%n%n", streamName, result.substring(0, Math.min(1000, result.length())));
            return false;
        }
        return true;
    }

    private static String escapeInvisibles(final String escape)
    {
        final int len = escape.length();
        final StringBuilder sb = new StringBuilder(Math.max(1005 * len / 1000, len));
        for (int i = 0; i < len; i++)
        {
            final char c = escape.charAt(i);
            switch (c)
            {
                case '\b':
                    sb.append('\\');
                    sb.append('b');
                    break;

                case '\f':
                    sb.append('\\');
                    sb.append('f');
                    break;

                case '\n':
                    sb.append('\\');
                    sb.append('n');
                    break;

                case '\r':
                    sb.append('\\');
                    sb.append('r');
                    break;

                case '\t':
                    sb.append('\\');
                    sb.append('t');
                    break;

                default:
                    if (c < ' ')
                    {
                        sb.append("\\0x");
                        sb.append(Integer.toHexString(c));
                    }
                    else
                    {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    private static class TestInfo
    {
        // config
        final String name;
        Path input;
        Path output;
        Path error;
        Path args;
        Path exitCode;
        Path generate;
        Path refsolution;
        Path timeout;
        Path runDir;
        Path inputFilesPath;
        Path outputFilesPath;
        Path environmentMap;
        Path description;

        // runtime
        int timeoutSeconds;
        Map<String, String> environment = new HashMap<>();
        List<Path> inFiles;
        List<Path> outFiles;
        List<String> inFilesStr;
        List<String> outFilesStr;

        public TestInfo(final String name)
        {
            this.name = name;
        }

        private static TestInfo ofName(final String name)
        {
            Objects.requireNonNull(name);
            return new TestInfo(name);
        }

        private void attachInput(final Path input)
        {
            this.input = input;
        }

        private void attachOutput(final Path output)
        {
            this.output = output;
        }

        private void attachError(final Path error)
        {
            this.error = error;
        }

        private void attachArguments(final Path args)
        {
            this.args = args;
        }

        private void attachExitCode(final Path exitCode)
        {
            this.exitCode = exitCode;
        }

        private void attachGenerate(final Path generate)
        {
            this.generate = generate;
        }

        private void attachRefSolution(final Path refsolution)
        {
            this.refsolution = refsolution;
        }

        private void attachTimeout(final Path timeout)
        {
            this.timeout = timeout;
        }

        private void attachRunDir(final Path runDir)
        {
            this.runDir = runDir;
        }

        private void attachInputFiles(final Path inputFiles)
        {
            this.inputFilesPath = inputFiles;
        }

        private void attachOutputFiles(final Path outputFiles)
        {
            this.outputFilesPath = outputFiles;
        }

        private void attachEnvironmentMap(final Path environmentMap)
        {
            this.environmentMap = environmentMap;
        }

        private void attachDescription(final Path description)
        {
            this.description = description;
        }

        private void printDescription(final PrintStream out) throws Exception
        {
            if (description != null)
            {
                Files.readAllLines(description).forEach(out::println);
            }
            System.out.println();
        }

        private boolean prepare(final Path testFolder) throws Exception
        {
            timeoutSeconds = timeout != null ? Integer.valueOf(Files.readString(timeout)) : mainTimeout;

            boolean requestGap = false;
            if (runDir != null)
            {
                // transform run directory
                runDir = Path.of(Files.readAllLines(runDir).get(0)).toAbsolutePath().normalize();

                requestGap = true;
                System.out.println("Running in directory: " + runDir.toString());
            }
            else
            {
                // set to current dir
                runDir = Paths.get(".").toAbsolutePath().normalize();
                if (DEBUG)
                {
                    System.out.println("DEBUG: Running in directory: " + runDir.toString());
                }
            }

            if (inputFilesPath != null)
            {
                inFiles = Files.readAllLines(inputFilesPath)
                    .stream()
                    .filter(not(String::isBlank))
                    .map(f -> testFolder.resolve(f))
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .toList();
                inFilesStr = inFiles.stream().map(Object::toString).toList();
            }
            else
            {
                inFiles = List.of();
                inFilesStr = List.of();
            }
            if (outputFilesPath != null)
            {
                outFiles = Files.readAllLines(outputFilesPath)
                    .stream()
                    .filter(not(String::isBlank))
                    .filter(s -> !s.startsWith("//"))
                    .map(f -> runDir.resolve(f))
                    .map(Path::toAbsolutePath)
                    .map(Path::normalize)
                    .toList();
                outFilesStr = outFiles.stream().map(Object::toString).toList();
            }
            else
            {
                outFiles = List.of();
                outFilesStr = List.of();
            }

            if (environmentMap != null)
            {
                final List<String> envMap = Files.readAllLines(environmentMap);

                requestGap = true;
                System.out.println("Running with additional environment variables: ");
                for (int i = 0; i < envMap.size(); i += 2)
                {
                    final String key = envMap.get(i);
                    final String value = expandVariables(envMap.get(i + 1), testFolder);

                    if (key.isBlank())
                    {
                        System.out.println("\t Empty key on line: " + i + ", skipping...");
                        return true;
                    }

                    environment.put(key, value);
                    System.out.println("\t" + key + " = " + value);
                }
            }

            if (requestGap)
            {
                System.out.println();
            }

            return false;
        }

        public ProcessBuilder prepareGenerateInput(final Path testFolder) throws Exception
        {
            final Path genIn = testFolder.resolve(name + "." + FileExtension.STDIN);

            final ProcessBuilder pb = new ProcessBuilder(expandVariables(Files.readAllLines(generate), testFolder));

            pb.redirectOutput(genIn.toFile());
            pb.directory(runDir.toFile());
            pb.environment().putAll(environment);

            input = genIn;

            return pb;
        }

        public ProcessBuilder prepareGenerateOutput(final Path testFolder) throws Exception
        {
            final Path genOut = testFolder.resolve(name + "." + FileExtension.STDOUT);
            final Path genErr = testFolder.resolve(name + "." + FileExtension.STDERR);

            final ProcessBuilder pb = new ProcessBuilder(expandVariables(Files.readAllLines(refsolution), testFolder));
            if (input != null)
            {
                pb.redirectInput(input.toFile());
            }
            pb.redirectOutput(genOut.toFile());
            pb.redirectError(genErr.toFile());
            pb.directory(runDir.toFile());
            pb.environment().putAll(environment);

            output = genOut;
            error = genErr;

            return pb;
        }

        public ProcessBuilder prepareMain(final Path testFolder, final String[] mainBase) throws Exception
        {
            final List<String> mainArgs = new ArrayList<>(Arrays.asList(mainBase));
            if (hasArguments())
            {
                mainArgs.addAll(Files.readAllLines(args));
            }

            final ProcessBuilder pb = new ProcessBuilder(expandVariables(mainArgs, testFolder));
            if (input != null)
            {
                pb.redirectInput(input.toFile());
            }
            pb.directory(runDir.toFile());
            pb.environment().putAll(environment);

            return pb;
        }

        public boolean runProcess(final Process process) throws Exception
        {
            runningProcess.set(process);
            if (timeoutSeconds != -1 && !process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            {
                process.destroy();
                runningProcess.set(null);
                return true;
            }
            else
            {
                process.waitFor();
                runningProcess.set(null);
            }
            return false;
        }

        private List<String> expandVariables(final List<String> args, final Path testFolder) throws Exception
        {
            for (int i = 0; i < args.size(); i++)
            {
                args.set(i, expandVariables(args.get(i), testFolder));
            }
            return args;
        }

        private String expandVariables(String str, Path testFolder) throws Exception
        {
            final String strOld = str;
            str = str.replace("$$TEST_FOLDER$$", testFolder.toString());
            str = str.replace("$$RUN_DIRECTORY$$", runDir.toString());

            if (inFiles.isEmpty() && outFiles.isEmpty())
            {
                if (DEBUG && !str.equals(strOld))
                {
                    System.err.println("DEBUG: expansion: " + strOld + " $$ " + str);
                }
                return str;
            }

            int index = 0;
            while (index < str.length())
            {
                final int locIn = inFiles.isEmpty() ? -1 : str.indexOf("$$INPUT_FILES_", index);
                final int locOut = outFiles.isEmpty() ? -1 : str.indexOf("$$OUTPUT_FILES_", index);

                if (locIn == -1 && locOut == -1) // found nothing -> end
                {
                    break;
                }

                final int loc = locIn == -1 ? locOut : (locOut == -1 ? locIn : Math.min(locIn, locOut));
                final List<String> files = loc == locIn ? inFilesStr : outFilesStr;
                final int locArg = loc + (loc == locIn ? "$$INPUT_FILES_".length() : "$$OUTPUT_FILES_".length());

                int locEnd = str.indexOf("$$", locArg);
                if (locEnd == -1)
                {
                    break;
                }

                index = locEnd + 2;
                String arg = str.substring(locArg, locEnd);

                try
                {
                    arg = files.get(Integer.parseInt(arg));
                }
                catch (NumberFormatException e)
                {
                    if (arg.length() > 5)
                    {
                        System.err.println("WARNING: probably wrong delimiter for files? file argument: " + str.substring(loc, index));
                    }
                    arg = String.join(arg, files);
                }

                str = str.substring(0, loc) + arg + str.substring(index);
            }

            if (DEBUG && !str.equals(strOld))
            {
                System.err.println("DEBUG: expansion: " + strOld + " $$ " + str);
            }
            return str;
        }

        private boolean hasInput()
        {
            return input != null;
        }

        private boolean hasArguments()
        {
            return args != null;
        }

        private boolean hasExitCode()
        {
            return exitCode != null;
        }

        private boolean hasGenerate()
        {
            return generate != null;
        }

        private boolean hasRefSolution()
        {
            return refsolution != null;
        }

        private boolean hasInputFiles()
        {
            return inputFilesPath != null;
        }

        private boolean hasOutputFiles()
        {
            return outputFilesPath != null;
        }
    }

    public static class FileExtension
    {
        String extension;
        String description;
        BiConsumer<TestInfo, Path> extensionProcessor;

        private static final Map<String, FileExtension> fileExtensionsByExt = new HashMap<>();
        private static final List<FileExtension> fileExtensionsById = new ArrayList<>();

        public static FileExtension STDIN = new FileExtension("in", "stdin", TestInfo::attachInput);
        public static FileExtension STDOUT = new FileExtension("out", "stdout", TestInfo::attachOutput);
        public static FileExtension STDERR = new FileExtension("err", "stderr", TestInfo::attachError);
        public static FileExtension ARGS = new FileExtension("args", "arguments", TestInfo::attachArguments);
        public static FileExtension EXIT_CODE = new FileExtension("exit", "exit code", TestInfo::attachExitCode);
        public static FileExtension INPUT_GEN = new FileExtension("genin", "stdin generator", TestInfo::attachGenerate);
        public static FileExtension OUTPUT_GEN = new FileExtension("gen", "reference solver", TestInfo::attachRefSolution);
        public static FileExtension TIMEOUT = new FileExtension("timeout", "timeout", TestInfo::attachTimeout);
        public static FileExtension RUN_DIRECTORY = new FileExtension("rundir", "run directory", TestInfo::attachRunDir);
        public static FileExtension IN_FILES = new FileExtension("infiles", "input files", TestInfo::attachInputFiles);
        public static FileExtension OUT_FILES = new FileExtension("outfiles", "output files", TestInfo::attachOutputFiles);
        public static FileExtension ENVIRONMENT_MAP = new FileExtension("envmap", "environment", TestInfo::attachEnvironmentMap);
        public static FileExtension DESCRIPTION = new FileExtension("desc", "description", TestInfo::attachDescription);

        public FileExtension(final String extension, final String description, final BiConsumer<TestInfo, Path> extensionProcessor)
        {
            this.extension = extension;
            this.description = description;
            this.extensionProcessor = extensionProcessor;

            fileExtensionsByExt.put(extension, this);
            fileExtensionsById.add(this);
        }

        public static boolean changeExtensions(final String[] extensions)
        {
            if (extensions.length != fileExtensionsById.size())
            {
                System.err.printf("expected %d parts (%s) for -Dtr.file_exts but got list with length: %d%n",
                    fileExtensionsById.size(),
                    fileExtensionsById.stream().map(ext -> ext.description).collect(Collectors.joining("/")),
                    extensions.length);
                return true;
            }

            for (int i = 0; i < extensions.length; i++)
            {
                fileExtensionsById.get(i).extension = extensions[i];
            }
            return false;
        }

        public static FileExtension get(final String extension)
        {
            return fileExtensionsByExt.get(extension);
        }

        @Override
        public String toString()
        {
            return extension;
        }
    }
}
