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
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
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

import java.io.IOException;
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
    static int upperBoundaryOfArguments = 262144;
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

    public class ObjectFile {
        private String objFileCommand;
        private String postfix;

        public ObjectFile(String objFileCommand, String postfix) {
            this.objFileCommand = objFileCommand;
            this.postfix = postfix;
        }

        public String getObjFileCommand() {
            return objFileCommand;
        }

        public void setObjFileCommand(String objFileCommand) {
            this.objFileCommand = objFileCommand;
        }

        public String getPostfix() {
            return postfix;
        }

        public void setPostfix(String postfix) {
            this.postfix = postfix;
        }
    }

    /**
     * Enhance spawn implementation to avoid "arguments too long" error due to the pre-setting of macOS 10.13.6 `getconf ARG_MAX`
     *
     * @param actionExecutionContext
     * @return the real execution spawn for the action
     * @throws ActionExecutionException
     */
    private Spawn createSpawn(ActionExecutionContext actionExecutionContext)
            throws ActionExecutionException {
        try {
            ImmutableList<String> commandLine = ImmutableList.copyOf(getCommandLine(actionExecutionContext.getArtifactExpander()));
            System.err.printf("[bazel:src/main/java/com/google/devtools/build/lib/rules/cpp/CppLinkAction.java] command line: %s, inputs: %s, outputs: %s, os: %s, tools: %s \n", commandLine, getInputs(), getOutputs(), OS.getCurrent(), estimateResourceConsumptionLocal(
                    OS.getCurrent(),
                    getLinkCommandLine().getLinkerInputArtifacts().memoizedFlattenAndGetSize()));
//      if (OS.getCurrent() == OS.DARWIN && OS.getVersion().equals("10.13.6")) {
            if (OS.getCurrent() == OS.DARWIN) {
                //see: only crack for macOS 10.13.6, refer to https://www.gnu.org/software/libtool/manual/libtool.html
                //libtool --mode=link cc -static -o libaz.a libabc.a libxyz.a
                //g++ -shared -o c.so -Wl,--whole-archive a.a b.a
                //libtool --mode=link cc -g -O -o libhello.a foo.lo hello.lo
                //ld -r foo.o hello.o -o merge.o
                final String fullNameOfOutputFile = getPrimaryOutput().getExecPathString();
                final String shortNameOfOutputFile = getPrimaryOutput().getFilename();
                final String dirPathOfOutputFile = getPrimaryOutput().getDirname();
                System.err.printf("[bazel:src/main/java/com/google/devtools/build/lib/rules/cpp/CppLinkAction.java] output library - execPath = %s, fileName = %s, dirName = %s\n", fullNameOfOutputFile, shortNameOfOutputFile, dirPathOfOutputFile);
                final String arguments = commandLine.parallelStream().collect(Collectors.joining(" "));
                if (arguments.getBytes().length > upperBoundaryOfArguments) {
                    final ProcessBuilder processBuilder = new ProcessBuilder();
                    System.err.printf("[bazel:src/main/java/com/google/devtools/build/lib/rules/cpp/CppLinkAction.java] need to split arguments to avoid the issue of \"error=7, Argument list too long\" on macOS 10.13.6\n");
                    final String intermediateFileNameTemplate = shortNameOfOutputFile.substring(0, shortNameOfOutputFile.lastIndexOf(".")) + "_intermediate_%d.o";
                    final String intermediateFilePath = dirPathOfOutputFile;
                    final int indexOfOutputLibrary = commandLine.indexOf(fullNameOfOutputFile);
                    final List<String> objectFiles = commandLine.subList(indexOfOutputLibrary + 1, commandLine.size());
                    final List<ObjectFile> objects = objectFiles.stream().map(strF -> new ObjectFile(strF, strF.substring(strF.lastIndexOf(".")))).collect(Collectors.toList());
                    final Map<String, List<ObjectFile>> postfixToObjectFileMap =objects.stream().collect(groupingBy(ObjectFile::getPostfix));
                    final int collectedSize = 200;
                    final Map<String, List<String>> intermediateToSubLibraries = new HashMap<>();
                    for (int i = indexOfOutputLibrary + 1, s = 0; i < commandLine.size(); ) {
                        final List<String> intermediate = new ArrayList();
                        for (int j = 0; j < collectedSize && i < commandLine.size(); j++) {
                            intermediate.add(commandLine.get(i));
                        }
                        final String intermediateOutputFile = dirPathOfOutputFile + String.format(intermediateFileNameTemplate, s++);
                        intermediateToSubLibraries.put(intermediateFilePath + intermediateOutputFile, intermediate);
                        final List<String> commands = Lists.newArrayList("/usr/bin/ld", "-r");
                        commands.addAll(intermediate);
                        commands.add("-o");
                        commands.add(intermediateOutputFile);
                        final Process process = processBuilder.command(commands).start();
                        if (process.exitValue() != 0) {
                            String message = String.format("failed to generate intermediate library %s", intermediateOutputFile);
                            DetailedExitCode code = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE);
                            throw new ActionExecutionException(message, this, /*catastrophe=*/ false, code);
                        }
                    }
                    //see: refine for simpleSpawn for intermediate files
                    final List<String> refinedCommands = commandLine.subList(0, indexOfOutputLibrary + 1);
                    refinedCommands.addAll(intermediateToSubLibraries.keySet());
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
                    ImmutableList.copyOf(getCommandLine(actionExecutionContext.getArtifactExpander())),
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
            DetailedExitCode code = createDetailedExitCode(message, Code.COMMAND_GENERATION_FAILURE);
            throw new ActionExecutionException(message, this, /*catastrophe=*/ false, code);
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
     * Estimates resource consumption when this action is executed locally. During investigation we
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
