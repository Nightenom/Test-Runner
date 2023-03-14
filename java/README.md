Can be either run as compiled jar or directly as single uncompiled file assuming you are using Java 11+ (see [JEP 330](https://openjdk.org/jeps/330)).

File tested for Java 16+.

Currently, arguments are passed using properties (eg. `-Dtr.folder=test`), list of properties:
- **`tr.folder`** _required_ - path to directory with tests (see main [README.md](https://github.com/Nightenom/Test-Runner/blob/main/README.md))
- **`tr.main`** _required_ - path to main, every space in this string is considered as argument splitter - eg. `a.out first second third` will result in running `a.out` with `[first, second, third, appended test.args according to specification]` as arguments
- **`tr.file_exts`** _defaults to: `in,out,err,args,exit,genin,gen,timeout,rundir,infiles,outfiles,envmap,desc`_ - list of file extensions to search in `tr.folder`
- **`tr.main_timeout`** _defaults to: `10`_ - global timeout in seconds, will/can be overriden per test case as defined in main README
- **`tr.debug`** _defaults to: `false`_ - whether to output debug info, especially good for checking variable expansion and main args

## TODOs
1. Parse base command in better way?
2. Special property flag to run Java directly in Test Runner using inject
3. ~Unite~ Check output stream processing, current version messes up bytes and chars
