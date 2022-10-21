# Test Runner

A dumb homework testing framework for any amazing thingy with huge test suite

## Description

This single file program is here to run your program under various environment changes, mainly for supplying various stdin/stdout/stderr tests.
You can choose among multiple implementations, but all should behave in the same way. The reason for many implementations is (future) posibility to inject given program into Test Runner process for faster execution (and eventually more precise timing).

For a successful execution you need to provide two arguments:
- a path to directory with test cases (test directory)
- a base command which can be executed in your OS's CLI

### How does this work

Test Runner job can be divided into following steps:
1. Form test cases from any file in the test directory
1. For each test case execute your base command (in environment adjusted according to test data) and compare its outputs with test data
1. Return count of successful tests, if you pass them all then a huge congratulation text will appear

### How is test case formed

For each file `name.ext` in the test directory create or adjust data for test case named `name`, following extensions are recognised (you can change extensions using Test Runner argument):

| Extension | Meaning | Description |
| :-: | :-- | :-- |
| `in` | stdin | Standard input that will get piped **unchanged** into your program. POSIX equivalent to `executable < name.in` |
| `out` | stdout | Expected standard output of your program. Your program must generate exact byte match. Similar to result of POSIX `executable \| diff name.out -` |
| `err` | stderr | Same as `out` except for standard error output |
| `args` | main arguments | Each line of this file forms one additional argument. All arguments are then append to your base command. Does not affect `genin` or `gen` |
| `exit` | exit code | First line of file must be parsable integer, rest of file is ignored. Currently your program's exit code must exactly match parsed value. TODO: allow ranges and "nonzero" special value |
| `genin` | input generator | First line must be a path pointing to executable, rest of file are arguments parsed in same way as `args`. Will be executed before your program and its output will be overwrite content of `name.in` |
| `gen` | solution generator | Same as `genin` except: stdin for this executable is `name.in`, output will overwrite `name.out` and `name.err` |
| `timeout` | general timeout | First line of file must be parsable integer, rest of file is ignored. Default timeout is usually 10 seconds for any part of any test case. Affects your program, `genin` and `gen`. If any part of test case timeouts then whole test case is skipped |
| `rundir` | working directory | First line must be valid path, rest of file is ignored. Affects your program, `genin` and `gen`. Is equivalent to `cd`ing before program execution |
| `outfiles` | output files | Each line of this file must be valid path (with root at `rundir`) that is produced by `gen` and/or your program. Each output file will be moved to the test directory and its name will be prepended with `name.outf_user` or `name.outf_ref` depending on being out of your program or `gen` respectively |
| **Planned extensions** | | |
| `desc` | description | Content of whole file is printed after test header |
| `prerun` and `postrun` | pre and post run tasks | Same format as `gen` tasks, run before/after your program. Eg. for compiling etc. |
| `envmap` | console environment | Per line mapping to change console environment |

### Description of test output

```
===== TEST name =====   <-------------------------------- test header
Running in directory: /bleh/blah   <--------------------- if `rundir` is specified then this is absolute and normalized version of parsed path
Generating input...   <---------------------------------- if `genin` is specified then input generation has begun
Input generation timeout, skipping...   <---------------- if `genin` is specified then generating input took longer than allowed, rest of test case is skipped
                                                     ^--- else `name.in` is overwritten

Generating reference solution...   <--------------------- if `gen` is specified then output generation has begun
Reference solution generation timeout, skipping...   <--- if `gen` is specified then generating output took longer than allowed, rest of test case is skipped
                                                     ^--- else `name.out` and `name.err` are overwritten

                                                     ˇ--- execution of your program has begun
TIMEOUT   time:    1541651 ms   <------------------------ your program's execution took longer than allowed, rest of test case is skipped

Result exit code: 12   <--------------------------------- your program's exit code
Expected exit code: 45   <------------------------------- expected exit code from `name.exit`
    OR
Nonzero exit code: 12   <-------------------------------- if `name.exit` doesn't exist and your program returned non-zero exit code
    OR nothing if exit code is correct
  
                                                     ˇ--- following part may appear two times - one for stdout and one for stderr
Result out should be empty:   <-------------------------- if your program produced anything on standard output but should not
[your program's standard output truncated to 1000 characters] (invisible ASCII characters are escaped - eg. `\r\n`, or printed as hexcode - eg. `\0x01`
    OR
Result out:   <------------------------------------------ if your program's standard output does not byte match given test data
[your program's standard output truncated to 1000 characters or `<empty>`] (invisible ASCII characters are escaped)
Expected out:
[content of `name.out` truncated to 1000 characters] (invisible ASCII characters are escaped)
Mismatch <in only character|after end|at start|at end|at [byte index]> of result out:
[mismatch detail if applicable]   <---------------------- `...` means a lot data around, `|` means start/end of data, `<> [character] <>` highlights where first byte mismatch appears, each part of data is surrounded with single space
    OR nothing if output is correct

TODO: output files info description
```

## Contributing

Any PRs are welcome as long as they:

- keep current code formatting, do not format things you don't touch
- are not single typo change
- are nice :)

When creating implementation of Test Runner in any lovely programming language please follow systems and designs in existing implementations. Current main implementation in [Java](https://github.com/Nightenom/Test-Runner/blob/main/java/TestRunner.java)
