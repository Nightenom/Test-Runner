cwd=$(pwd)/

test_folder="${cwd}data"
main="${JAVA_HOME}/bin/java ${cwd}src/main/java/Main.java"

path_to_testrunner="${cwd}TestRunner.java"

${JAVA_HOME}/bin/java -Dtr.folder="${test_folder}" -Dtr.main="${main}" ${path_to_testrunner}
