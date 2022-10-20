import java.io.IOException;
import java.io.InputStream;
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
import java.util.function.BiFunction;

public class TestRunner
{
    public static void main(final String[] args) throws Exception
    {
        if (System.getProperty("tr.folder") == null)
        {
            throw new IllegalArgumentException("missing -Dtr.folder pointing to .in/out/err/... files");
        }

        if (System.getProperty("tr.main") == null)
        {
            throw new IllegalArgumentException("missing -Dtr.main in format 'path_to_executable arguments'");
        }

        final Path testFolder = Path.of(System.getProperty("tr.folder")).toAbsolutePath().normalize();
        if (!Files.isDirectory(testFolder))
        {
            throw new IllegalArgumentException("non-directory path in -Dtr.folder");
        }

        final String[] testFileExtensions = Optional.ofNullable(System.getProperty("tr.file_exts"))
            .orElse("in,out,err,args,exit,genin,gen,timeout,rundir,outfiles")
            .toLowerCase()
            .split(",");
        if (testFileExtensions.length != 10)
        {
            throw new IllegalArgumentException(
                "expected xxx,xxx,xxx,xxx,xxx,xxx,xxx,xxx,xxx,xxx (input/output/error/arguments/exit code/generate/reference solution/timeout/run directory/output files) for -Dtr.file_exts but got list with length: " +
                    testFileExtensions.length);
        }

        final Map<String, TestInfo> testInfos = new HashMap<>();
        final Map<String, BiFunction<TestInfo, Path, TestInfo>> testInfoSetups = new HashMap<>(3);
        testInfoSetups.put(testFileExtensions[0], TestInfo::attachInput);
        testInfoSetups.put(testFileExtensions[1], TestInfo::attachOutput);
        testInfoSetups.put(testFileExtensions[2], TestInfo::attachError);
        testInfoSetups.put(testFileExtensions[3], TestInfo::attachArguments);
        testInfoSetups.put(testFileExtensions[4], TestInfo::attachExitCode);
        testInfoSetups.put(testFileExtensions[5], TestInfo::attachGenerate);
        testInfoSetups.put(testFileExtensions[6], TestInfo::attachRefSolution);
        testInfoSetups.put(testFileExtensions[7], TestInfo::attachTimeout);
        testInfoSetups.put(testFileExtensions[8], TestInfo::attachRunDir);
        testInfoSetups.put(testFileExtensions[9], TestInfo::attachOutputFiles);

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

                final var testInfoUpdater = testInfoSetups.get(fileNameWithExt.substring(lastPeriod + 1).toLowerCase());
                if (testInfoUpdater != null)
                {
                    final String fileName = fileNameWithExt.substring(0, lastPeriod);
                    testInfos.computeIfAbsent(fileName, TestInfo::ofName); // Map#merge value is not supplier
                    testInfos.computeIfPresent(fileName,
                        (key, old) -> testInfoUpdater.apply(old, testPath.toAbsolutePath().normalize()));
                }
            }
        }

        final String[] mainBase = System.getProperty("tr.main").split(" "); // arguments splitting? not so easy
        final int mainTimeout = Optional.ofNullable(System.getProperty("tr.main_timeout")).map(Integer::valueOf).orElse(10);

        int correctTests = 0;

        try
        {
            new ProcessBuilder("echo").start().waitFor(); // warmup process builder
        }
        catch (IOException e)
        {}

        for (TestInfo test : testInfos.values().stream().sorted(Comparator.comparing(TestInfo::name)).toList())
        {
            System.out.println("===== TEST " + test.name + " =====");

            final int timeout = test.hasTimeout() ? Integer.valueOf(Files.readString(test.timeout)) : mainTimeout;

            // transform run directory
            if (test.hasRunDirectory())
            {
                test = test.attachRunDir(Path.of(Files.readAllLines(test.runDir).get(0)).toAbsolutePath().normalize());
                System.out.println("Running in directory: " + test.runDir.toString());
            }

            // Generate input
            if (test.hasGenerate())
            {
                System.out.println("Generating input...");
                final Path genIn = test.generate.getParent().resolve(test.name + "." + testFileExtensions[0]);

                final ProcessBuilder pbGen = new ProcessBuilder(Files.readAllLines(test.generate));
                pbGen.redirectOutput(genIn.toFile());
                if (test.hasRunDirectory())
                {
                    pbGen.directory(test.runDir.toFile());
                }

                final Process processGen = pbGen.start();
                if (timeout != -1 && !processGen.waitFor(timeout, TimeUnit.SECONDS))
                {
                    processGen.destroy();
                    System.out.println("Input generation timeout for: " + test.name);
                    System.out.println();
                    continue;
                }
                else
                {
                    processGen.waitFor();
                }

                test = test.attachInput(genIn);
            }

            // Generate output and error
            if (test.hasRefSolution())
            {
                System.out.println("Generating reference solution...");
                final Path genOut = test.refsolution.getParent().resolve(test.name + "." + testFileExtensions[1]);
                final Path genErr = test.refsolution.getParent().resolve(test.name + "." + testFileExtensions[2]);

                final ProcessBuilder pbGen = new ProcessBuilder(Files.readAllLines(test.refsolution));
                if (test.hasInput())
                {
                    pbGen.redirectInput(test.input.toFile());
                }
                pbGen.redirectOutput(genOut.toFile());
                pbGen.redirectError(genErr.toFile());
                if (test.hasRunDirectory())
                {
                    pbGen.directory(test.runDir.toFile());
                }

                final Process processGen = pbGen.start();
                if (!test.hasInput())
                {
                    processGen.getOutputStream().close();
                }
                if (timeout != -1 && !processGen.waitFor(timeout, TimeUnit.SECONDS))
                {
                    processGen.destroy();
                    System.out.println("Reference solution generation timeout for: " + test.name);
                    System.out.println();
                    continue;
                }
                else
                {
                    processGen.waitFor();
                }

                processOutputFiles(test, testFolder, true);

                test = test.attachOutput(genOut);
                test = test.attachError(genErr);
            }

            // prepare "main" execution
            final ProcessBuilder pb = new ProcessBuilder();

            if (test.hasInput())
            {
                pb.redirectInput(test.input.toFile());
            }

            final List<String> mainArgs = new ArrayList<>(Arrays.asList(mainBase));
            if (test.hasArguments())
            {
                mainArgs.addAll(Files.readAllLines(test.args));
            }
            pb.command(mainArgs);

            if (test.hasRunDirectory())
            {
                pb.directory(test.runDir.toFile());
            }

            // execute "main"
            final long start = System.nanoTime();
            final Process process = pb.start();
            if (!test.hasInput())
            {
                process.getOutputStream().close();
            }
            if (timeout != -1 && !process.waitFor(timeout, TimeUnit.SECONDS))
            {
                process.destroy();
                System.out.printf("TIMEOUT   time: \t%.2fms%n%n", (System.nanoTime() - start) / 1000000.0);
                continue;
            }
            else
            {
                process.waitFor();
            }
            final long end = System.nanoTime();

            processOutputFiles(test, testFolder, false);

            // blame human for being SgTrUePaItD

            boolean isCorrect = true;
            isCorrect &= checkExitCode(test, process.exitValue());
            isCorrect &= checkStream(test.output, process.getInputStream(), "out");
            isCorrect &= checkStream(test.error, process.getErrorStream(), "err");
            isCorrect &= checkOutputFiles(test, testFolder);
            correctTests += isCorrect ? 1 : 0;

            System.out.printf("%s     time: \t%.2fms%n%n", isCorrect ? "OK   " : "ERROR", (end - start) / 1000000.0);
        }

        System.out.println("CORRECT: " + correctTests + "/" + testInfos.size());
        if (correctTests == testInfos.size())
        {
            System.out.println();
            System.out.println("=====>>>>>    WELL DONE    <<<<<=====");
            System.out.println();
        }
    }

    private static void processOutputFiles(final TestInfo test, final Path testFolder, final boolean isReference) throws IOException
    {
        if (!test.hasOutputFiles())
        {
            return;
        }

        for (final String outputFile : Files.readAllLines(test.outputFiles))
        {
            final Path programResult = (test.hasRunDirectory() ? test.runDir : Paths.get(".")).resolve(outputFile);
            if (Files.exists(programResult))
            {
                final Path moveTo = testFolder.resolve(test.name + ".outf_" + (isReference ? "ref." : "user.") + outputFile);
                Files.move(programResult, moveTo, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static boolean checkOutputFiles(final TestInfo test, final Path testFolder) throws IOException
    {
        if (!test.hasOutputFiles())
        {
            return true;
        }

        boolean isCorrect = true;

        for (final String outputFile : Files.readAllLines(test.outputFiles))
        {
            final Path user = testFolder.resolve(test.name + ".outf_user." + outputFile).toAbsolutePath().normalize();
            final Path reference = testFolder.resolve(test.name + ".outf_ref." + outputFile).toAbsolutePath().normalize();
            if (!Files.exists(user))
            {
                System.out.println("Missing user output file of name: \"" + outputFile + "\" at: " + user.toString());
                System.out.println();

                isCorrect = false;
            }
            else if (!Files.exists(reference))
            {
                System.out.println("Missing reference output file of name: \"" + outputFile + "\" at: " + reference.toString());
                System.out.println();

                isCorrect = false;
            }
            else
            {
                final long firstMismatchByte = Files.mismatch(user, reference);
                if (firstMismatchByte != -1)
                {
                    System.out.println("Your output file with name \"" + outputFile +
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
                System.out.println("Result exit code: " + exitCode);
                System.out.println("Expected exit code: " + processExitCode);
                return false;
            }
        }
        else if (processExitCode != 0)
        {
            System.out.println("Nonzero exit code: " + processExitCode);
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
                final String result = escapeInvisibles(new String(processBuffer));
                System.out.printf("Result %s:%n%s%n",
                    streamName,
                    result.length() == 0 ? "<empty>" : result.substring(0, Math.min(1000, result.length())));
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
                    System.out.printf("Mismatch in only character of %s:%n| %c |%n%n", streamName, result.charAt(0));
                }
                else if (firstByteMismatch >= result.length())
                {
                    System.out.printf("Mismatch after end of %s%n%n", streamName);
                }
                else if (firstByteMismatch == 0)
                {
                    System.out.printf("Mismatch at start of result %s:%n| %c <> %s %s%n%n",
                        streamName,
                        result.charAt(firstByteMismatch),
                        result.substring(firstByteMismatch + 1, Math.min(firstByteMismatch + 11, result.length())),
                        Math.min(firstByteMismatch + 11, result.length()) >= result.length() ? "|" : "...");
                }
                else if (firstByteMismatch == result.length() - 1)
                {
                    System.out.printf("Mismatch at end of result %s:%n%s %s <> %c |%n%n",
                        streamName,
                        firstByteMismatch < 11 ? "|" : "...",
                        result.substring(Math.max(firstByteMismatch - 10, 0), firstByteMismatch),
                        result.charAt(firstByteMismatch));
                }
                else
                {
                    System.out.printf("Mismatch at %d of result %s:%n%s %s <> %c <> %s %s%n%n",
                        firstByteMismatch,
                        streamName,
                        firstByteMismatch < 11 ? "|" : "...",
                        result.substring(Math.max(firstByteMismatch - 10, 0), firstByteMismatch),
                        result.charAt(firstByteMismatch),
                        result.substring(firstByteMismatch + 1, Math.min(firstByteMismatch + 11, result.length())),
                        Math.min(firstByteMismatch + 11, result.length()) >= result.length() ? "|" : "...");
                }
                return false;
            }
        }
        else if (processBuffer.length > 0)
        {
            System.out.printf("Result %s should be empty:%n%s%n", streamName, new String(processBuffer));
            return false;
        }
        return true;
    }

    private static String escapeInvisibles(final String escape)
    {
        final int len = escape.length();
        final StringBuilder sb = new StringBuilder(1005 * len / 1000);
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

    private static record TestInfo(String name,
        Path input,
        Path output,
        Path error,
        Path args,
        Path exitCode,
        Path generate,
        Path refsolution,
        Path timeout,
        Path runDir,
        Path outputFiles)
    {
        private static TestInfo ofName(final String name)
        {
            Objects.requireNonNull(name);
            return new TestInfo(name, null, null, null, null, null, null, null, null, null, null);
        }

        private TestInfo attachInput(final Path input)
        {
            return new TestInfo(name, input, output, error, args, exitCode, generate, refsolution, timeout, runDir, outputFiles);
        }

        private TestInfo attachOutput(final Path output)
        {
            return new TestInfo(name, input, output, error, args, exitCode, generate, refsolution, timeout, runDir, outputFiles);
        }

        private TestInfo attachError(final Path error)
        {
            return new TestInfo(name, input, output, error, args, exitCode, generate, refsolution, timeout, runDir, outputFiles);
        }

        private TestInfo attachArguments(final Path args)
        {
            return new TestInfo(name, input, output, error, args, exitCode, generate, refsolution, timeout, runDir, outputFiles);
        }

        private TestInfo attachExitCode(final Path exitCode)
        {
            return new TestInfo(name, input, output, error, args, exitCode, generate, refsolution, timeout, runDir, outputFiles);
        }

        private TestInfo attachGenerate(final Path generate)
        {
            return new TestInfo(name, input, output, error, args, exitCode, generate, refsolution, timeout, runDir, outputFiles);
        }

        private TestInfo attachRefSolution(final Path refsolution)
        {
            return new TestInfo(name, input, output, error, args, exitCode, generate, refsolution, timeout, runDir, outputFiles);
        }

        private TestInfo attachTimeout(final Path timeout)
        {
            return new TestInfo(name, input, output, error, args, exitCode, generate, refsolution, timeout, runDir, outputFiles);
        }

        private TestInfo attachRunDir(final Path runDir)
        {
            return new TestInfo(name, input, output, error, args, exitCode, generate, refsolution, timeout, runDir, outputFiles);
        }

        private TestInfo attachOutputFiles(final Path outputFiles)
        {
            return new TestInfo(name, input, output, error, args, exitCode, generate, refsolution, timeout, runDir, outputFiles);
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

        private boolean hasTimeout()
        {
            return timeout != null;
        }

        private boolean hasRunDirectory()
        {
            return runDir != null;
        }

        private boolean hasOutputFiles()
        {
            return outputFiles != null;
        }
    }
}
