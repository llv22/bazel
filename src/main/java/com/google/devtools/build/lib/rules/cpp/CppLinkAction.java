// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.cpp;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.*;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.actions.AbstractAction;
import com.google.devtools.build.lib.actions.ActionContinuationOrResult;
import com.google.devtools.build.lib.actions.ActionEnvironment;
import com.google.devtools.build.lib.actions.ActionExecutionContext;
import com.google.devtools.build.lib.actions.ActionExecutionException;
import com.google.devtools.build.lib.actions.ActionKeyContext;
import com.google.devtools.build.lib.actions.ActionOwner;
import com.google.devtools.build.lib.actions.ActionResult;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.ArtifactExpander;
import com.google.devtools.build.lib.actions.CommandAction;
import com.google.devtools.build.lib.actions.CommandLine;
import com.google.devtools.build.lib.actions.CommandLineExpansionException;
import com.google.devtools.build.lib.actions.CommandLines.CommandLineAndParamFileInfo;
import com.google.devtools.build.lib.actions.ExecException;
import com.google.devtools.build.lib.actions.ExecutionRequirements;
import com.google.devtools.build.lib.actions.ResourceSet;
import com.google.devtools.build.lib.actions.SimpleSpawn;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.SpawnContinuation;
import com.google.devtools.build.lib.actions.extra.CppLinkInfo;
import com.google.devtools.build.lib.actions.extra.ExtraActionInfo;
import com.google.devtools.build.lib.analysis.actions.ActionConstructionContext;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.starlark.Args;
import com.google.devtools.build.lib.cmdline.RepositoryName;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadCompatible;
import com.google.devtools.build.lib.exec.SpawnStrategyResolver;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext.Linkstamp;
import com.google.devtools.build.lib.rules.cpp.LinkerInputs.LibraryToLink;
import com.google.devtools.build.lib.server.FailureDetails.CppLink;
import com.google.devtools.build.lib.server.FailureDetails.CppLink.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.lib.starlarkbuildapi.CommandLineArgsApi;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.Fingerprint;
import com.google.devtools.build.lib.util.OS;
import com.google.devtools.build.lib.util.ShellEscaper;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkList;

import static java.util.stream.Collectors.groupingBy;

/**
 * Action that represents a linking step.
 */
@ThreadCompatible
@AutoCodec
public final class CppLinkAction extends AbstractAction implements CommandAction {

    /**
     * An implementation of {@link LinkArtifactFactory} that can only create artifacts in the package
     * directory.
     */
    public static final LinkArtifactFactory DEFAULT_ARTIFACT_FACTORY =
            new LinkArtifactFactory() {
                @Override
                public Artifact create(
                        ActionConstructionContext actionConstructionContext,
                        RepositoryName repositoryName,
                        BuildConfiguration configuration,
                        PathFragment rootRelativePath) {
                    return actionConstructionContext.getDerivedArtifact(
                            rootRelativePath, configuration.getBinDirectory(repositoryName));
                }
            };
    private static final String LINK_GUID = "58ec78bd-1176-4e36-8143-439f656b181d";
    public static final String CRACK_VERSION_OF_MACOS = "10.13.6";
    @Nullable
    private final String mnemonic;
    private final LibraryToLink outputLibrary;
    private final Artifact linkOutput;
    private final LibraryToLink interfaceOutputLibrary;
    private final ImmutableMap<String, String> toolchainEnv;
    private final ImmutableMap<String, String> executionRequirements;
    private final ImmutableMap<Linkstamp, Artifact> linkstamps;

    private final LinkCommandLine linkCommandLine;

    private final boolean isLtoIndexing;

    private final PathFragment ldExecutable;
    private final String targetCpu;


    /**
     * Use {@link CppLinkActionBuilder} to create instances of this class. Also see there for the
     * documentation of all parameters.
     *
     * <p>This constructor is intentionally private and is only to be called from {@link
     * CppLinkActionBuilder#build()}.
     */
    CppLinkAction(
            ActionOwner owner,
            String mnemonic,
            NestedSet<Artifact> inputs,
            ImmutableSet<Artifact> outputs,
            LibraryToLink outputLibrary,
            Artifact linkOutput,
            LibraryToLink interfaceOutputLibrary,
            boolean isLtoIndexing,
            ImmutableMap<Linkstamp, Artifact> linkstamps,
            LinkCommandLine linkCommandLine,
            ActionEnvironment env,
            ImmutableMap<String, String> toolchainEnv,
            ImmutableMap<String, String> executionRequirements,
            PathFragment ldExecutable,
            String targetCpu) {
        super(owner, inputs, outputs, env);
        this.mnemonic = getMnemonic(mnemonic, isLtoIndexing);
        this.outputLibrary = outputLibrary;
        this.linkOutput = linkOutput;
        this.interfaceOutputLibrary = interfaceOutputLibrary;
        this.isLtoIndexing = isLtoIndexing;
        this.linkstamps = linkstamps;
        this.linkCommandLine = linkCommandLine;
        this.toolchainEnv = toolchainEnv;
        this.executionRequirements = executionRequirements;
        this.ldExecutable = ldExecutable;
        this.targetCpu = targetCpu;
    }

    static String getMnemonic(String mnemonic, boolean isLtoIndexing) {
        if (mnemonic == null) {
            return isLtoIndexing ? "CppLTOIndexing" : "CppLink";
        }
        return mnemonic;
    }

    private static DetailedExitCode createDetailedExitCode(String message, Code detailedCode) {
        return DetailedExitCode.of(
                FailureDetail.newBuilder()
                        .setMessage(message)
                        .setCppLink(CppLink.newBuilder().setCode(detailedCode))
                        .build());
    }

    @VisibleForTesting
    public String getTargetCpu() {
        return targetCpu;
    }

    @Override
    @VisibleForTesting
    public NestedSet<Artifact> getPossibleInputsForTesting() {
        return getInputs();
    }

    @Override
    @VisibleForTesting
    public ImmutableMap<String, String> getIncompleteEnvironmentForTesting() {
        return getEffectiveEnvironment(ImmutableMap.of());
    }

    @Override
    public ImmutableMap<String, String> getEffectiveEnvironment(Map<String, String> clientEnv) {
        LinkedHashMap<String, String> result = Maps.newLinkedHashMapWithExpectedSize(env.size());
        env.resolve(result, clientEnv);

        result.putAll(toolchainEnv);

        if (!executionRequirements.containsKey(ExecutionRequirements.REQUIRES_DARWIN)) {
            // This prevents gcc from writing the unpredictable (and often irrelevant)
            // value of getcwd() into the debug info.
            result.put("PWD", "/proc/self/cwd");
        }
        return ImmutableMap.copyOf(result);
    }

    /**
     * Returns the link configuration; for correctness you should not call this method during
     * execution - only the argv is part of the action cache key, and we therefore don't guarantee
     * that the action will be re-executed if the contents change in a way that does not affect the
     * argv.
     */
    @VisibleForTesting
    public LinkCommandLine getLinkCommandLine() {
        return linkCommandLine;
    }

    /**
     * Returns the output of this action as a {@link LibraryToLink} or null if it is an executable.
     */
    @Nullable
    public LibraryToLink getOutputLibrary() {
        return outputLibrary;
    }

    public LibraryToLink getInterfaceOutputLibrary() {
        return interfaceOutputLibrary;
    }

    @Override
    public ImmutableMap<String, String> getExecutionInfo() {
        return executionRequirements;
    }

    @Override
    public Sequence<CommandLineArgsApi> getStarlarkArgs() throws EvalException {
        ImmutableSet<Artifact> directoryInputs =
                getInputs().toList().stream()
                        .filter(artifact -> artifact.isDirectory())
                        .collect(ImmutableSet.toImmutableSet());

        CommandLine commandLine = linkCommandLine.getCommandLineForStarlark();

        CommandLineAndParamFileInfo commandLineAndParamFileInfo =
                new CommandLineAndParamFileInfo(commandLine, /* paramFileInfo= */ null);

        Args args = Args.forRegisteredAction(commandLineAndParamFileInfo, directoryInputs);

        return StarlarkList.immutableCopyOf(ImmutableList.of(args));
    }

    @Override
    public List<String> getArguments() throws CommandLineExpansionException {
        return linkCommandLine.arguments();
    }

    /**
     * Returns the command line specification for this link, included any required linkstamp
     * compilation steps. The command line may refer to a .params file.
     *
     * @param expander ArtifactExpander for expanding TreeArtifacts.
     * @return a finalized command line suitable for execution
     */
    public final List<String> getCommandLine(@Nullable ArtifactExpander expander)
            throws CommandLineExpansionException {
        return linkCommandLine.getCommandLine(expander);
    }

    /**
     * Returns a (possibly empty) list of linkstamp object files.
     *
     * <p>This is used to embed various values from the build system into binaries to identify their
     * provenance.
     */
    public ImmutableList<Artifact> getLinkstampObjects() {
        return linkstamps.keySet().stream()
                .map(CcLinkingContext.Linkstamp::getArtifact)
                .collect(ImmutableList.toImmutableList());
    }

    public ImmutableCollection<Artifact> getLinkstampObjectFileInputs() {
        return linkstamps.values();
    }

    @Override
    @ThreadCompatible
    public ActionContinuationOrResult beginExecution(ActionExecutionContext actionExecutionContext)
            throws ActionExecutionException, InterruptedException {
        Spawn spawn = createSpawn(actionExecutionContext);
        SpawnContinuation spawnContinuation =
                actionExecutionContext
                        .getContext(SpawnStrategyResolver.class)
                        .beginExecution(spawn, actionExecutionContext);
        return new CppLinkActionContinuation(actionExecutionContext, spawnContinuation);
    }

    /**
     * Object File class to parse command line and generate intermediate link file
     */
    public static class ObjectFile {

        private final String objFileCommand;
        private String postfix;
        private String prefixOfObjFile = "";
        private final int index;

        public ObjectFile(String objFileCommand, int index) {
            this.objFileCommand = objFileCommand;
            this.index = index;
            if (this.objFileCommand.endsWith(".a")) {
                this.postfix = ".a";
            } else if (this.objFileCommand.endsWith(".lo")) {
                this.postfix = ".lo";
            } else if (this.objFileCommand.endsWith(".o")) {
                this.postfix = ".o";
            }
            if (this.isObjectFile()) {
                //see: check if prefix exists
                final int lastIndexOfComma = objFileCommand.lastIndexOf(",");
                if (lastIndexOfComma != -1) {
                    this.prefixOfObjFile = objFileCommand.substring(0, lastIndexOfComma + 1);
                }
            }
        }

        public String getObjFileCommand() {
            return objFileCommand;
        }

        public String getPostfix() {
            return postfix;
        }

        public int getIndex() {
            return index;
        }

        public boolean isObjectFile() {
            return postfix != null;
        }

        public String getPrefixOfObjFile() {
            return prefixOfObjFile;
        }

        public String getObjectFileWithoutPrefix() {
            return this.objFileCommand.substring(this.prefixOfObjFile.length());
        }
    }

    /**
     * remove duplicate arguments in command line in order to avoid unnecessary overflowing of input parameters
     *
     * @param origin original command line passed by bazel
     * @return refined command line without repeating of parameters
     */
    private List<String> removeDuplicateArguments(final ImmutableList<String> origin) {
        final List<String> finalized = new ArrayList<>();
        for (String command : origin) {
            if (!finalized.contains(command)) {
                finalized.add(command);
            }
        }
        return finalized;
    }

    /**
     * extract object file list and implicitly analyze internal status of object file command
     *
     * @param commandline          original command line
     * @param indexOfOutputLibrary index of output file
     * @return linked object file
     */
    private List<ObjectFile> extractObjectFileList(final ImmutableList<String> commandline, final int indexOfOutputLibrary) {
        final List<ObjectFile> objectFileList = new ArrayList<>();
        for (int i = indexOfOutputLibrary + 1; i < commandline.size(); i++) {
            objectFileList.add(new ObjectFile(commandline.get(i), i));
        }
        return objectFileList;
    }

    /**
     * merge intermediate object files and output the merged map
     *
     * @param postfix                      .a, .o, or .lo
     * @param originObjectFileList         original object file with the same postfix
     * @param intermediateFileNameTemplate <outputFileName>_intermediate_%d.<postfix>
     * @param intermediateFilePath         file path where intermediate object file will output
     * @return map with the key that describes the final merged intermediate object file as well as the value that represents the original object file.
     * Tips: in the return map, the value also includes the previous first command line
     */
    private Map<ObjectFile, List<ObjectFile>> mergeIntermediate(final String postfix, final List<ObjectFile> originObjectFileList, final String intermediateFileNameTemplate,
                                                                final String intermediateFilePath, final CppLinkAction action) throws ActionExecutionException {
        final int collectedSize = 200;
        final ProcessBuilder processBuilder = new ProcessBuilder();
        final Map<String, List<ObjectFile>> prefixToObjectFileMap = originObjectFileList.stream().collect(groupingBy(ObjectFile::getPrefixOfObjFile));
        final Map<ObjectFile, List<ObjectFile>> merged = new HashMap<>();
        int intermediateNumber = 0;
        for (Map.Entry<String, List<ObjectFile>> entry : prefixToObjectFileMap.entrySet()) {
            final String prefix = entry.getKey();
            final List<ObjectFile> objectFiles = entry.getValue();
            int i = 0;
            while (i < objectFiles.size()) {
                final List<ObjectFile> collected = new ArrayList<>();
                for (int j = 0; j < collectedSize && i < objectFiles.size(); j++) {
                    collected.add(objectFiles.get(i++));
                }
                final ObjectFile pre = collected.get(0);
                final ObjectFile m = new ObjectFile(String.format("%s%s%s", prefix, intermediateFilePath, String.format(intermediateFileNameTemplate, intermediateNumber++, postfix)), pre.getIndex());
                final List<String> commands = Lists.newArrayList("/usr/bin/libtool", "-static", "-s", "-o");
                commands.add(m.getObjFileCommand());
                commands.addAll(collected.stream().map(ObjectFile::getObjectFileWithoutPrefix).collect(Collectors.toList()));
                System.err.printf("[bazel:CppLinkAction.java] trigger an intermediate merging operation: %s", commands);
                try {
                    final Process process = processBuilder.command(commands).start();
                    if (process.exitValue() != 0) {
                        String message = String.format("failed to generate intermediate library %s",
                                m.getObjectFileWithoutPrefix());
                        throw this.newFailedExceptionOfAction(message, action);
                    }
                } catch (IOException e) {
                    String message = String.format("failed to generate intermediate library %s due to the failure of starting new process, referring to %s",
                            m.getObjectFileWithoutPrefix(), e.getMessage());
                    throw this.newFailedExceptionOfAction(message, action);
                }
                merged.put(m, collected);
            }
        }
        return merged;
    }

    /**
     * Refine the final output file with intermediate merged object files
     *
     * @param finalMerged         the merged object file together with original object files
     * @param originalCommandLine original command line that has already been processed to avoid duplication
     * @return the final link command line for output file based on merged object files
     */
    private List<String> refineCommand(final Map<ObjectFile, List<ObjectFile>> finalMerged, final List<String> originalCommandLine) {
        final List<String> refinedCommands = new ArrayList<>(originalCommandLine);
        for (Map.Entry<ObjectFile, List<ObjectFile>> entry : finalMerged.entrySet()) {
            final ObjectFile f = entry.getKey();
            final List<ObjectFile> target = entry.getValue();
            refinedCommands.set(f.getIndex(), f.objFileCommand);
            if (target.size() > 1) {
                for (int i = 1; i < target.size(); i++) {
                    //see: trim the unnecessary command which has been already merged by previous operation
                    refinedCommands.set(target.get(i).getIndex(), "");
                }
            }
        }
        return refinedCommands.stream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    /**
     * get ARG_MAX via getconf on macOS
     *
     * @return maximum parameters' buffer size that allows on macOS
     * @throws IOException exception raised
     */
    public synchronized int getARG_MAX() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("getconf", "ARG_MAX");
        Process p = pb.start();
        BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line = in.readLine().trim();
        return Integer.parseInt(line);
    }

    /**
     * Generate new failed exception for action
     *
     * @param message detailed message about failure
     * @param action  relevant action
     * @return the exception to action execution
     */
    private ActionExecutionException newFailedExceptionOfAction(String message, CppLinkAction action) {
        DetailedExitCode code = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE);
        return new ActionExecutionException(message, action, /*catastrophe=*/ false, code);
    }

    /**
     * Enhance spawn implementation to avoid "arguments too long" error due to the pre-setting of
     * macOS 10.13.6 `getconf ARG_MAX`
     *
     * @param actionExecutionContext execution context of action
     * @return the real execution spawn for the action
     * @throws ActionExecutionException exception raise during the execution of the action
     */
    private Spawn createSpawn(ActionExecutionContext actionExecutionContext)
            throws ActionExecutionException {
        try {
            ImmutableList<String> commandLine = ImmutableList.copyOf(
                    getCommandLine(actionExecutionContext.getArtifactExpander()));
            System.err.printf(
                    "[bazel:CppLinkAction.java] command line: %s, inputs: %s, outputs: %s, os: %s, tools: %s \n",
                    commandLine, getInputs(), getOutputs(), OS.getCurrent(), estimateResourceConsumptionLocal(
                            OS.getCurrent(),
                            getLinkCommandLine().getLinkerInputArtifacts().memoizedFlattenAndGetSize()));
            //see: only crack for macOS 10.13.6, refer to https://www.gnu.org/software/libtool/manual/libtool.html
            // /usr/bin/libtool -static -s -o <output> <input  1 ... input n>
            // here: either output or input can be ended with .o, .lo, .a
            if (OS.getCurrent() == OS.DARWIN && OS.getVersion().equals(CRACK_VERSION_OF_MACOS)) {
                //see: to avoid duplicate arguments in command line
                commandLine = ImmutableList.copyOf(this.removeDuplicateArguments(commandLine));
                final String fullNameOfOutputFile = getPrimaryOutput().getExecPathString();
                final String shortNameOfOutputFile = getPrimaryOutput().getFilename();
                final String dirPathOfOutputFile = getPrimaryOutput().getDirname();
                final String arguments = commandLine.parallelStream().collect(Collectors.joining(" "));
                final int upperBoundaryOfArguments = this.getARG_MAX();
                if (arguments.getBytes().length > upperBoundaryOfArguments) {
                    System.err.printf(
                            "[bazel:CppLinkAction.java] output library - execPath = %s, fileName = %s, dirName = %s\n",
                            fullNameOfOutputFile, shortNameOfOutputFile, dirPathOfOutputFile);
                    System.err.println(
                            "[bazel:CppLinkAction.java] need to split arguments to avoid the issue of \"error=7, Argument list too long\" on macOS 10.13.6");
                    final String intermediateFileNameTemplate =
                            shortNameOfOutputFile.substring(0, shortNameOfOutputFile.lastIndexOf("."))
                                    + "_intermediate_%d.%s";
                    final int indexOfOutputLibrary = commandLine.indexOf(fullNameOfOutputFile);
                    final List<ObjectFile> objects = this.extractObjectFileList(commandLine, indexOfOutputLibrary);
                    final Map<String, List<ObjectFile>> postfixToObjectFileMap = objects.stream().filter(ObjectFile::isObjectFile)
                            .collect(groupingBy(ObjectFile::getPostfix));
                    final Map<ObjectFile, List<ObjectFile>> finalMerged = new HashMap<>();
                    for (Map.Entry<String, List<ObjectFile>> set : postfixToObjectFileMap.entrySet()) {
                        final String postfix = set.getKey();
                        final List<ObjectFile> files = set.getValue();
                        System.err.printf("[bazel:CppLinkAction.java] %s with files size: %s \n", postfix, files.size());
                        final Map<ObjectFile, List<ObjectFile>> merged = this.mergeIntermediate(postfix, files, intermediateFileNameTemplate, dirPathOfOutputFile, this);
                        finalMerged.putAll(merged);
                    }
                    //see: refine for simpleSpawn for intermediate files
                    final List<String> refinedCommands = this.refineCommand(finalMerged, commandLine);
                    System.err.printf("[bazel:CppLinkAction.java] finally link via %s\n", refinedCommands);
                    final String compressed = commandLine.parallelStream().collect(Collectors.joining(" "));
                    if (compressed.getBytes().length > upperBoundaryOfArguments) {
                        String message = "compress commands in bazel by Orlando still failed, the bazel system can't finish the incremental compilation";
                        throw this.newFailedExceptionOfAction(message, this);
                    }
                    return new SimpleSpawn(
                            this,
                            ImmutableList.copyOf(refinedCommands),
                            getEffectiveEnvironment(actionExecutionContext.getClientEnv()),
                            getExecutionInfo(),
                            getInputs(),
                            getOutputs(),
                            estimateResourceConsumptionLocal(
                                    OS.getCurrent(),
                                    getLinkCommandLine().getLinkerInputArtifacts().memoizedFlattenAndGetSize()));
                }
            }
            return new SimpleSpawn(
                    this,
                    commandLine,
                    getEffectiveEnvironment(actionExecutionContext.getClientEnv()),
                    getExecutionInfo(),
                    getInputs(),
                    getOutputs(),
                    estimateResourceConsumptionLocal(
                            OS.getCurrent(),
                            getLinkCommandLine().getLinkerInputArtifacts().memoizedFlattenAndGetSize()));
        } catch (CommandLineExpansionException | IOException e) {
            String message =
                    String.format(
                            "failed to generate link command for rule '%s: %s",
                            getOwner().getLabel(), e.getMessage());
            throw this.newFailedExceptionOfAction(message, this);
        }
    }

    @Override
    public ExtraActionInfo.Builder getExtraActionInfo(ActionKeyContext actionKeyContext)
            throws CommandLineExpansionException, InterruptedException {
        // The uses of getLinkConfiguration in this method may not be consistent with the computed key.
        // I.e., this may be incrementally incorrect.
        CppLinkInfo.Builder info = CppLinkInfo.newBuilder();
        info.addAllInputFile(
                Artifact.toExecPaths(getLinkCommandLine().getLinkerInputArtifacts().toList()));
        info.setOutputFile(getPrimaryOutput().getExecPathString());
        if (interfaceOutputLibrary != null) {
            info.setInterfaceOutputFile(interfaceOutputLibrary.getArtifact().getExecPathString());
        }
        info.setLinkTargetType(getLinkCommandLine().getLinkTargetType().name());
        info.setLinkStaticness(getLinkCommandLine().getLinkingMode().name());
        info.addAllLinkStamp(Artifact.toExecPaths(getLinkstampObjects()));
        info.addAllBuildInfoHeaderArtifact(Artifact.toExecPaths(getBuildInfoHeaderArtifacts()));
        info.addAllLinkOpt(getLinkCommandLine().getRawLinkArgv(null));

        try {
            return super.getExtraActionInfo(actionKeyContext)
                    .setExtension(CppLinkInfo.cppLinkInfo, info.build());
        } catch (CommandLineExpansionException e) {
            throw new AssertionError("CppLinkAction command line expansion cannot fail.");
        }
    }

    /**
     * Returns the (ordered, immutable) list of header files that contain build info.
     */
    public Iterable<Artifact> getBuildInfoHeaderArtifacts() {
        return linkCommandLine.getBuildInfoHeaderArtifacts();
    }

    @Override
    protected void computeKey(
            ActionKeyContext actionKeyContext,
            @Nullable Artifact.ArtifactExpander artifactExpander,
            Fingerprint fp)
            throws CommandLineExpansionException {
        fp.addString(LINK_GUID);
        fp.addString(ldExecutable.getPathString());
        fp.addStrings(linkCommandLine.arguments());
        fp.addStringMap(toolchainEnv);
        fp.addStrings(getExecutionInfo().keySet());

        // TODO(bazel-team): For correctness, we need to ensure the invariant that all values accessed
        // during the execution phase are also covered by the key. Above, we add the argv to the key,
        // which covers most cases. Unfortunately, the extra action method above also
        // sometimes directly accesses settings from the link configuration that may or may not affect
        // the
        // key. We either need to change the code to cover them in the key computation, or change the
        // LinkConfiguration to disallow the combinations where the value of a setting does not affect
        // the argv.
        fp.addBoolean(linkCommandLine.isNativeDeps());
        fp.addBoolean(linkCommandLine.useTestOnlyFlags());
        if (linkCommandLine.getToolchainLibrariesSolibDir() != null) {
            fp.addPath(linkCommandLine.getToolchainLibrariesSolibDir());
        }
        fp.addBoolean(isLtoIndexing);
    }

    @Override
    public String describeKey() {
        StringBuilder message = new StringBuilder();
        message.append(getProgressMessage());
        message.append('\n');
        message.append("  Command: ");
        message.append(ShellEscaper.escapeString(linkCommandLine.getLinkerPathString()));
        message.append('\n');
        // Outputting one argument per line makes it easier to diff the results.
        try {
            List<String> arguments = linkCommandLine.arguments();
            for (String argument : ShellEscaper.escapeAll(arguments)) {
                message.append("  Argument: ");
                message.append(argument);
                message.append('\n');
            }
        } catch (CommandLineExpansionException e) {
            message.append("  Could not expand command line: ");
            message.append(e);
            message.append('\n');
        }
        return message.toString();
    }

    @Override
    public String getMnemonic() {
        return mnemonic;
    }

    @Override
    protected String getRawProgressMessage() {
        return (isLtoIndexing ? "LTO indexing " : "Linking ") + linkOutput.prettyPrint();
    }

    /**
     * Estimates resource consumption when this action is executed locally. During investigation, we
     * found linear dependency between used memory by action and number of inputs. For memory
     * estimation we are using form C + K * inputs, where C and K selected in such way, that more than
     * 95% of actions used less than C + K * inputs MB of memory during execution.
     */
    public ResourceSet estimateResourceConsumptionLocal(OS os, int inputs) {
        switch (os) {
            case DARWIN:
                return ResourceSet.createWithRamCpu(/* memoryMb= */ 15 + 0.05 * inputs, /* cpuUsage= */ 1);
            case LINUX:
                return ResourceSet.createWithRamCpu(
                        /* memoryMb= */ Math.max(50, -100 + 0.1 * inputs), /* cpuUsage= */ 1);
            default:
                return ResourceSet.createWithRamCpu(/* memoryMb= */ 1500 + inputs, /* cpuUsage= */ 1);
        }
    }

    @Override
    public Sequence<String> getStarlarkArgv() throws EvalException {
        try {
            return StarlarkList.immutableCopyOf(getArguments());
        } catch (CommandLineExpansionException ex) {
            throw new EvalException(ex);
        }
    }

    /**
     * An abstraction for creating intermediate and output artifacts for C++ linking.
     *
     * <p>This is unfortunately necessary, because most of the time, these artifacts are well-behaved
     * ones sitting under a package directory, but nativedeps link actions can be shared. In order to
     * avoid creating every artifact here with {@code getShareableArtifact()}, we abstract the
     * artifact creation away.
     */
    public interface LinkArtifactFactory {

        /**
         * Create an artifact at the specified root-relative path in the bin directory.
         */
        Artifact create(
                ActionConstructionContext actionConstructionContext,
                RepositoryName repositoryName,
                BuildConfiguration configuration,
                PathFragment rootRelativePath);
    }

    private final class CppLinkActionContinuation extends ActionContinuationOrResult {

        private final ActionExecutionContext actionExecutionContext;
        private final SpawnContinuation spawnContinuation;

        public CppLinkActionContinuation(
                ActionExecutionContext actionExecutionContext, SpawnContinuation spawnContinuation) {
            this.actionExecutionContext = actionExecutionContext;
            this.spawnContinuation = spawnContinuation;
        }

        @Override
        public ListenableFuture<?> getFuture() {
            return spawnContinuation.getFuture();
        }

        @Override
        public ActionContinuationOrResult execute()
                throws ActionExecutionException, InterruptedException {
            try {
                SpawnContinuation nextContinuation = spawnContinuation.execute();
                if (!nextContinuation.isDone()) {
                    return new CppLinkActionContinuation(actionExecutionContext, nextContinuation);
                }
                return ActionContinuationOrResult.of(ActionResult.create(nextContinuation.get()));
            } catch (ExecException e) {
                throw e.toActionExecutionException(
                        CppLinkAction.this);
            }
        }
    }
}
