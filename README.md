# Test Runner

A dumb homework testing framework for any amazing thingy with huge test suite

## Description

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

## Contributing

Any PRs are welcome as long as they:

- keep current code formatting, do not format things you don't touch
- are not single typo change
- are nice :)

When creating implementation of Test Runner in any lovely programming language please follow systems and designs in existing implementations. Current main implementation in [Java](https://github.com/Nightenom/Test-Runner/blob/main/java/TestRunner.java)
