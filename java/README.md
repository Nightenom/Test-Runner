Can be either run as compiled jar or directly as single uncompiled file assuming you are using Java 11+ (see [JEP 330](https://openjdk.org/jeps/330)).

**File itself requires record support => Java 16+.** Preview versions (14 and 15) weren't tested.

Currently, arguments are passed using properties (eg. `-Dtr.folder=test`), list of properties:
- **`tr.folder`** _required_ - path to directory with tests (see main [README.md](https://github.com/Nightenom/Test-Runner/blob/main/README.md))
- **`tr.main`** _required_ - path to main, every space in this string is considered as argument splitter - eg. `a.out first second third` will result in running `a.out` with `[first, second, third, appended test.args]` as arguments
- **`tr.file_exts`** _defaults to: `in,out,err,args,exit,genin,gen,timeout,rundir,outfiles`_ - list of file extensions to search in `tr.folder`
- **`tr.main_timeout`** _defaults to: `10`_ - global timeout in seconds, will/can be overriden per test case as defined in main README

## TODOs
1. Parse base command in better way?
2. Hexdump in case of mismatch in output files
3. Special property flag to run Java directly in Test Runner using inject
4. Allow environment values in addition to property arguments
5. Unite output stream processing, current version messes up bytes and chars
