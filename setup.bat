git submodule update --init --recursive
cd trace-model
call mvn install
cd ..
call mvn package

