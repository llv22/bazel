<!-- markdownlint-disable MD033 -->
<!-- markdownlint-disable MD004 -->
<!-- markdownlint-disable MD029 -->
# bazel 5.2.0 on macOS 10.13.6

--------------------------------------------------------------------------------

As official bazel requires libtool provided by Xcode to accept params file as arguments, such feature breaks the building of libraries on macOS 10.13.6 (Xcode 10.1) that is the basis of tensorflow on macOS with GPU supports. I identified this change occurs in the following codes

* [Comparing changes between 3.7.2 and 4.0.0](https://github.com/bazelbuild/bazel/compare/3.7.2...4.0.0)
* [Add support for params files for darwin](https://github.com/bazelbuild/bazel/commit/d3fc253a49a00c34408bbaf5378376cbcea1c5c9)

In order to avoid the building issue of bazel 5.2.0 mentioned below:

```bash
(base) Orlando:stage2 llv23$ bazel build //main:hello-world --verbose_failures --sandbox_debug
INFO: Analyzed target //main:hello-world (23 packages loaded, 86 targets configured).
INFO: Found 1 target...
ERROR: /Users/llv23/Downloads/dev/bazel_cc_library/cpp-tutorial/stage2/main/BUILD:3:11: Linking main/libhello-greet.a failed: (Exit 1): sandbox-exec failed: error executing command 
  (cd /private/var/tmp/_bazel_llv23/b288f6bc7334b92193bc7a75684f1dc9/sandbox/darwin-sandbox/14/execroot/__main__ && \
  exec env - \
    LD_LIBRARY_PATH=/usr/local/cuda/lib:/Developer/NVIDIA/CUDA-10.1/lib:/usr/local/cuda/extras/CUPTI/lib:/usr/local/opt/boost-python3/lib:/usr/local/opt/open-mpi/lib:/usr/local/Cellar/libomp/10.0.0/lib:/usr/local/Cellar/rdkit20210304/lib:/Users/llv23/opt/miniconda3/lib:/usr/local/openmm/lib:/usr/local/sox/lib:/Users/llv23/opt/intel/oneapi/mkl/latest/lib:/usr/local/lib \
    PATH=/Users/llv23/.poetry/bin:/Users/llv23/.rvm/gems/ruby-2.2.1/bin:/Users/llv23/.rvm/gems/ruby-2.2.1@global/bin:/Users/llv23/.rvm/rubies/ruby-2.2.1/bin:/Library/Frameworks/Python.framework/Versions/3.7/bin:/Library/Frameworks/Python.framework/Versions/3.6/bin:/Users/llv23/opt/miniconda3/bin:/Users/llv23/opt/miniconda3/condabin:/usr/local/sox/bin:/Users/llv23/.local/bin:/usr/local/cuda/lib:/Developer/NVIDIA/CUDA-10.1/lib:/usr/local/cuda/extras/CUPTI/lib:/usr/local/opt/boost-python3/lib:/usr/local/opt/open-mpi/lib:/usr/local/Cellar/libomp/10.0.0/lib:/usr/local/Cellar/rdkit20210304/lib:/usr/local/Cellar/spark/bin:/Library/TeX/texbin:/usr/local/sbin:/usr/local/cuda/bin:/Developer/NVIDIA/CUDA-10.1/bin:/Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin:/usr/local/opt/openssl/bin:/usr/local/opt/node@6/bin:/usr/local/protobuf/bin:/usr/local/opt/scala/bin:/usr/local/bin/vmware/Library:/Users/llv23/Documents/orlando_innovation/homekits_sol/mongodb/bin:/Library/Frameworks/GDAL.framework/Programs:/Users/llv23/Documents/02_apple_programming/06_classdump:/Applications/VirtualBox.app/Contents:/Users/llv23/Documents/04_linuxc/07_redis/redis-2.6.13/src:/Users/llv23/npm-global/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:/Library/TeX/texbin:/usr/local/share/dotnet:/opt/X11/bin:/Users/llv23/.rvm/bin \
    PWD=/proc/self/cwd \
    TMPDIR=/var/folders/p8/91_v9_9d12q9wmlydb406rbr0000gn/T/ \
  /usr/bin/sandbox-exec -f /private/var/tmp/_bazel_llv23/b288f6bc7334b92193bc7a75684f1dc9/sandbox/darwin-sandbox/14/sandbox.sb /var/tmp/_bazel_llv23/install/0c7899cb691a00c6ca493ede5765e1af/process-wrapper '--timeout=0' '--kill_delay=15' /usr/bin/libtool @bazel-out/darwin-fastbuild/bin/main/libhello-greet.a-2.params)
error: /Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/libtool: no output file specified (specify with -o output)
Usage: /Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/libtool -static [-] file [...] [-filelist listfile[,dirname]] [-arch_only arch] [-sacLT] [-no_warning_for_no_symbols]
Usage: /Applications/Xcode.app/Contents/Developer/Toolchains/XcodeDefault.xctoolchain/usr/bin/libtool -dynamic [-] file [...] [-filelist listfile[,dirname]] [-arch_only arch] [-o output] [-install_name name] [-compatibility_version #] [-current_version #] [-seg1addr 0x#] [-segs_read_only_addr 0x#] [-segs_read_write_addr 0x#] [-seg_addr_table <filename>] [-seg_addr_table_filename <file_system_path>] [-all_load] [-noall_load]
Target //main:hello-world failed to build
INFO: Elapsed time: 0.513s, Critical Path: 0.09s
INFO: 3 processes: 3 internal.
FAILED: Build did NOT complete successfully
```

One patch on top of 5.2.0 has been applied for bazel, which is the main purpose of this repository.

--------------------------------------------------------------------------------

# [Bazel](https://bazel.build)

*{Fast, Correct} - Choose two*

Build and test software of any size, quickly and reliably.

* **Speed up your builds and tests**:
  Bazel rebuilds only what is necessary.
  With advanced local and distributed caching, optimized dependency analysis and
  parallel execution, you get fast and incremental builds.

* **One tool, multiple languages**: Build and test Java, C++, Android, iOS, Go,
  and a wide variety of other language platforms. Bazel runs on Windows, macOS,
  and Linux.

* **Scalable**: Bazel helps you scale your organization, codebase, and
  continuous integration solution. It handles codebases of any size, in multiple
  repositories or a huge monorepo.

* **Extensible to your needs**: Easily add support for new languages and
  platforms with Bazel's familiar extension language. Share and re-use language
  rules written by the growing Bazel community.

## Getting Started

  * [Install Bazel](https://docs.bazel.build/install.html)
  * [Get started with Bazel](https://docs.bazel.build/getting-started.html)
  * Follow our tutorials:

    - [Build C++](https://docs.bazel.build/tutorial/cpp.html)
    - [Build Java](https://docs.bazel.build/tutorial/java.html)
    - [Android](https://docs.bazel.build/tutorial/android-app.html)
    - [iOS](https://docs.bazel.build/tutorial/ios-app.html)

## Documentation

  * [Bazel command line](https://docs.bazel.build/user-manual.html)
  * [Rule reference](https://docs.bazel.build/be/overview.html)
  * [Use the query command](https://docs.bazel.build/query.html)
  * [Extend Bazel](https://docs.bazel.build/skylark/concepts.html)
  * [Write tests](https://docs.bazel.build/test-encyclopedia.html)
  * [Roadmap](https://bazel.build/roadmap.html)
  * [Who is using Bazel?](https://github.com/bazelbuild/bazel/wiki/Bazel-Users)

## Reporting a Vulnerability

To report a security issue, please email security@bazel.build with a description
of the issue, the steps you took to create the issue, affected versions, and, if
known, mitigations for the issue. Our vulnerability management team will respond
within 3 working days of your email. If the issue is confirmed as a
vulnerability, we will open a Security Advisory. This project follows a 90 day
disclosure timeline.

## Contributing to Bazel

See [CONTRIBUTING.md](CONTRIBUTING.md)

[![Build status](https://badge.buildkite.com/1fd282f8ad98c3fb10758a821e5313576356709dd7d11e9618.svg?status=master)](https://ci.bazel.build)
